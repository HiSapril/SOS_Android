package com.javafinal.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

/**
 * Model ánh xạ View {@code public_sos_map} và dữ liệu điểm cảnh báo công khai
 * từ API #09 ({@code GET /api/sos/public/map}).
 * Tọa độ đã được làm mờ về 2 chữ số thập phân (sai số ~1km) nhằm bảo vệ quyền riêng tư nạn nhân.
 */
public class PublicSosPoint implements Serializable {

    /** UUID phiếu SOS */
    @SerializedName("sos_id")
    private String sosId;

    /** FK UUID -> emergency_types.id */
    @SerializedName("emergency_type_id")
    private String emergencyTypeId;

    /** Mã loại khẩn cấp từ bảng emergency_types */
    @SerializedName("emergency_type")
    private String emergencyType;

    /** Vĩ độ vùng đã làm tròn 2 chữ số thập phân */
    @SerializedName("area_lat")
    private double areaLat;

    /** Kinh độ vùng đã làm tròn 2 chữ số thập phân */
    @SerializedName("area_lng")
    private double areaLng;

    /** Thời điểm phát tín hiệu SOS */
    @SerializedName("created_at")
    private String createdAt;

    public PublicSosPoint() {
    }

    public PublicSosPoint(
            String sosId,
            String emergencyType,
            double areaLat,
            double areaLng,
            String createdAt
    ) {
        this.sosId = sosId;
        this.emergencyType = emergencyType;
        this.areaLat = areaLat;
        this.areaLng = areaLng;
        this.createdAt = createdAt;
    }

    public String getEmergencyTypeLabel() {
        return EmergencyType.resolveLabel(emergencyTypeId, emergencyType);
    }

    public String getSosId() {
        return sosId;
    }

    public void setSosId(String sosId) {
        this.sosId = sosId;
    }

    public String getEmergencyTypeId() {
        return emergencyTypeId;
    }

    public void setEmergencyTypeId(String emergencyTypeId) {
        this.emergencyTypeId = emergencyTypeId;
    }

    public String getEmergencyType() {
        return emergencyType;
    }

    public void setEmergencyType(String emergencyType) {
        this.emergencyType = emergencyType;
    }

    public double getAreaLat() {
        return areaLat;
    }

    public void setAreaLat(double areaLat) {
        this.areaLat = areaLat;
    }

    public double getAreaLng() {
        return areaLng;
    }

    public void setAreaLng(double areaLng) {
        this.areaLng = areaLng;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
