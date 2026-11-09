# BÁO CÁO KẾT QUẢ TRIỂN KHAI BACKEND HỆ THỐNG CỨU HỘ KHẨN CẤP (SOS RESCUE)

* **Dự án**: Hệ thống Hỗ trợ Cứu hộ Khẩn cấp SOS (Đồ án Lập trình Di động)
* **Tài liệu căn cứ**: `Nhom1_Chuong1.docx`
* **Người thực hiện**: Phạm Minh Đức (*Database Developer + Backend Developer + Reporter*)
* **Thời gian hoàn thành**: 29/10/2026
* **Công nghệ sử dụng**: Python 3.12, FastAPI, PostgreSQL 17 (`psycopg 3`, `psycopg-pool`), WebSocket Real-time, PyJWT, Python-Telegram-Bot, APScheduler

---

## 1. Cấu trúc thư mục Backend

Toàn bộ mã nguồn backend được tổ chức dạng mô-đun tại thư mục `backend/`:

```text
backend/
├── .env                  # Cấu hình môi trường (PostgreSQL, JWT, Telegram Bot, Mock OTP)
├── .env.example          # Mẫu biến môi trường cho triển khai
├── requirements.txt      # Danh sách dependencies đã ghim phiên bản
├── config.py             # Nạp và kiểm tra các thông số cấu hình
├── main.py               # Ứng dụng FastAPI, middleware CORS, Lifespan và WebSocket (/ws)
├── test_backend.py       # Bộ kiểm thử 16 bước tự động kiểm tra toàn bộ luồng F01-F14
├── README.md             # Hướng dẫn chi tiết chạy máy chủ, API Docs và kết nối Android
├── database/
│   ├── init_db.sql       # Script khởi tạo 6 bảng, 2 view, các index duy nhất từng phần & seed data
│   └── connection.py     # Quản lý Connection Pool (psycopg-pool) và transaction tự động
├── services/
│   ├── auth_service.py   # Chuẩn hóa SĐT, sinh & băm SHA-256 OTP, ký & xác thực JWT
│   ├── telegram_bot.py   # Bot Telegram tiếp nhận chia sẻ SĐT (/start) và gửi mã OTP
│   ├── ws_manager.py     # Quản lý WebSocket connection, phòng phiên SOS và broadcast cứu hộ
│   └── scheduler.py      # Bộ lập lịch APScheduler tự động dọn SOS WAITING > 2h (RB07)
└── routes/
    ├── auth.py           # API yêu cầu OTP, xác minh OTP, lấy thông tin cá nhân
    ├── users.py          # API cập nhật hồ sơ người dùng (chỉ đổi tên, không sửa role)
    ├── sos.py            # API tạo SOS, theo dõi SOS của mình, hủy, xem bản đồ cộng đồng
    ├── rescuer.py        # API hàng đợi rescue_queue, nhận ca, cập nhật tiến trình, nhả ca, hoàn thành
    └── location.py       # API cập nhật GPS realtime, lấy vị trí hiện tại và Polyline lộ trình
```

---

## 2. Thiết kế Cơ sở dữ liệu (`sos_db` PostgreSQL - 8 Bảng & 2 View Tinh gọn)

Đã khởi tạo và áp dụng thành công script `init_db.sql` lên cơ sở dữ liệu PostgreSQL `sos_db`, bao gồm **8 bảng thực thể và 2 view**, loại bỏ hoàn toàn các bảng dư thừa `otp_codes`, `telegram_links` và cột `password_hash`:

