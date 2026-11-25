import logging
from typing import Dict, Set
from fastapi import WebSocket

logger = logging.getLogger("backend.websocket")

class WebSocketManager:
    """
    Quản lý các kết nối WebSocket thời gian thực:
    - Kênh RESCUER: nhận thông báo khi có SOS mới trong hàng đợi (rescue_queue).
    - Kênh phiên SOS (sos_{sos_id}): đồng bộ trạng thái và vị trí giữa nạn nhân và đội cứu hộ.
    """
    def __init__(self):
        # Lưu thông tin metadata của socket: {websocket: {"user_id": str, "role": str, "rooms": set[str]}}
        self.active_sockets: Dict[WebSocket, dict] = {}
        # Lưu danh sách socket theo room: {"room_name": set[WebSocket]}
        self.rooms: Dict[str, Set[WebSocket]] = {}

    async def connect(self, websocket: WebSocket, user_id: str, role: str):
        """Chấp nhận kết nối và ghi nhận metadata"""
        await websocket.accept()
        self.active_sockets[websocket] = {
            "user_id": user_id,
            "role": role,
            "rooms": set()
        }
        
        # Nếu là RESCUER, tự động đăng ký vào kênh nhận SOS mới
        if role == "RESCUER":
            self.join_room(websocket, "rescuers")
            
        logger.info(f"WebSocket client kết nối: user_id={user_id}, role={role}")

    def disconnect(self, websocket: WebSocket):
        """Xử lý ngắt kết nối và dọn dẹp các phòng"""
        if websocket in self.active_sockets:
            user_info = self.active_sockets[websocket]
            user_id = user_info.get("user_id")
            for room in list(user_info.get("rooms", [])):
                self.leave_room(websocket, room)
            del self.active_sockets[websocket]
            logger.info(f"WebSocket client đã ngắt kết nối: user_id={user_id}")

    def join_room(self, websocket: WebSocket, room_name: str):
        """Thêm socket vào phòng"""
        if websocket not in self.active_sockets:
            return
        if room_name not in self.rooms:
            self.rooms[room_name] = set()
        self.rooms[room_name].add(websocket)
        self.active_sockets[websocket]["rooms"].add(room_name)

    def leave_room(self, websocket: WebSocket, room_name: str):
        """Rời khỏi phòng"""
        if room_name in self.rooms and websocket in self.rooms[room_name]:
            self.rooms[room_name].remove(websocket)
            if not self.rooms[room_name]:
                del self.rooms[room_name]
        if websocket in self.active_sockets:
            self.active_sockets[websocket]["rooms"].discard(room_name)

    async def broadcast_to_room(self, room_name: str, message: dict):
        """Gửi thông điệp tới tất cả các client trong một phòng"""
        if room_name not in self.rooms:
            return
        sockets = list(self.rooms[room_name])
        for ws in sockets:
            try:
                await ws.send_json(message)
            except Exception as e:
                logger.warning(f"Lỗi gửi tin tới client trong room {room_name}: {e}")
                self.disconnect(ws)

    async def broadcast_to_rescuers(self, message: dict):
        """Gửi sự kiện tới tất cả các đội cứu hộ đang trực tuyến (kênh 'rescuers')"""
        await self.broadcast_to_room("rescuers", message)

    async def send_to_user(self, user_id: str, message: dict):
        """Gửi trực tiếp tới một người dùng cụ thể"""
        for ws, info in list(self.active_sockets.items()):
            if info.get("user_id") == str(user_id):
                try:
                    await ws.send_json(message)
                except Exception as e:
                    logger.warning(f"Lỗi gửi tin tới user_id {user_id}: {e}")
                    self.disconnect(ws)

ws_manager = WebSocketManager()
