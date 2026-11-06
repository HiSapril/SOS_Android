# TỔNG HỢP TRIỂN KHAI GIAO DIỆN ANDROID & LUỒNG HOẠT ĐỘNG (MOCK DATA)

> **Tài liệu lịch sử:** Phần dưới mô tả phiên bản mock trước đây. Ứng dụng hiện đã chuyển
> toàn bộ màn hình sang API thật và xóa kho mock. Xem [TICH_HOP_API_GIAO_DIEN.md](TICH_HOP_API_GIAO_DIEN.md).

> **Dự án:** Hệ thống Cứu hộ Khẩn cấp (SOS Rescue Android App)  
> **Tài liệu đối chiếu chuẩn:** `ai_skills/BANG_API_VA_LUONG_HOAT_DONG.md`  
> **Trạng thái hiện tại:** Đã hoàn thiện toàn bộ 10 màn hình XML, các Activity Java, tích hợp bản đồ MapLibre Native SDK và kho dữ liệu giả lập (`MockDataRepository`) khớp 100% với cấu trúc API & các Ràng buộc nghiệp vụ (`RB04`, `RB05`, `RB06`, `RB07`, `RB09`).

---

## 1. Cấu Trúc Package & Các File Mã Nguồn Đã Triển Khai

Toàn bộ mã nguồn Java được tổ chức theo kiến trúc phân tầng rõ ràng tại `app/src/main/java/com/javafinal/`:

```text
com.javafinal
├── MainActivity.java                      # Router điều hướng mặc định sang SplashActivity
├── model/
│   └── SosRequest.java                    # Model chuẩn hóa khớp bảng sos_requests, location_current, sos_logs
├── data/
│   └── MockDataRepository.java            # Singleton lưu trữ Mock Data mô phỏng 8 bảng DB & 27 API
├── util/
│   ├── MapHelper.java                     # Tiện ích bản đồ MapLibre (4 lớp bản đồ, Marker Canvas, Polyline, Polygon)
│   └── NavigationHelper.java              # Hộp thoại Hồ sơ (/api/users/profile), Thông báo & Menu test nhanh 10 màn hình
└── ui/
    ├── splash/
    │   └── SplashActivity.java            # Màn hình 1: Khởi động, kiểm tra /health & JWT Token (/api/auth/me)
    ├── auth/
    │   └── LoginActivity.java             # Màn hình 2: Chọn vai trò + Đăng nhập OTP Telegram 2 bước tách biệt
    ├── user/
    │   ├── HomeActivity.java              # Màn hình 3: Bản đồ cộng đồng (/api/sos/public/map) & kiểm tra RB04
    │   ├── SendSosActivity.java           # Màn hình 4: Tạo yêu cầu cứu hộ mới (POST /api/sos)
    │   ├── TrackSosActivity.java          # Màn hình 5: Theo dõi tiến trình SOS (/api/sos/my-active & WebSocket)
    │   └── SosResultActivity.java         # Màn hình 6: Kết quả đóng phiên cứu hộ & Nhật ký kiểm toán (sos_logs)
    └── rescuer/
        ├── RescuerHomeActivity.java       # Màn hình 7: Hàng đợi cứu hộ (GET /api/rescuer/queue) & kiểm tra RB05
        ├── SosRequestAdapter.java         # Adapter hiển thị danh sách yêu cầu SOS đang WAITING
        ├── SosDetailActivity.java         # Màn hình 8: Chi tiết yêu cầu SOS (GET /api/sos/{id}) & Nhận ca
        ├── RealtimeTrackingActivity.java  # Màn hình 9: Bản đồ điều phối & Cập nhật tiến trình tuần tự
        └── ConfirmCompleteActivity.java   # Màn hình 10: Xác nhận hoàn thành ca (POST /complete & RB09)
```

---

## 2. Các Điểm Bất Hợp Lý Của Thiết Kế Cũ Đã Được Khắc Phục

