import logging
from typing import Literal
from fastapi import APIRouter, HTTPException, status, Depends
from pydantic import BaseModel, Field
import psycopg.errors
from database.connection import get_db_connection
from services.auth_service import require_rescuer
from services.ws_manager import ws_manager

logger = logging.getLogger("backend.rescuer")
router = APIRouter(prefix="/api/rescuer", tags=["Nghiệp vụ Đội Cứu Hộ (RESCUER)"])

class UpdateRescueStatusDto(BaseModel):
    status: Literal["RESCUING", "ARRIVED"] = Field(..., description="Trạng thái tiếp theo: RESCUING (đang đến) hoặc ARRIVED (đã tới nơi)")

@router.get("/queue", summary="Xem hàng đợi cứu hộ (UC07 / View rescue_queue)")
def get_rescue_queue(current_user: dict = Depends(require_rescuer)):
    """
    F08 / UC07 / View rescue_queue:
    Đội cứu hộ xem tất cả các SOS đang chờ (WAITING) trong vòng 2 giờ,
    bao gồm cả PUBLIC và PRIVATE với tọa độ ban đầu chính xác để xử lý tiếp nhận.
    """
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                """
                SELECT q.sos_id, q.emergency_type, q.visibility, q.initial_lat, q.initial_lng, q.created_at,
                       s.description, u.full_name AS requester_name, u.phone AS requester_phone
                FROM rescue_queue q
                JOIN sos_requests s ON q.sos_id = s.id
                JOIN users u ON s.requester_id = u.id
                ORDER BY q.created_at ASC
                """
            )
            rows = cursor.fetchall()

    queue_list = []
    for r in rows:
        queue_list.append({
            "sos_id": str(r["sos_id"]),
            "emergency_type": r["emergency_type"],
            "visibility": r["visibility"],
            "initial_lat": r["initial_lat"],
            "initial_lng": r["initial_lng"],
            "created_at": r["created_at"].isoformat() if r["created_at"] else None,
            "description": r["description"],
            "requester": {
                "full_name": r["requester_name"],
                "phone": r["requester_phone"]
            }
        })

    return {
        "success": True,
        "count": len(queue_list),
        "data": queue_list
    }

@router.get("/my-active", summary="Lấy ca cứu hộ đang xử lý của đội hiện tại")
def get_my_active_rescue(current_user: dict = Depends(require_rescuer)):
    """
    M05-R: Đội cứu hộ lấy ca đang tiếp nhận (ACCEPTED, RESCUING, ARRIVED).
    """
    rescuer_id = current_user["id"]
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                """
                SELECT s.id, s.requester_id, s.team_id, et.name AS emergency_type, s.emergency_type_id, s.description, s.visibility,
                       s.status, s.created_at, s.updated_at,
                       u.full_name AS victim_name, u.phone AS victim_phone
                FROM sos_requests s
                JOIN emergency_types et ON s.emergency_type_id = et.id
                JOIN rescue_team_members m ON s.team_id = m.team_id
                JOIN users u ON s.requester_id = u.id
                WHERE m.user_id = %s
                  AND s.status IN ('ACCEPTED', 'RESCUING', 'ARRIVED')
                LIMIT 1
                """,
                (rescuer_id,)
            )
            case = cursor.fetchone()

            if not case:
                return {"active": False, "case": None}

            sos_id = case["id"]
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

    loc_v = loc_dict.get("VICTIM", {})

    return {
        "active": True,
        "case": {
            "id": str(case["id"]),
            "emergency_type": case["emergency_type"],
            "description": case["description"],
            "visibility": case["visibility"],
            "status": case["status"],
            "initial_lat": loc_v.get("lat"),
            "initial_lng": loc_v.get("lng"),
            "created_at": case["created_at"].isoformat() if case["created_at"] else None,
            "victim": {
                "id": str(case["requester_id"]),
                "full_name": case["victim_name"],
                "phone": case["victim_phone"]
            },
            "locations": loc_dict
        }
    }

