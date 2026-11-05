import logging
from typing import Optional, Literal
from fastapi import APIRouter, HTTPException, status, Depends
from pydantic import BaseModel, Field, model_validator
import psycopg.errors
from database.connection import get_db_connection
from services.auth_service import get_current_user
from services.ws_manager import ws_manager

logger = logging.getLogger("backend.sos")
router = APIRouter(prefix="/api/sos", tags=["Yêu cầu SOS (USER)"])

class CreateSosDto(BaseModel):
    emergency_type_id: Optional[str] = Field(None, description="UUID của loại sự cố trong bảng emergency_types")
    emergency_type: Optional[str] = Field(None, max_length=50, description="Tên định danh loại sự cố (VD: TAI_NAN, NGAP_LUT, CHAY_NO, KHAC)")
    description: Optional[str] = Field(None, max_length=500, description="Mô tả tình huống khẩn cấp")
    visibility: Literal["PUBLIC", "PRIVATE"] = Field(default="PRIVATE", description="Chế độ chia sẻ: PUBLIC hoặc PRIVATE (mặc định PRIVATE)")
    initial_lat: float = Field(..., ge=-90.0, le=90.0, description="Vĩ độ GPS hiện tại (-90 đến 90)")
    initial_lng: float = Field(..., ge=-180.0, le=180.0, description="Kinh độ GPS hiện tại (-180 đến 180)")

    @model_validator(mode="after")
    def validate_type(self) -> "CreateSosDto":
        if not self.emergency_type_id and not self.emergency_type:
            raise ValueError("Vui lòng cung cấp emergency_type_id hoặc emergency_type.")
        if self.emergency_type is not None and not self.emergency_type.strip():
            raise ValueError("Loại sự cố không được để trống.")
        return self

class ChangeVisibilityDto(BaseModel):
    visibility: Literal["PUBLIC", "PRIVATE"]

