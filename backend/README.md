# HỆ THỐNG HỖ TRỢ CỨU HỘ KHẨN CẤP (SOS RESCUE BACKEND)

Máy chủ backend chính thức được triển khai bằng **Python + FastAPI + PostgreSQL (psycopg 3)**, hỗ trợ định vị GPS thời gian thực qua **WebSocket**, xác thực OTP qua **Telegram Bot**, phân quyền **JWT (USER / RESCUER)** và thực thi các ràng buộc nghiệp vụ theo đồ án **Nhom1_Chuong1**.

---

## 1. Cấu trúc thư mục Backend

```text
backend/
├── .env                  # Cấu hình môi trường cục bộ (DB, JWT, Telegram Bot)
├── .env.example          # Mẫu cấu hình môi trường
├── requirements.txt      # Danh sách các thư viện Python
├── config.py             # Nạp và kiểm tra các tham số cấu hình
├── main.py               # Ứng dụng FastAPI, middleware CORS, Lifespan và WebSocket /ws
├── test_backend.py       # Bộ kiểm thử tự động toàn diện 16 bước (F01 - F14, RB01 - RB10)
├── database/
│   ├── init_db.sql       # Script khởi tạo 6 bảng, 2 view, các chỉ mục duy nhất và seed data
│   └── connection.py     # Quản lý Connection Pool (psycopg-pool) và transaction
├── services/
│   ├── auth_service.py   # Chuẩn hóa SĐT, sinh và băm SHA-256 OTP, ký & xác thực JWT
│   ├── telegram_bot.py   # Bot Telegram tiếp nhận chia sẻ SĐT (/start) và gửi OTP
│   ├── ws_manager.py     # Quản lý kết nối WebSocket, phân luồng phòng SOS và broadcast cứu hộ
│   └── scheduler.py      # Tác vụ nền APScheduler tự động hủy SOS WAITING quá 2 giờ (RB07)
└── routes/
    ├── auth.py           # API yêu cầu OTP, xác minh OTP, lấy thông tin cá nhân
    ├── users.py          # API cập nhật hồ sơ người dùng
    ├── sos.py            # API tạo SOS, theo dõi SOS của mình, hủy, xem bản đồ cộng đồng
    ├── rescuer.py        # API hàng đợi cứu hộ, nhận ca, cập nhật tiến trình, nhả ca, hoàn thành
    └── location.py       # API cập nhật tọa độ GPS, lấy marker hiện tại và lộ trình Polyline
```

---

## 2. Thiết kế Cơ sở dữ liệu (PostgreSQL `sos_db` - 8 Bảng & 2 View)

Cơ sở dữ liệu được tinh giản và tối ưu hóa cao độ, bao gồm **8 bảng thực thể** và **2 view**:

1. **`users`**: Khóa chính UUID (`gen_random_uuid()`), họ tên, số điện thoại (`phone UNIQUE`), Telegram Chat ID (`telegram_chat_id BIGINT UNIQUE`), vai trò (`USER` hoặc `RESCUER`), trạng thái (`ACTIVE` hoặc `DISABLED`), thuộc tính hợp lệ (`is_valid BOOLEAN NOT NULL DEFAULT false`), thời gian tạo. Đã loại bỏ hoàn toàn `password_hash`.
2. **`rescue_teams`**: Quản lý các đội cứu hộ khẩn cấp: `team_id`, `name`, `description`, `status` (`ACTIVE`, `BUSY`, `INACTIVE`), `created_at`.
3. **`rescue_team_members`**: Quản lý thành viên từng đội: `member_id`, `team_id`, `user_id`, `role` (`LEADER`, `MEMBER`,...), `created_at`.
4. **`sos_requests`**: Lưu yêu cầu cứu trợ khớp 100% sơ đồ ERD: `id` (PK), `requester_id` (FK trỏ tới `users`), `team_id` (FK trỏ tới `rescue_teams`), `emergency_type`, `description`, `visibility` (`PUBLIC`/`PRIVATE`), `status` (`WAITING`, `ACCEPTED`, `RESCUING`, `ARRIVED`, `COMPLETED`, `CANCELLED`), `created_at`, `updated_at`.
   - *Tối giản hóa kiến trúc*: Tọa độ ban đầu của nạn nhân được lưu trực tiếp tại `location_current` (`actor = 'VICTIM'`), đội cứu hộ được liên kết qua `team_id` (thành viên quản lý qua `rescue_team_members`), lịch sử nhận ca và kết thúc ca được ghi vết tại `sos_logs`.
   - **RB04**: Ràng buộc duy nhất `idx_unique_active_requester` trên `requester_id` khi SOS chưa kết thúc (1 nạn nhân chỉ có tối đa 1 SOS hoạt động).
   - **RB05**: Ràng buộc duy nhất `idx_unique_active_team` trên `team_id` khi ca đang xử lý (1 đội cứu hộ chỉ xử lý 1 ca tại 1 thời điểm).