1. **Màn hình Đăng nhập & OTP (`activity_login.xml`):**
   - **Trước khi sửa:** Để phần nhập 6 số OTP nằm ngay bên dưới ô nhập số điện thoại trên cùng một màn hình ngay cả khi chưa bấm gửi mã; bắt người dùng đăng nhập xong mới hiện popup hỏi là Người dân hay Đội cứu hộ.
   - **Sau khi sửa:**
     - Đưa phần **Chọn vai trò (`Người dân - USER` / `Đội cứu hộ - RESCUER`) lên đầu Bước 1** trước khi đăng nhập.
     - Bổ sung ô nhập **Họ và tên hiển thị (`full_name`)** đúng chuẩn payload `POST /api/auth/verify-otp`.
     - **Tách thành 2 bước độc lập (`layoutStepPhone` và `layoutStepOtp`):** Ban đầu ẩn hoàn toàn khu vực nhập OTP (`visibility="gone"`). Khi bấm *"Nhận mã OTP qua Telegram"*, Bước 1 ẩn đi và Bước 2 mới hiện ra, kèm nút *"Đổi SĐT"*, giới hạn **5 lần nhập sai** và đếm ngược **30 giây** chống spam (`429 Too Many Requests`). Xác thực xong đi thẳng vào màn hình chính của vai trò đã chọn.

2. **Màn hình Tạo yêu cầu SOS (`activity_send_sos.xml`):**
   - **Trước khi sửa:** Có cụm 6 nút cộng/trừ đếm số Người lớn, Người già, Trẻ em không hề tồn tại trong DTO của API `POST /api/sos`; ghi sai bán kính làm tròn vị trí cộng đồng (`~200m` thay vì `~1km`).
   - **Sau khi sửa:** Chuẩn hóa theo đúng 5 trường dữ liệu đầu vào của `POST /api/sos`:
     - `initial_lat`, `initial_lng` (chạm lên bản đồ `mapPreview` để ghim hoặc bấm lấy lại GPS).
     - `emergency_type`: Sử dụng đúng mã chuẩn (`NGAP_LUT`, `Y_TE`, `MAC_KET`, `NHU_YEU_PHAM`, `CHAY_NO`).
     - `description`: Ô nhập mô tả thực tế kèm các Chip chèn nhanh (`+ Nước ngập tầng 1`, `+ Cần xuồng sơ tán`, `+ Có người già & trẻ nhỏ`).
     - `visibility`: `PUBLIC` (hiển thị vùng cảnh báo làm tròn 2 chữ số thập phân `~1km` trên `/api/sos/public/map`) hoặc `PRIVATE` (ẩn khỏi bản đồ cộng đồng, chỉ `RESCUER` xem được).

3. **Màn hình Trang chủ Người dân (`activity_home.xml`):**
   - **Trước khi sửa:** Có thanh tìm kiếm trạm y tế giả và không kiểm tra xem người dùng có đang có SOS nào hoạt động hay không.
   - **Sau khi sửa:**
     - Bổ sung thẻ cảnh báo **SOS đang hoạt động (`GET /api/sos/my-active`)** ở đầu bản đồ để người dùng bấm vào theo dõi ngay.
     - Kiểm tra **Ràng buộc RB04 (`409 Conflict`)**: Mỗi nạn nhân chỉ có tối đa 1 yêu cầu SOS đang hoạt động tại một thời điểm.
     - Vẽ các vùng cảnh báo `PUBLIC` trên bản đồ tại tọa độ làm tròn 2 chữ số (`area_lat`, `area_lng` ~1km).

4. **Màn hình Theo dõi SOS (`activity_track_sos.xml`) & Kết quả (`activity_sos_result.xml`):**
   - **Trước khi sửa:** Thanh tiến trình 5 bước tự chế, luôn hiển thị sẵn tên đội cứu hộ giả ngay cả khi mới gửi yêu cầu (`WAITING`); màn hình Kết quả lại có 2 nút tab cho người dùng tự bấm chuyển qua lại giữa *"Đã hoàn thành"* và *"Đã hủy"*, kèm mục đánh giá 5 sao không có trong API.
   - **Sau khi sửa:**
     - Thanh tiến trình chuẩn 4 bước State Machine: `1. WAITING` ➔ `2. ACCEPTED` ➔ `3. RESCUING` ➔ `4. ARRIVED`.
     - Khi đang `WAITING`, hiển thị trạng thái chờ trong `rescue_queue` (tự hủy sau 2h theo **RB07**). Khi có đội nhận ca mới hiển thị thông tin `rescuer` (`team_name`, `full_name`, `phone`) và vẽ lộ trình trên bản đồ.
     - Bổ sung nút **"⚡ Mô phỏng WS"** để test nhanh các sự kiện WebSocket (`SOS_ACCEPTED`, `STATUS_CHANGED`, `SOS_COMPLETED`).
     - Màn hình Kết quả (`activity_sos_result.xml`) bỏ hoàn toàn 2 tab chuyển đổi và bỏ phần đánh giá 5 sao; tự động hiển thị theo kết quả thực tế (`COMPLETED` hoặc `CANCELLED`), hiển thị lý do đóng (`end_reason`), thông báo xóa `location_current` (**RB09**) và danh sách **Nhật ký kiểm toán (`sos_logs` - `GET /api/sos/{id}/logs`)**.