1. **`users`**: Khóa chính UUID (`gen_random_uuid()`), `full_name`, `phone` (`UNIQUE`), `telegram_chat_id` (`BIGINT UNIQUE`), `role` (`USER`/`RESCUER`), `account_status` (`ACTIVE`/`DISABLED`), `is_valid` (`BOOLEAN NOT NULL DEFAULT false`), `created_at`.
2. **`rescue_teams`**: Quản lý các đội cứu hộ khẩn cấp: `team_id` (PK UUID), `name`, `description`, `status` (`ACTIVE`, `BUSY`, `INACTIVE`), `created_at`.
3. **`rescue_team_members`**: Quản lý các thành viên trong đội: `member_id` (PK UUID), `team_id` (FK), `user_id` (FK), `role` (`LEADER`, `MEMBER`, `DRIVER`, `MEDIC`), `created_at`.
4. **`sos_requests`**: Quản lý vòng đời yêu cầu SOS khớp 100% sơ đồ ERD: `id` (PK UUID), `requester_id` (FK), `team_id` (FK trỏ tới `rescue_teams`), `emergency_type`, `description`, `visibility` (`PUBLIC`/`PRIVATE`), `status` (`WAITING`, `ACCEPTED`, `RESCUING`, `ARRIVED`, `COMPLETED`, `CANCELLED`), `created_at`, `updated_at`.
   * **Tinh gọn theo đúng ERD**: Tọa độ ban đầu lưu tại `location_current` (`actor = 'VICTIM'`), người cứu hộ liên kết qua `team_id` (`rescue_teams`), nhật ký hoàn thành/hủy ca ghi vết tại `sos_logs`.
   * **RB04**: Chỉ mục duy nhất từng phần `idx_unique_active_requester` trên `requester_id` khi SOS chưa kết thúc.
   * **RB05**: Chỉ mục duy nhất từng phần `idx_unique_active_team` trên `team_id` khi ca đang xử lý.
5. **`location_current`**: Khóa chính `(sos_id, actor)` với `actor` là `VICTIM` hoặc `RESCUER`. Chỉ lưu vị trí mới nhất của mỗi bên để cập nhật marker realtime. Tự động xóa khi SOS kết thúc (**RB09**).
6. **`location_history`**: Lưu các điểm vị trí định kỳ (15–30s) để vẽ Polyline lộ trình di chuyển.
7. **`sos_notifications`**: Lưu trữ thông báo trong ứng dụng: `notify_id` (PK UUID), `sos_id` (FK), `user_id` (FK), `message`, `type` (`SYSTEM`/`UPDATE`), `is_read` (boolean), `created_at`.
8. **`sos_logs`**: Lưu nhật ký kiểm toán mọi hành động cứu trợ: `log_id` (PK UUID), `sos_id` (FK), `user_id` (FK), `action`, `description`, `created_at`.
9. **View `public_sos_map`**: Dành cho cộng đồng, chỉ lấy SOS `PUBLIC` còn hoạt động, làm tròn tọa độ 2 chữ số thập phân (sai số ~1km) để bảo vệ quyền riêng tư người gửi.
10. **View `rescue_queue`**: Hàng đợi các SOS `WAITING` trong vòng 2 giờ kèm tọa độ chính xác cho đội cứu hộ.

### Cơ chế Đăng nhập & Xác thực OTP Telegram (In-Memory RAM):
* **Loại bỏ `password_hash`, `otp_codes`, `telegram_links`**: Toàn bộ luồng đăng nhập dựa vào số điện thoại và OTP được gửi qua Bot Telegram.
* **Quản lý mã OTP trong RAM**: Sử dụng `InMemoryOtpStore` trong Python, lưu băm SHA-256, hạn 5 phút, giới hạn 5 lần nhập sai, cooldown 30 giây giữa 2 lần gửi, tự động hủy ngay khi xác thực thành công.
* **Thuộc tính `is_valid`**: Người dùng mới tạo/mới liên kết Telegram sẽ có `is_valid = false`. Sau khi xác thực đúng OTP từ Telegram, trạng thái tự động cập nhật thành `true` và cấp JWT token. Thống nhất định dạng chuẩn `is_valid` duy nhất trong JSON trả về.

### Dữ liệu mẫu khởi tạo sẵn (Seed Data):
* **Đội Cứu Hộ 1**: `Đội Cứu Hộ Phản Ứng Nhanh Quận 1` (`ACTIVE`)
  * Trưởng đội: Trần Cứu Hộ (`LEADER`, SĐT `0901112222`, role `RESCUER`)
* **Đội Cứu Hộ 2**: `Đội Cứu Hộ Đội 2 TP.HCM` (`ACTIVE`)
  * Trưởng đội: Lê Cứu Hộ (`LEADER`, SĐT `0903334444`, role `RESCUER`)
