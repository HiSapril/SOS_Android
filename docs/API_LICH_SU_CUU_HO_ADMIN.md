# TÀI LIỆU ĐẶC TẢ API BỔ SUNG: LỊCH SỬ CỨU HỘ CHO QUẢN TRỊ VIÊN (ADMIN)

> **Mục đích tài liệu:** Tài liệu này được tạo tự động nhằm đặc tả các API còn thiếu trên Backend để phục vụ trọn vẹn màn hình **"Lịch sử cứu hộ"** của ứng dụng Web Quản trị (**SOS Rescue Admin Console**).
> 
> *Lưu ý:* Hiện tại, phía Frontend Admin đã được xây dựng sẵn giao diện đầy đủ với cơ chế **Smart Adapter (Tương thích kép)**:
> 1. Frontend sẽ ưu tiên gọi API chuyên dụng `/api/admin/sos/history`.
> 2. Nếu Backend chưa triển khai (trả về 404), Frontend tự động kích hoạt cơ chế fallback lấy dữ liệu từ `/api/admin/sos` và tự lọc/phân trang trên giao diện mà không gây gián đoạn trải nghiệm người dùng.
> 3. Khi lập trình viên Backend triển khai xong các API dưới đây, hệ thống sẽ tự động chuyển sang Server-Side Filtering & Pagination để đạt hiệu năng tối ưu nhất.

---

## 1. Bối Cảnh & Vấn Đề Kỹ Thuật

Hiện tại, hệ thống Backend (`/backend/routes/admin.py`) mới chỉ cung cấp API bản đồ cứu hộ:
- `GET /api/admin/sos?q=...&status=...`:
  - Mặc định chỉ lấy các trạng thái đang diễn ra: `s.status IN ('WAITING', 'ACCEPTED', 'RESCUING', 'ARRIVED')`.
  - Nếu truyền `status=COMPLETED` hoặc `status=CANCELLED` thì chỉ lấy được 1 trạng thái riêng lẻ, không hỗ trợ xem "Toàn bộ lịch sử" (gồm cả hoàn thành và đã hủy).
  - **Chưa có phân trang** (`page`, `page_size`, `total`), khi số lượng ca cứu hộ lên đến hàng nghìn sẽ gây nghẽn băng thông và chậm giao diện.
  - **Chưa có bộ lọc nâng cao:** Chưa hỗ trợ lọc theo khoảng ngày (`from_date`, `to_date`), theo đội cứu hộ (`team_id`), theo loại sự cố (`emergency_type_id`).
  - **Nhật ký ca cứu hộ (`sos_logs`):** API `/api/sos/{id}/logs` chỉ cấp quyền cho tài khoản nạn nhân (`USER`) hoặc cứu hộ viên trong đội (`RESCUER`), tài khoản `ADMIN` gọi vào sẽ bị từ chối `403 Forbidden`.

---

## 2. Đặc Tả Chi Tiết Các API Cần Triển Khai Thêm

### 2.1. API Tra cứu danh sách Lịch sử cứu hộ (Có Phân Trang & Bộ Lọc Đa Tiêu Chí)

- **Endpoint:** `GET /api/admin/sos/history`
- **Quyền hạn:** `Bearer Token` (Tài khoản thuộc vai trò `ADMIN`)
- **Mô tả:** Lấy danh sách toàn bộ các ca cứu hộ trong lịch sử hệ thống, sắp xếp giảm dần theo thời gian tạo mới nhất.

#### Query Parameters:

