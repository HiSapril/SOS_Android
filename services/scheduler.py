import logging
import asyncio
from apscheduler.schedulers.asyncio import AsyncIOScheduler
import config
from database.connection import get_db_connection
from services.ws_manager import ws_manager

logger = logging.getLogger("backend.scheduler")
_scheduler: AsyncIOScheduler | None = None

async def check_expired_sos():
    """
    Ràng buộc RB07:
    Tác vụ định kỳ kiểm tra các SOS ở trạng thái WAITING quá 2 giờ.
    Chuyển trạng thái sang CANCELLED và ghi nhận nhật ký vào sos_logs.
    Xóa location_current và thông báo qua WebSocket (RB09).
    """
    try:
        expired_records = []
        with get_db_connection() as conn:
            with conn.cursor() as cursor:
                # Tìm và cập nhật nguyên tử các SOS quá hạn
                cursor.execute(
                    """
                    UPDATE sos_requests
                    SET status = 'CANCELLED',
                        updated_at = now()
                    WHERE status = 'WAITING'
                      AND created_at < now() - (%s || ' hours')::interval
                    RETURNING id, requester_id
                    """,
                    (str(config.SOS_TIMEOUT_HOURS),)
                )
                expired_records = cursor.fetchall()

                # Xóa vị trí hiện tại trong location_current của các SOS hết hạn (RB09)
                for record in expired_records:
                    cursor.execute(
                        "DELETE FROM location_current WHERE sos_id = %s",
                        (record["id"],)
                    )
                    cursor.execute(
                        """
                        INSERT INTO sos_logs (sos_id, action, description, created_at)
                        VALUES (%s, 'EXPIRED_SOS', 'Yêu cầu SOS tự động hủy do quá hạn 2 giờ chờ.', now())
                        """,
                        (record["id"],)
                    )
                    cursor.execute(
                        """
                        INSERT INTO sos_notifications (sos_id, user_id, message, type, created_at)
                        VALUES (%s, %s, 'Yêu cầu SOS của bạn đã tự động kết thúc do quá hạn 2 giờ chờ.', 'SYSTEM', now())
                        """,
                        (record["id"], record["requester_id"])
                    )

        if expired_records:
            logger.info(f"Đã xử lý tự động {len(expired_records)} SOS quá hạn {config.SOS_TIMEOUT_HOURS} giờ.")
            for rec in expired_records:
                sos_id = str(rec["id"])
                requester_id = str(rec["requester_id"])
                
                # Thông báo cho nạn nhân
                await ws_manager.send_to_user(
                    requester_id,
                    {
                        "type": "SOS_EXPIRED",
                        "sos_id": sos_id,
                        "status": "CANCELLED",
                        "end_reason": "EXPIRED",
                        "message": f"Yêu cầu SOS đã tự động kết thúc do quá hạn {config.SOS_TIMEOUT_HOURS} giờ chờ."
                    }
                )
                
                # Cập nhật danh sách chờ của đội cứu hộ
                await ws_manager.broadcast_to_rescuers({
                    "type": "QUEUE_UPDATED",
                    "action": "EXPIRED",
                    "sos_id": sos_id
                })
    except Exception as e:
        logger.error(f"Lỗi khi kiểm tra SOS quá hạn: {e}")

async def cleanup_unverified_users():
    """
    Tác vụ định kỳ: Xóa các tài khoản tạm (chỉ mới bấm bot Telegram nhưng chưa nhập OTP)
    đã tồn tại quá 24 giờ để tránh rác CSDL.
    """
    try:
        with get_db_connection() as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    """
                    DELETE FROM users 
                    WHERE is_valid = false 
                      AND created_at < now() - INTERVAL '24 hours'
                    RETURNING phone;
                    """
                )
                deleted = cursor.fetchall()
                if deleted:
                    logger.info(f"Đã dọn dẹp {len(deleted)} tài khoản tạm (chưa xác thực OTP) quá hạn 24 giờ.")
    except Exception as e:
        logger.error(f"Lỗi khi dọn dẹp tài khoản tạm: {e}")

def start_scheduler():
    """Khởi động lịch trình chạy nền"""
    global _scheduler
    if _scheduler is None:
        _scheduler = AsyncIOScheduler()
        # Chạy kiểm tra mỗi 60 giây cho SOS quá hạn (RB07)
        _scheduler.add_job(check_expired_sos, "interval", seconds=60, id="check_expired_sos")
        # Chạy kiểm tra dọn dẹp tài khoản tạm mỗi 1 giờ
        _scheduler.add_job(cleanup_unverified_users, "interval", hours=1, id="cleanup_unverified_users")
        _scheduler.start()
        logger.info("Bộ lập lịch tác vụ định kỳ (Scheduler) đã khởi động!")

def stop_scheduler():
    """Dừng lịch trình chạy nền"""
    global _scheduler
    if _scheduler is not None:
        _scheduler.shutdown(wait=False)
        _scheduler = None
        logger.info("Bộ lập lịch tác vụ định kỳ (Scheduler) đã dừng.")
