import hashlib
import secrets
import re
from datetime import datetime, timezone, timedelta
from typing import Annotated
import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
import config
from database.connection import get_db_connection

security = HTTPBearer()

def normalize_phone(phone: str) -> str:
    """
    Chuẩn hóa số điện thoại:
    Loại bỏ dấu cách, gạch nối, ngoặc, và chuyển đầu +84/84 thành 0.
    """
    cleaned = re.sub(r"[^\d+]", "", phone.strip())
    if cleaned.startswith("+84"):
        cleaned = "0" + cleaned[3:]
    elif cleaned.startswith("84") and len(cleaned) >= 10:
        cleaned = "0" + cleaned[2:]
    return cleaned

def generate_otp() -> str:
    """Sinh mã OTP ngẫu nhiên gồm 6 chữ số"""
    return f"{secrets.randbelow(1_000_000):06d}"

def hash_otp(otp: str) -> str:
    """Băm mã OTP bằng SHA-256 theo chuẩn RB10"""
    return hashlib.sha256(otp.encode("utf-8")).hexdigest()

def verify_otp_hash(otp: str, hashed: str) -> bool:
    """Kiểm tra mã OTP khớp với chuỗi băm an toàn theo thời gian hằng số"""
    target = hash_otp(otp)
    return secrets.compare_digest(target, hashed)

def create_access_token(user_id: str, phone: str, role: str) -> str:
    """Tạo JWT access token với thời hạn cấu hình"""
    now = datetime.now(timezone.utc)
    expires_at = now + timedelta(minutes=config.JWT_EXPIRE_MINUTES)
    payload = {
        "sub": str(user_id),
        "phone": phone,
        "role": role,
        "iat": int(now.timestamp()),
        "exp": int(expires_at.timestamp())
    }
    return jwt.encode(payload, config.JWT_SECRET, algorithm=config.JWT_ALGORITHM)

def decode_access_token(token: str) -> dict:
    """Giải mã và xác minh tính toàn vẹn của JWT token"""
    try:
        return jwt.decode(token, config.JWT_SECRET, algorithms=[config.JWT_ALGORITHM])
    except jwt.ExpiredSignatureError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại."
        )
    except jwt.InvalidTokenError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token xác thực không hợp lệ."
        )

def get_current_user(credentials: Annotated[HTTPAuthorizationCredentials, Depends(security)]) -> dict:
    """
    Dependency lấy thông tin tài khoản hiện tại từ Bearer JWT Token.
    Đồng thời truy vấn DB để đảm bảo tài khoản tồn tại và account_status = 'ACTIVE'.
    """
    token = credentials.credentials
    payload = decode_access_token(token)
    user_id = payload.get("sub")
    if not user_id:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token không chứa thông tin định danh người dùng."
        )

    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                "SELECT id, full_name, phone, role, account_status, is_valid, created_at FROM users WHERE id = %s",
                (user_id,)
            )
            user = cursor.fetchone()

    if not user:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Tài khoản không tồn tại trên hệ thống."
        )

    if user["account_status"] != "ACTIVE":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Tài khoản đang bị khóa hoặc chưa kích hoạt."
        )

    user["id"] = str(user["id"])
    user["is_valid"] = bool(user.get("is_valid", False))
    return user

def require_rescuer(current_user: Annotated[dict, Depends(get_current_user)]) -> dict:
    """Dependency đảm bảo người dùng có vai trò RESCUER"""
    if current_user.get("role") != "RESCUER":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Quyền bị từ chối: Chức năng này chỉ dành cho đội cứu hộ (RESCUER)."
        )
    return current_user

def require_user(current_user: Annotated[dict, Depends(get_current_user)]) -> dict:
    """Dependency đảm bảo người dùng có vai trò USER"""
    if current_user.get("role") != "USER":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Quyền bị từ chối: Chức năng này chỉ dành cho người dùng (USER)."
        )
    return current_user
