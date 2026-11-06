package com.javafinal.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

/**
 * Model gói tin Realtime WebSocket cho API #26 ({@code WS /ws?token={jwt}})
 * theo đặc tả Mục 3.8 trong BANG_API_VA_LUONG_HOAT_DONG.md.
 */
public class WebSocketMessage implements Serializable {

    // Gói tin từ Client -> Server
    public static final String EVENT_PING = "PING";
    public static final String EVENT_SUBSCRIBE_SOS = "SUBSCRIBE_SOS";

    // Gói tin từ Server -> Client
    public static final String EVENT_PONG = "PONG";
    public static final String EVENT_SUBSCRIBED = "SUBSCRIBED";
    public static final String EVENT_NEW_SOS = "NEW_SOS";
    public static final String EVENT_SOS_ACCEPTED = "SOS_ACCEPTED";
    public static final String EVENT_STATUS_CHANGED = "STATUS_CHANGED";
    public static final String EVENT_LOCATION_BROADCAST = "LOCATION_BROADCAST";
    public static final String EVENT_SOS_RELEASED = "SOS_RELEASED";
    public static final String EVENT_SOS_COMPLETED = "SOS_COMPLETED";
    public static final String EVENT_SOS_CANCELLED = "SOS_CANCELLED";
    public static final String EVENT_QUEUE_UPDATED = "QUEUE_UPDATED";

    /** Tên sự kiện WebSocket */
    @SerializedName("event")
    private String event;

    /** UUID phiếu SOS liên quan */
    @SerializedName("sos_id")
    private String sosId;

    /** Trạng thái mới ("WAITING", "ACCEPTED", "RESCUING", "ARRIVED", "COMPLETED", "CANCELLED") */
    @SerializedName("status")
    private String status;

    /** Lý do kết thúc ("RESCUER_COMPLETED", "USER_CANCELLED") */
    @SerializedName("end_reason")
    private String endReason;

    /** Hành động cập nhật hàng đợi ("ADDED", "REMOVED") trong sự kiện QUEUE_UPDATED */
    @SerializedName("action")
    private String action;

    /** Thông báo đính kèm (VD: trong SOS_RELEASED) */
    @SerializedName("message")
    private String message;

    // Trường dữ liệu cho sự kiện NEW_SOS
    @SerializedName("emergency_type_id")
    private String emergencyTypeId;

    @SerializedName("emergency_type")
    private String emergencyType;

    @SerializedName("description")
    private String description;

    @SerializedName("visibility")
    private String visibility;

    @SerializedName("initial_lat")
    private double initialLat;

    @SerializedName("initial_lng")
    private double initialLng;

    @SerializedName("requester_name")
    private String requesterName;

    @SerializedName("requester_phone")
    private String requesterPhone;

    @SerializedName("created_at")
    private String createdAt;

    // Trường dữ liệu cho sự kiện SOS_ACCEPTED
    @SerializedName("rescuer")
    private SosRequest.RescuerInfo rescuer;

    // Trường dữ liệu cho sự kiện LOCATION_BROADCAST
    @SerializedName("actor")
    private String actor;

    @SerializedName("lat")
    private double lat;

    @SerializedName("lng")
    private double lng;

    @SerializedName("accuracy_m")
    private double accuracyM;

    @SerializedName("updated_at")
    private String updatedAt;

    public WebSocketMessage() {
    }

    public static WebSocketMessage createPing() {
        WebSocketMessage msg = new WebSocketMessage();
        msg.setEvent(EVENT_PING);
        return msg;
    }

    public static WebSocketMessage createSubscribeSos(String sosId) {
        WebSocketMessage msg = new WebSocketMessage();
        msg.setEvent(EVENT_SUBSCRIBE_SOS);
        msg.setSosId(sosId);
        return msg;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public String getSosId() {
        return sosId;
    }

    public void setSosId(String sosId) {
        this.sosId = sosId;
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

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
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

    public String getRequesterName() {
        return requesterName;
    }

    public void setRequesterName(String requesterName) {
        this.requesterName = requesterName;
    }

    public String getRequesterPhone() {
        return requesterPhone;
    }

    public void setRequesterPhone(String requesterPhone) {
        this.requesterPhone = requesterPhone;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public SosRequest.RescuerInfo getRescuer() {
        return rescuer;
    }

    public void setRescuer(SosRequest.RescuerInfo rescuer) {
        this.rescuer = rescuer;
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