| Tên tham số | Kiểu dữ liệu | Bắt buộc | Mặc định | Mô tả |
| :--- | :--- | :---: | :---: | :--- |
| `q` | `string` | Không | `""` | Từ khóa tìm kiếm (họ tên nạn nhân, số điện thoại, mô tả sự cố, tên đội tiếp nhận) |
| `status` | `string` | Không | `null` | Trạng thái cần lọc (`COMPLETED`, `CANCELLED`, `WAITING`, `ACCEPTED`, `RESCUING`, `ARRIVED`). Nếu để trống/null, trả về **toàn bộ trạng thái trong lịch sử** (hoặc mặc định các ca đã kết thúc) |
| `team_id` | `UUID` | Không | `null` | Lọc theo ID đội cứu hộ tiếp nhận xử lý ca |
| `emergency_type_id` | `UUID` | Không | `null` | Lọc theo ID loại sự cố (ngập lụt, sạt lở, tai nạn...) |
| `from_date` | `string (YYYY-MM-DD)` | Không | `null` | Lọc các ca cứu hộ được tạo từ ngày này (00:00:00) |
| `to_date` | `string (YYYY-MM-DD)` | Không | `null` | Lọc các ca cứu hộ được tạo đến hết ngày này (23:59:59) |
| `page` | `integer` | Không | `1` | Số trang (bắt đầu từ 1) |
| `page_size` | `integer` | Không | `10` | Số bản ghi trên mỗi trang (1 đến 200) |

#### Phản Hồi Thành Công (HTTP 200 OK):

```json
{
  "success": true,
  "total": 142,
  "page": 1,
  "page_size": 10,
  "data": [
    {
      "sos_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
      "status": "COMPLETED",
      "visibility": "PUBLIC",
      "emergency_type_id": "eeeeeeee-0001-0000-0000-000000000001",
      "emergency_type": "NGAP_LUT",
      "emergency_type_description": "Ngập lụt, triều cường dâng cao cô lập nhà cửa khu dân cư",
      "description": "Nước dâng nhanh ngập đến tầng 1, có người già và trẻ nhỏ.",
      "requester_id": "33333333-3333-3333-3333-333333333333",
      "requester_name": "Nguyễn Văn A",
      "requester_phone": "0901234567",
      "team_id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
      "team_name": "Đội Cứu Hộ Phản Ứng Nhanh Quận 1",
      "lat": 10.7769,
      "lng": 106.7009,
      "created_at": "2026-11-09T08:30:00+07:00",
      "updated_at": "2026-11-09T09:45:00+07:00",
      "duration_seconds": 4500,
      "logs_count": 4
    }
  ]
}
```

---

### 2.2. API Xem Nhật Ký Chi Tiết Ca Cứu Hộ Dành Riêng Cho Quản Trị Viên

- **Endpoint:** `GET /api/admin/sos/{sos_id}/logs`
- **Quyền hạn:** `Bearer Token` (Tài khoản thuộc vai trò `ADMIN`)
- **Mô tả:** Lấy toàn bộ lịch sử vết thao tác trong bảng `sos_logs` của phiên cứu hộ bất kỳ mà không bị hạn chế bởi quyền sở hữu hay đội phụ trách.

#### Path Parameters:
- `sos_id` (UUID): ID của yêu cầu SOS.

#### Phản Hồi Thành Công (HTTP 200 OK):

```json
{
  "success": true,
  "count": 4,
  "data": [
    {
      "log_id": "c1a2b3c4-1111-2222-3333-444455556666",
      "sos_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
      "action": "CREATED",
      "description": "Nạn nhân gửi yêu cầu SOS khẩn cấp.",
      "created_at": "2026-11-09T08:30:00+07:00",
      "actor_name": "Nguyễn Văn A"
    },
    {
      "log_id": "c1a2b3c4-2222-3333-4444-555566667777",
      "action": "ACCEPTED",
      "description": "Đội Cứu Hộ Phản Ứng Nhanh Quận 1 đã tiếp nhận ca cứu hộ.",
      "created_at": "2026-11-09T08:35:12+07:00",
      "actor_name": "Trần Văn Đội Trưởng"
    },
    {
      "log_id": "c1a2b3c4-3333-4444-5555-666677778888",
      "action": "ARRIVED",
      "description": "Lực lượng cứu hộ đã tiếp cận hiện trường an toàn.",
      "created_at": "2026-11-09T08:52:00+07:00",
      "actor_name": "Trần Văn Đội Trưởng"
    },
    {
      "log_id": "c1a2b3c4-4444-5555-6666-777788889999",
      "action": "COMPLETED",
      "description": "Đã đưa 3 nạn nhân về nơi tránh trú an toàn. Ca cứu hộ hoàn tất.",
      "created_at": "2026-11-09T09:45:00+07:00",
      "actor_name": "Trần Văn Đội Trưởng"
    }
  ]
}
```

