"""Admin APIs. Every endpoint checks an active rescuer ADMIN membership."""
import re
from contextlib import contextmanager
from datetime import date
from typing import Literal
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field, field_validator
from psycopg.errors import ForeignKeyViolation, RestrictViolation, UniqueViolation

from database.connection import get_db_connection
from services.auth_service import normalize_phone, require_admin

router = APIRouter(prefix="/api/admin", tags=["Quản trị ADMIN"], dependencies=[Depends(require_admin)])
MemberRole = Literal["ADMIN", "LEADER", "MEMBER", "DRIVER", "MEDIC"]
AccountStatus = Literal["ACTIVE", "DISABLED"]
SOSStatus = Literal["WAITING", "ACCEPTED", "RESCUING", "ARRIVED", "COMPLETED", "CANCELLED"]


class UserDto(BaseModel):
    full_name: str = Field(min_length=1, max_length=100)
    phone: str = Field(min_length=9, max_length=30)
    account_status: AccountStatus = "ACTIVE"

    @field_validator("full_name")
    @classmethod
    def clean_name(cls, value):
        value = value.strip()
        if not value:
            raise ValueError("Vui lòng nhập họ tên.")
        return value

    @field_validator("phone")
    @classmethod
    def clean_phone(cls, value):
        value = normalize_phone(value)
        if not re.fullmatch(r"0[0-9]{9}", value):
            raise ValueError("Số điện thoại Việt Nam phải có 10 chữ số.")
        return value


class RescuerDto(UserDto):
    team_id: UUID
    role: MemberRole = "MEMBER"


