import asyncio
import logging
from contextlib import asynccontextmanager
from typing import Optional
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Query, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

import config
from database.connection import init_db_pool, close_db_pool, get_db_connection
from services.auth_service import decode_access_token
from services.ws_manager import ws_manager
from services.telegram_bot import start_telegram_bot, stop_telegram_bot
from services.scheduler import start_scheduler, stop_scheduler

from routes.auth import router as auth_router
from routes.users import router as users_router
from routes.sos import router as sos_router
from routes.rescuer import router as rescuer_router
from routes.location import router as location_router
from routes.emergency_types import router as emergency_types_router

# Thiết lập ghi log
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("backend.main")

@asynccontextmanager
async def lifespan(app: FastAPI):
    """Quản lý vòng đời khởi động và dừng của máy chủ backend"""
    logger.info("=" * 60)
    logger.info("🚀 KHỞI ĐỘNG HỆ THỐNG CỨU HỘ KHẨN CẤP SOS (BACKEND SERVER)")
    logger.info(f"📍 Database: {config.PGHOST}:{config.PGPORT}/{config.PGDATABASE}")
    logger.info("=" * 60)

    # 1. Khởi tạo Connection Pool tới PostgreSQL
    init_db_pool()

    # 2. Khởi động bộ lập lịch tác vụ nền (kiểm tra timeout 2h RB07)
    start_scheduler()

    # 3. Khởi động Telegram Bot (nếu có cấu hình TELEGRAM_BOT_TOKEN)
    asyncio.create_task(start_telegram_bot())

    yield

    logger.info("Đang tắt các dịch vụ backend...")
    stop_scheduler()
    await stop_telegram_bot()
    close_db_pool()
    logger.info("Backend đã dừng an toàn.")

app = FastAPI(
    title="Hệ Thống Hỗ Trợ Cứu Hộ Khẩn Cấp (SOS Rescue Backend)",
    description=(
        "Máy chủ backend phục vụ ứng dụng Android Java, bản đồ cứu hộ, định vị realtime, "
        "xác thực OTP Telegram và cơ sở dữ liệu PostgreSQL theo đồ án Nhom1_Chuong1."
    ),
    version="1.0.0",
    lifespan=lifespan
)

# Cấu hình CORS để Android Emulator (10.0.2.2), thiết bị thật và trình duyệt có thể gọi API
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Đăng ký các router nghiệp vụ
app.include_router(auth_router)
app.include_router(users_router)
app.include_router(sos_router)
app.include_router(rescuer_router)
app.include_router(location_router)
app.include_router(emergency_types_router)

@app.get("/", summary="Trang thông tin tổng quan hệ thống")
def root():
    return {
        "system": "Hệ Thống Cứu Hộ Khẩn Cấp SOS Backend",
        "version": "1.0.0",
        "status": "online",
        "docs": "/docs",
        "developer": "Phạm Minh Đức (Database Developer + Backend Developer)"
    }

@app.get("/health", summary="Kiểm tra trạng thái máy chủ và kết nối database")
def health_check():
    try:
        with get_db_connection() as conn:
            with conn.cursor() as cursor:
                cursor.execute("SELECT 1 AS ok")
                row = cursor.fetchone()
        db_ok = (row is not None and row.get("ok") == 1)
    except Exception as e:
        logger.error(f"Kiểm tra sức khỏe database thất bại: {e}")
        return JSONResponse(
            status_code=503,
            content={"status": "degraded", "database": "disconnected", "error": str(e)}
        )

    return {
        "status": "healthy",
        "database": "connected" if db_ok else "unhealthy"
    }