@router.post("/sos/{sos_id}/accept", summary="Nhận yêu cầu cứu hộ (UC09)")
async def accept_sos(sos_id: str, current_user: dict = Depends(require_rescuer)):
    """
    F10 / UC09: Đội cứu hộ nhận trách nhiệm xử lý yêu cầu.
    Ràng buộc RB05: Mỗi RESCUER/đội chỉ được nhận 1 ca tại một thời điểm.
    Ràng buộc RB06: Nhận ca là thao tác nguyên tử (UPDATE có điều kiện status='WAITING').
    """
    rescuer_id = current_user["id"]

    try:
        with get_db_connection() as conn:
            with conn.cursor() as cursor:
                # 1. Tìm team_id của rescuer
                cursor.execute(
                    "SELECT team_id FROM rescue_team_members WHERE user_id = %s LIMIT 1",
                    (rescuer_id,)
                )
                team_row = cursor.fetchone()
                if not team_row:
                    cursor.execute("SELECT team_id FROM rescue_teams LIMIT 1")
                    first_team = cursor.fetchone()
                    if first_team:
                        cursor.execute(
                            "INSERT INTO rescue_team_members (team_id, user_id, role) VALUES (%s, %s, 'MEMBER') ON CONFLICT DO NOTHING",
                            (first_team["team_id"], rescuer_id)
                        )
                        team_id = first_team["team_id"]
                    else:
                        team_id = None
                else:
                    team_id = team_row["team_id"]

                # Kiểm tra RB05: Đội hoặc Rescuer đã có ca chưa kết thúc chưa
                cursor.execute(
                    """
                    SELECT s.id, s.status FROM sos_requests s
                    JOIN rescue_team_members m ON s.team_id = m.team_id
                    WHERE m.user_id = %s
                      AND s.status IN ('ACCEPTED', 'RESCUING', 'ARRIVED')
                    LIMIT 1
                    """,
                    (rescuer_id,)
                )
                active_case = cursor.fetchone()
                if active_case:
                    raise HTTPException(
                        status_code=status.HTTP_409_CONFLICT,
                        detail={
                            "message": "Bạn đang có một ca cứu hộ đang xử lý. Mỗi đội chỉ được nhận một ca tại một thời điểm (RB05).",
                            "active_sos_id": str(active_case["id"])
                        }
                    )

                # 2. Thực hiện UPDATE có điều kiện nguyên tử (RB06)
                cursor.execute(
                    """
                    UPDATE sos_requests
                    SET status = 'ACCEPTED',
                        team_id = %s,
                        updated_at = now()
                    WHERE id = %s
                      AND status = 'WAITING'
                      AND (now() - created_at <= INTERVAL '2 hours')
                    RETURNING id, requester_id, status, emergency_type_id, team_id
                    """,
                    (team_id, sos_id)
                )
                updated = cursor.fetchone()

                if not updated:
                    raise HTTPException(
                        status_code=status.HTTP_409_CONFLICT,
                        detail="Yêu cầu SOS này đã được đội khác tiếp nhận, đã bị hủy hoặc quá thời hạn tiếp nhận."
                    )

                requester_id = str(updated["requester_id"])

                # Ghi nhận sos_logs (ERD Table 10)
                cursor.execute(
                    """
                    INSERT INTO sos_logs (sos_id, user_id, action, description, created_at)
                    VALUES (%s, %s, 'ACCEPT_SOS', %s, now())
                    """,
                    (sos_id, rescuer_id, f"Đội cứu hộ '{current_user['full_name']}' đã tiếp nhận yêu cầu.")
                )

                # Tạo thông báo sos_notifications (ERD Table 9)
                cursor.execute(
                    """
                    INSERT INTO sos_notifications (sos_id, user_id, message, type, created_at)
                    VALUES (%s, %s, %s, 'UPDATE', now())
                    """,
                    (sos_id, requester_id, f"Đội cứu hộ '{current_user['full_name']}' đã tiếp nhận yêu cầu của bạn!")
                )

    except psycopg.errors.UniqueViolation:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Bạn đang có một ca cứu hộ chưa kết thúc (vi phạm ràng buộc duy nhất RB05)."
        )

    # 3. Phát thông báo qua WebSocket
    # Thông báo trực tiếp cho nạn nhân
    await ws_manager.send_to_user(
        requester_id,
        {
            "type": "SOS_ACCEPTED",
            "sos_id": sos_id,
            "status": "ACCEPTED",
            "rescuer": {
                "id": str(current_user["id"]),
                "full_name": current_user["full_name"],
                "phone": current_user["phone"]
            },
            "message": f"Đội cứu hộ '{current_user['full_name']}' đã tiếp nhận yêu cầu của bạn!"
        }
    )

    # Thông báo cho phòng phiên SOS
    await ws_manager.broadcast_to_room(
        f"sos_{sos_id}",
        {
            "type": "SOS_ACCEPTED",
            "sos_id": sos_id,
            "status": "ACCEPTED",
            "rescuer": {
                "id": str(current_user["id"]),
                "full_name": current_user["full_name"],
                "phone": current_user["phone"]
            }
        }
    )

    # Cập nhật danh sách chờ của các RESCUER khác (rời rescue_queue)
    await ws_manager.broadcast_to_rescuers({
        "type": "QUEUE_UPDATED",
        "action": "ACCEPTED",
        "sos_id": sos_id
    })

    return {
        "success": True,
        "message": "Đã tiếp nhận yêu cầu cứu hộ thành công. Bắt đầu phiên điều phối.",
        "sos_id": sos_id,
        "status": "ACCEPTED"
    }

