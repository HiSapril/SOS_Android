import logging
from datetime import datetime, timezone
from typing import Optional
from fastapi import APIRouter, HTTPException, status, Depends
from pydantic import BaseModel, Field
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

class VerifyOtpDto(BaseModel):
    phone: str = Field(..., min_length=9, max_length=15)
    otp: str = Field(..., min_length=6, max_length=6, description="Mã OTP 6 chữ số")
    full_name: Optional[str] = Field(None, max_length=100, description="Họ tên khi đăng ký lần đầu")

class DevLinkTelegramDto(BaseModel):
    phone: str
    telegram_chat_id: int = 123456789

@router.post("/request-otp", summary="Yêu cầu gửi mã OTP xác minh qua Telegram")
async def request_otp(dto: RequestOtpDto):
    """
    UC01 - Bước 1 & 2:
    Kiểm tra số điện thoại, đối chiếu liên kết Telegram trong users và phát sinh mã OTP qua Telegram.
    Mã OTP được lưu trong bộ nhớ máy chủ (in-memory) có hạn 5 phút.
    """
    phone = normalize_phone(dto.phone)
    if len(phone) < 9 or len(phone) > 15:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Số điện thoại không hợp lệ."
        )

    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            # 1. Kiểm tra số điện thoại đã liên kết Telegram chưa (đọc trực tiếp từ users)
            cursor.execute(
                "SELECT id, telegram_chat_id, is_valid FROM users WHERE phone = %s",
                (phone,)
            )
            user = cursor.fetchone()
            if not user or not user["telegram_chat_id"]:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail=(
                        "Số điện thoại này chưa được liên kết với Telegram Bot của hệ thống. "
                        "Vui lòng mở Telegram, tìm bot và nhấn 'Chia sẻ số điện thoại' trước khi yêu cầu OTP."
                    )
                )
            
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
    sent_ok = await send_otp_telegram(phone, chat_id, otp_code)
    if not sent_ok:
        logger.warning(f"Không thể gửi tin nhắn Telegram tới chat_id={chat_id}")

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
    Mỗi lần đăng nhập đều xác thực bằng OTP Telegram.
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

    # 2. Cập nhật hoặc tạo tài khoản trong bảng users
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                "SELECT id, full_name, phone, role, account_status, is_valid FROM users WHERE phone = %s",
                (phone,)
            )
            user = cursor.fetchone()

            is_new_user = False
            if not user:
                # Nếu người dùng chưa có tài khoản: Tạo mới với is_valid = true (vì đã xác thực OTP)
                full_name = dto.full_name.strip() if dto.full_name else f"Người dùng {phone[-4:]}"
                cursor.execute(
                    """
                    INSERT INTO users (full_name, phone, role, account_status, is_valid)
                    VALUES (%s, %s, 'USER', 'ACTIVE', true)
                    RETURNING id, full_name, phone, role, account_status, is_valid
                    """,
                    (full_name, phone)
                )
                user = cursor.fetchone()
                is_new_user = True
            else:
                if user["account_status"] != "ACTIVE":
                    raise HTTPException(
                        status_code=status.HTTP_403_FORBIDDEN,
                        detail="Tài khoản này đã bị vô hiệu hóa hoặc tạm khóa."
                    )
                
                # Người dùng cũ: nếu trước đó is_valid = false (mới liên kết Telegram) thì chuyển thành true
                was_unverified = not user["is_valid"]
                new_name = dto.full_name.strip() if dto.full_name else user["full_name"]
                
                cursor.execute(
                    """
                    UPDATE users
                    SET is_valid = true,
                        full_name = %s
                    WHERE id = %s
                    RETURNING id, full_name, phone, role, account_status, is_valid
                    """,
                    (new_name, user["id"])
                )
                user = cursor.fetchone()
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
    Nếu người dùng mới: is_valid = false (người dùng mới thì False, sau khi xác nhận OTP mới thành True).
    """
    phone = normalize_phone(dto.phone)
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                """
                INSERT INTO users (full_name, phone, telegram_chat_id, role, account_status, is_valid)
                VALUES (%s, %s, %s, 'USER', 'ACTIVE', false)
                ON CONFLICT (phone) DO UPDATE 
                SET telegram_chat_id = EXCLUDED.telegram_chat_id
                """,
                (f"Người dùng {phone[-4:]}", phone, dto.telegram_chat_id)
            )
    return {
        "success": True,
        "message": f"Đã liên kết số {phone} với chat_id {dto.telegram_chat_id} trong bảng users (DEV)."
    }
