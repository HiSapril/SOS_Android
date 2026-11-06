package com.javafinal.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.javafinal.R;
import com.javafinal.model.User;
import com.javafinal.network.ApiError;
import com.javafinal.ui.auth.LoginActivity;
import com.javafinal.ui.rescuer.RescuerHomeActivity;
import com.javafinal.ui.user.HomeActivity;
import com.javafinal.viewmodel.ApiState;
import com.javafinal.viewmodel.ApiViewModelFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** UI chung cho loading, retry, phiên hết hạn và thao tác đang chờ qua cấu hình mới. */
public abstract class ApiActivity extends AppCompatActivity {
    public static final String SOS_ID = "SOS_ID";
    protected ApiViewModelFactory factory;
    private ScreenRequests requests;
    private final Map<String, Binding<?>> bindings = new HashMap<>();
    private final Map<String, LiveData<?>> observed = new HashMap<>();
    private LinearLayout statusPanel;
    private LinearProgressIndicator progress;
    private TextView statusText;
    private MaterialButton retryButton;
    private boolean redirecting;

    protected void setupScreen(int layout) {
        EdgeToEdge.enable(this);
        setContentView(layout);
        factory = ApiViewModelFactory.getDefault(getApplicationContext());
        requests = new ViewModelProvider(this).get(ScreenRequests.class);
        FrameLayout content = findViewById(android.R.id.content);
        View screen = content.getChildAt(0);
        content.removeView(screen);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        statusPanel = new LinearLayout(this);
        statusPanel.setOrientation(LinearLayout.VERTICAL);
        statusPanel.setPadding(dp(16), dp(6), dp(16), dp(6));
        progress = new LinearProgressIndicator(this);
        progress.setIndeterminate(true);
        statusPanel.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        statusText = new TextView(this);
        statusText.setTextSize(13);
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1));
        retryButton = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        retryButton.setText("Thử lại");
        statusRow.addView(retryButton);
        statusPanel.addView(statusRow);
        wrapper.addView(statusPanel, new LinearLayout.LayoutParams(-1, -2));
        wrapper.addView(screen, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(wrapper, new FrameLayout.LayoutParams(-1, -1));
        clearStatus();
        ViewCompat.setOnApplyWindowInsetsListener(wrapper, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    protected <T extends ViewModel> T vm(Class<T> type) {
        return new ViewModelProvider(this, factory).get(type);
    }

    protected boolean requireSession() {
        if (factory.getSession().isLoggedIn()) return true;
        goToLogin();
        return false;
    }

    public void goToLogin() {
        if (redirecting) return;
        redirecting = true;
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    protected void goToRoleHome(User user) {
        if (user == null || redirecting) return;
        redirecting = true;
        Intent intent = new Intent(this, user.isRescuer() ? RescuerHomeActivity.class : HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    protected void openSos(Class<?> screen, String id) {
        startActivity(new Intent(this, screen).putExtra(SOS_ID, id));
    }

    public void showLoading(String message) {
        statusPanel.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        statusText.setText(message);
        retryButton.setVisibility(View.GONE);
    }
    public void showStatus(String message, Runnable retry) {
        statusPanel.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
        statusText.setText(message);
        retryButton.setVisibility(retry == null ? View.GONE : View.VISIBLE);
        retryButton.setOnClickListener(v -> { if (retry != null) retry.run(); });
    }
    public void clearStatus() { statusPanel.setVisibility(View.GONE); }
    public void showApiError(ApiError error, Runnable retry) {
        if (error == null || error.getKind() == ApiError.Kind.CANCELLED) return;
        if (error.isUnauthorized() && !(this instanceof LoginActivity)) {
            factory.getSession().clear();
            goToLogin();
        } else showStatus(error.getMessage(), retry);
    }

    protected <T> void bind(String key, Consumer<T> success, Consumer<ApiError> error, Consumer<Boolean> busy) {
        Binding<T> binding = new Binding<>(success, error, busy);
        bindings.put(key, binding);
        LiveData<ApiState<T>> source = requests.get(key);
        if (source != null && !requests.isConsumed(key)) attach(key, source, binding);
    }

    protected <T> void execute(String key, Supplier<LiveData<ApiState<T>>> action) {
        LiveData<ApiState<T>> previous = requests.get(key);
        if (previous != null && previous.getValue() != null
                && previous.getValue().getStatus() == ApiState.Status.LOADING) return;
        LiveData<ApiState<T>> source = action.get();
        requests.put(key, source);
        @SuppressWarnings("unchecked") Binding<T> binding = (Binding<T>) bindings.get(key);
        if (binding == null) throw new IllegalStateException("Missing UI binding: " + key);
        attach(key, source, binding);
    }

    private <T> void attach(String key, LiveData<ApiState<T>> source, Binding<T> binding) {
        if (observed.get(key) == source) return;
        observed.put(key, source);
        source.observe(this, new Observer<ApiState<T>>() {
            @Override public void onChanged(ApiState<T> state) {
                if (state == null || isFinishing()) return;
                boolean loading = state.getStatus() == ApiState.Status.LOADING;
                if (binding.busy != null) binding.busy.accept(loading);
                if (loading) return;
                source.removeObserver(this);
                if (!requests.consume(key, source)) return;
                if (state.getStatus() == ApiState.Status.SUCCESS) binding.success.accept(state.getData());
                else if (binding.error != null) binding.error.accept(state.getError());
                else showApiError(state.getError(), null);
            }
        });
    }

    private static final class Binding<T> {
        final Consumer<T> success;
        final Consumer<ApiError> error;
        final Consumer<Boolean> busy;
        Binding(Consumer<T> success, Consumer<ApiError> error, Consumer<Boolean> busy) {
            this.success = success; this.error = error; this.busy = busy;
        }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    protected static String text(String value) { return value == null || value.trim().isEmpty() ? "—" : value; }
}
