package com.javafinal.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Model ánh xạ bảng {@code sos_requests} trong CSDL (so_do_csdl.jpg)
 * và kết quả trả về từ các API SOS & Rescuer (#07, #08, #10, #16, #17, #18, #19, #20, #21)
 * trong đặc tả BANG_API_VA_LUONG_HOAT_DONG.md.
 */
public class SosRequest implements Serializable {

    // Chế độ hiển thị visibility
    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    public static final String VISIBILITY_PRIVATE = "PRIVATE";

    // Vòng đời trạng thái chuẩn (State Machine Mục 4 - Luồng 5)
    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_RESCUING = "RESCUING";
    public static final String STATUS_ARRIVED = "ARRIVED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    // Lý do kết thúc (end_reason)
    public static final String END_RESCUER_COMPLETED = "RESCUER_COMPLETED";
    public static final String END_USER_CANCELLED = "USER_CANCELLED";

    /** PK uuid - Hỗ trợ cả "id" (bảng sos_requests) và "sos_id" (API queue/active) */
    @SerializedName(value = "id", alternate = {"sos_id"})
    private String id;

    /** FK uuid -> users.id */
    @SerializedName("requester_id")
    private String requesterId;

    private String requesterName;
    private String requesterPhone;

    /** FK uuid -> rescue_teams.team_id */
    @SerializedName("team_id")
    private String teamId;

    @SerializedName("rescuer_id")
    private String rescuerId;

    private String rescuerName;
    private String rescuerPhone;
    private String teamName;

    /** FK uuid -> emergency_types.id (CSDL so_do_csdl.jpg) */
    @SerializedName("emergency_type_id")
    private String emergencyTypeId;

    /** Mã loại khẩn cấp chuẩn ("NGAP_LUT", "Y_TE", "MAC_KET", ...) */
    @SerializedName("emergency_type")
    private String emergencyType;

    /** text - Mô tả chi tiết tình trạng hiện trường */
    @SerializedName("description")
    private String description;

    /** varchar - Chế độ hiển thị ("PUBLIC" hoặc "PRIVATE") */
    @SerializedName("visibility")
    private String visibility;

    /** varchar - Trạng thái cứu hộ ("WAITING", "ACCEPTED", "RESCUING", "ARRIVED", "COMPLETED", "CANCELLED") */
    @SerializedName("status")
    private String status;

    /** varchar - Lý do kết thúc ("RESCUER_COMPLETED", "USER_CANCELLED") */
    @SerializedName("end_reason")
    private String endReason;

    /** timestamptz - Thời điểm khởi tạo */
    @SerializedName("created_at")
    private String createdAt;

    /** timestamptz - Thời điểm cập nhật gần nhất (CSDL so_do_csdl.jpg) */
    @SerializedName("updated_at")
    private String updatedAt;

    // Tọa độ khởi tạo ban đầu
    @SerializedName("initial_lat")
    private Double initialLat;

    @SerializedName("initial_lng")
    private Double initialLng;

    // Đối tượng lồng nhau trả về từ API #08, #16, #17 (hỗ trợ cả key "requester" và "victim")
    @SerializedName(value = "requester", alternate = {"victim"})
    private RequesterInfo requester;

    @SerializedName("rescuer")
    private RescuerInfo rescuer;

    @SerializedName("locations")
    private LocationsPair locations;

    // Bảng location_current (VICTIM & RESCUER)
    private double victimLat;
    private double victimLng;
    private double victimAccuracyM;

    private double rescuerLat;
    private double rescuerLng;
    private double rescuerAccuracyM;

    // Nhật ký kiểm toán sos_logs (GET /api/sos/{id}/logs)
    private final List<String> logs = new ArrayList<>();
    private final List<SosLog> auditLogs = new ArrayList<>();

    // Lộ trình đường đi thực tế trên bản đồ (danh sách các điểm [lat, lng] theo đường giao thông)
    private final List<double[]> routePoints = new ArrayList<>();
    private int routeStepIndex = 0;
    private boolean osrmLoaded = false;

    public SosRequest() {
    }

    public SosRequest(
            String id,
            String requesterId,
            String requesterName,
            String requesterPhone,
            String emergencyType,
            String description,
            String visibility,
            double initialLat,
            double initialLng,
            double victimAccuracyM,
            String status,
            String createdAt
    ) {
        this.id = id;
        this.requesterId = requesterId;
        this.requesterName = requesterName;
        this.requesterPhone = requesterPhone;
        this.emergencyType = emergencyType;
        this.description = description;
        this.visibility = visibility;
        this.initialLat = initialLat;
        this.initialLng = initialLng;
        this.victimLat = initialLat;
        this.victimLng = initialLng;
        this.victimAccuracyM = victimAccuracyM;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /**
     * Đồng bộ dữ liệu từ các object lồng nhau ({@code requester}, {@code rescuer}, {@code locations})
     * sau khi parse JSON từ API (#08, #16, #17) vào các trường phẳng phục vụ UI.
     */
    public void syncFromNestedApiObjects() {
        if (requester != null) {
            if (requester.getId() != null) this.requesterId = requester.getId();
            if (requester.getFullName() != null) this.requesterName = requester.getFullName();
            if (requester.getPhone() != null) this.requesterPhone = requester.getPhone();
        }
        if (rescuer != null) {
            if (rescuer.getId() != null) this.rescuerId = rescuer.getId();
            if (rescuer.getFullName() != null) this.rescuerName = rescuer.getFullName();
            if (rescuer.getPhone() != null) this.rescuerPhone = rescuer.getPhone();
            if (rescuer.getTeamName() != null) this.teamName = rescuer.getTeamName();
            if (rescuer.getTeamId() != null) this.teamId = rescuer.getTeamId();
        }
        if (locations != null) {
            LocationCurrent victimLoc = locations.getVictim();
            if (victimLoc != null) {
                this.victimLat = victimLoc.getLat();
                this.victimLng = victimLoc.getLng();
                this.victimAccuracyM = victimLoc.getAccuracyM();
            }
            LocationCurrent rescuerLoc = locations.getRescuer();
            if (rescuerLoc != null) {
                this.rescuerLat = rescuerLoc.getLat();
                this.rescuerLng = rescuerLoc.getLng();
                this.rescuerAccuracyM = rescuerLoc.getAccuracyM();
            }
        }
        if (this.victimLat == 0 && hasInitialLocation()) {
            this.victimLat = this.initialLat;
            this.victimLng = this.initialLng;
        }
    }

    public void assignRescuer(
            String teamId,
            String rescuerId,
            String rescuerName,
            String rescuerPhone,
            String teamName,
            double rescuerLat,
            double rescuerLng,
            double rescuerAccuracyM
    ) {
        this.teamId = teamId;
        this.rescuerId = rescuerId;
        this.rescuerName = rescuerName;
        this.rescuerPhone = rescuerPhone;
        this.teamName = teamName;
        this.rescuerLat = rescuerLat;
        this.rescuerLng = rescuerLng;
        this.rescuerAccuracyM = rescuerAccuracyM;
        this.rescuer = new RescuerInfo(rescuerId, rescuerName, rescuerPhone, teamName, teamId);
    }

    public void clearRescuer() {
        this.teamId = null;
        this.rescuerId = null;
        this.rescuerName = null;
        this.rescuerPhone = null;
        this.teamName = null;
        this.rescuer = null;
        this.routeStepIndex = 0;
    }

    public synchronized void setRoutePoints(List<double[]> points, boolean fromOsrm) {
        double progressRatio = 0;
        if (this.routePoints.size() > 1 && this.routeStepIndex > 0) {
            progressRatio = (double) this.routeStepIndex / (this.routePoints.size() - 1);
        }
        this.routePoints.clear();
        if (points != null) {
            this.routePoints.addAll(points);
        }
        this.osrmLoaded = fromOsrm;
        if (!this.routePoints.isEmpty()) {
            if (STATUS_ARRIVED.equals(this.status)) {
                this.routeStepIndex = Math.max(0, this.routePoints.size() - 2);
            } else if (progressRatio > 0) {
                this.routeStepIndex = Math.min(
                        Math.max(0, this.routePoints.size() - 2),
                        (int) Math.round(progressRatio * (this.routePoints.size() - 1))
                );
            } else {
                this.routeStepIndex = 0;
            }
            double[] cur = this.routePoints.get(this.routeStepIndex);
            this.rescuerLat = cur[0];
            this.rescuerLng = cur[1];
        }
    }

    public synchronized List<double[]> getRoutePoints() {
        return new ArrayList<>(routePoints);
    }

    public synchronized List<double[]> getRemainingRoutePoints() {
        if (routePoints.isEmpty()) return new ArrayList<>();
        int start = Math.max(0, Math.min(routeStepIndex, routePoints.size() - 1));
        return new ArrayList<>(routePoints.subList(start, routePoints.size()));
    }

    public synchronized boolean advanceAlongRoute() {
        if (routePoints.size() <= 2) return false;
        int step = Math.max(1, routePoints.size() / 5);
        int nextIndex = Math.min(routePoints.size() - 2, routeStepIndex + step);
        if (nextIndex > routeStepIndex) {
            routeStepIndex = nextIndex;
            double[] pt = routePoints.get(routeStepIndex);
            this.rescuerLat = pt[0];
            this.rescuerLng = pt[1];
            return true;
        }
        return false;
    }

    public synchronized void snapRescuerToDestination() {
        if (!routePoints.isEmpty()) {
            routeStepIndex = Math.max(0, routePoints.size() - 2);
            double[] pt = routePoints.get(routeStepIndex);
            this.rescuerLat = pt[0];
            this.rescuerLng = pt[1];
        } else {
            this.rescuerLat = getVictimLat();
            this.rescuerLng = getVictimLng();
        }
    }

    public synchronized boolean isOsrmLoaded() {
        return osrmLoaded;
    }

    public synchronized double getRemainingRouteDistanceKm() {
        List<double[]> rem = getRemainingRoutePoints();
        if (rem.size() >= 2) {
            double totalKm = 0;
            for (int i = 0; i < rem.size() - 1; i++) {
                totalKm += computeHaversineKm(
                        rem.get(i)[0], rem.get(i)[1],
                        rem.get(i + 1)[0], rem.get(i + 1)[1]
                );
            }
            return Math.round(totalKm * 10.0) / 10.0;
        }
        return getDistanceKmFrom(getRescuerLat(), getRescuerLng());
    }

    public static double computeHaversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return 6371.0 * c;
    }

    public void addLog(String logEntry) {
        logs.add(0, logEntry);
    }

    public List<String> getLogs() {
        return logs;
    }

    public List<SosLog> getAuditLogs() {
        return auditLogs;
    }

    public void setAuditLogs(List<SosLog> items) {
        this.auditLogs.clear();
        if (items != null) {
            this.auditLogs.addAll(items);
        }
    }

    public String getShortId() {
        if (id == null) return "#SOS";
        return id.length() > 8 ? "#" + id.substring(0, 8).toUpperCase(Locale.ROOT) : "#" + id;
    }

    public String getEmergencyTypeLabel() {
        return EmergencyType.resolveLabel(emergencyTypeId, emergencyType);
    }

    public static String getEmergencyTypeLabel(String type) {
        return EmergencyType.resolveLabel(null, type);
    }

    public String getStatusLabel() {
        return getStatusLabel(status);
    }

    public static String getStatusLabel(String status) {
        if (STATUS_WAITING.equals(status)) return "Đang chờ tiếp nhận";
        if (STATUS_ACCEPTED.equals(status)) return "Đã tiếp nhận";
        if (STATUS_RESCUING.equals(status)) return "Đang di chuyển";
        if (STATUS_ARRIVED.equals(status)) return "Đã tới hiện trường";
        if (STATUS_COMPLETED.equals(status)) return "Đã hoàn thành";
        if (STATUS_CANCELLED.equals(status)) return "Đã hủy";
        return status != null ? status : "Đang xử lý";
    }

    public String getVisibilityLabel() {
        return VISIBILITY_PRIVATE.equals(visibility) ? "Riêng tư" : "Công khai";
    }

    /**
     * Tọa độ làm tròn 2 chữ số thập phân (~1km) dùng cho GET /api/sos/public/map
     */
    public double getAreaLat() {
        return Math.round(getVictimLat() * 100.0) / 100.0;
    }

    public double getAreaLng() {
        return Math.round(getVictimLng() * 100.0) / 100.0;
    }

    public double getDistanceKmFrom(double lat, double lng) {
        synchronized (this) {
            if (routePoints.size() >= 2) {
                double totalKm = 0;
                int start = Math.max(0, Math.min(routeStepIndex, routePoints.size() - 1));
                for (int i = start; i < routePoints.size() - 1; i++) {
                    totalKm += computeHaversineKm(
                            routePoints.get(i)[0], routePoints.get(i)[1],
                            routePoints.get(i + 1)[0], routePoints.get(i + 1)[1]
                    );
                }
                return Math.round(totalKm * 10.0) / 10.0;
            }
        }
        double straightKm = computeHaversineKm(lat, lng, getVictimLat(), getVictimLng());
        return Math.round((straightKm * 1.3) * 10.0) / 10.0;
    }

    public boolean isActive() {
        return !STATUS_COMPLETED.equals(status) && !STATUS_CANCELLED.equals(status);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRequesterId() {
        if (requesterId == null && requester != null) {
            return requester.getId();
        }
        return requesterId;
    }

    public void setRequesterId(String requesterId) {
        this.requesterId = requesterId;
    }

    public String getRequesterName() {
        if (requesterName == null && requester != null) {
            return requester.getFullName();
        }
        return requesterName;
    }

    public void setRequesterName(String requesterName) {
        this.requesterName = requesterName;
    }

    public String getRequesterPhone() {
        if (requesterPhone == null && requester != null) {
            return requester.getPhone();
        }
        return requesterPhone;
    }

    public void setRequesterPhone(String requesterPhone) {
        this.requesterPhone = requesterPhone;
    }

    public String getTeamId() {
        if (teamId == null && rescuer != null) {
            return rescuer.getTeamId();
        }
        return teamId;
    }

    public void setTeamId(String teamId) {
        this.teamId = teamId;
    }

    public String getRescuerId() {
        if (rescuerId == null && rescuer != null) {
            return rescuer.getId();
        }
        return rescuerId;
    }

    public void setRescuerId(String rescuerId) {
        this.rescuerId = rescuerId;
    }

    public String getRescuerName() {
        if (rescuerName == null && rescuer != null) {
            return rescuer.getFullName();
        }
        return rescuerName;
    }

    public void setRescuerName(String rescuerName) {
        this.rescuerName = rescuerName;
    }

    public String getRescuerPhone() {
        if (rescuerPhone == null && rescuer != null) {
            return rescuer.getPhone();
        }
        return rescuerPhone;
    }

    public void setRescuerPhone(String rescuerPhone) {
        this.rescuerPhone = rescuerPhone;
    }

    public String getTeamName() {
        if (teamName == null && rescuer != null) {
            return rescuer.getTeamName();
        }
        return teamName;
    }

    public void setTeamName(String teamName) {
        this.teamName = teamName;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getEndReason() {
        return endReason;
    }

    public void setEndReason(String endReason) {
        this.endReason = endReason;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public double getInitialLat() {
        return initialLat == null ? Double.NaN : initialLat;
    }

    public void setInitialLat(double initialLat) {
        this.initialLat = initialLat;
    }

    public double getInitialLng() {
        return initialLng == null ? Double.NaN : initialLng;
    }

    public boolean hasInitialLocation() { return initialLat != null && initialLng != null; }

    public void setInitialLng(double initialLng) {
        this.initialLng = initialLng;
    }

    public double getVictimLat() {
        if (victimLat == 0 && locations != null && locations.getVictim() != null) {
            return locations.getVictim().getLat();
        }
        if (victimLat == 0 && hasInitialLocation()) {
            return initialLat;
        }
        return victimLat;
    }

    public void setVictimLat(double victimLat) {
        this.victimLat = victimLat;
    }

    public double getVictimLng() {
        if (victimLng == 0 && locations != null && locations.getVictim() != null) {
            return locations.getVictim().getLng();
        }
        if (victimLng == 0 && hasInitialLocation()) {
            return initialLng;
        }
        return victimLng;
    }

    public void setVictimLng(double victimLng) {
        this.victimLng = victimLng;
    }

    public double getVictimAccuracyM() {
        if (victimAccuracyM == 0 && locations != null && locations.getVictim() != null) {
            return locations.getVictim().getAccuracyM();
        }
        return victimAccuracyM;
    }

    public void setVictimAccuracyM(double victimAccuracyM) {
        this.victimAccuracyM = victimAccuracyM;
    }

    public double getRescuerLat() {
        if (rescuerLat == 0 && locations != null && locations.getRescuer() != null) {
            return locations.getRescuer().getLat();
        }
        return rescuerLat;
    }

    public void setRescuerLat(double rescuerLat) {
        this.rescuerLat = rescuerLat;
    }

    public double getRescuerLng() {
        if (rescuerLng == 0 && locations != null && locations.getRescuer() != null) {
            return locations.getRescuer().getLng();
        }
        return rescuerLng;
    }

    public void setRescuerLng(double rescuerLng) {
        this.rescuerLng = rescuerLng;
    }

    public double getRescuerAccuracyM() {
        if (rescuerAccuracyM == 0 && locations != null && locations.getRescuer() != null) {
            return locations.getRescuer().getAccuracyM();
        }
        return rescuerAccuracyM;
    }

    public void setRescuerAccuracyM(double rescuerAccuracyM) {
        this.rescuerAccuracyM = rescuerAccuracyM;
    }

    public RequesterInfo getRequester() {
        return requester;
    }

    public void setRequester(RequesterInfo requester) {
        this.requester = requester;
        syncFromNestedApiObjects();
    }

    public RescuerInfo getRescuer() {
        return rescuer;
    }

    public void setRescuer(RescuerInfo rescuer) {
        this.rescuer = rescuer;
        syncFromNestedApiObjects();
    }

    public LocationsPair getLocations() {
        return locations;
    }

    public void setLocations(LocationsPair locations) {
        this.locations = locations;
        syncFromNestedApiObjects();
    }

    /**
     * Đối tượng con {@code requester} lồng trong kết quả API #16 ({@code GET /api/rescuer/queue})
     * và API #17 ({@code GET /api/rescuer/my-active}).
     */
    public static class RequesterInfo implements Serializable {
        @SerializedName("id")
        private String id;

        @SerializedName("full_name")
        private String fullName;

        @SerializedName("phone")
        private String phone;

        public RequesterInfo() {
        }

        public RequesterInfo(String id, String fullName, String phone) {
            this.id = id;
            this.fullName = fullName;
            this.phone = phone;
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
    }

    /**
     * Đối tượng con {@code rescuer} lồng trong kết quả API #08 ({@code GET /api/sos/my-active})
     * và sự kiện WebSocket {@code SOS_ACCEPTED}.
     */
    public static class RescuerInfo implements Serializable {
        @SerializedName(value = "id", alternate = {"rescuer_id"})
        private String id;

        @SerializedName(value = "full_name", alternate = {"rescuer_name"})
        private String fullName;

        @SerializedName(value = "phone", alternate = {"rescuer_phone"})
        private String phone;

        @SerializedName("team_name")
        private String teamName;

        @SerializedName("team_id")
        private String teamId;

        public RescuerInfo() {
        }

        public RescuerInfo(String id, String fullName, String phone, String teamName, String teamId) {
            this.id = id;
            this.fullName = fullName;
            this.phone = phone;
            this.teamName = teamName;
            this.teamId = teamId;
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

        public String getTeamName() {
            return teamName;
        }

        public void setTeamName(String teamName) {
            this.teamName = teamName;
        }

        public String getTeamId() {
            return teamId;
        }

        public void setTeamId(String teamId) {
            this.teamId = teamId;
        }
    }

    /**
     * Cặp tọa độ hiện tại {@code locations: { "VICTIM": {...}, "RESCUER": {...} }}
     * trả về từ API #08 ({@code GET /api/sos/my-active}), #17 ({@code GET /api/rescuer/my-active})
     * và #24 ({@code GET /api/location/{sos_id}/current}).
     */
    public static class LocationsPair implements Serializable {
        @SerializedName("VICTIM")
        private LocationCurrent victim;

        @SerializedName("RESCUER")
        private LocationCurrent rescuer;

        public LocationsPair() {
        }

        public LocationsPair(LocationCurrent victim, LocationCurrent rescuer) {
            this.victim = victim;
            this.rescuer = rescuer;
        }

        public LocationCurrent getVictim() {
            return victim;
        }

        public void setVictim(LocationCurrent victim) {
            this.victim = victim;
        }

        public LocationCurrent getRescuer() {
            return rescuer;
        }

        public void setRescuer(LocationCurrent rescuer) {
            this.rescuer = rescuer;
        }
    }
}
