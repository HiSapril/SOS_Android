import logging
from fastapi import APIRouter, HTTPException, status, Depends
from pydantic import BaseModel, Field
from database.connection import get_db_connection
from services.auth_service import get_current_user

logger = logging.getLogger("backend.users")
router = APIRouter(prefix="/api/users", tags=["Người dùng"])

class UpdateFullNameDto(BaseModel):
    full_name: str = Field(..., min_length=1, max_length=100, description="Họ và tên mới")

# Alias DTO để tương thích ngược
UpdateProfileDto = UpdateFullNameDto

@router.get("/profile", summary="Lấy hồ sơ cá nhân")
def get_profile(current_user: dict = Depends(get_current_user)):
    return {
        "success": True,
        "user": current_user
    }

def _do_update_full_name(new_name: str, current_user: dict):
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                "UPDATE users SET full_name = %s WHERE id = %s RETURNING id, full_name, phone, account_status, is_valid",
                (new_name, current_user["id"])
            )
            updated_user = cursor.fetchone()

    updated_user["id"] = str(updated_user["id"])
    updated_user["is_valid"] = bool(updated_user.get("is_valid", False))
    updated_user["role"] = current_user["role"]
    return {
        "success": True,
        "message": "Cập nhật họ tên thành công.",
        "user": updated_user
    }

@router.put("/full-name", summary="API cập nhật họ và tên người dùng")
@router.patch("/full-name", summary="API cập nhật họ và tên người dùng (PATCH)")
def update_full_name(dto: UpdateFullNameDto, current_user: dict = Depends(get_current_user)):
    """
    API chuyên biệt để cập nhật lại họ và tên của người dùng sau khi xác thực OTP.
    (Mặc định khi đăng ký tài khoản mới, full_name là số điện thoại).
    """
    return _do_update_full_name(dto.full_name.strip(), current_user)

@router.put("/profile", summary="Cập nhật hồ sơ hiển thị (Họ tên)")
def update_profile(dto: UpdateProfileDto, current_user: dict = Depends(get_current_user)):
    """
    Cập nhật họ tên của người dùng (tương thích endpoint /profile).
    """
    return _do_update_full_name(dto.full_name.strip(), current_user)
