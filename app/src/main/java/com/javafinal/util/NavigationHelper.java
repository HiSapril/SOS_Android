package com.javafinal.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.javafinal.R;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;
import com.javafinal.model.User;
import com.javafinal.model.RescueTeam;
import com.javafinal.model.RescueTeamMember;
import com.javafinal.model.SosNotification;
import com.javafinal.network.ApiError;
import com.javafinal.ui.ApiActivity;
import com.javafinal.ui.user.TrackSosActivity;
import com.javafinal.ui.rescuer.SosDetailActivity;
import com.javafinal.viewmodel.ApiState;
import com.javafinal.viewmodel.ApiViewModelFactory;
import com.javafinal.viewmodel.AuthViewModel;
import com.javafinal.viewmodel.UserViewModel;
import com.javafinal.viewmodel.RescuerViewModel;
import com.javafinal.viewmodel.NotificationViewModel;
import com.javafinal.ui.auth.LoginActivity;

import java.util.List;

public class NavigationHelper {

    private static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    /**
     * Hiển thị thông báo ngắn (Snackbar tối giản thay cho Toast mặc định).
     */
    public static void showSnack(Activity activity, String message) {
        if (activity == null || activity.isFinishing()) return;
        View root = activity.findViewById(R.id.main);
        if (root == null) {
            root = activity.findViewById(android.R.id.content);
        }
        if (root != null) {
            Snackbar sb = Snackbar.make(root, message, Snackbar.LENGTH_SHORT);
            sb.setBackgroundTint(ContextCompat.getColor(activity, R.color.ink));
            sb.setTextColor(ContextCompat.getColor(activity, R.color.white));
            sb.show();
        } else {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Tạo khung BottomSheet chuẩn phong cách tối giản với thanh kéo ở đầu.
     */
    public static LinearLayout createSheetRoot(Context context, String badgeText, String titleText, String subtitleText) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 24), dp(context, 12), dp(context, 24), dp(context, 24));

        View handle = new View(context);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(context, 36), dp(context, 4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dp(context, 18);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_handle);
        root.addView(handle);

