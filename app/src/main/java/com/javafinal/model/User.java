package com.javafinal.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

/**
 * Model ánh xạ bảng {@code users} trong CSDL (so_do_csdl.jpg)
 * và đối tượng {@code user} trong nhóm API Auth & Users (#02, #03, #04, #05, #06).
 */
public class User implements Serializable {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_RESCUER = "RESCUER";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_LOCKED = "LOCKED";
    public static final String STATUS_INACTIVE = "INACTIVE";

    /** PK uuid */
    @SerializedName("id")
    private String id;

    /** varchar - Họ và tên hiển thị */
    @SerializedName("full_name")
    private String fullName;

    /** UK varchar - Số điện thoại định danh */
    @SerializedName("phone")
    private String phone;

    /** UK bigint - Chat ID của tài khoản Telegram liên kết nhận OTP */
    @SerializedName("telegram_chat_id")
    private Long telegramChatId;

    /** Vai trò trả về từ JWT / API Auth ("USER" hoặc "RESCUER") */
    @SerializedName("role")
    private String role;

    /** varchar - Trạng thái tài khoản (VD: "ACTIVE") */
    @SerializedName("account_status")
    private String accountStatus;

    /** boolean - Cờ xác thực tài khoản hợp lệ */
    @SerializedName("is_valid")
    private boolean isValid;

    /** timestamptz - Thời điểm tạo tài khoản (ISO-8601) */
    @SerializedName("created_at")
    private String createdAt;

    public User() {
    }

    public User(
            String id,
            String fullName,
            String phone,
            Long telegramChatId,
            String role,
            String accountStatus,
            boolean isValid,
            String createdAt
    ) {
        this.id = id;
        this.fullName = fullName;
        this.phone = phone;
        this.telegramChatId = telegramChatId;
        this.role = role;
        this.accountStatus = accountStatus;
        this.isValid = isValid;
        this.createdAt = createdAt;
    }

    public boolean isRescuer() {
        return ROLE_RESCUER.equalsIgnoreCase(role);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Long getTelegramChatId() {
        return telegramChatId;
    }

    public void setTelegramChatId(Long telegramChatId) {
        this.telegramChatId = telegramChatId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getAccountStatus() {
        return accountStatus;
    }

    public void setAccountStatus(String accountStatus) {
        this.accountStatus = accountStatus;
    }

    public boolean isValid() {
        return isValid;
    }

    public void setValid(boolean valid) {
        isValid = valid;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
