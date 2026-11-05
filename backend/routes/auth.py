import logging
from datetime import datetime, timezone
from typing import Optional
from fastapi import APIRouter, HTTPException, status, Depends
from pydantic import BaseModel, Field, field_validator
import config
from database.connection import get_db_connection
from services.auth_service import (
    normalize_phone,
    generate_otp,
    create_access_token,
    get_current_user
)
from services.otp_service import otp_store
from services.telegram_bot import send_otp_telegram

logger = logging.getLogger("backend.auth")
router = APIRouter(prefix="/api/auth", tags=["Xác thực & Tài khoản"])

class RequestOtpDto(BaseModel):
    phone: str = Field(..., min_length=9, max_length=15, description="Số điện thoại của người dùng")
    role: str = Field(..., description="Vai trò đăng nhập: USER hoặc RESCUER")

    @field_validator("role")
    @classmethod
    def validate_role(cls, v: str) -> str:
        role_upper = v.strip().upper()
        if role_upper not in ("USER", "RESCUER"):
            raise ValueError("Vai trò không hợp lệ. Chỉ chấp nhận 'USER' hoặc 'RESCUER'.")
        return role_upper

class VerifyOtpDto(BaseModel):
    phone: str = Field(..., min_length=9, max_length=15, description="Số điện thoại")
    otp: str = Field(..., min_length=6, max_length=6, description="Mã OTP 6 chữ số")

class DevLinkTelegramDto(BaseModel):
    phone: str
    telegram_chat_id: int = 123456789
    role: Optional[str] = "USER"

