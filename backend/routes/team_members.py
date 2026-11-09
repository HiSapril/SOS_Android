"""Danh sách đội của tôi và quản lý đội viên theo quyền LEADER trong từng đội."""
import re
from typing import Literal
from uuid import UUID

import psycopg.errors
from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field, field_validator

from database.connection import get_db_connection
from services.auth_service import normalize_phone, require_rescuer

router = APIRouter(prefix="/api/rescuer/teams", tags=["Quản lý đội viên"])
TeamRole = Literal["LEADER", "MEMBER", "DRIVER", "MEDIC"]


class UpdateTeamMemberDto(BaseModel):
    role: TeamRole


class AddTeamMemberDto(UpdateTeamMemberDto):
    phone: str = Field(..., min_length=10, max_length=30)

    @field_validator("phone")
    @classmethod
    def clean_phone(cls, value: str) -> str:
        phone = normalize_phone(value)
        if not re.fullmatch(r"0[0-9]{9}", phone):
            raise ValueError("Số điện thoại Việt Nam không hợp lệ.")
        return phone


def _member_json(row):
    return {
        "member_id": str(row["member_id"]),
        "team_id": str(row["team_id"]),
        "user_id": str(row["user_id"]),
        "name": row["name"],
        "phone": row["phone"],
        "role": row["role"],
        "created_at": row["created_at"].isoformat() if row["created_at"] else None,
    }


def _members(cursor, team_id):
    cursor.execute(
        """
        SELECT m.member_id, m.team_id, m.user_id, m.role, m.created_at,
               u.full_name AS name, u.phone
        FROM rescue_team_members m JOIN users u ON u.id = m.user_id
        WHERE m.team_id = %s
        ORDER BY (m.role = 'LEADER') DESC, m.created_at, m.member_id
        """,
        (team_id,),
    )
    return [_member_json(row) for row in cursor.fetchall()]


def _authorize(cursor, team_id, user_id, *, manage=False):
    # Khóa cùng một hàng đội cho mọi thao tác ghi, kể cả kiểm tra leader cuối cùng.
    cursor.execute(
        "SELECT team_id FROM rescue_teams WHERE team_id = %s" + (" FOR UPDATE" if manage else ""),
        (team_id,),
    )
    if not cursor.fetchone():
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Không tìm thấy đội cứu hộ.")
    cursor.execute(
        "SELECT role FROM rescue_team_members WHERE team_id = %s AND user_id = %s",
        (team_id, user_id),
    )
    membership = cursor.fetchone()
    if not membership or (manage and membership["role"] not in ("LEADER", "ADMIN")):
        raise HTTPException(
            status.HTTP_403_FORBIDDEN,
            "Chỉ trưởng đội hoặc ADMIN của đội này được quản lý đội viên." if manage
            else "Bạn không thuộc đội cứu hộ này.",
        )
    return membership["role"]


def _target_member(cursor, team_id, member_id):
    cursor.execute(
        "SELECT member_id, role FROM rescue_team_members WHERE team_id = %s AND member_id = %s",
        (team_id, member_id),
    )
    member = cursor.fetchone()
    if not member:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Không tìm thấy đội viên trong đội này.")
    if member["role"] == "ADMIN":
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Chỉ quản trị viên được thay đổi hoặc xóa thành viên ADMIN.")
    return member


def _keep_leader(cursor, team_id, member):
    if member["role"] == "LEADER":
        cursor.execute(
            "SELECT count(*) AS count FROM rescue_team_members WHERE team_id = %s AND role IN ('LEADER', 'ADMIN')",
            (team_id,),
        )
        if cursor.fetchone()["count"] <= 1:
            raise HTTPException(
                status.HTTP_409_CONFLICT,
                "Đội phải có ít nhất một trưởng đội. Hãy bổ nhiệm trưởng đội khác trước.",
            )


