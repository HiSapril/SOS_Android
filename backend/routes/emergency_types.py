import logging
import uuid
from typing import Optional
from fastapi import APIRouter, HTTPException, status, Depends
from pydantic import BaseModel, Field, field_validator
from database.connection import get_db_connection
from services.auth_service import get_current_user

logger = logging.getLogger("backend.emergency_types")
router = APIRouter(prefix="/api/emergency-types", tags=["Quản lý Loại sự cố (Emergency Types)"])

class CreateEmergencyTypeDto(BaseModel):
    name: str = Field(..., min_length=2, max_length=50, description="Tên định danh loại sự cố (VD: NGAP_LUT, TAI_NAN)")
    description: Optional[str] = Field(None, max_length=500, description="Mô tả tình huống sự cố")

    @field_validator("name")
    @classmethod
    def clean_name(cls, v: str) -> str:
        cleaned = v.strip().upper()
        if not cleaned:
            raise ValueError("Tên loại sự cố không được để trống.")
        return cleaned

class UpdateEmergencyTypeDto(BaseModel):
    name: Optional[str] = Field(None, min_length=2, max_length=50, description="Tên định danh mới")
    description: Optional[str] = Field(None, max_length=500, description="Mô tả tình huống mới")

    @field_validator("name")
    @classmethod
    def clean_name(cls, v: Optional[str]) -> Optional[str]:
        if v is not None:
            cleaned = v.strip().upper()
            if not cleaned:
                raise ValueError("Tên loại sự cố không được để trống.")
            return cleaned
        return v

@router.get("", summary="Lấy danh sách tất cả các loại sự cố khẩn cấp (Hỗ trợ tìm kiếm)")
def list_emergency_types(search: Optional[str] = None):
    """
    Trả về danh mục các loại sự cố phục vụ cho ứng dụng người dùng và đội cứu hộ.
    Hỗ trợ lọc theo từ khóa tìm kiếm trong name hoặc description.
    """
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            if search and search.strip():
                keyword = f"%{search.strip().upper()}%"
                cursor.execute(
                    """
                    SELECT id, name, description, created_at
                    FROM emergency_types
                    WHERE UPPER(name) LIKE %s OR UPPER(description) LIKE %s
                    ORDER BY name ASC
                    """,
                    (keyword, keyword)
                )
            else:
                cursor.execute(
                    """
                    SELECT id, name, description, created_at
                    FROM emergency_types
                    ORDER BY name ASC
                    """
                )
            rows = cursor.fetchall()

    result = []
    for r in rows:
        result.append({
            "id": str(r["id"]),
            "name": r["name"],
            "description": r["description"],
            "created_at": r["created_at"].isoformat() if r["created_at"] else None
        })

    return {
        "success": True,
        "count": len(result),
        "data": result
    }

@router.get("/{type_id}", summary="Xem chi tiết một loại sự cố theo ID")
def get_emergency_type(type_id: str):
    """
    Lấy thông tin chi tiết của một loại sự cố theo UUID.
    """
    try:
        uuid_obj = uuid.UUID(type_id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Định dạng ID loại sự cố không hợp lệ (phải là UUID)."
        )

    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                "SELECT id, name, description, created_at FROM emergency_types WHERE id = %s",
                (str(uuid_obj),)
            )
            item = cursor.fetchone()

    if not item:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Không tìm thấy loại sự cố với ID '{type_id}'."
        )

    return {
        "success": True,
        "data": {
            "id": str(item["id"]),
            "name": item["name"],
            "description": item["description"],
            "created_at": item["created_at"].isoformat() if item["created_at"] else None
        }
    }