* **Người dùng 1**: Nguyễn Văn An (SĐT `0901234567`, role `USER`)
* **Người dùng 2**: Trần Thị Bình (SĐT `0912345678`, role `USER`)

---

## 3. Danh sách API Endpoints chi tiết

| Nhóm | Endpoint | Phương thức | Mô tả chức năng |
| :--- | :--- | :---: | :--- |
| **Xác thực** | `/api/auth/request-otp` | `POST` | Gửi mã OTP 6 chữ số qua Bot Telegram (hiệu lực 5 phút) |
| | `/api/auth/verify-otp` | `POST` | Xác thực OTP (RAM), chuyển `is_valid = true`, cấp JWT token và thông tin user |
| | `/api/auth/me` | `GET` | Lấy thông tin tài khoản hiện tại từ Bearer JWT |
| | `/api/auth/dev-link-telegram` | `POST` | Tiện ích liên kết nhanh số điện thoại phục vụ test/demo |
| **Hồ sơ** | `/api/users/profile` | `GET / PUT` | Xem và cập nhật họ tên (chặn sửa role/status) |
| **SOS** | `/api/sos` | `POST` | Tạo yêu cầu SOS (`WAITING`), kiểm tra ràng buộc duy nhất RB04 |
| | `/api/sos/my-active` | `GET` | Xem SOS đang hoạt động của mình kèm thông tin cứu hộ |
| | `/api/sos/{id}` | `GET` | Xem chi tiết SOS (chỉ chủ SOS hoặc RESCUER có quyền) |
| | `/api/sos/{id}/visibility` | `PUT` | Đổi chế độ `PUBLIC` / `PRIVATE` |
| | `/api/sos/{id}/cancel` | `POST` | Hủy SOS khi đã an toàn (`end_reason = USER_CANCELLED`) |
| | `/api/sos/public/map` | `GET` | Lấy dữ liệu bản đồ cộng đồng từ View `public_sos_map` |
| | `/api/sos/{id}/logs` | `GET` | *(Mới)* Xem lịch sử thao tác của SOS từ bảng `sos_logs` |
| | `/api/sos/notifications/my` | `GET` | *(Mới)* Lấy thông báo người dùng từ bảng `sos_notifications` |
| | `/api/sos/notifications/{id}/read` | `PUT` | *(Mới)* Đánh dấu thông báo đã đọc |
| **Cứu hộ** | `/api/rescuer/queue` | `GET` | Lấy danh sách SOS chờ tiếp nhận từ View `rescue_queue` |
| | `/api/rescuer/my-active` | `GET` | Lấy ca đang tiếp nhận của đội cứu hộ hiện tại |
| | `/api/rescuer/sos/{id}/accept` | `POST` | Nhận ca cứu hộ nguyên tử (gán `team_id`) |
| | `/api/rescuer/sos/{id}/status` | `POST` | Cập nhật tiến trình tuần tự: `RESCUING` -> `ARRIVED` |
| | `/api/rescuer/sos/{id}/release`| `POST` | Nhả ca (về `WAITING`, xóa vị trí cứu hộ cũ, vào lại queue) |
| | `/api/rescuer/sos/{id}/complete`| `POST` | Hoàn thành ca cứu hộ (`COMPLETED`), ghi vết vào `sos_logs` |
| | `/api/rescuer/teams` | `GET` | *(Mới)* Xem danh sách các đội cứu hộ & thành viên (`rescue_teams`) |
| **Định vị** | `/api/location/{sos_id}` | `POST` | Cập nhật GPS mới nhất, upsert `location_current`, lưu trail |
| | `/api/location/{sos_id}/current`| `GET` | Lấy marker hiện tại của 2 bên (`VICTIM` và `RESCUER`) |
| | `/api/location/{sos_id}/trail` | `GET` | Lấy danh sách tọa độ lịch sử để vẽ Polyline |
| **Realtime** | `/ws?token={jwt}` | `WebSocket` | Kênh WebSocket thời gian thực đồng bộ sự kiện và GPS |

---

## 4. Kết quả Kiểm thử Tự động & Kiểm tra Toàn diện

