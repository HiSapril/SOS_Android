package com.javafinal.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Các lớp Request / Response DTO cho các nhóm API:
 * - Nhóm 3: Danh mục Loại Khẩn Cấp (#27 -> #31: {@code /api/emergency-types})
 * - Nhóm 4: SOS dành cho Người dân (#07 -> #13: {@code /api/sos})
 * - Nhóm 5: Thông báo (#14 -> #15: {@code /api/sos/notifications})
 * - Nhóm 6: Đội cứu hộ (#16 -> #22: {@code /api/rescuer})
 * - Nhóm 7: Tọa độ GPS (#23 -> #25: {@code /api/location})
 */
public final class SosApiModels {

    private SosApiModels() {
    }

    // =========================================================================
    // NHÓM 3: DANH MỤC LOẠI KHẨN CẤP (/api/emergency-types) - API #27 -> #31
    // =========================================================================

    /**
     * Body gửi lên API #29 ({@code POST /api/emergency-types})
     * và API #30 ({@code PUT /api/emergency-types/{id}}).
     */
    public static class EmergencyTypeUpsertRequest implements Serializable {
        @SerializedName("name")
        private String name;

        @SerializedName("description")
        private String description;

        public EmergencyTypeUpsertRequest() {
        }

        public EmergencyTypeUpsertRequest(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    /**
     * Kết quả trả về từ API #27 ({@code GET /api/emergency-types}).
     */
    public static class EmergencyTypeListResponse implements Serializable {
        @SerializedName("success")
        private boolean success;

        @SerializedName("count")
        private int count;

        @SerializedName("data")
        private List<EmergencyType> data = new ArrayList<>();

        public boolean isSuccess() {
            return success;
        }

        public int getCount() {
            return count;
        }

        public List<EmergencyType> getData() {
            return data;
        }
    }

    /**
     * Kết quả trả về từ API #28, #29, #30, #31 ({@code /api/emergency-types/{id}}).
     */
    public static class EmergencyTypeSingleResponse implements Serializable {
        @SerializedName("success")
        private boolean success;

        @SerializedName("message")
        private String message;

        @SerializedName("data")
        private EmergencyType data;

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public EmergencyType getData() {
            return data;
        }
    }

    // =========================================================================
    // NHÓM 4: SOS DÀNH CHO NGƯỜI DÂN (/api/sos) - API #07 -> #13
    // =========================================================================

    /**
     * Body gửi lên API #07 ({@code POST /api/sos}).
     */
    public static class CreateSosRequest implements Serializable {
        @SerializedName("emergency_type")
        private String emergencyType;

        @SerializedName("emergency_type_id")
        private String emergencyTypeId;

        @SerializedName("description")
        private String description;

        @SerializedName("visibility")
        private String visibility;

        @SerializedName("initial_lat")
        private double initialLat;

        @SerializedName("initial_lng")
        private double initialLng;

        public CreateSosRequest() {
        }

        public CreateSosRequest(
                EmergencyType type,
                String description,
                String visibility,
                double initialLat,
                double initialLng
        ) {
            this(type != null ? type.getId() : null,
                    type != null ? type.getName() : null,
                    description, visibility, initialLat, initialLng);
        }

        public CreateSosRequest(
                String emergencyTypeId,
                String emergencyType,
                String description,
                String visibility,
                double initialLat,
                double initialLng
        ) {
            this.emergencyTypeId = emergencyTypeId;
            this.emergencyType = emergencyType;
            this.description = description;
            this.visibility = visibility;
            this.initialLat = initialLat;
            this.initialLng = initialLng;
        }

        public CreateSosRequest(
                String emergencyType,
                String description,
                String visibility,
                double initialLat,
                double initialLng
        ) {
            this.emergencyType = emergencyType;
            this.description = description;
            this.visibility = visibility;
            this.initialLat = initialLat;
            this.initialLng = initialLng;
        }

        public String getEmergencyType() {
            return emergencyType;
        }

        public void setEmergencyType(String emergencyType) {
            this.emergencyType = emergencyType;
        }

        public String getEmergencyTypeId() {
            return emergencyTypeId;
        }

        public void setEmergencyTypeId(String emergencyTypeId) {
            this.emergencyTypeId = emergencyTypeId;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getVisibility() {
            return visibility;
        }

        public void setVisibility(String visibility) {
            this.visibility = visibility;
        }

        public double getInitialLat() {
            return initialLat;
        }

        public void setInitialLat(double initialLat) {
            this.initialLat = initialLat;
        }

        public double getInitialLng() {
            return initialLng;
        }

        public void setInitialLng(double initialLng) {
            this.initialLng = initialLng;
        }
    }

    /**
     * Kết quả trả về từ API #07 ({@code POST /api/sos}),
     * API #10 ({@code GET /api/sos/{id}}) và API #11 ({@code PUT /api/sos/{id}/visibility}).
     */
    public static class SosSingleResponse implements Serializable {
        @SerializedName("success")
        private boolean success;

        @SerializedName("message")
        private String message;

        @SerializedName("sos")
        private SosRequest sos;

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public SosRequest getSos() {
            if (sos != null) {
                sos.syncFromNestedApiObjects();
            }
            return sos;
        }
    }

    /**
     * Kết quả trả về từ API #08 ({@code GET /api/sos/my-active})
     * và API #17 ({@code GET /api/rescuer/my-active}).
     */
    public static class ActiveSosResponse implements Serializable {
        @SerializedName("active")
        private boolean active;

        @SerializedName(value = "sos", alternate = {"mission", "case"})
        private SosRequest sos;

        public boolean isActive() {
            return active;
        }

        public SosRequest getSos() {
            if (sos != null) {
                sos.syncFromNestedApiObjects();
            }
            return sos;
        }
    }

    /**
     * Kết quả trả về từ API #09 ({@code GET /api/sos/public/map}).
     */
    public static class PublicMapResponse implements Serializable {
        @SerializedName("success")
        private boolean success;

        @SerializedName("count")
        private int count;

        @SerializedName("data")
        private List<PublicSosPoint> data = new ArrayList<>();

        public boolean isSuccess() {
            return success;
        }

        public int getCount() {
            return count;
        }

        public List<PublicSosPoint> getData() {
            return data;
        }
    }

    /**
     * Body gửi lên API #11 ({@code PUT /api/sos/{id}/visibility}).
     */
    public static class UpdateVisibilityRequest implements Serializable {
        @SerializedName("visibility")
        private String visibility;

        public UpdateVisibilityRequest() {
        }

        public UpdateVisibilityRequest(String visibility) {
            this.visibility = visibility;
        }

        public String getVisibility() {
            return visibility;
        }

        public void setVisibility(String visibility) {
            this.visibility = visibility;
        }
    }

    /**
     * Kết quả trả về từ API #12 ({@code POST /api/sos/{id}/cancel}).
     */
    public static class CancelSosResponse implements Serializable {
        @SerializedName("success")
        private boolean success;

        @SerializedName("message")
        private String message;

        @SerializedName("status")
        private String status;

        @SerializedName("end_reason")
        private String endReason;

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public String getStatus() {
            return status;
        }

        public String getEndReason() {
            return endReason;
        }
    }

    /**
     * Kết quả trả về từ API #13 ({@code GET /api/sos/{id}/logs}).
     */
    public static class SosLogsResponse implements Serializable {
        @SerializedName("success")
        private boolean success;

        @SerializedName("sos_id")
        private String sosId;

        @SerializedName("count")
        private int count;

        @SerializedName(value = "logs", alternate = {"data"})
        private List<SosLog> logs = new ArrayList<>();

        public boolean isSuccess() {
            return success;
        }

        public String getSosId() {
            return sosId;
        }

        public int getCount() {
            return count;
        }

        public List<SosLog> getLogs() {
            return logs;
        }
    }

    // =========================================================================
    // NHÓM 5: THÔNG BÁO (/api/sos/notifications) - API #14 -> #15
    // =========================================================================

    /**
     * Kết quả trả về từ API #14 ({@code GET /api/sos/notifications/my}).
     */
    public static class NotificationsResponse implements Serializable {
        @SerializedName("success")
        private boolean success;

        @SerializedName("count")
        private int count;

        @SerializedName("data")
        private List<SosNotification> data = new ArrayList<>();

        public boolean isSuccess() {
            return success;
        }

        public int getCount() {
            return count;
        }

        public List<SosNotification> getData() {
            return data;
        }
    }

    /**
     * Kết quả trả về từ API #15 ({@code PUT /api/sos/notifications/{id}/read}).
     */
    public static class MarkNotificationReadResponse implements Serializable {
        @SerializedName("success")
        private boolean success;

        @SerializedName("message")
        private String message;

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    }

    // =========================================================================
    // NHÓM 6: ĐỘI CỨU HỘ (/api/rescuer) - API #16 -> #22
    // =========================================================================

    /**
     * Kết quả trả về từ API #16 ({@code GET /api/rescuer/queue}).
     */
    public static class RescuerQueueResponse implements Serializable {
        @SerializedName("success")
        private boolean success;

        @SerializedName("count")
        private int count;

        @SerializedName("data")
        private List<SosRequest> data = new ArrayList<>();

        public boolean isSuccess() {
            return success;
        }

        public int getCount() {
            return count;
        }

        public List<SosRequest> getData() {
            if (data != null) {
                for (SosRequest item : data) {
                    if (item != null) item.syncFromNestedApiObjects();
                }
            }
            return data;
        }
    }

    /**
     * Kết quả trả về từ API #18 ({@code POST /api/rescuer/sos/{id}/accept}).
     */
    public static class AcceptSosResponse implements Serializable {
        @SerializedName("success")
        private boolean success;

        @SerializedName("message")
        private String message;

        @SerializedName("sos_id")
        private String sosId;

        @SerializedName("status")
        private String status;

        @SerializedName("team_id")
        private String teamId;

        @SerializedName("team_name")
        private String teamName;

        @SerializedName("rescuer")
        private SosRequest.RescuerInfo rescuer;

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public String getSosId() {
            return sosId;
        }

        public String getStatus() {
            return status;
        }

        public String getTeamId() {
            return teamId;
        }

        public String getTeamName() {
            return teamName;
        }

        public SosRequest.RescuerInfo getRescuer() {
            return rescuer;
        }
    }

    /**
     * Body gửi lên API #19 ({@code POST /api/rescuer/sos/{id}/status}).
     */
    public static class UpdateMissionStatusRequest implements Serializable {
        @SerializedName("status")
        private String status;

        public UpdateMissionStatusRequest() {
        }

        public UpdateMissionStatusRequest(String status) {
            this.status = status;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    /**
     * Kết quả trả về từ API #19 ({@code status}), API #20 ({@code release})
     * và API #21 ({@code complete}).
     */
    public static class MissionActionResponse implements Serializable {
        @SerializedName("success")
        private boolean success;

        @SerializedName("message")
        private String message;

        @SerializedName("sos_id")
        private String sosId;

        @SerializedName("status")
        private String status;

        @SerializedName("end_reason")
        private String endReason;

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public String getSosId() {
            return sosId;
        }

        public String getStatus() {
            return status;
        }

        public String getEndReason() {
            return endReason;
        }
    }

    /**
     * Kết quả trả về từ API #22 ({@code GET /api/rescuer/teams}).
     */
    public static class RescueTeamsResponse implements Serializable {
        @SerializedName("success")
        private boolean success;

        @SerializedName("count")
        private int count;

        @SerializedName("data")
        private List<RescueTeam> data = new ArrayList<>();

        public boolean isSuccess() {
            return success;
        }

        public int getCount() {
            return count;
        }

        public List<RescueTeam> getData() {
            return data;
        }
    }

    // =========================================================================
    // NHÓM 7: TỌA ĐỘ GPS (/api/location) - API #23 -> #25
    // =========================================================================

    /**
     * Body gửi lên API #23 ({@code POST /api/location/{sos_id}}).
     */
    public static class UpdateLocationRequest implements Serializable {
        @SerializedName("lat")
        private double lat;

        @SerializedName("lng")
        private double lng;

        @SerializedName("accuracy_m")
        private double accuracyM;

        @SerializedName("save_history")
        private boolean saveHistory;

        public UpdateLocationRequest() {
        }

        public UpdateLocationRequest(double lat, double lng, double accuracyM, boolean saveHistory) {
            this.lat = lat;
            this.lng = lng;
            this.accuracyM = accuracyM;
            this.saveHistory = saveHistory;
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

        public boolean isSaveHistory() {
            return saveHistory;
        }

        public void setSaveHistory(boolean saveHistory) {
            this.saveHistory = saveHistory;
        }
    }

    /**
     * Kết quả trả về từ API #23 ({@code POST /api/location/{sos_id}}).
     */
    public static class UpdateLocationResponse implements Serializable {
        @SerializedName("success")
        private boolean success;

        @SerializedName("actor")
        private String actor;

        @SerializedName("lat")
        private double lat;

        @SerializedName("lng")
        private double lng;

        @SerializedName("updated_at")
        private String updatedAt;

        public boolean isSuccess() {
            return success;
        }

        public String getActor() {
            return actor;
        }

        public double getLat() {
            return lat;
        }

        public double getLng() {
            return lng;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }
    }

    /**
     * Kết quả trả về từ API #24 ({@code GET /api/location/{sos_id}/current}).
     */
    public static class LocationCurrentResponse implements Serializable {
        @SerializedName("success")
        private boolean success;

        @SerializedName("sos_id")
        private String sosId;

        @SerializedName("locations")
        private SosRequest.LocationsPair locations;

        public boolean isSuccess() {
            return success;
        }

        public String getSosId() {
            return sosId;
        }

        public SosRequest.LocationsPair getLocations() {
            return locations;
        }
    }

    /**
     * Kết quả trả về từ API #25 ({@code GET /api/location/{sos_id}/trail}).
     */
    public static class LocationTrailResponse implements Serializable {
        @SerializedName("success")
        private boolean success;

        @SerializedName("sos_id")
        private String sosId;

        @SerializedName("count")
        private int count;

        @SerializedName("trail")
        private List<LocationHistory> trail = new ArrayList<>();

        public boolean isSuccess() {
            return success;
        }

        public String getSosId() {
            return sosId;
        }

        public int getCount() {
            return count;
        }

        public List<LocationHistory> getTrail() {
            return trail;
        }
    }
}
