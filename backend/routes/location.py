import logging
from typing import Optional
from datetime import datetime, timezone
from fastapi import APIRouter, HTTPException, status, Depends
from pydantic import BaseModel, Field
from database.connection import get_db_connection
from services.auth_service import get_current_user
from services.ws_manager import ws_manager

logger = logging.getLogger("backend.location")
router = APIRouter(prefix="/api/location", tags=["Định vị & Lộ trình Thời gian thực"])

class UpdateLocationDto(BaseModel):
    lat: float = Field(..., ge=-90.0, le=90.0, description="Vĩ độ hiện tại")
    lng: float = Field(..., ge=-180.0, le=180.0, description="Kinh độ hiện tại")
    accuracy_m: Optional[float] = Field(None, ge=0.0, description="Độ chính xác GPS (mét)")
    save_history: bool = Field(default=False, description="Ghi nhận điểm này vào location_history (chu kỳ 15-30s)")

@router.post("/{sos_id}", summary="Cập nhật vị trí GPS thời gian thực (F05 / F11 / UC04 / UC10)")
async def update_location(
    sos_id: str,
    dto: UpdateLocationDto,
    current_user: dict = Depends(get_current_user)
):
    """
    Cập nhật vị trí mới nhất cho phiên SOS:
    - Nếu người gọi là người tạo SOS -> actor = 'VICTIM'
    - Nếu người gọi là đội cứu hộ đã nhận ca -> actor = 'RESCUER'
    - Ghi đè vào location_current (chỉ lưu 1 vị trí mới nhất của mỗi bên)
    - Nếu save_history = True -> ghi thêm vào location_history để vẽ polyline lộ trình
    - Phát broadcast ngay lập tức qua WebSocket cho bên còn lại thấy marker di chuyển (F12 / UC11)
    """
    user_id = str(current_user["id"])
    now_utc = datetime.now(timezone.utc)

    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            # 1. Kiểm tra SOS và vai trò của người gửi tọa độ
            cursor.execute(
                """
                SELECT id, requester_id, team_id, status
                FROM sos_requests
                WHERE id = %s
                """,
                (sos_id,)
            )
            sos = cursor.fetchone()

            if not sos:
                raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không tìm thấy yêu cầu SOS.")

            if sos["status"] in ("COMPLETED", "CANCELLED"):
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail=f"Phiên SOS đã kết thúc ({sos['status']}). Ngừng nhận vị trí mới (RB09)."
                )

            is_victim = (str(sos["requester_id"]) == user_id)
            is_rescuer = False
            if sos["team_id"]:
                cursor.execute(
                    "SELECT 1 FROM rescue_team_members WHERE team_id = %s AND user_id = %s",
                    (sos["team_id"], user_id)
                )
                is_rescuer = (cursor.fetchone() is not None)

            if not is_victim and not is_rescuer:
                raise HTTPException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    detail="Bạn không phải nạn nhân cũng không phải đội cứu hộ của phiên SOS này."
                )

            actor = "VICTIM" if is_victim else "RESCUER"

            # 2. Cập nhật location_current (RB09: upsert vị trí mới nhất)
            cursor.execute(
                """
                INSERT INTO location_current (sos_id, actor, lat, lng, accuracy_m, updated_at)
                VALUES (%s, %s, %s, %s, %s, now())
                ON CONFLICT (sos_id, actor) DO UPDATE
                SET lat = EXCLUDED.lat,
                    lng = EXCLUDED.lng,
                    accuracy_m = EXCLUDED.accuracy_m,
                    updated_at = now()
                """,
                (sos_id, actor, dto.lat, dto.lng, dto.accuracy_m)
            )

            # 3. Ghi vào location_history nếu có cờ save_history
            if dto.save_history:
                cursor.execute(
                    """
                    INSERT INTO location_history (sos_id, actor, lat, lng, created_at)
                    VALUES (%s, %s, %s, %s, now())
                    """,
                    (sos_id, actor, dto.lat, dto.lng)
                )

    # 4. Phát sự kiện WebSocket cho phòng phiên cứu hộ (UC11)
    await ws_manager.broadcast_to_room(
        f"sos_{sos_id}",
        {
            "type": "LOCATION_BROADCAST",
            "sos_id": sos_id,
            "actor": actor,
            "lat": dto.lat,
            "lng": dto.lng,
            "accuracy_m": dto.accuracy_m,
            "updated_at": now_utc.isoformat()
        }
    )

    return {
        "success": True,
        "actor": actor,
        "lat": dto.lat,
        "lng": dto.lng,
        "updated_at": now_utc.isoformat()
    }