@router.post("", summary="Tạo và gửi yêu cầu SOS mới (UC03)")
async def create_sos(dto: CreateSosDto, current_user: dict = Depends(get_current_user)):
    """
    F04 & UC03: Người dùng tạo yêu cầu SOS kèm vị trí GPS và tình huống.
    Hỗ trợ truyền emergency_type_id (UUID) hoặc emergency_type (Tên định danh).
    Ràng buộc RB01: requester_id lấy từ token phiên đăng nhập.
    Ràng buộc RB02: Tọa độ hợp lệ.
    Ràng buộc RB04: Mỗi người gửi chỉ có tối đa 1 SOS chưa kết thúc.
    """
    user_id = current_user["id"]

    try:
        with get_db_connection() as conn:
            with conn.cursor() as cursor:
                # 1. Kiểm tra chủ động xem người dùng có SOS đang hoạt động không (RB04)
                cursor.execute(
                    """
                    SELECT s.id, s.status, et.name AS emergency_type, s.created_at
                    FROM sos_requests s
                    JOIN emergency_types et ON s.emergency_type_id = et.id
                    WHERE s.requester_id = %s
                      AND s.status IN ('WAITING', 'ACCEPTED', 'RESCUING', 'ARRIVED')
                    LIMIT 1
                    """,
                    (user_id,)
                )
                existing_sos = cursor.fetchone()
                if existing_sos:
                    raise HTTPException(
                        status_code=status.HTTP_409_CONFLICT,
                        detail={
                            "message": "Bạn đang có một yêu cầu SOS đang hoạt động chưa kết thúc.",
                            "active_sos_id": str(existing_sos["id"]),
                            "status": existing_sos["status"]
                        }
                    )

                # 2. Phân giải emergency_type_id và emergency_type name từ bảng emergency_types
                type_val = (dto.emergency_type_id or dto.emergency_type or "KHAC").strip()
                cursor.execute(
                    """
                    SELECT id, name FROM emergency_types 
                    WHERE id::text = %s OR name = %s
                    LIMIT 1
                    """,
                    (type_val, type_val.upper())
                )
                et_row = cursor.fetchone()
                if not et_row:
                    # Nếu chưa có, tự động tạo mới loại sự cố trong bảng emergency_types
                    type_name = type_val.upper()
                    cursor.execute(
                        """
                        INSERT INTO emergency_types (name, description)
                        VALUES (%s, %s)
                        ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name
                        RETURNING id, name
                        """,
                        (type_name, f"Loại sự cố {type_name}")
                    )
                    et_row = cursor.fetchone()

                resolved_type_id = et_row["id"]
                resolved_type_name = et_row["name"]

                # 3. Ghi bản ghi sos_requests trong một giao dịch
                cursor.execute(
                    """
                    INSERT INTO sos_requests (
                        requester_id, emergency_type_id, description, visibility,
                        status, created_at, updated_at
                    )
                    VALUES (%s, %s, %s, %s, 'WAITING', now(), now())
                    RETURNING id, requester_id, emergency_type_id, description, visibility,
                              status, created_at
                    """,
                    (user_id, resolved_type_id, dto.description, dto.visibility)
                )
                sos_record = cursor.fetchone()
                sos_id = sos_record["id"]

                # 4. Khởi tạo điểm vị trí ban đầu trong location_current và location_history (actor='VICTIM')
                cursor.execute(
                    """
                    INSERT INTO location_current (sos_id, actor, lat, lng, updated_at)
                    VALUES (%s, 'VICTIM', %s, %s, now())
                    """,
                    (sos_id, dto.initial_lat, dto.initial_lng)
                )

                cursor.execute(
                    """
                    INSERT INTO location_history (sos_id, actor, lat, lng, created_at)
                    VALUES (%s, 'VICTIM', %s, %s, now())
                    """,
                    (sos_id, dto.initial_lat, dto.initial_lng)
                )

                # 5. Ghi nhật ký vào sos_logs (ERD Table 10)
                cursor.execute(
                    """
                    INSERT INTO sos_logs (sos_id, user_id, action, description, created_at)
                    VALUES (%s, %s, 'CREATE_SOS', %s, now())
                    """,
                    (sos_id, user_id, f"Khởi tạo tín hiệu SOS khẩn cấp loại {resolved_type_name} ({dto.visibility})")
                )

                # 6. Tạo thông báo vào sos_notifications (ERD Table 9)
                cursor.execute(
                    """
                    INSERT INTO sos_notifications (sos_id, user_id, message, type, created_at)
                    VALUES (%s, %s, %s, 'SYSTEM', now())
                    """,
                    (sos_id, user_id, f"Yêu cầu SOS [{resolved_type_name}] đã được gửi đi và đang chờ đội cứu hộ tiếp nhận.")
                )

    except psycopg.errors.UniqueViolation:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Bạn đang có một yêu cầu SOS đang hoạt động (vi phạm ràng buộc duy nhất RB04)."
        )

    sos_id_str = str(sos_id)

    # 7. Phát sự kiện SOS mới qua WebSocket cho tất cả các RESCUER (UC03 / UC07)
    await ws_manager.broadcast_to_rescuers({
        "type": "NEW_SOS",
        "sos_id": sos_id_str,
        "emergency_type": resolved_type_name,
        "emergency_type_id": str(resolved_type_id),
        "visibility": dto.visibility,
        "initial_lat": dto.initial_lat,
        "initial_lng": dto.initial_lng,
        "created_at": sos_record["created_at"].isoformat()
    })

    return {
        "success": True,
        "message": "Đã gửi tín hiệu cứu hộ SOS thành công. Đang kết nối tới đội cứu hộ gần nhất.",
        "sos": {
            "id": sos_id_str,
            "requester_id": str(sos_record["requester_id"]),
            "emergency_type": resolved_type_name,
            "emergency_type_id": str(resolved_type_id),
            "description": sos_record["description"],
            "visibility": sos_record["visibility"],
            "initial_lat": dto.initial_lat,
            "initial_lng": dto.initial_lng,
            "status": sos_record["status"],
            "created_at": sos_record["created_at"].isoformat()
        }
    }