5. **Các màn hình Đội cứu hộ (`RescuerHomeActivity`, `SosDetailActivity`, `RealtimeTrackingActivity`, `ConfirmCompleteActivity`):**
   - **Trước khi sửa:** Có công tắc trực chiến giả, bán kính quét giả, và hàng loạt trường bịa đặt không có trong DB (*"Chỉ đạo từ Tổng đài 114"*, *"Cảnh báo dây điện hạ thế chùng"*, *"Bình oxy 10L bắt buộc"*, *"Đã kết nối 115"*); cho phép bấm *"Hoàn thành ca"* nhảy cóc bất kỳ lúc nào.
   - **Sau khi sửa:**
     - Hiển thị đúng dữ liệu từ `GET /api/rescuer/queue`, `GET /api/rescuer/my-active`, `GET /api/sos/{id}` và `GET /api/sos/{id}/logs`.
     - Kiểm tra **Ràng buộc RB05 (`409 Conflict`)**: Đội cứu hộ chỉ được nhận 1 ca tại một thời điểm.
     - Tuân thủ nghiêm ngặt quy trình cập nhật trạng thái tuần tự của `POST /api/rescuer/sos/{id}/status`: **`ACCEPTED`** ➔ **`RESCUING`** ➔ **`ARRIVED`** ➔ **`COMPLETED`** (chặn nhảy cóc trạng thái, và khóa nút **"Nhả ca (`POST /release`)"** khi đã ở trạng thái `ARRIVED`).

---

## 3. Ánh Xạ Chi Tiết 10 Màn Hình Với Hệ Thống API

| Màn hình | Layout XML & Activity Java | API & Ràng buộc được mô phỏng |
| :---: | :--- | :--- |
| **01** | `activity_splash.xml`<br/>`SplashActivity.java` | • `GET /health` (Kiểm tra kết nối máy chủ)<br/>• `GET /api/auth/me` (Kiểm tra phiên đăng nhập) |
| **02** | `activity_login.xml`<br/>`LoginActivity.java` | • `POST /api/auth/dev-link-telegram`<br/>• `POST /api/auth/request-otp` (Giới hạn 30s `429`)<br/>• `POST /api/auth/verify-otp` (`phone`, `otp`, `full_name`, giới hạn 5 lần thử) |
| **03** | `activity_home.xml`<br/>`HomeActivity.java` | • `GET /api/sos/public/map` (Làm tròn `area_lat`, `area_lng` 2 chữ số ~1km)<br/>• `GET /api/sos/my-active` (Kiểm tra **RB04**)<br/>• `GET / PUT /api/users/profile` (Sửa `full_name`, chặn sửa `role`)<br/>• `GET /api/sos/notifications/my` & `PUT /read` |
| **04** | `activity_send_sos.xml`<br/>`SendSosActivity.java` | • `POST /api/sos` (`emergency_type`, `description`, `visibility`, `initial_lat`, `initial_lng`)<br/>• Kiểm tra hợp lệ tọa độ (`422`) & kiểm tra **RB04** (`409`) |
| **05** | `activity_track_sos.xml`<br/>`TrackSosActivity.java` | • `GET /api/sos/my-active` & `WS /ws` (`SUBSCRIBE_SOS`)<br/>• `GET /api/location/{sos_id}/current` & `/trail`<br/>• `PUT /api/sos/{id}/visibility` (`PUBLIC` ↔ `PRIVATE`)<br/>• `POST /api/sos/{id}/cancel` & `GET /api/sos/{id}/logs` |
| **06** | `activity_sos_result.xml`<br/>`SosResultActivity.java` | • Hiển thị kết quả `COMPLETED` (`RESCUER_COMPLETED`) hoặc `CANCELLED` (`USER_CANCELLED`)<br/>• Thực thi **RB09** (Xóa `location_current`, giữ `location_history`)<br/>• `GET /api/sos/{id}/logs` |
| **07** | `activity_rescuer_home.xml`<br/>`item_sos_request.xml`<br/>`RescuerHomeActivity.java` | • `GET /api/rescuer/queue` (Danh sách `WAITING` < 2h theo **RB07**)<br/>• `GET /api/rescuer/my-active` (Kiểm tra **RB05**)<br/>• `GET /api/rescuer/teams` |
| **08** | `activity_sos_detail.xml`<br/>`SosDetailActivity.java` | • `GET /api/sos/{id}` & `GET /api/location/{sos_id}/current`<br/>• `GET /api/sos/{id}/logs`<br/>• `POST /api/rescuer/sos/{id}/accept` (Cập nhật nguyên tử **RB06** & kiểm tra **RB05**) |
| **09** | `activity_realtime_tracking.xml`<br/>`RealtimeTrackingActivity.java` | • `POST /api/rescuer/sos/{id}/status` (`ACCEPTED` ➔ `RESCUING` ➔ `ARRIVED`)<br/>• `POST /api/rescuer/sos/{id}/release` (Chỉ cho phép trước khi `ARRIVED`)<br/>• `POST /api/location/{sos_id}` (Cập nhật GPS định kỳ & vẽ Polyline) |
| **10** | `activity_confirm_complete.xml`<br/>`ConfirmCompleteActivity.java` | • `POST /api/rescuer/sos/{id}/complete` (Yêu cầu trạng thái `ARRIVED`)<br/>• Thực thi **RB09** (Xóa `location_current`) & **RB05** (Giải phóng đội cứu hộ) |