@router.get("/{sos_id}/current", summary="Lấy vị trí hiện tại của hai bên trong phiên SOS (UC11)")
def get_current_locations(sos_id: str, current_user: dict = Depends(get_current_user)):
    """
    F12 / UC11: Lấy marker vị trí hiện tại của nạn nhân và đội cứu hộ.
    """
    user_id = str(current_user["id"])

    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            # Kiểm tra quyền truy cập phiên
            cursor.execute(
                "SELECT requester_id, team_id FROM sos_requests WHERE id = %s",
                (sos_id,)
            )
            sos = cursor.fetchone()
            if not sos:
                raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không tìm thấy yêu cầu SOS.")

            is_victim = (str(sos["requester_id"]) == user_id)
            is_rescuer = False
            if sos["team_id"]:
                cursor.execute(
                    "SELECT 1 FROM rescue_team_members WHERE team_id = %s AND user_id = %s",
                    (sos["team_id"], user_id)
                )
                is_rescuer = (cursor.fetchone() is not None)

            if not is_victim and not is_rescuer:
                raise HTTPException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    detail="Bạn không có quyền xem vị trí chính xác của phiên này."
                )

            cursor.execute(
                "SELECT actor, lat, lng, accuracy_m, updated_at FROM location_current WHERE sos_id = %s",
                (sos_id,)
            )
            rows = cursor.fetchall()

    result = {}
    for r in rows:
        result[r["actor"]] = {
            "lat": r["lat"],
            "lng": r["lng"],
            "accuracy_m": r["accuracy_m"],
            "updated_at": r["updated_at"].isoformat() if r["updated_at"] else None
        }

    return {
        "success": True,
        "sos_id": sos_id,
        "locations": result
    }

@router.get("/{sos_id}/trail", summary="Lấy lộ trình di chuyển phục vụ vẽ Polyline (UC11)")
def get_location_trail(
    sos_id: str,
    actor: Optional[str] = None,
    limit: int = 100,
    current_user: dict = Depends(get_current_user)
):
    """
    F12 / UC11: Lấy danh sách tọa độ lịch sử từ location_history để vẽ đường Polyline.
    Chỉ lấy tối đa số điểm quy định để tối ưu hiệu năng mạng.
    """
    user_id = str(current_user["id"])

    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                "SELECT requester_id, team_id FROM sos_requests WHERE id = %s",
                (sos_id,)
            )
            sos = cursor.fetchone()
            if not sos:
                raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không tìm thấy yêu cầu SOS.")

            is_victim = (str(sos["requester_id"]) == user_id)
            is_rescuer = False
            if sos["team_id"]:
                cursor.execute(
                    "SELECT 1 FROM rescue_team_members WHERE team_id = %s AND user_id = %s",
                    (sos["team_id"], user_id)
                )
                is_rescuer = (cursor.fetchone() is not None)

            if not is_victim and not is_rescuer:
                raise HTTPException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    detail="Bạn không có quyền xem lịch sử lộ trình của phiên này."
                )

            query = "SELECT history_id AS id, actor, lat, lng, created_at FROM location_history WHERE sos_id = %s"
            params = [sos_id]
            if actor in ("VICTIM", "RESCUER"):
                query += " AND actor = %s"
                params.append(actor)
            query += " ORDER BY created_at ASC LIMIT %s"
            params.append(min(limit, 500))

            cursor.execute(query, tuple(params))
            rows = cursor.fetchall()

    points = []
    for r in rows:
        points.append({
            "id": str(r["id"]),
            "actor": r["actor"],
            "lat": r["lat"],
            "lng": r["lng"],
            "recorded_at": r["created_at"].isoformat() if r["created_at"] else None
        })

    return {
        "success": True,
        "sos_id": sos_id,
        "count": len(points),
        "trail": points
    }