---

### 2.3. API Thống Kê Tổng Quan Lịch Sử Cứu Hộ (Tùy chọn nâng cao)

- **Endpoint:** `GET /api/admin/sos/history/summary`
- **Quyền hạn:** `Bearer Token` (`ADMIN`)
- **Mô tả:** Trả về các chỉ số KPI thống kê lịch sử cứu trợ theo thời gian thực để hiển thị trên đầu trang.

#### Phản Hồi Thành Công (HTTP 200 OK):

```json
{
  "success": true,
  "data": {
    "total_sos": 142,
    "completed_count": 118,
    "cancelled_count": 24,
    "success_rate_percent": 83.1,
    "average_duration_minutes": 52.4
  }
}
```

---

## 3. Hướng Dẫn Code Mẫu Triển Khai Vào Backend FastAPI

Bạn có thể sao chép đoạn code Python dưới đây và thêm trực tiếp vào file `routes/admin.py` trong thư mục backend (`D:\FinalJava\JavaBackend\SOS_Android\backend\routes\admin.py`):

```python
# =============================================================================
# BỔ SUNG: API LỊCH SỬ CỨU HỘ VÀ NHẬT KÝ CHI TIẾT DÀNH CHO ADMIN
# =============================================================================

@router.get("/sos/history")
def rescue_history(
    q: str = Query("", max_length=100),
    status: str | None = Query(None, description="Lọc trạng thái: COMPLETED, CANCELLED, hoặc để trống để lấy tất cả"),
    team_id: UUID | None = Query(None, description="Lọc theo ID đội cứu hộ"),
    emergency_type_id: UUID | None = Query(None, description="Lọc theo ID loại sự cố"),
    from_date: str | None = Query(None, description="Từ ngày định dạng YYYY-MM-DD"),
    to_date: str | None = Query(None, description="Đến ngày định dạng YYYY-MM-DD"),
    page: int = Query(1, ge=1),
    page_size: int = Query(10, ge=1, le=200)
):
    """
    Xem toàn bộ lịch sử cứu hộ dành cho Quản trị viên (Hỗ trợ phân trang và đa bộ lọc).
    """
    conditions = []
    params = []

    # 1. Lọc từ khóa tìm kiếm
    if q.strip():
        search_term = f"%{q.strip()}%"
        conditions.append(
            "(u.full_name ILIKE %s OR u.phone ILIKE %s OR COALESCE(s.description,'') ILIKE %s OR t.name ILIKE %s OR et.name ILIKE %s)"
        )
        params.extend([search_term] * 5)

    # 2. Lọc trạng thái
    if status:
        conditions.append("s.status = %s")
        params.append(status)

    # 3. Lọc đội cứu hộ
    if team_id:
        conditions.append("s.team_id = %s")
        params.append(team_id)

    # 4. Lọc loại sự cố
    if emergency_type_id:
        conditions.append("s.emergency_type_id = %s")
        params.append(emergency_type_id)

    # 5. Lọc khoảng ngày tạo
    if from_date:
        conditions.append("s.created_at >= %s::date")
        params.append(from_date)
    if to_date:
        conditions.append("s.created_at < (%s::date + INTERVAL '1 day')")
        params.append(to_date)

    where_clause = (" WHERE " + " AND ".join(conditions)) if conditions else ""

    select_sql = """
        SELECT s.id AS sos_id, s.description, s.status, s.visibility, s.created_at, s.updated_at,
               s.emergency_type_id, et.name AS emergency_type, et.description AS emergency_type_description,
               s.requester_id, u.full_name AS requester_name, u.phone AS requester_phone,
               s.team_id, t.name AS team_name,
               lc.lat, lc.lng, lc.updated_at AS location_updated_at,
               ROUND(EXTRACT(EPOCH FROM (s.updated_at - s.created_at))) AS duration_seconds,
               (SELECT COUNT(*) FROM sos_logs l WHERE l.sos_id = s.id) AS logs_count
        FROM sos_requests s
        JOIN users u ON u.id = s.requester_id
        JOIN emergency_types et ON et.id = s.emergency_type_id
        LEFT JOIN rescue_teams t ON t.team_id = s.team_id
        LEFT JOIN location_current lc ON lc.sos_id = s.id AND lc.actor = 'VICTIM'
    """

    order_sql = " ORDER BY s.created_at DESC, s.id DESC"

    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            return page_result(cursor, select_sql, where_clause, params, order_sql, page, page_size)


@router.get("/sos/{sos_id}/logs")
def admin_get_sos_logs(sos_id: UUID):
    """
    Quản trị viên xem toàn bộ nhật ký thao tác (audit trail) của một phiên cứu hộ bất kỳ.
    """
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            # Kiểm tra phiên SOS có tồn tại không
            cursor.execute("SELECT id FROM sos_requests WHERE id = %s", (sos_id,))
            if not cursor.fetchone():
                raise HTTPException(status_code=404, detail="Không tìm thấy yêu cầu SOS.")

            cursor.execute("""
                SELECT l.log_id, l.sos_id, l.user_id, l.action, l.description, l.created_at,
                       COALESCE(u.full_name, 'Hệ thống') AS actor_name
                FROM sos_logs l
                LEFT JOIN users u ON l.user_id = u.id
                WHERE l.sos_id = %s
                ORDER BY l.created_at ASC
            """, (sos_id,))
            logs = cursor.fetchall()

    return {
        "success": True,
        "count": len(logs),
        "data": [{
            "log_id": str(l["log_id"]),
            "sos_id": str(l["sos_id"]),
            "action": l["action"],
            "description": l["description"],
            "created_at": l["created_at"].isoformat() if l["created_at"] else None,
            "actor_name": l["actor_name"]
        } for l in logs]
    }
```

