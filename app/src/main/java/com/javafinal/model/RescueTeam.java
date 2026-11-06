package com.javafinal.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Model ánh xạ bảng {@code rescue_teams} trong CSDL (so_do_csdl.jpg)
 * và API #22 ({@code GET /api/rescuer/teams}).
 */
public class RescueTeam implements Serializable {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_BUSY = "BUSY";
    public static final String STATUS_INACTIVE = "INACTIVE";

    /** PK uuid - Hố trợ cả tên trường "team_id" (CSDL) và "id" (API JSON) */
    @SerializedName(value = "team_id", alternate = {"id"})
    private String teamId;

    /** varchar - Tên đội cứu hộ */
    @SerializedName("name")
    private String name;

    /** text - Mô tả khu vực/nhiệm vụ của đội */
    @SerializedName("description")
    private String description;

    /** varchar - Trạng thái hoạt động của đội ("ACTIVE", "BUSY", ...) */
    @SerializedName("status")
    private String status;

    /** timestamptz - Thời điểm khởi tạo đội */
    @SerializedName("created_at")
    private String createdAt;

    /** Danh sách thành viên thuộc đội (nếu API trả kèm) */
    @SerializedName("members")
    private List<RescueTeamMember> members = new ArrayList<>();

    public RescueTeam() {
    }

    public RescueTeam(String teamId, String name, String description, String status, String createdAt) {
        this.teamId = teamId;
        this.name = name;
        this.description = description;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getTeamId() {
        return teamId;
    }

    public void setTeamId(String teamId) {
        this.teamId = teamId;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public List<RescueTeamMember> getMembers() {
        return members;
    }

    public void setMembers(List<RescueTeamMember> members) {
        this.members = members != null ? members : new ArrayList<>();
    }
}
