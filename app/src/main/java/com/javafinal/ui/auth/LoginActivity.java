package com.javafinal.ui.auth;

import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.javafinal.R;
import com.javafinal.model.AuthModels;
import com.javafinal.model.User;
import com.javafinal.network.ApiError;
import com.javafinal.ui.ApiActivity;
import com.javafinal.viewmodel.AuthViewModel;
import java.util.Locale;

public class LoginActivity extends ApiActivity {
    private AuthViewModel auth;
    private View stepPhone, stepOtp;
    private TextInputEditText phoneInput;
    private EditText[] otpInputs;
    private MaterialButton sendButton, verifyButton, resendButton;
    private TextView countdown;
    private String selectedRole = User.ROLE_USER;
    private String otpPhone;
    private boolean busy;
    private long resendAt;
    private CountDownTimer timer;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setupScreen(R.layout.activity_login);
        auth = vm(AuthViewModel.class);
        stepPhone = findViewById(R.id.layoutStepPhone);
        stepOtp = findViewById(R.id.layoutStepOtp);
        phoneInput = findViewById(R.id.edtPhone);
        sendButton = findViewById(R.id.btnSendOtp);
        verifyButton = findViewById(R.id.btnConfirmOtp);
        resendButton = findViewById(R.id.btnResend);
        countdown = findViewById(R.id.tvCountdown);
        otpInputs = new EditText[]{findViewById(R.id.edtOtp1), findViewById(R.id.edtOtp2), findViewById(R.id.edtOtp3),
                findViewById(R.id.edtOtp4), findViewById(R.id.edtOtp5), findViewById(R.id.edtOtp6)};
        setupOtpInputs();
        MaterialButtonToggleGroup roles = findViewById(R.id.toggleRole);
        roles.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) selectedRole = id == R.id.btnRoleRescuer ? User.ROLE_RESCUER : User.ROLE_USER;
        });
        if (state != null) {
            selectedRole = state.getString("role", User.ROLE_USER);
            otpPhone = state.getString("otpPhone");
            resendAt = state.getLong("resendAt");
            roles.check(User.ROLE_RESCUER.equals(selectedRole) ? R.id.btnRoleRescuer : R.id.btnRoleUser);
            if (otpPhone != null) showOtp();
            startCountdown();
        }
        bind("otp", (AuthModels.RequestOtpResponse response) -> {
            clearStatus();
            otpPhone = response.getPhone() == null ? phoneInput.getText().toString().trim() : response.getPhone();
            for (EditText input : otpInputs) input.setText("");
            showOtp();
            resendAt = SystemClock.elapsedRealtime() + 30000;
            startCountdown();
        }, this::otpError, this::setBusy);
        bind("verify", (AuthModels.VerifyOtpResponse response) -> goToRoleHome(response.getUser()),
                this::otpError, this::setBusy);
        sendButton.setOnClickListener(v -> requestOtp());
        resendButton.setOnClickListener(v -> requestOtp());
        verifyButton.setOnClickListener(v -> {
            StringBuilder code = new StringBuilder();
            for (EditText input : otpInputs) code.append(input.getText().toString().trim());
            execute("verify", () -> auth.verifyOtp(otpPhone, code.toString()));
        });
        findViewById(R.id.btnBack).setOnClickListener(v -> showPhone());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (stepOtp.getVisibility() == View.VISIBLE) showPhone(); else finish();
            }
        });
    }
    private void requestOtp() {
        String phone = stepOtp.getVisibility() == View.VISIBLE ? otpPhone
                : phoneInput.getText() == null ? "" : phoneInput.getText().toString().trim();
        execute("otp", () -> auth.requestOtp(phone, selectedRole));
    }
    private void otpError(ApiError error) {
        if (error.getStatusCode() == 429) {
            long seconds = 30;
            try { seconds = Long.parseLong(error.getRetryAfter()); } catch (RuntimeException ignored) { }
            resendAt = SystemClock.elapsedRealtime() + Math.max(1, seconds) * 1000;
            startCountdown();
        }
        showApiError(error, null);
    }
    private void showOtp() {
        stepPhone.setVisibility(View.GONE);
        stepOtp.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.tvOtpSentTo)).setText("Nhập mã 6 số được gửi qua Telegram cho " + otpPhone);
    }
    private void showPhone() {
        if (busy) return;
        stepOtp.setVisibility(View.GONE);
        stepPhone.setVisibility(View.VISIBLE);
        otpPhone = null;
        clearStatus();
    }
    private void setBusy(boolean value) {
        busy = value;
        sendButton.setEnabled(!value && SystemClock.elapsedRealtime() >= resendAt);
        verifyButton.setEnabled(!value);
        resendButton.setEnabled(!value && SystemClock.elapsedRealtime() >= resendAt);
        phoneInput.setEnabled(!value);
        findViewById(R.id.btnRoleUser).setEnabled(!value);
        findViewById(R.id.btnRoleRescuer).setEnabled(!value);
        if (value) showLoading("Đang xử lý đăng nhập…");
    }
    private void startCountdown() {
        if (timer != null) timer.cancel();
        long remaining = resendAt - SystemClock.elapsedRealtime();
        if (remaining <= 0) { countdown.setText("Có thể gửi lại mã"); setBusy(busy); return; }
        timer = new CountDownTimer(remaining, 1000) {
            @Override public void onTick(long ms) {
                countdown.setText(String.format(Locale.getDefault(), "Gửi lại sau %02ds", (ms + 999) / 1000));
                resendButton.setEnabled(false);
                sendButton.setEnabled(false);
            }
            @Override public void onFinish() { countdown.setText("Có thể gửi lại mã"); setBusy(busy); }
        }.start();
    }
    private void setupOtpInputs() {
        for (int i = 0; i < otpInputs.length; i++) {
            final int index = i;
            otpInputs[i].addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                public void afterTextChanged(Editable s) { }
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (s.length() == 1 && index < 5) otpInputs[index + 1].requestFocus();
                }
            });
            otpInputs[i].setOnKeyListener((v, key, event) -> {
                if (key == KeyEvent.KEYCODE_DEL && event.getAction() == KeyEvent.ACTION_DOWN
                        && otpInputs[index].getText().length() == 0 && index > 0) {
                    otpInputs[index - 1].requestFocus(); return true;
                }
                return false;
            });
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("role", selectedRole); state.putString("otpPhone", otpPhone); state.putLong("resendAt", resendAt);
    }
    @Override protected void onDestroy() { if (timer != null) timer.cancel(); super.onDestroy(); }
}