@router.post("", status_code=status.HTTP_201_CREATED, summary="Tạo mới một loại sự cố khẩn cấp")
def create_emergency_type(dto: CreateEmergencyTypeDto, current_user: dict = Depends(get_current_user)):
    """
    Thêm mới một loại sự cố vào danh mục hệ thống.
    Yêu cầu đăng nhập tài khoản.
    """
    name = dto.name.strip().upper()
    description = dto.description.strip() if dto.description else None

    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            # Kiểm tra trùng tên
            cursor.execute("SELECT id FROM emergency_types WHERE name = %s", (name,))
            if cursor.fetchone():
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail=f"Loại sự cố với tên '{name}' đã tồn tại trong hệ thống."
                )

            cursor.execute(
                """
                INSERT INTO emergency_types (name, description)
                VALUES (%s, %s)
                RETURNING id, name, description, created_at
                """,
                (name, description)
            )
            new_item = cursor.fetchone()

    return {
        "success": True,
        "message": f"Tạo mới loại sự cố '{name}' thành công.",
        "data": {
            "id": str(new_item["id"]),
            "name": new_item["name"],
            "description": new_item["description"],
            "created_at": new_item["created_at"].isoformat() if new_item["created_at"] else None
        }
    }

@router.put("/{type_id}", summary="Cập nhật thông tin loại sự cố")
def update_emergency_type(type_id: str, dto: UpdateEmergencyTypeDto, current_user: dict = Depends(get_current_user)):
    """
    Chỉnh sửa tên hoặc mô tả của một loại sự cố.
    """
    try:
        uuid_obj = uuid.UUID(type_id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Định dạng ID loại sự cố không hợp lệ (phải là UUID)."
        )

    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            # Kiểm tra tồn tại
            cursor.execute("SELECT id, name, description FROM emergency_types WHERE id = %s", (str(uuid_obj),))
            existing = cursor.fetchone()
            if not existing:
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND,
                    detail=f"Không tìm thấy loại sự cố với ID '{type_id}'."
                )

            new_name = dto.name if dto.name is not None else existing["name"]
            new_desc = dto.description if dto.description is not None else existing["description"]

            # Kiểm tra trùng lặp tên với bản ghi khác
            if dto.name is not None and dto.name != existing["name"]:
                cursor.execute(
                    "SELECT id FROM emergency_types WHERE name = %s AND id != %s",
                    (new_name, str(uuid_obj))
                )
                if cursor.fetchone():
                    raise HTTPException(
                        status_code=status.HTTP_409_CONFLICT,
                        detail=f"Tên loại sự cố '{new_name}' đã được sử dụng bởi bản ghi khác."
                    )

            cursor.execute(
                """
                UPDATE emergency_types
                SET name = %s, description = %s
                WHERE id = %s
                RETURNING id, name, description, created_at
                """,
                (new_name, new_desc, str(uuid_obj))
            )
            updated = cursor.fetchone()

    return {
        "success": True,
        "message": "Cập nhật loại sự cố thành công.",
        "data": {
            "id": str(updated["id"]),
            "name": updated["name"],
            "description": updated["description"],
            "created_at": updated["created_at"].isoformat() if updated["created_at"] else None
        }
    }

@router.delete("/{type_id}", summary="Xóa một loại sự cố khỏi hệ thống")
def delete_emergency_type(type_id: str, current_user: dict = Depends(get_current_user)):
    """
    Xóa một loại sự cố.
    Ràng buộc an toàn: Nếu đang có yêu cầu SOS nào liên kết với loại sự cố này, từ chối xóa.
    """
    try:
        uuid_obj = uuid.UUID(type_id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Định dạng ID loại sự cố không hợp lệ (phải là UUID)."
        )

    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute("SELECT id, name FROM emergency_types WHERE id = %s", (str(uuid_obj),))
            existing = cursor.fetchone()
            if not existing:
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND,
                    detail=f"Không tìm thấy loại sự cố với ID '{type_id}'."
                )

            # Kiểm tra xem có sos_requests nào đang dùng không
            cursor.execute(
                "SELECT 1 FROM sos_requests WHERE emergency_type_id = %s LIMIT 1",
                (str(uuid_obj),)
            )
            if cursor.fetchone():
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail=f"Không thể xóa loại sự cố '{existing['name']}' vì đang có các yêu cầu cứu hộ SOS sử dụng nó."
                )

            cursor.execute("DELETE FROM emergency_types WHERE id = %s", (str(uuid_obj),))

    return {
        "success": True,
        "message": f"Đã xóa loại sự cố '{existing['name']}' thành công."
    }
