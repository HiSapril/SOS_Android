package com.javafinal.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

/**
 * Model ánh xạ bảng nhật ký kiểm toán {@code sos_logs} trong CSDL (so_do_csdl.jpg)
 * và kết quả trả về từ API #13 ({@code GET /api/sos/{id}/logs}).
 */
public class SosLog implements Serializable {

    public static final String ACTION_CREATE_SOS = "CREATE_SOS";
    public static final String ACTION_ACCEPT_SOS = "ACCEPT_SOS";
    public static final String ACTION_UPDATE_STATUS = "UPDATE_STATUS";
    public static final String ACTION_RELEASE_SOS = "RELEASE_SOS";
    public static final String ACTION_COMPLETE_SOS = "COMPLETE_SOS";
    public static final String ACTION_CANCEL_SOS = "CANCEL_SOS";
    public static final String ACTION_UPDATE_VISIBILITY = "UPDATE_VISIBILITY";

    /** PK uuid - Hỗ trợ cả "log_id" (CSDL) và "id" (API JSON) */
    @SerializedName(value = "log_id", alternate = {"id"})
    private String logId;

    /** FK uuid -> sos_requests.id */
    @SerializedName("sos_id")
    private String sosId;

    /** FK uuid -> users.id */
    @SerializedName("user_id")
    private String userId;

    /** varchar - Mã hành động (VD: "CREATE_SOS", "ACCEPT_SOS", ...) */
    @SerializedName("action")
    private String action;

    /** text - Diễn giải chi tiết hành động */
    @SerializedName("description")
    private String description;

    /** timestamptz - Thời điểm ghi nhận nhật ký */
    @SerializedName("created_at")
    private String createdAt;

    public SosLog() {
    }

    public SosLog(
            String logId,
            String sosId,
            String userId,
            String action,
            String description,
            String createdAt
    ) {
        this.logId = logId;
        this.sosId = sosId;
        this.userId = userId;
        this.action = action;
        this.description = description;
        this.createdAt = createdAt;
    }

    public String getLogId() {
        return logId;
    }

    public void setLogId(String logId) {
        this.logId = logId;
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

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