@router.post("/sos/{sos_id}/status", summary="Cập nhật trạng thái tiến trình cứu hộ (UC12)")
async def update_rescue_status(
    sos_id: str,
    dto: UpdateRescueStatusDto,
    current_user: dict = Depends(require_rescuer)
):
    """
    F13 / UC12: Đội cứu hộ cập nhật trạng thái đang đến (RESCUING), đã tới nơi (ARRIVED).
    Tuân thủ thứ tự: WAITING -> ACCEPTED -> RESCUING -> ARRIVED.
    """
    rescuer_id = current_user["id"]
    new_status = dto.status

    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                "SELECT id, requester_id, team_id, status FROM sos_requests WHERE id = %s",
                (sos_id,)
            )
            sos = cursor.fetchone()
            if not sos:
                raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không tìm thấy yêu cầu SOS.")

            is_assigned = False
            if sos["team_id"]:
                cursor.execute(
                    "SELECT 1 FROM rescue_team_members WHERE team_id = %s AND user_id = %s",
                    (sos["team_id"], rescuer_id)
                )
                is_assigned = (cursor.fetchone() is not None)

            if not is_assigned:
                raise HTTPException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    detail="Bạn không phải là đội cứu hộ đang xử lý ca này."
                )

            current_status = sos["status"]
            requester_id = str(sos["requester_id"])

            # Kiểm tra thứ tự chuyển đổi trạng thái hợp lệ
            if new_status == "RESCUING" and current_status != "ACCEPTED":
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail=f"Không thể chuyển sang RESCUING từ trạng thái {current_status} (phải là ACCEPTED)."
                )

            if new_status == "ARRIVED" and current_status != "RESCUING":
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail=f"Không thể chuyển sang ARRIVED từ trạng thái {current_status} (phải là RESCUING)."
                )

            cursor.execute(
                "UPDATE sos_requests SET status = %s, updated_at = now() WHERE id = %s",
                (new_status, sos_id)
            )

            status_text = "Đội cứu hộ đang trên đường tới vị trí của bạn." if new_status == "RESCUING" else "Đội cứu hộ đã tới vị trí của bạn!"

            # Ghi sos_logs và sos_notifications
            cursor.execute(
                """
                INSERT INTO sos_logs (sos_id, user_id, action, description, created_at)
                VALUES (%s, %s, %s, %s, now())
                """,
                (sos_id, rescuer_id, f"STATUS_{new_status}", status_text)
            )

            cursor.execute(
                """
                INSERT INTO sos_notifications (sos_id, user_id, message, type, created_at)
                VALUES (%s, %s, %s, 'UPDATE', now())
                """,
                (sos_id, requester_id, status_text)
            )

    # Phát thông báo WebSocket tới nạn nhân và phòng phiên
    status_text = "Đội cứu hộ đang trên đường tới vị trí của bạn." if new_status == "RESCUING" else "Đội cứu hộ đã tới vị trí của bạn!"
    event_payload = {
        "type": "STATUS_CHANGED",
        "sos_id": sos_id,
        "status": new_status,
        "message": status_text
    }

    await ws_manager.send_to_user(requester_id, event_payload)
    await ws_manager.broadcast_to_room(f"sos_{sos_id}", event_payload)

    return {
        "success": True,
        "message": f"Đã cập nhật trạng thái sang {new_status}.",
        "sos_id": sos_id,
        "status": new_status
    }

