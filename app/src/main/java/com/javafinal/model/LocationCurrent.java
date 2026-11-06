package com.javafinal.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

/**
 * Model ánh xạ bảng {@code location_current} trong CSDL (so_do_csdl.jpg)
 * và kết quả trả về từ các API:
 * - {@code POST /api/location/{sos_id}} (#23)
 * - {@code GET /api/location/{sos_id}/current} (#24)
 * - Đối tượng {@code locations} lồng trong {@code GET /api/sos/my-active} (#08) và {@code GET /api/rescuer/my-active} (#17)
 */
public class LocationCurrent implements Serializable {

    public static final String ACTOR_VICTIM = "VICTIM";
    public static final String ACTOR_RESCUER = "RESCUER";

    /** PK uuid */
    @SerializedName(value = "loc_id", alternate = {"id"})
    private String locId;

    /** FK uuid -> sos_requests.id */
    @SerializedName("sos_id")
    private String sosId;

    /** varchar - Vai trò phát tọa độ ("VICTIM" hoặc "RESCUER") */
    @SerializedName("actor")
    private String actor;

    /** float - Vĩ độ GPS hiện tại */
    @SerializedName("lat")
    private double lat;

    /** float - Kinh độ GPS hiện tại */
    @SerializedName("lng")
    private double lng;

    /** real - Sai số GPS tính theo mét */
    @SerializedName("accuracy_m")
    private double accuracyM;

    /** timestamptz - Thời điểm cập nhật gần nhất */
    @SerializedName("updated_at")
    private String updatedAt;

    public LocationCurrent() {
    }

    public LocationCurrent(
            String locId,
            String sosId,
            String actor,
            double lat,
            double lng,
            double accuracyM,
            String updatedAt
    ) {
        this.locId = locId;
        this.sosId = sosId;
        this.actor = actor;
        this.lat = lat;
        this.lng = lng;
        this.accuracyM = accuracyM;
        this.updatedAt = updatedAt;
    }

    public String getLocId() {
        return locId;
    }

    public void setLocId(String locId) {
        this.locId = locId;
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

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
