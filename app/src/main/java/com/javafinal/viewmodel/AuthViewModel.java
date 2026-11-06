package com.javafinal.viewmodel;

import androidx.lifecycle.LiveData;
import com.google.gson.JsonObject;
import com.javafinal.model.AuthModels;
import com.javafinal.model.User;
import com.javafinal.network.ApiClient;

/** API #01–04. Vai trò đăng nhập lấy từ response server, không từ lựa chọn UI. */
public final class AuthViewModel extends BaseApiViewModel {
    public AuthViewModel(ApiClient api) { super(api); }

    public LiveData<ApiState<AuthModels.RequestOtpResponse>> requestOtp(String phone, String role) {
        AuthModels.RequestOtpRequest body = new AuthModels.RequestOtpRequest();
        return request("POST", "api/auth/request-otp", body, false, AuthModels.RequestOtpResponse.class,
                () -> {
                    body.setPhone(InputValidator.phone(phone));
                    InputValidator.role(role);
                    body.setRole(role);
                }, null);
    }

    public LiveData<ApiState<AuthModels.VerifyOtpResponse>> verifyOtp(String phone, String otp) {
        AuthModels.VerifyOtpRequest body = new AuthModels.VerifyOtpRequest();
        long version = api.getSession().getVersion();
        return request("POST", "api/auth/verify-otp", body, false, AuthModels.VerifyOtpResponse.class,
                () -> {
                    body.setPhone(InputValidator.phone(phone));
                    String value = InputValidator.required(otp, "Mã OTP");
                    if (!value.matches("[0-9]{6}")) throw new IllegalArgumentException("OTP phải gồm 6 chữ số.");
                    body.setOtp(value);
                }, response -> {
                    if (version != api.getSession().getVersion()) {
                        throw new IllegalArgumentException("Phiên đăng nhập đã thay đổi. Vui lòng thử lại.");
                    }
                    if (response.getAccessToken() == null || response.getUser() == null) {
                        throw new IllegalArgumentException("Phản hồi đăng nhập thiếu token hoặc tài khoản.");
                    }
                    api.getSession().save(response.getAccessToken(), response.getUser());
                });
    }

    public LiveData<ApiState<AuthModels.UserResponse>> loadMe() {
        return request("GET", "api/auth/me", null, true, AuthModels.UserResponse.class, null,
                response -> {
                    if (response.getUser() == null) throw new IllegalArgumentException("Phản hồi thiếu tài khoản.");
                    api.getSession().updateUser(response.getUser());
                });
    }

    /** DEV only. Tài liệu chưa mô tả response nên giữ JSON nguyên gốc. */
    public LiveData<ApiState<JsonObject>> devLinkTelegram(String phone, long telegramChatId) {
        JsonObject body = new JsonObject();
        return request("POST", "api/auth/dev-link-telegram", body, false, JsonObject.class,
                () -> {
                    body.addProperty("phone", InputValidator.phone(phone));
                    if (telegramChatId == 0) throw new IllegalArgumentException("Telegram Chat ID không hợp lệ.");
                    body.addProperty("telegram_chat_id", telegramChatId);
                }, null);
    }

    public User getCurrentUser() { return api.getSession().getUser(); }
    public boolean isLoggedIn() { return api.getSession().isLoggedIn(); }

    public void logout() {
        cancelRequests();
        api.getSession().clear();
    }
}