@router.post("/request-otp", summary="Yêu cầu gửi mã OTP xác minh qua Telegram")
async def request_otp(dto: RequestOtpDto):
    """
    UC01 - Bước 1 & 2:
    Kiểm tra số điện thoại, đối chiếu liên kết Telegram và kiểm tra vai trò:
    - Nếu role là RESCUER: kiểm tra user_id có nằm trong bảng rescue_team_members không.
    - Nếu role là USER: kiểm tra xem có trong bảng users không, chưa có thì tự động tạo mới vì là OTP.
    Mã OTP được lưu trong bộ nhớ máy chủ (in-memory) có hạn 5 phút.
    """
    phone = normalize_phone(dto.phone)
    if len(phone) < 9 or len(phone) > 15:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Số điện thoại không hợp lệ."
        )

    expected_role = dto.role.strip().upper()

    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            # 1. Tìm user theo phone (kèm cờ kiểm tra rescue_team_members)
            cursor.execute(
                """
                SELECT u.id, u.full_name, u.phone, u.telegram_chat_id, u.account_status, u.is_valid,
                       CASE WHEN rtm.user_id IS NOT NULL THEN 'RESCUER' ELSE 'USER' END AS actual_role
                FROM users u
                LEFT JOIN rescue_team_members rtm ON rtm.user_id = u.id
                WHERE u.phone = %s
                LIMIT 1
                """,
                (phone,)
            )
            user = cursor.fetchone()

            if expected_role == "RESCUER":
                # Phải tồn tại trong users và id phải nằm trong rescue_team_members
                if not user or user["actual_role"] != "RESCUER":
                    raise HTTPException(
                        status_code=status.HTTP_400_BAD_REQUEST,
                        detail="Số điện thoại này không thuộc đội cứu hộ nào (không có quyền RESCUER)."
                    )
            else:
                # expected_role == "USER":
                # Kiểm tra đơn giản nằm trong bảng users không, không có thì thêm vào vì là OTP
                if not user:
                    cursor.execute(
                        """
                        INSERT INTO users (full_name, phone, account_status, is_valid)
                        VALUES (%s, %s, 'ACTIVE', false)
                        RETURNING id, full_name, phone, telegram_chat_id, account_status, is_valid
                        """,
                        (phone, phone)
                    )
                    user = cursor.fetchone()
                    user["actual_role"] = "USER"
                elif user["actual_role"] == "RESCUER":
                    # Đã có trong rescue_team_members (là RESCUER), không cho đăng nhập với vai trò USER
                    raise HTTPException(
                        status_code=status.HTTP_400_BAD_REQUEST,
                        detail="Số điện thoại này thuộc về Đội cứu hộ (RESCUER). Vui lòng đăng nhập với vai trò RESCUER."
                    )

            if user["account_status"] != "ACTIVE":
                raise HTTPException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    detail="Tài khoản này đã bị vô hiệu hóa hoặc tạm khóa."
                )

            # Kiểm tra telegram_chat_id
            if not user.get("telegram_chat_id"):
                if not config.DEV_MOCK_OTP and config.TELEGRAM_BOT_TOKEN:
                    raise HTTPException(
                        status_code=status.HTTP_400_BAD_REQUEST,
                        detail=(
                            "Số điện thoại này chưa được liên kết với Telegram Bot của hệ thống. "
                            "Vui lòng mở Telegram, tìm bot và nhấn 'Chia sẻ số điện thoại' trước khi yêu cầu OTP."
                        )
                    )
                chat_id = user.get("telegram_chat_id")
            else:
                chat_id = user["telegram_chat_id"]

    # 2. Kiểm tra giới hạn tần suất gửi lại (ít nhất 30 giây giữa 2 lần từ in-memory store)
    last_created = otp_store.get_last_created_time(phone)
    if last_created:
        now_utc = datetime.now(timezone.utc)
        elapsed_sec = (now_utc - last_created).total_seconds()
        if elapsed_sec < 30:
            wait_time = int(30 - elapsed_sec)
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail=f"Vui lòng đợi {wait_time} giây trước khi yêu cầu mã OTP mới."
            )

    # 3. Sinh mã OTP mới và lưu vào bộ nhớ RAM
    otp_code = generate_otp()
    otp_store.set_otp(phone, otp_code)

    # 4. Gửi mã OTP qua Telegram
    if chat_id:
        sent_ok = await send_otp_telegram(phone, chat_id, otp_code)
        if not sent_ok:
            logger.warning(f"Không thể gửi tin nhắn Telegram tới chat_id={chat_id}")
    else:
        logger.info(f"[DEV MOCK] Bỏ qua gửi Telegram vì chưa có chat_id. OTP cho {phone} là: {otp_code}")

    response = {
        "success": True,
        "message": f"Mã OTP gồm 6 chữ số đã được gửi qua Telegram của bạn (hiệu lực {config.OTP_EXPIRE_MINUTES} phút).",
        "phone": phone,
        "expires_in_minutes": config.OTP_EXPIRE_MINUTES
    }

    # Nếu đang ở chế độ DEV/MOCK, trả kèm mã OTP để tiện kiểm thử tự động và debug
    if config.DEV_MOCK_OTP:
        response["dev_otp"] = otp_code

    return response