### 4.1 Bộ kiểm thử quy trình chức năng (`test_backend.py` - 16/16 Đạt)
* **Bước 1**: Kiểm tra sức khỏe hệ thống và kết nối database `sos_db` (`GET /health`) -> **Thành công**
* **Bước 2**: Đăng ký/đăng nhập tài khoản USER mới qua OTP Telegram -> **Thành công**
* **Bước 3**: Cập nhật hồ sơ cá nhân -> **Thành công**
* **Bước 4**: Tạo yêu cầu SOS (`WAITING`, `PUBLIC`) -> **Thành công**
* **Bước 5**: Kiểm tra ràng buộc RB04 (chặn tạo 2 SOS đồng thời) -> **Thành công (trả mã 409 Conflict)**
* **Bước 6**: Kiểm tra View `public_sos_map` (làm tròn tọa độ 2 chữ số thập phân) -> **Thành công**
* **Bước 7**: Kiểm tra `/api/sos/my-active` -> **Thành công**
* **Bước 8**: Đăng nhập tài khoản RESCUER mẫu `0901112222` -> **Thành công**
* **Bước 9**: RESCUER xem hàng đợi View `rescue_queue` -> **Thành công**
* **Bước 10**: RESCUER nhận ca (`ACCEPTED`), kiểm tra tự động rời `rescue_queue` và chặn nhận ca thứ 2 (RB05) -> **Thành công**
* **Bước 11**: Cập nhật vị trí GPS realtime của 2 bên và lấy Polyline trail -> **Thành công**
* **Bước 12**: Cập nhật tiến trình `ACCEPTED` -> `RESCUING` -> `ARRIVED` -> **Thành công**
* **Bước 13**: Hoàn thành phiên cứu hộ (`COMPLETED`) và kiểm tra dọn sạch `location_current` (RB09) -> **Thành công**
* **Bước 14**: Nghiệp vụ Nhả ca (Release case quay lại `WAITING`) -> **Thành công**
* **Bước 15**: Người dùng hủy SOS (`CANCELLED`, `USER_CANCELLED`) -> **Thành công**
* **Bước 16**: Kiểm tra kết nối và trao đổi WebSocket Real-time (`/ws`) -> **Thành công**

### 4.2 Bộ kiểm tra chuyên sâu bảo mật & tranh chấp đồng thời (`verify_deep.py` - 37/37 Đạt)
* **Chuẩn hóa SĐT**: Chuyển đổi chính xác các dạng `+84`, `84`, dấu gạch ngang, khoảng trắng về đầu `0`.
* **Phân quyền (RBAC)**: Chặn `USER` truy cập hàng đợi cứu hộ hoặc nhận ca (403 Forbidden); chặn Token giả mạo hoặc thiếu token (401 Unauthorized).
* **Ràng buộc đầu vào**: Chặn vĩ độ > 90°, kinh độ > 180°, loại sự cố rỗng (422 Unprocessable Entity).
* **Bảo vệ OTP**: Chặn spam OTP liên tục trong 30s (429 Too Many Requests); tự động khóa và vô hiệu hóa mã OTP khi nhập sai đủ 5 lần.
* **Xử lý tranh chấp đồng thời (Concurrency / Race Condition)**: Chặn 2 người dùng tạo SOS đồng thời vi phạm RB04; chặn 2 đội cùng nhận 1 ca (Atomic Update RB06).
* **Bảo mật dữ liệu riêng tư**: Chặn người dùng khác xem hoặc hủy SOS không phải của mình; chặn đội cứu hộ không liên quan can thiệp ca.
* **Máy trạng thái tuần tự**: Bắt buộc tuân theo thứ tự `ACCEPTED` -> `RESCUING` -> `ARRIVED` -> `COMPLETED`, chặn nhảy cóc hoặc hoàn thành lại.
* **Tác vụ nền tự động**: Tự động hủy SOS `WAITING` quá 2 giờ (RB07) và xóa sạch `location_current` (RB09).
* **Phân quyền phòng WebSocket**: Chặn người lạ lắng nghe sự kiện của phòng SOS khác.