@router.get("/my-active", summary="Lấy yêu cầu SOS đang hoạt động của tài khoản hiện tại")
def get_my_active_sos(current_user: dict = Depends(get_current_user)):
    """
    UC05 / M05-U:
    Người dùng kiểm tra SOS đang diễn ra của chính mình, thông tin đội cứu hộ và tọa độ realtime.
    """
    user_id = current_user["id"]
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                """
                SELECT s.id, s.requester_id, s.team_id, et.name AS emergency_type, s.emergency_type_id,
                       s.description, s.visibility, s.status, s.created_at, s.updated_at,
                       t.name AS team_name, t.description AS team_description
                FROM sos_requests s
                JOIN emergency_types et ON s.emergency_type_id = et.id
                LEFT JOIN rescue_teams t ON s.team_id = t.team_id
                WHERE s.requester_id = %s
                  AND s.status IN ('WAITING', 'ACCEPTED', 'RESCUING', 'ARRIVED')
                LIMIT 1
                """,
                (user_id,)
            )
            sos = cursor.fetchone()

            if not sos:
                return {"active": False, "sos": None}

            sos_id = sos["id"]

            # Lấy vị trí realtime hiện tại của cả 2 phía từ bảng location_current
            cursor.execute(
                """
                SELECT actor, lat, lng, accuracy_m, updated_at
                FROM location_current
                WHERE sos_id = %s
                """,
                (sos_id,)
            )
            locations = cursor.fetchall()
            loc_dict = {loc["actor"]: {
                "lat": loc["lat"],
                "lng": loc["lng"],
                "accuracy_m": loc["accuracy_m"],
                "updated_at": loc["updated_at"].isoformat() if loc["updated_at"] else None
            } for loc in locations}

            # Thông tin người phụ trách cứu hộ (nếu đã được tiếp nhận)
            rescuer_info = None
            if sos["team_id"]:
                cursor.execute(
                    """
                    SELECT u.id, u.full_name, u.phone
                    FROM rescue_team_members m
                    JOIN users u ON m.user_id = u.id
                    WHERE m.team_id = %s
                    LIMIT 1
                    """,
                    (sos["team_id"],)
                )
                r_user = cursor.fetchone()
                if r_user:
                    rescuer_info = {
                        "id": str(r_user["id"]),
                        "full_name": r_user["full_name"],
                        "phone": r_user["phone"],
                        "team_name": sos["team_name"]
                    }

    loc_v = loc_dict.get("VICTIM", {})

    return {
        "active": True,
        "sos": {
            "id": str(sos["id"]),
            "requester_id": str(sos["requester_id"]),
            "team_id": str(sos["team_id"]) if sos["team_id"] else None,
            "emergency_type": sos["emergency_type"],
            "description": sos["description"],
            "visibility": sos["visibility"],
            "initial_lat": loc_v.get("lat"),
            "initial_lng": loc_v.get("lng"),
            "status": sos["status"],
            "created_at": sos["created_at"].isoformat() if sos["created_at"] else None,
            "rescuer": rescuer_info,
            "locations": loc_dict
        }
    }

@router.get("/public/map", summary="Xem bản đồ SOS PUBLIC của cộng đồng (UC07 / View public_sos_map)")
def get_public_sos_map():
    """
    F08 / UC07 / View public_sos_map:
    Chỉ hiển thị SOS có visibility=PUBLIC và còn hoạt động.
    Tọa độ được làm tròn để bảo vệ quyền riêng tư (sai số ~1km).
    Không chứa họ tên, số điện thoại, mô tả riêng tư.
    """
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                """
                SELECT sos_id, emergency_type, area_lat, area_lng, created_at
                FROM public_sos_map
                ORDER BY created_at DESC
                """
            )
            rows = cursor.fetchall()

    items = []
    for r in rows:
        items.append({
            "sos_id": str(r["sos_id"]),
            "emergency_type": r["emergency_type"],
            "area_lat": r["area_lat"],
            "area_lng": r["area_lng"],
            "created_at": r["created_at"].isoformat() if r["created_at"] else None
        })

    return {
        "success": True,
        "count": len(items),
        "data": items
    }