5. **`location_current`**: Khóa chính `(sos_id, actor)` với `actor` là `VICTIM` hoặc `RESCUER`. Chỉ lưu 1 dòng tọa độ mới nhất của mỗi bên để đồng bộ marker thời gian thực. Bị dọn sạch khi phiên kết thúc (RB09).
6. **`location_history`**: Lưu các điểm định kỳ (15–30s) để vẽ đường Polyline lộ trình di chuyển.
7. **`sos_notifications`**: Lưu trữ các thông báo trong ứng dụng cho người dùng: `notify_id`, `sos_id`, `user_id`, `message`, `type`, `is_read`, `created_at`.
8. **`sos_logs`**: Nhật ký hoạt động kiểm toán chi tiết: `log_id`, `sos_id`, `user_id`, `action`, `description`, `created_at`.
9. **View `public_sos_map`**: Dành cho cộng đồng, chỉ chọn SOS `PUBLIC` còn hoạt động, làm tròn tọa độ 2 chữ số thập phân (sai lệch ~1km) để bảo vệ quyền riêng tư.
10. **View `rescue_queue`**: Hàng đợi cứu hộ dành cho `RESCUER`, hiển thị mọi SOS `WAITING` trong vòng 2 giờ kèm tọa độ chính xác.

### Cơ chế Quản lý OTP & Xác thực Đăng nhập (In-Memory RAM):
- **Loại bỏ bảng `otp_codes` & `telegram_links`**: Mã OTP ngắn hạn (5 phút) và liên kết phiên xác thực được quản lý trực tiếp bằng bộ nhớ RAM (`InMemoryOtpStore`), giảm tải I/O cho PostgreSQL.
- **Bảo mật OTP**: Băm SHA-256 an toàn, thời hạn 5 phút, giới hạn nhập sai 5 lần (sau 5 lần tự hủy mã), giới hạn tần suất gửi lại tối thiểu 30 giây giữa 2 lần.
- **Trạng thái tài khoản (`is_valid`)**:
  - Người dùng mới khi liên kết Telegram Bot sẽ có `is_valid = false`.
  - Sau khi nhập đúng mã OTP gửi qua Telegram, hệ thống chuyển `is_valid = true` và cấp JWT Token.

---

## 3. Danh sách Tài khoản Mẫu Cấp Sẵn (Seed Data)

Hệ thống đã nạp sẵn dữ liệu mẫu trong `init_db.sql`:

| Số điện thoại | Vai trò | Họ tên hiển thị | Mô tả |
| :--- | :--- | :--- | :--- |
| **`0901112222`** | `RESCUER` | Đội Cứu Hộ Quận 1 | Tài khoản đội cứu hộ mẫu số 1 |
| **`0903334444`** | `RESCUER` | Đội Cứu Hộ TP.HCM Đội 2 | Tài khoản đội cứu hộ mẫu số 2 |
| **`0901234567`** | `USER` | Nguyễn Văn An | Người dùng mẫu số 1 |
| **`0912345678`** | `USER` | Trần Thị Bình | Người dùng mẫu số 2 |

*Các số điện thoại trên đã được liên kết sẵn Telegram trong DB để có thể yêu cầu OTP và test ngay lập tức.*

---

## 4. Hướng dẫn Cài đặt & Khởi chạy

### Bước 1: Khởi tạo Cơ sở dữ liệu (nếu cần thiết lập mới)
```powershell
$env:PGPASSWORD='postgres'
psql -U postgres -h localhost -d sos_db -f "backend/database/init_db.sql"
```

### Bước 2: Cài đặt thư viện Python (nếu chạy môi trường mới)
```powershell
pip install -r backend/requirements.txt
```

### Bước 3: Khởi chạy máy chủ Backend
```powershell
cd backend
python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```
- Máy chủ sẽ chạy tại: `http://localhost:8000`
- Giao diện tài liệu Swagger UI tương tác: `http://localhost:8000/docs`
- Địa chỉ truy cập từ máy ảo Android Emulator: `http://10.0.2.2:8000`

### Bước 4: Chạy kiểm thử tự động
```powershell
cd backend
python test_backend.py        # Bộ kiểm thử tích hợp 16 bước nghiệp vụ (F01 - F14, RB01 - RB10)
python verify_deep.py         # Kiểm thử bảo mật chuyên sâu, phân quyền RBAC và chống Race Condition (37 bài test)
python test_erd_10_tables.py  # Kiểm thử liên kết đội cứu hộ, nhật ký sos_logs và sos_notifications
```
*(Toàn bộ các ca kiểm thử chạy tự động và đạt 100% PASS không có lỗi).*