@router.post("/verify-otp", summary="Xác minh mã OTP và đăng nhập/đăng ký tài khoản")
def verify_otp(dto: VerifyOtpDto):
    """
    UC01 - Bước 3 & 4:
    Xác thực mã OTP từ Telegram, cập nhật is_valid = true cho tài khoản, và cấp JWT token.
    Input chỉ bao gồm phone và otp. Họ tên mặc định ban đầu là số điện thoại.
    Role được xác định động qua bảng rescue_team_members.
    """
    phone = normalize_phone(dto.phone)
    raw_otp = dto.otp.strip()

    # 1. Xác thực mã OTP qua in-memory store
    is_ok, err_msg = otp_store.verify_otp(phone, raw_otp)
    if not is_ok:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=err_msg
        )

    # 2. Cập nhật hoặc tạo tài khoản trong bảng users và xác định role từ rescue_team_members
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                """
                SELECT u.id, u.full_name, u.phone, u.account_status, u.is_valid,
                       CASE WHEN rtm.user_id IS NOT NULL THEN 'RESCUER' ELSE 'USER' END AS role
                FROM users u
                LEFT JOIN rescue_team_members rtm ON rtm.user_id = u.id
                WHERE u.phone = %s
                LIMIT 1
                """,
                (phone,)
            )
            user = cursor.fetchone()

            is_new_user = False
            if not user:
                # Nếu người dùng chưa có tài khoản: Tạo mới với full_name = phone, is_valid = true
                cursor.execute(
                    """
                    INSERT INTO users (full_name, phone, account_status, is_valid)
                    VALUES (%s, %s, 'ACTIVE', true)
                    RETURNING id, full_name, phone, account_status, is_valid
                    """,
                    (phone, phone)
                )
                user = cursor.fetchone()
                user["role"] = "USER"
                is_new_user = True
            else:
                if user["account_status"] != "ACTIVE":
                    raise HTTPException(
                        status_code=status.HTTP_403_FORBIDDEN,
                        detail="Tài khoản này đã bị vô hiệu hóa hoặc tạm khóa."
                    )
                
                was_unverified = not user["is_valid"]
                cursor.execute(
                    """
                    UPDATE users
                    SET is_valid = true
                    WHERE id = %s
                    RETURNING id, full_name, phone, account_status, is_valid
                    """,
                    (user["id"],)
                )
                updated = cursor.fetchone()
                role = user["role"]
                user = updated
                user["role"] = role
                is_new_user = was_unverified

    user_id_str = str(user["id"])
    access_token = create_access_token(user_id_str, user["phone"], user["role"])

    return {
        "success": True,
        "is_new_user": is_new_user,
        "access_token": access_token,
        "token_type": "bearer",
        "user": {
            "id": user_id_str,
            "full_name": user["full_name"],
            "phone": user["phone"],
            "role": user["role"],
            "account_status": user["account_status"],
            "is_valid": user["is_valid"]
        }
    }

@router.get("/me", summary="Lấy thông tin tài khoản hiện tại từ token")
def get_me(current_user: dict = Depends(get_current_user)):
    return {
        "success": True,
        "user": current_user
    }

@router.post("/dev-link-telegram", summary="[DEV HELPER] Liên kết nhanh số điện thoại với Telegram Chat")
def dev_link_telegram(dto: DevLinkTelegramDto):
    """
    Tiện ích hỗ trợ kiểm thử:
    Ghi nhận telegram_chat_id trực tiếp vào bảng users.
    Nếu role là RESCUER, gán vào đội cứu hộ 1 để dynamic role thành RESCUER.
    """
    phone = normalize_phone(dto.phone)
    role = (dto.role or "USER").strip().upper()
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                """
                INSERT INTO users (full_name, phone, telegram_chat_id, account_status, is_valid)
                VALUES (%s, %s, %s, 'ACTIVE', false)
                ON CONFLICT (phone) DO UPDATE 
                SET telegram_chat_id = EXCLUDED.telegram_chat_id
                RETURNING id
                """,
                (phone, phone, dto.telegram_chat_id)
            )
            u_id = cursor.fetchone()["id"]
            if role == "RESCUER":
                # Đảm bảo người dùng có mặt trong rescue_team_members nếu chưa thuộc đội nào
                cursor.execute("SELECT 1 FROM rescue_team_members WHERE user_id = %s", (u_id,))
                if not cursor.fetchone():
                    cursor.execute(
                        """
                        INSERT INTO rescue_team_members (team_id, user_id, role)
                        VALUES ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', %s, 'LEADER')
                        ON CONFLICT (team_id, user_id) DO NOTHING
                        """,
                        (u_id,)
                    )
            elif role == "USER":
                # Xóa khỏi rescue_team_members nếu có để đảm bảo dynamic role là USER
                cursor.execute(
                    "DELETE FROM rescue_team_members WHERE user_id = %s",
                    (u_id,)
                )
    return {
        "success": True,
        "message": f"Đã liên kết số {phone} với chat_id {dto.telegram_chat_id} (role={role}) trong hệ thống (DEV)."
    }