@router.get("/{sos_id}", summary="Xem chi tiết yêu cầu SOS theo ID")
def get_sos_detail(sos_id: str, current_user: dict = Depends(get_current_user)):
    """
    UC08 / M04-R:
    Kiểm tra phân quyền nghiêm ngặt:
    - Nếu là USER: chỉ được xem SOS do chính mình tạo (chủ SOS).
    - Nếu là RESCUER: được xem các SOS WAITING hoặc SOS do chính mình tiếp nhận.
    """
    user_id = current_user["id"]
    role = current_user["role"]

    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                """
                SELECT s.id, s.requester_id, s.team_id, et.name AS emergency_type, s.emergency_type_id,
                       s.description, s.visibility, s.status, s.created_at, s.updated_at,
                       u.full_name AS requester_name, u.phone AS requester_phone,
                       t.name AS team_name
                FROM sos_requests s
                JOIN emergency_types et ON s.emergency_type_id = et.id
                JOIN users u ON s.requester_id = u.id
                LEFT JOIN rescue_teams t ON s.team_id = t.team_id
                WHERE s.id = %s
                """,
                (sos_id,)
            )
            sos = cursor.fetchone()

            if not sos:
                raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không tìm thấy yêu cầu SOS.")

            # Kiểm tra quyền truy cập dữ liệu chi tiết
            is_owner = (str(sos["requester_id"]) == str(user_id))
            is_assigned_rescuer = False
            if sos["team_id"]:
                cursor.execute(
                    "SELECT 1 FROM rescue_team_members WHERE team_id = %s AND user_id = %s",
                    (sos["team_id"], user_id)
                )
                is_assigned_rescuer = (cursor.fetchone() is not None)

            if role == "USER" and not is_owner:
                raise HTTPException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    detail="Bạn không có quyền xem thông tin chi tiết yêu cầu SOS của người khác."
                )

            if role == "RESCUER" and not (is_assigned_rescuer or sos["status"] == "WAITING"):
                raise HTTPException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    detail="Đội cứu hộ chỉ có quyền xem SOS đang chờ hoặc do chính mình tiếp nhận."
                )

            # Lấy vị trí mới nhất
            cursor.execute(
                "SELECT actor, lat, lng, accuracy_m, updated_at FROM location_current WHERE sos_id = %s",
                (sos_id,)
            )
            locations = cursor.fetchall()
            loc_dict = {l["actor"]: {
                "lat": l["lat"],
                "lng": l["lng"],
                "accuracy_m": l["accuracy_m"],
                "updated_at": l["updated_at"].isoformat() if l["updated_at"] else None
            } for l in locations}

            # Thông tin đội cứu hộ nếu có
            rescuer_info = None
            if sos["team_id"]:
                cursor.execute(
                    """
                    SELECT u.id, u.full_name, u.phone
                    FROM rescue_team_members m
                    JOIN users u ON m.user_id = u.id
                    WHERE m.team_id = %s
                    LIMIT 1
                    """,
                    (sos["team_id"],)
                )
                r_u = cursor.fetchone()
                if r_u:
                    rescuer_info = {
                        "id": str(r_u["id"]),
                        "full_name": r_u["full_name"],
                        "phone": r_u["phone"],
                        "team_name": sos["team_name"]
                    }

    loc_v = loc_dict.get("VICTIM", {})

    return {
        "success": True,
        "sos": {
            "id": str(sos["id"]),
            "emergency_type": sos["emergency_type"],
            "description": sos["description"],
            "visibility": sos["visibility"],
            "status": sos["status"],
            "initial_lat": loc_v.get("lat"),
            "initial_lng": loc_v.get("lng"),
            "created_at": sos["created_at"].isoformat() if sos["created_at"] else None,
            "updated_at": sos["updated_at"].isoformat() if sos["updated_at"] else None,
            "requester": {
                "id": str(sos["requester_id"]),
                "full_name": sos["requester_name"],
                "phone": sos["requester_phone"]
            },
            "rescuer": rescuer_info,
            "locations": loc_dict
        }
    }