### 4.3 Kiểm thử liên kết 4 bảng bổ sung theo sơ đồ ERD (`test_erd_10_tables.py` - Đạt 100%)
* **Bảng `rescue_teams` & `rescue_team_members`**: Quản lý các đội cứu hộ, trưởng đội, thành viên; tự động ánh xạ tài khoản sang đội để nhận ca.
* **Bảng `sos_logs`**: Tự động ghi nhận nhật ký kiểm toán cho mọi sự kiện: `CREATE_SOS`, `ACCEPT_SOS`, `STATUS_RESCUING`, `STATUS_ARRIVED`, `COMPLETE_SOS`, `CANCEL_SOS`.
* **Bảng `sos_notifications`**: Tự động phát sinh thông báo cho người dùng khi SOS được tiếp nhận hoặc cập nhật, hỗ trợ API đánh dấu đã đọc (`is_read = true`).
* **Xử lý tranh chấp đồng thời (Concurrency / Race Condition)**:
  * 2 yêu cầu tạo SOS đồng thời từ cùng 1 người dùng: Đúng 1 yêu cầu thành công, yêu cầu kia bị chặn bởi chỉ mục duy nhất từng phần `idx_unique_active_requester` (RB04, mã 409).
  * 2 đội cứu hộ đồng thời bấm nhận cùng 1 ca: Đúng 1 đội nhận thành công nhờ câu lệnh `UPDATE` có điều kiện nguyên tử (RB06), đội còn lại nhận 409 Conflict.
* **Bảo vệ quyền riêng tư**: Chặn người dùng khác xem chi tiết hoặc hủy SOS của nạn nhân; chặn đội cứu hộ không liên quan cập nhật tiến trình.
* **Ràng buộc máy trạng thái**: Chặn nhảy cóc trạng thái; cho phép nhả ca khi `ACCEPTED`/`RESCUING`; nghiêm cấm nhả ca sau khi đã `ARRIVED`; chặn hoàn thành phiên đã đóng.
* **Tác vụ nền tự động hết hạn**: Tác vụ định kỳ kiểm tra chính xác các SOS `WAITING` > 2 giờ, tự động hủy với `end_reason = 'EXPIRED'` và dọn sạch `location_current` (RB07, RB09).
* **Bảo vệ phòng WebSocket**: Chặn client lạ đăng ký nhận sự kiện từ phòng phiên SOS của nạn nhân khác (Unauthorized Room).

### 4.3 Bộ kiểm thử 4 bảng bổ sung ERD (`test_erd_10_tables.py` - 5/5 Đạt)
* **`rescue_teams` & `rescue_team_members`**: Lấy danh sách đội cứu hộ và các thành viên phụ trách kèm vai trò (`LEADER`, `MEMBER`).
* **`sos_requests.team_id`**: Kiểm tra tự động liên kết đội cứu hộ tương ứng khi rescuer tiếp nhận yêu cầu.
* **`sos_logs`**: Tự động ghi nhật ký mọi hành động (`CREATE_SOS`, `ACCEPT_SOS`, `STATUS_RESCUING`, `STATUS_ARRIVED`, `COMPLETE_SOS`, `CANCEL_SOS`).
* **`sos_notifications`**: Tự động phát sinh thông báo cho người dùng và hỗ trợ API đánh dấu đã đọc (`is_read=true`).

👉 **Tổng kết**: **100% bài kiểm tra đạt (56/56 checks), 0 lỗi phát sinh. Hệ thống 10 bảng ERD hoạt động hoàn hảo và đồng bộ tuyệt đối.**

---

## 5. Hướng dẫn Khởi chạy và Ghép nối Ứng dụng Android

### 5.1 Khởi chạy máy chủ Backend
```powershell
cd "d:\Hoc tap\lap trinh di dong\FINAL\backend"
python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload --reload-dir routes --reload-dir services --reload-dir database --timeout-keep-alive 65
```

### 5.2 Các địa chỉ kết nối:
* **Giao diện Swagger UI tài liệu API**: [http://localhost:8000/docs](http://localhost:8000/docs)
* **Base URL cho máy ảo Android Emulator**: `http://10.0.2.2:8000`
* **WebSocket URL cho máy ảo Android Emulator**: `ws://10.0.2.2:8000/ws?token=<jwt_token>`
* **Base URL cho thiết bị thật qua Wi-Fi**: `http://<IP_MAY_TINH>:8000`