@app.websocket("/ws")
async def websocket_endpoint(
    websocket: WebSocket,
    token: Optional[str] = Query(None)
):
    """
    Kênh WebSocket kết nối thời gian thực:
    - Nhận token qua query param (?token=xxx) hoặc qua bản tin {"type": "AUTH", "token": "xxx"}.
    - RESCUER: tự động đăng ký kênh hàng đợi SOS mới ('rescuers').
    - Client gửi SUBSCRIBE_SOS để đăng ký phòng phiên cứu hộ (sos_{id}).
    - Client có thể gửi LOCATION_UPDATE định kỳ qua WebSocket.
    """
    user_info = None

    if token:
        try:
            payload = decode_access_token(token)
            user_id = payload.get("sub")
            role = payload.get("role")
            if user_id and role:
                user_info = {"user_id": user_id, "role": role}
        except Exception:
            await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
            return

    if not user_info:
        await websocket.accept()
        try:
            # Chờ thông điệp AUTH đầu tiên
            init_msg = await websocket.receive_json()
            if init_msg.get("type") == "AUTH" and init_msg.get("token"):
                payload = decode_access_token(init_msg["token"])
                user_id = payload.get("sub")
                role = payload.get("role")
                if user_id and role:
                    user_info = {"user_id": user_id, "role": role}
                    await ws_manager.connect(websocket, user_id, role)
                    await websocket.send_json({"type": "AUTH_SUCCESS", "user_id": user_id, "role": role})
                else:
                    await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
                    return
            else:
                await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
                return
        except Exception as e:
            logger.warning(f"Lỗi xác thực ban đầu qua WebSocket: {e}")
            await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
            return
    else:
        await ws_manager.connect(websocket, user_info["user_id"], user_info["role"])
        await websocket.send_json({"type": "AUTH_SUCCESS", "user_id": user_info["user_id"], "role": user_info["role"]})

    user_id = user_info["user_id"]
    role = user_info["role"]

    try:
        while True:
            data = await websocket.receive_json()
            msg_type = data.get("type")

            if msg_type == "PING":
                await websocket.send_json({"type": "PONG"})

            elif msg_type == "SUBSCRIBE_SOS":
                sos_id = data.get("sos_id")
                if not sos_id:
                    continue

                # Kiểm tra quyền tham gia phòng phiên cứu hộ (UC05 / UC11):
                # Chỉ chủ SOS và RESCUER được gán mới có quyền tham gia phòng này
                with get_db_connection() as conn:
                    with conn.cursor() as cursor:
                        cursor.execute(
                            "SELECT requester_id, team_id FROM sos_requests WHERE id = %s",
                            (sos_id,)
                        )
                        sos = cursor.fetchone()
                        is_rescuer = False
                        if sos and sos["team_id"]:
                            cursor.execute(
                                "SELECT 1 FROM rescue_team_members WHERE team_id = %s AND user_id = %s",
                                (sos["team_id"], user_id)
                            )
                            is_rescuer = (cursor.fetchone() is not None)

                if sos:
                    is_owner = (str(sos["requester_id"]) == user_id)
                    if is_owner or is_rescuer:
                        room_name = f"sos_{sos_id}"
                        ws_manager.join_room(websocket, room_name)
                        await websocket.send_json({
                            "type": "SUBSCRIBED",
                            "sos_id": sos_id,
                            "room": room_name,
                            "role_in_session": "VICTIM" if is_owner else "RESCUER"
                        })
                    else:
                        await websocket.send_json({
                            "type": "ERROR",
                            "message": "Từ chối tham gia phòng: Bạn không thuộc phiên cứu hộ này."
                        })

            elif msg_type == "UNSUBSCRIBE_SOS":
                sos_id = data.get("sos_id")
                if sos_id:
                    ws_manager.leave_room(websocket, f"sos_{sos_id}")
                    await websocket.send_json({"type": "UNSUBSCRIBED", "sos_id": sos_id})

            elif msg_type == "LOCATION_UPDATE":
                # Nhận cập nhật tọa độ nhanh qua WebSocket (UC04 / UC10)
                sos_id = data.get("sos_id")
                lat = data.get("lat")
                lng = data.get("lng")
                accuracy_m = data.get("accuracy_m")
                save_history = bool(data.get("save_history", False))

                if sos_id and lat is not None and lng is not None:
                    # Xác định actor
                    with get_db_connection() as conn:
                        with conn.cursor() as cursor:
                            cursor.execute(
                                "SELECT requester_id, team_id, status FROM sos_requests WHERE id = %s",
                                (sos_id,)
                            )
                            sos = cursor.fetchone()
                            if sos and sos["status"] in ('WAITING', 'ACCEPTED', 'RESCUING', 'ARRIVED'):
                                is_victim = (str(sos["requester_id"]) == user_id)
                                is_rescuer = False
                                if sos["team_id"]:
                                    cursor.execute(
                                        "SELECT 1 FROM rescue_team_members WHERE team_id = %s AND user_id = %s",
                                        (sos["team_id"], user_id)
                                    )
                                    is_rescuer = (cursor.fetchone() is not None)

                                if is_victim or is_rescuer:
                                    actor = "VICTIM" if is_victim else "RESCUER"
                                    cursor.execute(
                                        """
                                        INSERT INTO location_current (sos_id, actor, lat, lng, accuracy_m, updated_at)
                                        VALUES (%s, %s, %s, %s, %s, now())
                                        ON CONFLICT (sos_id, actor) DO UPDATE
                                        SET lat = EXCLUDED.lat, lng = EXCLUDED.lng, accuracy_m = EXCLUDED.accuracy_m, updated_at = now()
                                        """,
                                        (sos_id, actor, lat, lng, accuracy_m)
                                    )
                                    if save_history:
                                        cursor.execute(
                                            "INSERT INTO location_history (sos_id, actor, lat, lng, created_at) VALUES (%s, %s, %s, %s, now())",
                                            (sos_id, actor, lat, lng)
                                        )

                                    # Phát tới các bên trong phòng phiên
                                    await ws_manager.broadcast_to_room(
                                        f"sos_{sos_id}",
                                        {
                                            "type": "LOCATION_BROADCAST",
                                            "sos_id": sos_id,
                                            "actor": actor,
                                            "lat": lat,
                                            "lng": lng,
                                            "accuracy_m": accuracy_m
                                        }
                                    )

    except WebSocketDisconnect:
        ws_manager.disconnect(websocket)
    except Exception as e:
        logger.warning(f"Ngoại lệ trong vòng lặp WebSocket: {e}")
        ws_manager.disconnect(websocket)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8000,
        reload=True,
        reload_dirs=["routes", "services", "database"],
        timeout_keep_alive=65
    )