@router.post("/sos/{sos_id}/release", summary="Nhả ca cứu hộ (UC12 / Quay về WAITING)")
async def release_rescue(sos_id: str, current_user: dict = Depends(require_rescuer)):
    """
    F13 / UC12: Đội cứu hộ nhả ca khi không thể tiếp tục (chỉ khi đang ACCEPTED hoặc RESCUING).
    Thực hiện trong một giao dịch:
    - status quay về WAITING, team_id về NULL.
    - Xóa vị trí của RESCUER trong location_current và location_history.
    - SOS tự động xuất hiện lại trong view rescue_queue.
    """
    rescuer_id = current_user["id"]

    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                """
                SELECT s.id, s.requester_id, s.team_id, s.status, et.name AS emergency_type, s.emergency_type_id, s.visibility, s.created_at
                FROM sos_requests s
                JOIN emergency_types et ON s.emergency_type_id = et.id
                WHERE s.id = %s
                """,
                (sos_id,)
            )
            sos = cursor.fetchone()
            if not sos:
                raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không tìm thấy yêu cầu SOS.")

            is_assigned = False
            if sos["team_id"]:
                cursor.execute(
                    "SELECT 1 FROM rescue_team_members WHERE team_id = %s AND user_id = %s",
                    (sos["team_id"], rescuer_id)
                )
                is_assigned = (cursor.fetchone() is not None)

            if not is_assigned:
                raise HTTPException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    detail="Bạn không phải là đội cứu hộ đang xử lý ca này."
                )

            # Chỉ cho phép nhả ca khi chưa ARRIVED
            if sos["status"] not in ("ACCEPTED", "RESCUING"):
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="Chỉ có thể nhả ca khi đang ở trạng thái ACCEPTED hoặc RESCUING (chưa ARRIVED)."
                )

            requester_id = str(sos["requester_id"])

            # Cập nhật status về WAITING, giải phóng team_id
            cursor.execute(
                """
                UPDATE sos_requests
                SET status = 'WAITING',
                    team_id = NULL,
                    updated_at = now()
                WHERE id = %s
                """,
                (sos_id,)
            )

            # Lấy vị trí của nạn nhân từ location_current để broadcast
            cursor.execute(
                "SELECT lat, lng FROM location_current WHERE sos_id = %s AND actor = 'VICTIM'",
                (sos_id,)
            )
            v_loc = cursor.fetchone()
            v_lat = v_loc["lat"] if v_loc else 0.0
            v_lng = v_loc["lng"] if v_loc else 0.0

            # Xóa các dòng vị trí của RESCUER trong location_current và location_history (UC12)
            cursor.execute(
                "DELETE FROM location_current WHERE sos_id = %s AND actor = 'RESCUER'",
                (sos_id,)
            )
            cursor.execute(
                "DELETE FROM location_history WHERE sos_id = %s AND actor = 'RESCUER'",
                (sos_id,)
            )

            # Ghi sos_logs và sos_notifications
            cursor.execute(
                """
                INSERT INTO sos_logs (sos_id, user_id, action, description, created_at)
                VALUES (%s, %s, 'RELEASE_SOS', 'Đội cứu hộ đã nhả ca, yêu cầu trở lại hàng đợi.', now())
                """,
                (sos_id, rescuer_id)
            )
            cursor.execute(
                """
                INSERT INTO sos_notifications (sos_id, user_id, message, type, created_at)
                VALUES (%s, %s, 'Đội cứu hộ trước đã nhả ca. Hệ thống đang tìm đội cứu hộ khác cho bạn.', 'SYSTEM', now())
                """,
                (sos_id, requester_id)
            )

    # Thông báo cho nạn nhân biết đội cứu hộ đã nhả ca
    await ws_manager.send_to_user(
        requester_id,
        {
            "type": "SOS_RELEASED",
            "sos_id": sos_id,
            "status": "WAITING",
            "message": "Đội cứu hộ trước không thể tiếp tục và đã nhả ca. Hệ thống đang tìm đội cứu hộ khác cho bạn."
        }
    )

    await ws_manager.broadcast_to_room(
        f"sos_{sos_id}",
        {
            "type": "SOS_RELEASED",
            "sos_id": sos_id,
            "status": "WAITING"
        }
    )

    # Phát lại vào hàng đợi của các RESCUER
    await ws_manager.broadcast_to_rescuers({
        "type": "NEW_SOS",
        "sos_id": sos_id,
        "emergency_type": sos["emergency_type"],
        "visibility": sos["visibility"],
        "initial_lat": v_lat,
        "initial_lng": v_lng,
        "created_at": sos["created_at"].isoformat() if sos["created_at"] else None
    })

    return {
        "success": True,
        "message": "Đã nhả ca thành công. SOS đã trở lại hàng đợi chờ đội khác tiếp nhận.",
        "sos_id": sos_id,
        "status": "WAITING"
    }