---

## 5. Tóm tắt Hợp đồng REST API & WebSocket

### 5.1 Xác thực & Tài khoản (`/api/auth`)
- `POST /api/auth/request-otp`: Nhận `{ phone }`. Gửi OTP 6 chữ số qua Telegram (hiệu lực 5 phút, lưu RAM).
- `POST /api/auth/verify-otp`: Nhận `{ phone, otp, full_name? }`. Xác thực OTP, cập nhật `is_valid = true`, cấp JWT token và thông tin `user`.
- `GET /api/auth/me`: Lấy thông tin tài khoản hiện tại từ Bearer JWT.
- `POST /api/auth/dev-link-telegram`: Tiện ích dev liên kết nhanh số điện thoại vào bảng `users` (`is_valid = false`).

### 5.2 Người dùng (`/api/users`)
- `GET /api/users/profile`: Xem hồ sơ.
- `PUT /api/users/profile`: Cập nhật `full_name` (client không được tự sửa `role` hay `account_status`).

### 5.3 Yêu cầu Cứu hộ SOS (`/api/sos`)
- `POST /api/sos`: Tạo SOS mới (`WAITING`). Body: `{ emergency_type, description?, visibility: "PUBLIC"|"PRIVATE", initial_lat, initial_lng }`. Kiểm tra RB04.
- `GET /api/sos/my-active`: Lấy SOS đang diễn ra của người gửi (kèm thông tin cứu hộ và tọa độ 2 bên).
- `GET /api/sos/{id}`: Xem chi tiết SOS (chỉ chủ SOS hoặc RESCUER được quyền xem).
- `PUT /api/sos/{id}/visibility`: Đổi chế độ `PUBLIC`/`PRIVATE` (chủ SOS).
- `POST /api/sos/{id}/cancel`: Hủy SOS khi an toàn/gửi nhầm (`CANCELLED`, `USER_CANCELLED`).
- `GET /api/sos/public/map`: Bản đồ cộng đồng từ View `public_sos_map` (tọa độ làm tròn ~1km).

### 5.4 Nghiệp vụ Đội Cứu Hộ (`/api/rescuer` - Yêu cầu role RESCUER)
- `GET /api/rescuer/queue`: Hàng đợi các SOS `WAITING` từ View `rescue_queue`.
- `GET /api/rescuer/my-active`: Lấy ca cứu hộ đội đang xử lý.
- `POST /api/rescuer/sos/{id}/accept`: Tiếp nhận ca (nguyên tử RB06, kiểm tra RB05).
- `POST /api/rescuer/sos/{id}/status`: Cập nhật tiến trình (`RESCUING` -> `ARRIVED`).
- `POST /api/rescuer/sos/{id}/release`: Nhả ca (quay về `WAITING`, xóa dấu vết rescuer, vào lại hàng đợi).
- `POST /api/rescuer/sos/{id}/complete`: Hoàn thành ca cứu hộ (`COMPLETED`, `RESCUER_COMPLETED`).

### 5.5 Định vị & Lộ trình (`/api/location`)
- `POST /api/location/{sos_id}`: Cập nhật GPS realtime `{ lat, lng, accuracy_m, save_history }`. Tự xác định actor (`VICTIM` hoặc `RESCUER`), upsert `location_current` và broadcast WebSocket.
- `GET /api/location/{sos_id}/current`: Lấy tọa độ mới nhất của 2 bên.
- `GET /api/location/{sos_id}/trail`: Lấy danh sách điểm từ `location_history` để vẽ Polyline.

### 5.6 Kênh WebSocket Real-time (`/ws`)
- Kết nối: `ws://10.0.2.2:8000/ws?token=<JWT_TOKEN>`
- Các sự kiện đẩy từ server:
  - `NEW_SOS`: Phát cho tất cả RESCUER khi có SOS mới hoặc khi một ca được nhả.
  - `QUEUE_UPDATED`: Cập nhật hàng đợi khi SOS được nhận, hủy, hoặc hoàn thành.
  - `SOS_ACCEPTED`: Báo cho nạn nhân đội cứu hộ đã tiếp nhận ca.
  - `STATUS_CHANGED`: Báo thay đổi tiến trình (`RESCUING`, `ARRIVED`).
  - `SOS_RELEASED`: Báo cho nạn nhân đội cứu hộ đã nhả ca (quay lại tìm đội khác).
  - `SOS_COMPLETED`: Báo phiên cứu hộ hoàn tất.
  - `SOS_CANCELLED`: Báo hủy yêu cầu.
  - `LOCATION_BROADCAST`: Tọa độ di chuyển realtime của đối phương để cập nhật marker trên bản đồ.