---

## 4. Tích Hợp Bản Đồ MapLibre (`MapHelper.java`)

- **Khởi tạo an toàn:** Luôn gọi `MapHelper.initMapLibre(this)` trước `setContentView(...)` trong mọi Activity có chứa `<org.maplibre.android.maps.MapView>`.
- **4 lớp bản đồ trực quan (không cần API Key):**
  1. *Bản đồ Đường phố Cứu hộ (Carto Voyager / OSM Raster)* – Mặc định, tốc độ tải nhanh và ổn định.
  2. *Bản đồ Vector 3D (OpenFreeMap Liberty)*.
  3. *Bản đồ Vệ tinh Thực địa (Esri World Imagery)*.
  4. *Bản đồ Tác chiến Đêm (Carto Dark Matter)*.
- **Vẽ trực quan bằng Canvas & Geometry:**
  - Tạo icon ghim (`Icon`) động bằng `Canvas` với nhãn phân biệt rõ: `BẠN`, `VICTIM`, `RESCUER`, `PUBLIC`.
  - Vẽ vùng cảnh báo ngập lụt / vùng làm tròn tọa độ `PUBLIC` bằng `PolygonOptions`.
  - Vẽ đường lộ trình di chuyển (`trail`) giữa `RESCUER` và `VICTIM` bằng `PolylineOptions`.
  - Chống trôi thao tác vuốt bản đồ bên trong `ScrollView` thông qua `MapHelper.enableScrollViewTouch(mapView)`.

---

## 5. Hướng Dẫn Chạy & Kiểm Thử Trong Android Studio

1. Mở thư mục `D:\JavaFinal` trong **Android Studio**.
2. Bấm **Sync Project with Gradle Files** để đồng bộ cấu trúc code.
3. Chọn máy ảo **Android Emulator** (hoặc thiết bị thật) và bấm **Run `▶` (`Shift + F10`)**.
4. **Mẹo kiểm thử nhanh:**
   - Tại màn hình Đăng nhập (Bước 1), chọn tab **Người dân (`USER`)** hoặc **Đội cứu hộ (`RESCUER`)** để trải nghiệm từng luồng.
   - Tại màn hình Theo dõi SOS của Người dân (`TrackSosActivity`), bấm nút **"⚡ Mô phỏng WS"** để xem tiến trình tự động chuyển qua các bước `WAITING` ➔ `ACCEPTED` ➔ `RESCUING` ➔ `ARRIVED` ➔ `COMPLETED`.
   - Tại bất kỳ màn hình chính nào, bấm vào nút **Hồ sơ (icon người ở góc trên bên phải)** ➔ chọn **"Test 10 Màn hình"** nếu muốn nhảy nhanh tới bất kỳ màn hình nào từ `1 -> 10`.