@router.put("/{sos_id}/visibility", summary="Thay đổi chế độ chia sẻ PUBLIC/PRIVATE (chủ SOS)")
async def change_visibility(sos_id: str, dto: ChangeVisibilityDto, current_user: dict = Depends(get_current_user)):
    """
    F04 / UC03: Người dùng chủ SOS đổi chế độ PUBLIC/PRIVATE.
    Khi đổi sang PRIVATE: tự động biến mất khỏi view public_sos_map.
    """
    user_id = current_user["id"]
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                """
                UPDATE sos_requests
                SET visibility = %s
                WHERE id = %s AND requester_id = %s
                RETURNING id, visibility, status
                """,
                (dto.visibility, sos_id, user_id)
            )
            updated = cursor.fetchone()
            if not updated:
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND,
                    detail="Không tìm thấy SOS hoặc bạn không phải người tạo."
                )

    await ws_manager.broadcast_to_room(
        f"sos_{sos_id}",
        {"type": "VISIBILITY_CHANGED", "sos_id": sos_id, "visibility": dto.visibility}
    )

    return {
        "success": True,
        "message": f"Đã chuyển chế độ chia sẻ sang {dto.visibility}.",
        "visibility": dto.visibility
    }

@router.post("/{sos_id}/cancel", summary="Hủy yêu cầu SOS (UC06 / Chủ SOS)")
async def cancel_sos(sos_id: str, current_user: dict = Depends(get_current_user)):
    """
    F07 / UC06 / M06-U: Người dùng hủy yêu cầu SOS khi đã an toàn hoặc gửi nhầm.
    Ràng buộc RB09: Khi kết thúc, ngừng ghi vị trí mới và xóa dòng location_current.
    """
    user_id = current_user["id"]

    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            # Kiểm tra quyền chủ sở hữu và trạng thái chưa kết thúc
            cursor.execute(
                """
                SELECT id, status, team_id
                FROM sos_requests
                WHERE id = %s AND requester_id = %s
                """,
                (sos_id, user_id)
            )
            sos = cursor.fetchone()
            if not sos:
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND,
                    detail="Không tìm thấy yêu cầu SOS hoặc bạn không có quyền hủy."
                )

            if sos["status"] in ("COMPLETED", "CANCELLED"):
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail=f"Yêu cầu SOS đã ở trạng thái kết thúc ({sos['status']}). Không thể hủy tiếp."
                )

            # Cập nhật status = 'CANCELLED', updated_at = now()
            cursor.execute(
                """
                UPDATE sos_requests
                SET status = 'CANCELLED',
                    updated_at = now()
                WHERE id = %s
                RETURNING id, status, updated_at
                """,
                (sos_id,)
            )

            # Dọn dẹp location_current (RB09)
            cursor.execute("DELETE FROM location_current WHERE sos_id = %s", (sos_id,))

            # Ghi nhật ký sos_logs
            cursor.execute(
                """
                INSERT INTO sos_logs (sos_id, user_id, action, description, created_at)
                VALUES (%s, %s, 'CANCEL_SOS', 'Người dùng đã hủy yêu cầu SOS.', now())
                """,
                (sos_id, user_id)
            )

            # Tạo thông báo sos_notifications
            cursor.execute(
                """
                INSERT INTO sos_notifications (sos_id, user_id, message, type, created_at)
                VALUES (%s, %s, 'Yêu cầu cứu trợ của bạn đã được hủy an toàn.', 'SYSTEM', now())
                """,
                (sos_id, user_id)
            )

            if sos["team_id"]:
                cursor.execute(
                    "SELECT user_id FROM rescue_team_members WHERE team_id = %s",
                    (sos["team_id"],)
                )
                for tm in cursor.fetchall():
                    cursor.execute(
                        """
                        INSERT INTO sos_notifications (sos_id, user_id, message, type, created_at)
                        VALUES (%s, %s, 'Nạn nhân đã hủy yêu cầu cứu trợ (báo an toàn/gửi nhầm).', 'SYSTEM', now())
                        """,
                        (sos_id, tm["user_id"])
                    )

    # Phát sự kiện qua WebSocket tới phòng phiên và các RESCUER
    await ws_manager.broadcast_to_room(
        f"sos_{sos_id}",
        {
            "type": "SOS_CANCELLED",
            "sos_id": sos_id,
            "status": "CANCELLED",
            "end_reason": "USER_CANCELLED",
            "message": "Người gặp nạn đã hủy yêu cầu SOS (báo đã an toàn hoặc gửi nhầm)."
        }
    )

    await ws_manager.broadcast_to_rescuers({
        "type": "QUEUE_UPDATED",
        "action": "CANCELLED",
        "sos_id": sos_id
    })

    return {
        "success": True,
        "message": "Đã hủy yêu cầu SOS thành công. Dừng theo dõi vị trí.",
        "status": "CANCELLED",
        "end_reason": "USER_CANCELLED"
    }