@router.get("/mine", summary="Các đội tôi tham gia, danh sách đội viên và quyền quản lý")
def my_teams(current_user: dict = Depends(require_rescuer)):
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                """
                SELECT t.team_id, t.name, t.description, t.status, t.created_at,
                       m.role AS current_user_role
                FROM rescue_teams t JOIN rescue_team_members m ON m.team_id = t.team_id
                WHERE m.user_id = %s ORDER BY t.created_at, t.team_id
                """,
                (current_user["id"],),
            )
            teams = cursor.fetchall()
            data = []
            for team in teams:
                data.append({
                    "team_id": str(team["team_id"]),
                    "name": team["name"],
                    "description": team["description"],
                    "status": team["status"],
                    "created_at": team["created_at"].isoformat() if team["created_at"] else None,
                    "current_user_role": team["current_user_role"],
                    "can_manage": team["current_user_role"] in ("LEADER", "ADMIN"),
                    "members": _members(cursor, team["team_id"]),
                })
    return {"success": True, "count": len(data), "data": data}


@router.get("/{team_id}/members", summary="Xem đội viên của đội mình")
def list_members(team_id: UUID, current_user: dict = Depends(require_rescuer)):
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            role = _authorize(cursor, team_id, current_user["id"])
            members = _members(cursor, team_id)
    return {"success": True, "current_user_role": role, "can_manage": role in ("LEADER", "ADMIN"),
            "count": len(members), "data": members}


@router.post("/{team_id}/members", status_code=status.HTTP_201_CREATED,
             summary="Trưởng đội thêm đội viên bằng số điện thoại đã đăng ký")
def add_member(team_id: UUID, dto: AddTeamMemberDto, current_user: dict = Depends(require_rescuer)):
    try:
        with get_db_connection() as conn:
            with conn.cursor() as cursor:
                _authorize(cursor, team_id, current_user["id"], manage=True)
                cursor.execute("SELECT id, account_status FROM users WHERE phone = %s", (dto.phone,))
                user = cursor.fetchone()
                if not user:
                    raise HTTPException(status.HTTP_404_NOT_FOUND, "Số điện thoại này chưa đăng ký tài khoản.")
                if user["account_status"] != "ACTIVE":
                    raise HTTPException(status.HTTP_409_CONFLICT, "Không thể thêm tài khoản đang bị khóa.")
                cursor.execute(
                    """INSERT INTO rescue_team_members (team_id, user_id, role)
                       VALUES (%s, %s, %s) RETURNING member_id""",
                    (team_id, user["id"], dto.role),
                )
                member_id = str(cursor.fetchone()["member_id"])
                member = next(m for m in _members(cursor, team_id) if m["member_id"] == member_id)
    except psycopg.errors.UniqueViolation:
        raise HTTPException(status.HTTP_409_CONFLICT, "Người này đã là đội viên của đội.") from None
    return {"success": True, "message": "Đã thêm đội viên.", "data": member}


@router.put("/{team_id}/members/{member_id}", summary="Trưởng đội đổi vai trò đội viên theo ID thành viên")
def update_member(team_id: UUID, member_id: UUID, dto: UpdateTeamMemberDto,
                  current_user: dict = Depends(require_rescuer)):
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            _authorize(cursor, team_id, current_user["id"], manage=True)
            member = _target_member(cursor, team_id, member_id)
            if dto.role != "LEADER":
                _keep_leader(cursor, team_id, member)
            cursor.execute(
                "UPDATE rescue_team_members SET role = %s WHERE team_id = %s AND member_id = %s",
                (dto.role, team_id, member_id),
            )
            updated = next(m for m in _members(cursor, team_id) if m["member_id"] == str(member_id))
    return {"success": True, "message": "Đã cập nhật vai trò đội viên.", "data": updated}


@router.delete("/{team_id}/members/{member_id}", summary="Trưởng đội xóa đội viên theo ID thành viên")
def delete_member(team_id: UUID, member_id: UUID, current_user: dict = Depends(require_rescuer)):
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            _authorize(cursor, team_id, current_user["id"], manage=True)
            member = _target_member(cursor, team_id, member_id)
            _keep_leader(cursor, team_id, member)
            cursor.execute(
                "DELETE FROM rescue_team_members WHERE team_id = %s AND member_id = %s",
                (team_id, member_id),
            )
    return {"success": True, "message": "Đã xóa đội viên khỏi đội.", "member_id": str(member_id)}
