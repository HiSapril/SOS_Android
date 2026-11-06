package com.javafinal.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

/**
 * Model ánh xạ bảng {@code location_history} trong CSDL (so_do_csdl.jpg)
 * và kết quả trả về từ API #25 ({@code GET /api/location/{sos_id}/trail}).
 */
public class LocationHistory implements Serializable {

    public static final String ACTOR_VICTIM = "VICTIM";
    public static final String ACTOR_RESCUER = "RESCUER";

    /** PK uuid - Hố trợ cả tên cột "history_id" (CSDL) và "id" (API trail) */
    @SerializedName(value = "history_id", alternate = {"id"})
    private String historyId;

    /** FK uuid -> sos_requests.id */
    @SerializedName("sos_id")
    private String sosId;

    /** varchar - Vai trò phát tọa độ ("VICTIM" hoặc "RESCUER") */
    @SerializedName("actor")
    private String actor;

    /** float - Vĩ độ GPS */
    @SerializedName("lat")
    private double lat;

    /** float - Kinh độ GPS */
    @SerializedName("lng")
    private double lng;

    /** real - Sai số GPS tính theo mét */
    @SerializedName("accuracy_m")
    private double accuracyM;

    /** timestamptz - Thời điểm ghi nhận điểm tọa độ ("created_at" hoặc "recorded_at" trong API #25) */
    @SerializedName(value = "created_at", alternate = {"recorded_at"})
    private String createdAt;

    public LocationHistory() {
    }

    public LocationHistory(
            String historyId,
            String sosId,
            String actor,
            double lat,
            double lng,
            double accuracyM,
            String createdAt
    ) {
        this.historyId = historyId;
        this.sosId = sosId;
        this.actor = actor;
        this.lat = lat;
        this.lng = lng;
        this.accuracyM = accuracyM;
        this.createdAt = createdAt;
    }

    public String getHistoryId() {
        return historyId;
    }

    public void setHistoryId(String historyId) {
        this.historyId = historyId;
    }

    public String getSosId() {
        return sosId;
    }

    public void setSosId(String sosId) {
        this.sosId = sosId;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public double getLat() {
        return lat;
    }

    public void setLat(double lat) {
        this.lat = lat;
    }

    public double getLng() {
        return lng;
    }

    public void setLng(double lng) {
        this.lng = lng;
    }

    public double getAccuracyM() {
        return accuracyM;
    }

    public void setAccuracyM(double accuracyM) {
        this.accuracyM = accuracyM;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getRecordedAt() {
        return createdAt;
    }
}