---

## 4. Tóm Tắt Trạng Thái Tích Hợp Trên Giao Diện

- **Giao diện Quản trị:** Đã có mục **"Lịch sử cứu hộ"** trong Sidebar Navigation (`#history`).
- **Các tính năng đã hoàn thiện phía Frontend:**
  1. Thanh tìm kiếm từ khóa đa thông tin (tên người yêu cầu, số điện thoại, tên đội, nội dung sự cố).
  2. Dropdown lọc theo Trạng thái (Hoàn thành, Đã hủy, Đang cứu trợ, Chờ tiếp nhận...).
  3. Dropdown lọc theo Loại sự cố (tải danh mục trực tiếp từ API backend `/api/emergency-types`).
  4. Dropdown lọc theo Đội cứu hộ (tải trực tiếp từ `/api/admin/teams`).
  5. Bộ chọn khoảng ngày: Từ ngày (`from_date`) - Đến ngày (`to_date`).
  6. Nút **Đặt lại bộ lọc** (Reset all filters) tiện lợi.
  7. Nút **Xuất file CSV** (Export CSV) có dấu Tiếng Việt chuẩn UTF-8 phục vụ báo cáo.
  8. Thẻ số liệu thống kê nhanh (Tổng số ca, Hoàn thành, Đã hủy, Tỷ lệ giải cứu thành công).
  9. Modal Xem chi tiết ca cứu hộ đầy đủ mốc thời gian, vị trí GPS và Timeline nhật ký thao tác.
  10. Cơ chế tự động fallback mượt mà: nếu Backend chưa có `/api/admin/sos/history`, frontend tự động chuyển sang dùng `/api/admin/sos` và phân trang phía client. Khi bạn triển khai code trên vào backend, giao diện sẽ tự chuyển sang dùng server-side pagination mà không cần thay đổi frontend!
