package com.javafinal.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

/**
 * Model ánh xạ bảng {@code sos_notifications} trong CSDL (so_do_csdl.jpg)
 * và nhóm API Thông báo (#14: {@code GET /api/sos/notifications/my},
 * #15: {@code PUT /api/sos/notifications/{id}/read}).
 */
public class SosNotification implements Serializable {

    public static final String TYPE_SYSTEM = "SYSTEM";
    public static final String TYPE_SOS_STATUS = "SOS_STATUS";
    public static final String TYPE_ALERT = "ALERT";

    /** PK uuid - Hỗ trợ cả "notify_id" (CSDL) và "id" (API JSON) */
    @SerializedName(value = "notify_id", alternate = {"id"})
    private String notifyId;

    /** FK uuid -> sos_requests.id */
    @SerializedName("sos_id")
    private String sosId;

    /** FK uuid -> users.id */
    @SerializedName("user_id")
    private String userId;

    /** text - Nội dung thông báo */
    @SerializedName("message")
    private String message;

    /** varchar - Phân loại thông báo (VD: "SYSTEM") */
    @SerializedName("type")
    private String type;

    /** boolean - Trạng thái đã đọc hay chưa */
    @SerializedName("is_read")
    private boolean isRead;

    /** timestamptz - Thời điểm phát sinh thông báo */
    @SerializedName("created_at")
    private String createdAt;

    public SosNotification() {
    }

    public SosNotification(
            String notifyId,
            String sosId,
            String userId,
            String message,
            String type,
            boolean isRead,
            String createdAt
    ) {
        this.notifyId = notifyId;
        this.sosId = sosId;
        this.userId = userId;
        this.message = message;
        this.type = type;
        this.isRead = isRead;
        this.createdAt = createdAt;
    }

    public String getNotifyId() {
        return notifyId;
    }

    public void setNotifyId(String notifyId) {
        this.notifyId = notifyId;
    }

    public String getSosId() {
        return sosId;
    }

    public void setSosId(String sosId) {
        this.sosId = sosId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isRead() {
        return isRead;
    }

    public void setRead(boolean read) {
        isRead = read;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