        if (badgeText != null && !badgeText.isEmpty()) {
            TextView badge = new TextView(context);
            badge.setText(badgeText);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            badge.setTextColor(ContextCompat.getColor(context, R.color.ink_secondary));
            badge.setBackgroundResource(R.drawable.bg_pill);
            badge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.fill)));
            badge.setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4));
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeLp.bottomMargin = dp(context, 8);
            badge.setLayoutParams(badgeLp);
            root.addView(badge);
        }

        if (titleText != null && !titleText.isEmpty()) {
            TextView title = new TextView(context);
            title.setText(titleText);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setTextColor(ContextCompat.getColor(context, R.color.ink));
            root.addView(title);
        }

        if (subtitleText != null && !subtitleText.isEmpty()) {
            TextView sub = new TextView(context);
            sub.setText(subtitleText);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            sub.setTextColor(ContextCompat.getColor(context, R.color.ink_secondary));
            sub.setLineSpacing(dp(context, 2), 1f);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(context, 6);
            sub.setLayoutParams(subLp);
            root.addView(sub);
        }

        return root;
    }

    public static void addKeyValueRow(Context context, LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(context, 8), 0, dp(context, 8));

        TextView tvLabel = new TextView(context);
        tvLabel.setText(label);
        tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvLabel.setTextColor(ContextCompat.getColor(context, R.color.ink_secondary));
        LinearLayout.LayoutParams lpLabel = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvLabel.setLayoutParams(lpLabel);

        TextView tvValue = new TextView(context);
        tvValue.setText(value);
        tvValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvValue.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvValue.setTextColor(ContextCompat.getColor(context, R.color.ink));
        tvValue.setGravity(Gravity.END);

        row.addView(tvLabel);
        row.addView(tvValue);
        parent.addView(row);
    }

    public static MaterialButton createSheetButton(Context context, String text, boolean isPrimary, boolean isDanger) {
        int styleAttr = isPrimary
                ? com.google.android.material.R.attr.materialButtonStyle
                : com.google.android.material.R.attr.materialButtonOutlinedStyle;
        MaterialButton btn = new MaterialButton(context, null, styleAttr);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setCornerRadius(dp(context, 14));
        btn.setMinHeight(dp(context, 50));
        btn.setInsetTop(0);
        btn.setInsetBottom(0);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (isPrimary) {
            int bg = ContextCompat.getColor(context, isDanger ? R.color.red : R.color.ink);
            btn.setBackgroundTintList(ColorStateList.valueOf(bg));
            btn.setTextColor(ContextCompat.getColor(context, R.color.white));
        } else {
            btn.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.white)));
            btn.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(context,
                    isDanger ? R.color.red : R.color.line_strong)));
            btn.setStrokeWidth(dp(context, 1));
            btn.setTextColor(ContextCompat.getColor(context, isDanger ? R.color.red : R.color.ink));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 10);
        btn.setLayoutParams(lp);
        return btn;
    }

    /**
     * Hộp thoại xác nhận / cảnh báo dạng BottomSheet tối giản thay thế hoàn toàn AlertDialog.
     */
    public static void showConfirmSheet(
            Activity activity,
            String badge,
            String title,
            String message,
            String primaryText,
            boolean isDanger,
            Runnable onPrimary,
            String secondaryText,
            Runnable onSecondary
    ) {
        if (activity == null || activity.isFinishing()) return;
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        LinearLayout root = createSheetRoot(activity, badge, title, message);

        View spacer = new View(activity);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 8)));
        root.addView(spacer);

        if (primaryText != null) {
            MaterialButton btnPrimary = createSheetButton(activity, primaryText, true, isDanger);
            btnPrimary.setOnClickListener(v -> {
                dialog.dismiss();
                if (onPrimary != null) onPrimary.run();
            });
            root.addView(btnPrimary);
        }

        if (secondaryText != null) {
            MaterialButton btnSecondary = createSheetButton(activity, secondaryText, false, false);
            btnSecondary.setOnClickListener(v -> {
                dialog.dismiss();
                if (onSecondary != null) onSecondary.run();
            });
            root.addView(btnSecondary);
        }

        dialog.setContentView(root);
        dialog.show();
    }

    /** Hồ sơ lấy từ API; chỉ ghi nhận thay đổi sau khi server xác nhận. */
    public static void showProfileDialog(Activity activity, Runnable onProfileUpdated) {
        if (activity == null || activity.isFinishing()) return;
        UserViewModel users = viewModel(activity, UserViewModel.class);
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        LinearLayout root = createSheetRoot(activity, null, "Hồ sơ", null);
        LinearLayout body = sheetBody(activity, root);
        MaterialButton edit = createSheetButton(activity, "Đổi tên hiển thị", true, false);
        edit.setEnabled(false);
        edit.setOnClickListener(v -> { dialog.dismiss(); showEditFullNameDialog(activity, onProfileUpdated); });
        root.addView(edit);
        MaterialButton logout = createSheetButton(activity, "Đăng xuất", false, true);
        logout.setOnClickListener(v -> {
            viewModel(activity, AuthViewModel.class).logout(); dialog.dismiss();
            activity.startActivity(new Intent(activity, LoginActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            activity.finish();
        });
        root.addView(logout);
        dialog.setContentView(root); dialog.show();
        Runnable[] load = new Runnable[1];
        load[0] = () -> {
            body.removeAllViews(); addSheetText(activity, body, "Đang tải hồ sơ…");
            observeSheet(activity, dialog, users.loadProfile(), response -> {
                User user = response.getUser();
                body.removeAllViews();
                if (user == null) { addSheetText(activity, body, "Không có dữ liệu hồ sơ."); return; }
                addKeyValueRow(activity, body, "Họ và tên", safe(user.getFullName()));
                addKeyValueRow(activity, body, "Số điện thoại", safe(user.getPhone()));
                addKeyValueRow(activity, body, "Vai trò", user.isRescuer() ? "Đội cứu hộ" : "Người dân");
                addKeyValueRow(activity, body, "Trạng thái", safe(user.getAccountStatus()));
                edit.setEnabled(true);
            }, error -> sheetError(activity, body, error, load[0]));
        };
        load[0].run();
    }

    public static void showRescueTeamSheet(Activity activity, Runnable onProfileUpdated) {
        if (activity == null || activity.isFinishing()) return;
        RescuerViewModel rescuer = viewModel(activity, RescuerViewModel.class);
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        LinearLayout root = createSheetRoot(activity, null, "Đội cứu hộ", null);
        LinearLayout body = scrollingBody(activity, root);
        MaterialButton profile = createSheetButton(activity, "Tài khoản", false, false);
        profile.setOnClickListener(v -> { dialog.dismiss(); showProfileDialog(activity, onProfileUpdated); }); root.addView(profile);
        MaterialButton close = createSheetButton(activity, "Đóng", true, false);
        close.setOnClickListener(v -> dialog.dismiss()); root.addView(close);
        dialog.setContentView(root); dialog.show();
        Runnable[] load = new Runnable[1];
        load[0] = () -> {
            body.removeAllViews(); addSheetText(activity, body, "Đang tải các đội cứu hộ…");
            observeSheet(activity, dialog, rescuer.loadTeams(), response -> {
                body.removeAllViews();
                if (response.getData() == null || response.getData().isEmpty()) { addSheetText(activity, body, "Chưa có thông tin đội cứu hộ."); return; }
                for (RescueTeam team : response.getData()) {
                    if (team == null) continue;
                    addSheetText(activity, body, safe(team.getName()) + " • " + safe(team.getStatus()));
                    if (team.getDescription() != null) addSheetText(activity, body, team.getDescription());
                    if (team.getMembers() != null) for (RescueTeamMember member : team.getMembers()) {
                        if (member == null) continue;
                        String memberName = member.getName();
                        String memberPhone = member.getPhone();
                        String identity = (memberName != null || memberPhone != null)
                                ? safe(memberName) + " • " + safe(memberPhone)
                                : safe(member.getUserId());
                        addSheetText(activity, body, identity + " • " + safe(member.getRole()));
                    }
                }
            }, error -> sheetError(activity, body, error, load[0]));
        };
        load[0].run();
    }

    private static void showEditFullNameDialog(Activity activity, Runnable onProfileUpdated) {
        UserViewModel users = viewModel(activity, UserViewModel.class);
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        LinearLayout root = createSheetRoot(activity, null, "Đổi tên hiển thị", null);
        TextInputLayout inputLayout = new TextInputLayout(activity);
        inputLayout.setHint("Họ và tên");
        TextInputEditText input = new TextInputEditText(inputLayout.getContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        User current = ApiViewModelFactory.getDefault(activity).getSession().getUser();
        input.setText(current == null ? "" : current.getFullName());
        inputLayout.addView(input); root.addView(inputLayout);
        MaterialButton save = createSheetButton(activity, "Lưu", true, false);
        save.setOnClickListener(v -> {
            String name = input.getText() == null ? "" : input.getText().toString();
            save.setEnabled(false); input.setEnabled(false); inputLayout.setError(null);
            observeSheet(activity, dialog, users.updateFullName(name), response -> {
                dialog.dismiss(); showSnack(activity, "Đã cập nhật họ tên");
                if (onProfileUpdated != null) onProfileUpdated.run();
            }, error -> {
                save.setEnabled(true); input.setEnabled(true); inputLayout.setError(error.getMessage());
                if (error.isUnauthorized()) handleSheetAuth(activity, error);
            });
        });
        root.addView(save);
        MaterialButton cancel = createSheetButton(activity, "Hủy", false, false);
        cancel.setOnClickListener(v -> dialog.dismiss()); root.addView(cancel);
        dialog.setContentView(root); dialog.show();
    }

    public static void showNotificationsDialog(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        NotificationViewModel notifications = viewModel(activity, NotificationViewModel.class);
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        LinearLayout root = createSheetRoot(activity, null, "Thông báo", null);
        LinearLayout body = scrollingBody(activity, root);
        MaterialButton close = createSheetButton(activity, "Đóng", true, false);
        close.setOnClickListener(v -> dialog.dismiss()); root.addView(close);
        dialog.setContentView(root); dialog.show();
        Runnable[] load = new Runnable[1];
        load[0] = () -> {
            body.removeAllViews(); addSheetText(activity, body, "Đang tải thông báo…");
            observeSheet(activity, dialog, notifications.loadMyNotifications(), response -> {
                body.removeAllViews();
                if (response.getData() == null || response.getData().isEmpty()) { addSheetText(activity, body, "Bạn chưa có thông báo."); return; }
                for (SosNotification notification : response.getData()) {
                    if (notification == null) continue;
                    TextView message = addSheetText(activity, body, (notification.isRead() ? "" : "● ") + safe(notification.getMessage())
                            + "\n" + ApiDisplay.time(notification.getCreatedAt()));
                    MaterialButton read = createSheetButton(activity, notification.isRead() ? "Đã đọc" : "Đánh dấu đã đọc", false, false);
                    read.setEnabled(!notification.isRead());
                    read.setOnClickListener(v -> {
                        read.setEnabled(false);
                        observeSheet(activity, dialog, notifications.markAsRead(notification.getNotifyId()), result -> {
                            notification.setRead(true); read.setText("Đã đọc");
                            message.setText(safe(notification.getMessage()) + "\n" + ApiDisplay.time(notification.getCreatedAt()));
                        }, error -> { read.setEnabled(true); showSnack(activity, error.getMessage()); handleSheetAuth(activity, error); });
                    });
                    body.addView(read);
                    if (notification.getSosId() != null && !notification.getSosId().trim().isEmpty()) {
                        MaterialButton open = createSheetButton(activity, "Xem yêu cầu SOS", false, false);
                        open.setOnClickListener(v -> {
                            User user = ApiViewModelFactory.getDefault(activity).getSession().getUser();
                            Class<?> target = user != null && user.isRescuer() ? SosDetailActivity.class : TrackSosActivity.class;
                            activity.startActivity(new Intent(activity, target).putExtra(ApiActivity.SOS_ID, notification.getSosId()));
                            dialog.dismiss();
                        });
                        body.addView(open);
                    }
                }
            }, error -> sheetError(activity, body, error, load[0]));
        };
        load[0].run();
    }

    private static <T extends androidx.lifecycle.ViewModel> T viewModel(Activity activity, Class<T> type) {
        return new ViewModelProvider((ViewModelStoreOwner) activity, ApiViewModelFactory.getDefault(activity)).get(type);
    }
    private static LinearLayout sheetBody(Activity activity, LinearLayout root) {
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(-1, -2)); return body;
    }
    private static LinearLayout scrollingBody(Activity activity, LinearLayout root) {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1,
                (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.50))); return body;
    }
    private static TextView addSheetText(Activity activity, LinearLayout root, String text) {
        TextView view = new TextView(activity); view.setText(text); view.setTextSize(14);
        view.setTextColor(ContextCompat.getColor(activity, R.color.ink));
        view.setPadding(0, dp(activity, 10), 0, dp(activity, 10)); root.addView(view); return view;
    }
    private static void sheetError(Activity activity, LinearLayout body, ApiError error, Runnable retry) {
        body.removeAllViews(); addSheetText(activity, body, error.getMessage());
        handleSheetAuth(activity, error);
        MaterialButton button = createSheetButton(activity, "Thử lại", false, false);
        button.setOnClickListener(v -> retry.run()); body.addView(button);
    }
    private static void handleSheetAuth(Activity activity, ApiError error) {
        if (error.isUnauthorized() && activity instanceof ApiActivity) ((ApiActivity) activity).showApiError(error, null);
    }
    private static String safe(String value) { return value == null || value.trim().isEmpty() ? "—" : value; }
    private static <T> void observeSheet(Activity activity, BottomSheetDialog dialog, LiveData<ApiState<T>> source,
            java.util.function.Consumer<T> success, java.util.function.Consumer<ApiError> failure) {
        Observer<ApiState<T>> observer = new Observer<ApiState<T>>() {
            @Override public void onChanged(ApiState<T> state) {
                if (state == null || state.getStatus() == ApiState.Status.LOADING) return;
                source.removeObserver(this);
                if (!dialog.isShowing() || activity.isFinishing()) return;
                if (state.getStatus() == ApiState.Status.SUCCESS) success.accept(state.getData());
                else failure.accept(state.getError());
            }
        };
        if (dialog instanceof LifecycleOwner) source.observe((LifecycleOwner) dialog, observer);
        else {
            source.observe((LifecycleOwner) activity, observer);
            dialog.setOnDismissListener(ignored -> source.removeObserver(observer));
        }
    }
    public static void dialPhone(Context context, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_DIAL);
            intent.setData(Uri.fromParts("tel", phoneNumber, null));
            context.startActivity(intent);
        } catch (Exception e) {
            if (context instanceof Activity) {
                showSnack((Activity) context, "Không mở được ứng dụng gọi điện.");
            }
        }
    }

    public static void sendSms(Context context, String phoneNumber, String message) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_SENDTO);
            intent.setData(Uri.fromParts("smsto", phoneNumber, null));
            intent.putExtra("sms_body", message);
            context.startActivity(intent);
        } catch (Exception e) {
            if (context instanceof Activity) {
                showSnack((Activity) context, "Không mở được ứng dụng nhắn tin.");
            }
        }
    }
}