class TeamDto(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    description: str = Field(default="", max_length=2000)
    status: Literal["ACTIVE", "BUSY", "INACTIVE"] = "ACTIVE"

    @field_validator("name")
    @classmethod
    def clean_name(cls, value):
        return UserDto.clean_name(value)


@contextmanager
def write_cursor():
    try:
        with get_db_connection() as conn:
            with conn.cursor() as cursor:
                # Serialize admin writes so simultaneous edits cannot remove the last admin.
                cursor.execute("SELECT pg_advisory_xact_lock(72401926)")
                yield cursor
    except UniqueViolation:
        raise HTTPException(409, "Số điện thoại hoặc thành viên trong đội đã tồn tại.") from None
    except (ForeignKeyViolation, RestrictViolation):
        raise HTTPException(409, "Dữ liệu đang được sử dụng. Hãy vô hiệu hóa thay vì xóa.") from None


def found(cursor, query, params, message="Không tìm thấy dữ liệu."):
    cursor.execute(query, params)
    row = cursor.fetchone()
    if row is None:
        raise HTTPException(404, message)
    return row


def page_result(cursor, select, where, params, order, page, page_size):
    cursor.execute("SELECT COUNT(*) AS total FROM (" + select + where + ") items", params)
    total = cursor.fetchone()["total"]
    cursor.execute(select + where + order + " LIMIT %s OFFSET %s", (*params, page_size, (page - 1) * page_size))
    return {"success": True, "data": cursor.fetchall(), "total": total, "page": page, "page_size": page_size}


def protect_active_team(cursor, team_id):
    cursor.execute("SELECT 1 FROM sos_requests WHERE team_id = %s AND status IN ('ACCEPTED','RESCUING','ARRIVED') LIMIT 1", (team_id,))
    if cursor.fetchone():
        raise HTTPException(409, "Đội đang xử lý SOS. Hãy hoàn tất ca trước khi thay đổi đội viên hoặc trạng thái đội.")


def protect_admin_membership(cursor, member, current_user):
    if str(member["user_id"]) == current_user["id"] and member["role"] == "ADMIN":
        cursor.execute("SELECT 1 FROM rescue_team_members WHERE user_id = %s AND role = 'ADMIN' AND member_id <> %s LIMIT 1",
                       (member["user_id"], member["member_id"]))
        if not cursor.fetchone():
            raise HTTPException(409, "Không thể xóa quyền ADMIN cuối cùng của chính bạn.")


def protect_leader(cursor, member):
    if member["role"] == "LEADER":
        cursor.execute("SELECT 1 FROM rescue_team_members WHERE team_id = %s AND role IN ('LEADER', 'ADMIN') AND member_id <> %s LIMIT 1",
                       (member["team_id"], member["member_id"]))
        if not cursor.fetchone():
            raise HTTPException(409, "Hãy chỉ định trưởng đội khác trước khi xóa hoặc đổi vai trò trưởng đội cuối cùng.")


def update_user_record(cursor, user_id, dto, current_user):
    found(cursor, "SELECT id FROM users WHERE id = %s FOR UPDATE", (user_id,))
    if str(user_id) == current_user["id"] and dto.account_status != "ACTIVE":
        raise HTTPException(409, "Không thể khóa tài khoản đang đăng nhập.")
    if dto.account_status != "ACTIVE":
        cursor.execute("SELECT team_id FROM rescue_team_members WHERE user_id = %s", (user_id,))
        for member in cursor.fetchall():
            protect_active_team(cursor, member["team_id"])
    cursor.execute("UPDATE users SET full_name = %s, phone = %s, account_status = %s WHERE id = %s RETURNING *",
                   (dto.full_name, dto.phone, dto.account_status, user_id))
    row = cursor.fetchone()
    row.pop("telegram_chat_id", None)
    return row


@router.get("/me")
def me(current_user: dict = Depends(require_admin)):
    return {"success": True, "user": current_user}


@router.get("/overview")
def overview():
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute("""SELECT
                (SELECT count(*) FROM sos_requests WHERE status IN ('WAITING','ACCEPTED','RESCUING','ARRIVED')) AS active_sos,
                (SELECT count(*) FROM sos_requests WHERE status = 'WAITING') AS waiting_sos,
                (SELECT count(DISTINCT user_id) FROM rescue_team_members) AS rescuers,
                (SELECT count(*) FROM rescue_teams) AS teams,
                (SELECT count(*) FROM rescue_teams WHERE status = 'ACTIVE') AS available_teams,
                (SELECT count(*) FROM users) AS users""")
            return {"success": True, "data": cursor.fetchone()}


@router.get("/sos")
def sos_map(q: str = Query("", max_length=100), status: SOSStatus | None = None):
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute("""SELECT s.id AS sos_id, s.description, s.status, s.visibility, s.created_at, s.updated_at,
                et.name AS emergency_type, u.full_name AS requester_name, u.phone AS requester_phone,
                t.name AS team_name, lc.lat, lc.lng, lc.updated_at AS location_updated_at
                FROM sos_requests s JOIN users u ON u.id = s.requester_id
                JOIN emergency_types et ON et.id = s.emergency_type_id
                LEFT JOIN rescue_teams t ON t.team_id = s.team_id
                LEFT JOIN location_current lc ON lc.sos_id = s.id AND lc.actor = 'VICTIM'
                WHERE ((%s::text IS NULL AND s.status IN ('WAITING','ACCEPTED','RESCUING','ARRIVED')) OR s.status = %s)
                AND (u.full_name ILIKE %s OR u.phone ILIKE %s OR COALESCE(s.description,'') ILIKE %s OR et.name ILIKE %s)
                ORDER BY (s.status = 'WAITING') DESC, s.created_at DESC, s.id""",
                (status, status, *([f"%{q}%"] * 4)))
            rows = cursor.fetchall()
    return {"success": True, "data": rows, "count": len(rows)}


def _build_sos_history_where(
    q: str = "",
    status: SOSStatus | None = None,
    team_id: UUID | None = None,
    emergency_type_id: UUID | None = None,
    from_date: date | None = None,
    to_date: date | None = None,
):
    conditions = []
    params = []

    if q and q.strip():
        search_term = f"%{q.strip()}%"
        conditions.append(
            "(u.full_name ILIKE %s OR u.phone ILIKE %s OR COALESCE(s.description,'') ILIKE %s OR COALESCE(t.name,'') ILIKE %s OR et.name ILIKE %s)"
        )
        params.extend([search_term] * 5)

    if status:
        conditions.append("s.status = %s")
        params.append(status)

    if team_id:
        conditions.append("s.team_id = %s")
        params.append(team_id)

    if emergency_type_id:
        conditions.append("s.emergency_type_id = %s")
        params.append(emergency_type_id)

    if from_date:
        conditions.append("s.created_at >= %s::date")
        params.append(str(from_date))
    if to_date:
        conditions.append("s.created_at < (%s::date + INTERVAL '1 day')")
        params.append(str(to_date))

    where_clause = (" WHERE " + " AND ".join(conditions)) if conditions else ""
    return where_clause, params


@router.get("/sos/history")
def rescue_history(
    q: str = Query("", max_length=100),
    status: SOSStatus | None = Query(None, description="Lọc trạng thái: COMPLETED, CANCELLED, WAITING, ACCEPTED, RESCUING, ARRIVED hoặc để trống để lấy tất cả"),
    team_id: UUID | None = Query(None, description="Lọc theo ID đội cứu hộ"),
    emergency_type_id: UUID | None = Query(None, description="Lọc theo ID loại sự cố"),
    from_date: date | None = Query(None, description="Từ ngày định dạng YYYY-MM-DD"),
    to_date: date | None = Query(None, description="Đến ngày định dạng YYYY-MM-DD"),
    page: int = Query(1, ge=1),
    page_size: int = Query(10, ge=1, le=200),
):
    """
    Xem toàn bộ lịch sử cứu hộ dành cho Quản trị viên (Hỗ trợ phân trang và đa bộ lọc).
    """
    where_clause, params = _build_sos_history_where(
        q=q,
        status=status,
        team_id=team_id,
        emergency_type_id=emergency_type_id,
        from_date=from_date,
        to_date=to_date,
    )

    select_sql = """
        SELECT s.id AS sos_id, s.description, s.status, s.visibility, s.created_at, s.updated_at,
               s.emergency_type_id, et.name AS emergency_type, et.description AS emergency_type_description,
               s.requester_id, u.full_name AS requester_name, u.phone AS requester_phone,
               s.team_id, t.name AS team_name,
               COALESCE(lc.lat, lh.lat) AS lat,
               COALESCE(lc.lng, lh.lng) AS lng,
               lc.updated_at AS location_updated_at,
               ROUND(EXTRACT(EPOCH FROM (s.updated_at - s.created_at)))::int AS duration_seconds,
               (SELECT COUNT(*) FROM sos_logs l WHERE l.sos_id = s.id) AS logs_count
        FROM sos_requests s
        JOIN users u ON u.id = s.requester_id
        JOIN emergency_types et ON et.id = s.emergency_type_id
        LEFT JOIN rescue_teams t ON t.team_id = s.team_id
        LEFT JOIN location_current lc ON lc.sos_id = s.id AND lc.actor = 'VICTIM'
        LEFT JOIN LATERAL (
            SELECT lat, lng FROM location_history
            WHERE sos_id = s.id AND actor = 'VICTIM'
            ORDER BY created_at DESC LIMIT 1
        ) lh ON lc.sos_id IS NULL
    """

    order_sql = " ORDER BY s.created_at DESC, s.id DESC"

    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            return page_result(cursor, select_sql, where_clause, params, order_sql, page, page_size)


@router.get("/sos/history/summary")
def rescue_history_summary(
    q: str = Query("", max_length=100),
    status: SOSStatus | None = Query(None, description="Lọc trạng thái"),
    team_id: UUID | None = Query(None, description="Lọc theo ID đội cứu hộ"),
    emergency_type_id: UUID | None = Query(None, description="Lọc theo ID loại sự cố"),
    from_date: date | None = Query(None, description="Từ ngày định dạng YYYY-MM-DD"),
    to_date: date | None = Query(None, description="Đến ngày định dạng YYYY-MM-DD"),
):
    """
    Trả về các chỉ số KPI thống kê lịch sử cứu trợ theo thời gian thực.
    """
    where_clause, params = _build_sos_history_where(
        q=q,
        status=status,
        team_id=team_id,
        emergency_type_id=emergency_type_id,
        from_date=from_date,
        to_date=to_date,
    )

    sql_query = f"""
        SELECT
            COUNT(*) AS total_sos,
            COUNT(*) FILTER (WHERE s.status = 'COMPLETED') AS completed_count,
            COUNT(*) FILTER (WHERE s.status = 'CANCELLED') AS cancelled_count,
            AVG(EXTRACT(EPOCH FROM (s.updated_at - s.created_at)) / 60.0)
                FILTER (WHERE s.status = 'COMPLETED' AND s.updated_at >= s.created_at) AS avg_duration_minutes
        FROM sos_requests s
        JOIN users u ON u.id = s.requester_id
        JOIN emergency_types et ON et.id = s.emergency_type_id
        LEFT JOIN rescue_teams t ON t.team_id = s.team_id
        {where_clause}
    """

    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(sql_query, params)
            row = cursor.fetchone()

    total_sos = int(row["total_sos"] or 0)
    completed_count = int(row["completed_count"] or 0)
    cancelled_count = int(row["cancelled_count"] or 0)
    success_rate_percent = round((completed_count / total_sos) * 100.0, 1) if total_sos > 0 else 0.0
    average_duration_minutes = (
        round(float(row["avg_duration_minutes"]), 1)
        if row["avg_duration_minutes"] is not None
        else 0.0
    )

    return {
        "success": True,
        "data": {
            "total_sos": total_sos,
            "completed_count": completed_count,
            "cancelled_count": cancelled_count,
            "success_rate_percent": success_rate_percent,
            "average_duration_minutes": average_duration_minutes,
        },
    }


@router.get("/sos/{sos_id}/logs")
def admin_get_sos_logs(sos_id: UUID):
    """
    Quản trị viên xem toàn bộ nhật ký thao tác (audit trail) của một phiên cứu hộ bất kỳ.
    """
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute("SELECT id FROM sos_requests WHERE id = %s", (sos_id,))
            if not cursor.fetchone():
                raise HTTPException(status_code=404, detail="Không tìm thấy yêu cầu SOS.")

            cursor.execute(
                """
                SELECT l.log_id, l.sos_id, l.user_id, l.action, l.description, l.created_at,
                       COALESCE(u.full_name, 'Hệ thống') AS actor_name
                FROM sos_logs l
                LEFT JOIN users u ON l.user_id = u.id
                WHERE l.sos_id = %s
                ORDER BY l.created_at ASC
                """,
                (sos_id,),
            )
            logs = cursor.fetchall()

    return {
        "success": True,
        "count": len(logs),
        "data": [
            {
                "log_id": str(l["log_id"]),
                "sos_id": str(l["sos_id"]),
                "action": l["action"],
                "description": l["description"],
                "created_at": l["created_at"].isoformat() if l["created_at"] else None,
                "actor_name": l["actor_name"],
            }
            for l in logs
        ],
    }


@router.get("/users")
def users(q: str = Query("", max_length=100), status: AccountStatus | None = None,
          page: int = Query(1, ge=1), page_size: int = Query(10, ge=1, le=200)):
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            return page_result(cursor, """SELECT u.id, u.full_name, u.phone, u.account_status, u.is_valid, u.created_at,
                (SELECT count(*) FROM rescue_team_members m WHERE m.user_id = u.id) AS team_count FROM users u""",
                " WHERE (u.full_name ILIKE %s OR u.phone ILIKE %s) AND (%s::text IS NULL OR u.account_status = %s)",
                (f"%{q}%", f"%{q}%", status, status), " ORDER BY u.created_at DESC, u.id", page, page_size)


@router.post("/users", status_code=201)
def create_user(dto: UserDto):
    with write_cursor() as cursor:
        cursor.execute("""INSERT INTO users (full_name, phone, account_status) VALUES (%s, %s, %s)
            RETURNING id, full_name, phone, account_status, is_valid, created_at""", (dto.full_name, dto.phone, dto.account_status))
        return {"success": True, "data": cursor.fetchone()}


@router.put("/users/{user_id}")
def update_user(user_id: UUID, dto: UserDto, current_user: dict = Depends(require_admin)):
    with write_cursor() as cursor:
        row = update_user_record(cursor, user_id, dto, current_user)
    return {"success": True, "data": row}


@router.delete("/users/{user_id}")
def delete_user(user_id: UUID, current_user: dict = Depends(require_admin)):
    if str(user_id) == current_user["id"]:
        raise HTTPException(409, "Không thể xóa tài khoản đang đăng nhập.")
    with write_cursor() as cursor:
        found(cursor, "SELECT id FROM users WHERE id = %s FOR UPDATE", (user_id,))
        cursor.execute("SELECT * FROM rescue_team_members WHERE user_id = %s", (user_id,))
        for member in cursor.fetchall():
            protect_active_team(cursor, member["team_id"])
            protect_leader(cursor, member)
        found(cursor, "DELETE FROM users WHERE id = %s RETURNING id", (user_id,))
    return {"success": True}


RESCUER_SELECT = """SELECT m.member_id, m.team_id, m.user_id, m.role, m.created_at,
    u.full_name, u.phone, u.account_status, u.is_valid, t.name AS team_name
    FROM rescue_team_members m JOIN users u ON u.id = m.user_id JOIN rescue_teams t ON t.team_id = m.team_id"""


@router.get("/rescuers")
def rescuers(q: str = Query("", max_length=100), role: MemberRole | None = None, team_id: UUID | None = None,
             page: int = Query(1, ge=1), page_size: int = Query(10, ge=1, le=200)):
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            return page_result(cursor, RESCUER_SELECT,
                " WHERE (u.full_name ILIKE %s OR u.phone ILIKE %s OR t.name ILIKE %s) AND (%s::text IS NULL OR m.role = %s) AND (%s::uuid IS NULL OR m.team_id = %s)",
                (*([f"%{q}%"] * 3), role, role, team_id, team_id), " ORDER BY m.created_at DESC, m.member_id", page, page_size)


@router.post("/rescuers", status_code=201)
def create_rescuer(dto: RescuerDto):
    with write_cursor() as cursor:
        found(cursor, "SELECT team_id FROM rescue_teams WHERE team_id = %s FOR UPDATE", (dto.team_id,), "Không tìm thấy đội cứu hộ.")
        protect_active_team(cursor, dto.team_id)
        cursor.execute("SELECT id, account_status FROM users WHERE phone = %s FOR UPDATE", (dto.phone,))
        user = cursor.fetchone()
        if user:
            if user["account_status"] != "ACTIVE":
                raise HTTPException(409, "Tài khoản đã tồn tại và đang bị khóa. Hãy mở khóa trong mục Người dùng.")
        else:
            cursor.execute("INSERT INTO users (full_name, phone, account_status) VALUES (%s, %s, %s) RETURNING id",
                           (dto.full_name, dto.phone, dto.account_status))
            user = cursor.fetchone()
        cursor.execute("INSERT INTO rescue_team_members (team_id, user_id, role) VALUES (%s, %s, %s) RETURNING member_id",
                       (dto.team_id, user["id"], dto.role))
        mid = cursor.fetchone()["member_id"]
        row = found(cursor, RESCUER_SELECT + " WHERE m.member_id = %s", (mid,))
    return {"success": True, "data": row}


@router.put("/rescuers/{member_id}")
def update_rescuer(member_id: UUID, dto: RescuerDto, current_user: dict = Depends(require_admin)):
    with write_cursor() as cursor:
        member = found(cursor, "SELECT * FROM rescue_team_members WHERE member_id = %s FOR UPDATE", (member_id,))
        found(cursor, "SELECT team_id FROM rescue_teams WHERE team_id = %s FOR UPDATE", (dto.team_id,))
        if str(dto.team_id) != str(member["team_id"]) or dto.role != member["role"]:
            protect_active_team(cursor, member["team_id"])
            protect_active_team(cursor, dto.team_id)
            if str(dto.team_id) != str(member["team_id"]) or dto.role != "LEADER":
                protect_leader(cursor, member)
            if dto.role != "ADMIN":
                protect_admin_membership(cursor, member, current_user)
        update_user_record(cursor, member["user_id"], dto, current_user)
        cursor.execute("UPDATE rescue_team_members SET team_id = %s, role = %s WHERE member_id = %s", (dto.team_id, dto.role, member_id))
        row = found(cursor, RESCUER_SELECT + " WHERE m.member_id = %s", (member_id,))
    return {"success": True, "data": row}


@router.delete("/rescuers/{member_id}")
def delete_rescuer(member_id: UUID, current_user: dict = Depends(require_admin)):
    with write_cursor() as cursor:
        member = found(cursor, "SELECT * FROM rescue_team_members WHERE member_id = %s FOR UPDATE", (member_id,))
        protect_admin_membership(cursor, member, current_user)
        protect_active_team(cursor, member["team_id"])
        protect_leader(cursor, member)
        cursor.execute("DELETE FROM rescue_team_members WHERE member_id = %s", (member_id,))
    return {"success": True}


@router.get("/teams")
def teams(q: str = Query("", max_length=100), status: Literal["ACTIVE", "BUSY", "INACTIVE"] | None = None,
          page: int = Query(1, ge=1), page_size: int = Query(10, ge=1, le=200)):
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            return page_result(cursor, """SELECT t.*, (SELECT count(*) FROM rescue_team_members m WHERE m.team_id = t.team_id) AS member_count
                FROM rescue_teams t""", " WHERE (t.name ILIKE %s OR COALESCE(t.description,'') ILIKE %s) AND (%s::text IS NULL OR t.status = %s)",
                (f"%{q}%", f"%{q}%", status, status), " ORDER BY t.created_at DESC, t.team_id", page, page_size)


@router.post("/teams", status_code=201)
def create_team(dto: TeamDto):
    if dto.status == "BUSY":
        raise HTTPException(409, "Trạng thái BUSY được hệ thống cập nhật khi nhận SOS.")
    with write_cursor() as cursor:
        cursor.execute("INSERT INTO rescue_teams (name, description, status) VALUES (%s, %s, %s) RETURNING *", (dto.name, dto.description, dto.status))
        return {"success": True, "data": cursor.fetchone()}


@router.put("/teams/{team_id}")
def update_team(team_id: UUID, dto: TeamDto):
    with write_cursor() as cursor:
        team = found(cursor, "SELECT * FROM rescue_teams WHERE team_id = %s FOR UPDATE", (team_id,))
        if dto.status != team["status"]:
            protect_active_team(cursor, team_id)
            if dto.status == "BUSY":
                raise HTTPException(409, "Trạng thái BUSY được hệ thống cập nhật khi nhận SOS.")
        cursor.execute("UPDATE rescue_teams SET name = %s, description = %s, status = %s WHERE team_id = %s RETURNING *",
                       (dto.name, dto.description, dto.status, team_id))
        return {"success": True, "data": cursor.fetchone()}


@router.delete("/teams/{team_id}")
def delete_team(team_id: UUID, current_user: dict = Depends(require_admin)):
    with write_cursor() as cursor:
        found(cursor, "SELECT team_id FROM rescue_teams WHERE team_id = %s FOR UPDATE", (team_id,))
        protect_active_team(cursor, team_id)
        cursor.execute("SELECT 1 FROM rescue_team_members WHERE team_id = %s AND user_id = %s AND role = 'ADMIN'", (team_id, current_user["id"]))
        if cursor.fetchone():
            cursor.execute("SELECT 1 FROM rescue_team_members WHERE team_id <> %s AND user_id = %s AND role = 'ADMIN'", (team_id, current_user["id"]))
            if not cursor.fetchone():
                raise HTTPException(409, "Không thể xóa đội chứa quyền ADMIN cuối cùng của chính bạn.")
        cursor.execute("DELETE FROM rescue_teams WHERE team_id = %s", (team_id,))
    return {"success": True}
