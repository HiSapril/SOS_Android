package com.javafinal.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

/**
 * Model ánh xạ bảng liên kết {@code rescue_team_members} trong CSDL (so_do_csdl.jpg).
 * Liên kết giữa {@link User} ({@code user_id}) và {@link RescueTeam} ({@code team_id}).
 */
public class RescueTeamMember implements Serializable {

    public static final String ROLE_LEADER = "LEADER";
    public static final String ROLE_MEMBER = "MEMBER";

    /** PK uuid */
    @SerializedName(value = "member_id", alternate = {"id"})
    private String memberId;

    /** FK uuid -> rescue_teams.team_id */
    @SerializedName("team_id")
    private String teamId;

    /** FK uuid -> users.id */
    @SerializedName("user_id")
    private String userId;

    /** varchar - Vai trò trong đội (VD: "LEADER", "MEMBER") */
    @SerializedName("role")
    private String role;

    /** timestamptz - Thời điểm gia nhập đội */
    @SerializedName("created_at")
    private String createdAt;

    /** Họ tên thành viên trả về trực tiếp từ API GET /api/rescuer/teams */
    @SerializedName(value = "name", alternate = {"full_name"})
    private String name;

    /** Số điện thoại thành viên trả về trực tiếp từ API GET /api/rescuer/teams */
    @SerializedName("phone")
    private String phone;

    /** Thông tin người dùng đính kèm (nếu có) */
    @SerializedName("user")
    private User user;

    public RescueTeamMember() {
    }

    public RescueTeamMember(String memberId, String teamId, String userId, String role, String createdAt) {
        this.memberId = memberId;
        this.teamId = teamId;
        this.userId = userId;
        this.role = role;
        this.createdAt = createdAt;
    }

    public String getMemberId() {
        return memberId;
    }

    public void setMemberId(String memberId) {
        this.memberId = memberId;
    }

    public String getTeamId() {
        return teamId;
    }

    public void setTeamId(String teamId) {
        this.teamId = teamId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getName() {
        if (name != null && !name.trim().isEmpty()) return name;
        return user != null ? user.getFullName() : null;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhone() {
        if (phone != null && !phone.trim().isEmpty()) return phone;
        return user != null ? user.getPhone() : null;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }
}