@router.get("/{sos_id}/logs", summary="Lấy nhật ký hoạt động của phiên SOS (ERD Table 10: sos_logs)")
def get_sos_logs(sos_id: str, current_user: dict = Depends(get_current_user)):
    """
    Xem toàn bộ lịch sử thao tác của phiên cứu trợ (chủ SOS hoặc RESCUER).
    """
    user_id = current_user["id"]
    role = current_user["role"]

    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            # Kiểm tra quyền
            cursor.execute("SELECT requester_id, team_id FROM sos_requests WHERE id = %s", (sos_id,))
            sos = cursor.fetchone()
            if not sos:
                raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không tìm thấy yêu cầu SOS.")

            is_owner = (str(sos["requester_id"]) == str(user_id))
            if role == "USER" and not is_owner:
                raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Không có quyền xem nhật ký SOS này.")

            cursor.execute(
                """
                SELECT l.log_id, l.sos_id, l.user_id, l.action, l.description, l.created_at,
                       u.full_name AS actor_name
                FROM sos_logs l
                LEFT JOIN users u ON l.user_id = u.id
                WHERE l.sos_id = %s
                ORDER BY l.created_at ASC
                """,
                (sos_id,)
            )
            logs = cursor.fetchall()

    return {
        "success": True,
        "count": len(logs),
        "data": [{
            "log_id": str(l["log_id"]),
            "sos_id": str(l["sos_id"]),
            "action": l["action"],
            "description": l["description"],
            "created_at": l["created_at"].isoformat() if l["created_at"] else None,
            "actor_name": l["actor_name"]
        } for l in logs]
    }

@router.get("/notifications/my", summary="Lấy danh sách thông báo của tài khoản (ERD Table 9: sos_notifications)")
def get_my_notifications(current_user: dict = Depends(get_current_user)):
    user_id = current_user["id"]
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                """
                SELECT notify_id, sos_id, message, type, is_read, created_at
                FROM sos_notifications
                WHERE user_id = %s
                ORDER BY created_at DESC
                LIMIT 50
                """,
                (user_id,)
            )
            notifs = cursor.fetchall()

    return {
        "success": True,
        "count": len(notifs),
        "data": [{
            "notify_id": str(n["notify_id"]),
            "sos_id": str(n["sos_id"]),
            "message": n["message"],
            "type": n["type"],
            "is_read": n["is_read"],
            "created_at": n["created_at"].isoformat() if n["created_at"] else None
        } for n in notifs]
    }

@router.put("/notifications/{notify_id}/read", summary="Đánh dấu thông báo đã đọc")
def mark_notification_read(notify_id: str, current_user: dict = Depends(get_current_user)):
    user_id = current_user["id"]
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                "UPDATE sos_notifications SET is_read = true WHERE notify_id = %s AND user_id = %s RETURNING notify_id",
                (notify_id, user_id)
            )
            res = cursor.fetchone()
            if not res:
                raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không tìm thấy thông báo.")

    return {"success": True, "message": "Đã đánh dấu thông báo là đã đọc."}

