import logging
from fastapi import APIRouter, HTTPException, status, Depends
from pydantic import BaseModel, Field
from database.connection import get_db_connection
from services.auth_service import get_current_user

logger = logging.getLogger("backend.users")
router = APIRouter(prefix="/api/users", tags=["Người dùng"])

class UpdateProfileDto(BaseModel):
    full_name: str = Field(..., min_length=2, max_length=100, description="Họ và tên mới")

@router.get("/profile", summary="Lấy hồ sơ cá nhân")
def get_profile(current_user: dict = Depends(get_current_user)):
    return {
        "success": True,
        "user": current_user
    }

@router.put("/profile", summary="Cập nhật họ tên hiển thị")
def update_profile(dto: UpdateProfileDto, current_user: dict = Depends(get_current_user)):
    """
    Cập nhật họ tên của người dùng.
    Ràng buộc: Client không thể tự đổi role hoặc account_status.
    """
    new_name = dto.full_name.strip()
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                "UPDATE users SET full_name = %s WHERE id = %s RETURNING id, full_name, phone, role, account_status, is_valid",
                (new_name, current_user["id"])
            )
            updated_user = cursor.fetchone()

    updated_user["id"] = str(updated_user["id"])
    updated_user["is_valid"] = bool(updated_user.get("is_valid", False))
    return {
        "success": True,
        "message": "Cập nhật hồ sơ thành công.",
        "user": updated_user
    }