@router.post("/sos/{sos_id}/complete", summary="Hoàn thành phiên cứu hộ (UC13)")
async def complete_rescue(sos_id: str, current_user: dict = Depends(require_rescuer)):
    """
    F14 / UC13 / M06-R: Đội cứu hộ hoàn thành ca sau khi đã tiếp cận và hỗ trợ nạn nhân.
    Tiền điều kiện: Trạng thái hiện tại phải là ARRIVED.
    Tác động:
    - status = 'COMPLETED', updated_at = now(), ghi vết vào sos_logs.
    - Dừng tracking realtime, xóa location_current (RB09).
    - Giải phóng ca cứu hộ của RESCUER.
    """
    rescuer_id = current_user["id"]

    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                "SELECT id, requester_id, team_id, status FROM sos_requests WHERE id = %s",
                (sos_id,)
            )
            sos = cursor.fetchone()
            if not sos:
                raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Không tìm thấy yêu cầu SOS.")

            is_assigned = False
            if sos["team_id"]:
                cursor.execute(
                    "SELECT 1 FROM rescue_team_members WHERE team_id = %s AND user_id = %s",
                    (sos["team_id"], rescuer_id)
                )
                is_assigned = (cursor.fetchone() is not None)

            if not is_assigned:
                raise HTTPException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    detail="Bạn không phải là đội cứu hộ đang xử lý ca này."
                )

            if sos["status"] != "ARRIVED":
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail=f"Chỉ có thể hoàn thành khi đã tới nơi (ARRIVED). Trạng thái hiện tại là {sos['status']}."
                )

            requester_id = str(sos["requester_id"])

            cursor.execute(
                """
                UPDATE sos_requests
                SET status = 'COMPLETED',
                    updated_at = now()
                WHERE id = %s
                RETURNING id, status, updated_at
                """,
                (sos_id,)
            )

            # Xóa dòng location_current (RB09)
            cursor.execute("DELETE FROM location_current WHERE sos_id = %s", (sos_id,))

            # Ghi sos_logs và sos_notifications
            cursor.execute(
                """
                INSERT INTO sos_logs (sos_id, user_id, action, description, created_at)
                VALUES (%s, %s, 'COMPLETE_SOS', 'Phiên cứu hộ đã hoàn thành xuất sắc.', now())
                """,
                (sos_id, rescuer_id)
            )
            cursor.execute(
                """
                INSERT INTO sos_notifications (sos_id, user_id, message, type, created_at)
                VALUES (%s, %s, 'Phiên cứu hộ đã hoàn thành thành công. Cảm ơn bạn và đội cứu hộ!', 'UPDATE', now())
                """,
                (sos_id, requester_id)
            )

    # Phát thông báo hoàn thành qua WebSocket
    event_payload = {
        "type": "SOS_COMPLETED",
        "sos_id": sos_id,
        "status": "COMPLETED",
        "end_reason": "RESCUER_COMPLETED",
        "message": "Phiên cứu hộ đã hoàn thành thành công. Cảm ơn đội cứu hộ!"
    }

    await ws_manager.send_to_user(requester_id, event_payload)
    await ws_manager.broadcast_to_room(f"sos_{sos_id}", event_payload)

    await ws_manager.broadcast_to_rescuers({
        "type": "QUEUE_UPDATED",
        "action": "COMPLETED",
        "sos_id": sos_id
    })

    return {
        "success": True,
        "message": "Phiên cứu hộ đã hoàn thành xuất sắc. Ca của bạn đã được giải phóng.",
        "sos_id": sos_id,
        "status": "COMPLETED",
        "end_reason": "RESCUER_COMPLETED"
    }

@router.get("/teams", summary="Danh sách các đội cứu hộ và thành viên (ERD Tables 5 & 6: rescue_teams, rescue_team_members)")
def get_rescue_teams(current_user: dict = Depends(require_rescuer)):
    """
    Lấy danh sách các đội cứu hộ và thành viên trong đội.
    """
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                """
                SELECT t.team_id, t.name, t.description, t.status, t.created_at,
                       m.member_id, m.role AS member_role, u.id AS user_id, u.full_name AS member_name, u.phone AS member_phone
                FROM rescue_teams t
                LEFT JOIN rescue_team_members m ON t.team_id = m.team_id
                LEFT JOIN users u ON m.user_id = u.id
                ORDER BY t.created_at ASC
                """
            )
            rows = cursor.fetchall()

    teams_dict = {}
    for r in rows:
        tid = str(r["team_id"])
        if tid not in teams_dict:
            teams_dict[tid] = {
                "team_id": tid,
                "name": r["name"],
                "description": r["description"],
                "status": r["status"],
                "created_at": r["created_at"].isoformat() if r["created_at"] else None,
                "members": []
            }
        if r["member_id"]:
            teams_dict[tid]["members"].append({
                "member_id": str(r["member_id"]),
                "user_id": str(r["user_id"]),
                "name": r["member_name"],
                "phone": r["member_phone"],
                "role": r["member_role"]
            })

    return {
        "success": True,
        "count": len(teams_dict),
        "data": list(teams_dict.values())
    }

