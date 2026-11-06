package com.javafinal.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

/**
 * Các lớp Request / Response DTO cho Nhóm 1 (Auth: #01, #02, #03)
 * và Nhóm 2 (Users: #04, #05, #06) theo đặc tả BANG_API_VA_LUONG_HOAT_DONG.md.
 */
public final class AuthModels {

    private AuthModels() {
    }

    /**
     * Body gửi lên API #01: {@code POST /api/auth/request-otp}
     */
    public static class RequestOtpRequest implements Serializable {
        @SerializedName("phone")
        private String phone;

        @SerializedName("role")
        private String role;

        public RequestOtpRequest() {
        }

        public RequestOtpRequest(String phone, String role) {
            this.phone = phone;
            this.role = role;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }
    }

    /**
     * Kết quả trả về từ API #01: {@code POST /api/auth/request-otp}
     */
    public static class RequestOtpResponse implements Serializable {
        @SerializedName("success")
        private boolean success;

        @SerializedName("message")
        private String message;

        @SerializedName("phone")
        private String phone;

        @SerializedName("expires_in_minutes")
        private int expiresInMinutes;

        /** Thông báo lỗi khi chưa liên kết Telegram (HTTP 400) */
        @SerializedName("detail")
        private String detail;

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public int getExpiresInMinutes() {
            return expiresInMinutes;
        }

        public void setExpiresInMinutes(int expiresInMinutes) {
            this.expiresInMinutes = expiresInMinutes;
        }

        public String getDetail() {
            return detail;
        }

        public void setDetail(String detail) {
            this.detail = detail;
        }
    }

    /**
     * Body gửi lên API #02: {@code POST /api/auth/verify-otp}
     */
    public static class VerifyOtpRequest implements Serializable {
        @SerializedName("phone")
        private String phone;

        @SerializedName("otp")
        private String otp;

        public VerifyOtpRequest() {
        }

        public VerifyOtpRequest(String phone, String otp) {
            this.phone = phone;
            this.otp = otp;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getOtp() {
            return otp;
        }

        public void setOtp(String otp) {
            this.otp = otp;
        }
    }

    /**
     * Kết quả trả về từ API #02: {@code POST /api/auth/verify-otp}
     */
    public static class VerifyOtpResponse implements Serializable {
        @SerializedName("success")
        private boolean success;

        @SerializedName("is_new_user")
        private boolean isNewUser;

        @SerializedName("access_token")
        private String accessToken;

        @SerializedName("token_type")
        private String tokenType;

        @SerializedName("user")
        private User user;

        @SerializedName("detail")
        private String detail;

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public boolean isNewUser() {
            return isNewUser;
        }

        public void setNewUser(boolean newUser) {
            isNewUser = newUser;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        public String getTokenType() {
            return tokenType;
        }

        public void setTokenType(String tokenType) {
            this.tokenType = tokenType;
        }

        public User getUser() {
            return user;
        }

        public void setUser(User user) {
            this.user = user;
        }

        public String getDetail() {
            return detail;
        }

        public void setDetail(String detail) {
            this.detail = detail;
        }
    }

    /**
     * Body gửi lên API #05 ({@code PUT /api/users/full-name})
     * và API #06 ({@code PUT /api/users/profile}).
     */
    public static class UpdateFullNameRequest implements Serializable {
        @SerializedName("full_name")
        private String fullName;

        public UpdateFullNameRequest() {
        }

        public UpdateFullNameRequest(String fullName) {
            this.fullName = fullName;
        }

        public String getFullName() {
            return fullName;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }
    }

    /**
     * Kết quả trả về từ API #03 ({@code GET /api/auth/me}),
     * API #04 ({@code GET /api/users/profile}),
     * API #05 ({@code PUT /api/users/full-name}) và API #06 ({@code PUT /api/users/profile}).
     */
    public static class UserResponse implements Serializable {
        @SerializedName("success")
        private boolean success;

        @SerializedName("message")
        private String message;

        @SerializedName("user")
        private User user;

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public User getUser() {
            return user;
        }

        public void setUser(User user) {
            this.user = user;
        }
    }
}
