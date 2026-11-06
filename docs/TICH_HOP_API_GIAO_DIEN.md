# Giao diện dùng API thật

Toàn bộ 10 màn hình và các hộp thoại hồ sơ, đội cứu hộ, thông báo đã kết nối ViewModel.
APK chính không có kho dữ liệu mock, OTP mặc định, tọa độ GPS ngẫu nhiên, vị trí đội cứu hộ
cố định hoặc mô phỏng di chuyển dọc lộ trình. Dữ liệu mẫu XML chỉ dùng `tools:text` để xem
trong trình thiết kế; khi chạy, dữ liệu chưa tải được hiển thị bằng dấu `—`.

## Cấu hình backend

Mặc định `BuildConfig.API_BASE_URL` là `http://10.0.2.2:8000/` cho Android Emulator.
Máy chạy FastAPI phải mở cổng 8000; máy thật cần địa chỉ LAN mà điện thoại truy cập được.
Có thể đặt trong `gradle.properties` cá nhân hoặc truyền khi build:

```powershell
.\gradlew.bat :app:assembleDebug -PAPI_BASE_URL=http://192.168.1.10:8000/
```

`ApiViewModelFactory.getDefault()` đọc cấu hình này, chia sẻ client và phiên cho toàn ứng dụng.
Không có cơ chế tự chuyển sang dữ liệu giả khi server lỗi. Giao diện hiện thông báo và nút
Thử lại. Token hết hạn sẽ xóa phiên và chuyển về đăng nhập.

## Dữ liệu và thao tác trên từng màn hình

| Màn hình | Dữ liệu / thao tác API |
| --- | --- |
| Splash | Kiểm tra `/health`, xác thực `/auth/me`, mở trang chủ theo vai trò server trả về |
| Đăng nhập | Gửi OTP Telegram, nhập mã thật, xác thực và lưu JWT; không điền sẵn số điện thoại hay mã OTP |
| Trang chủ người dân | Bản đồ cộng đồng dùng `area_lat/area_lng` đã làm mờ, banner SOS đang hoạt động; không tự tính lại tọa độ công khai từ vị trí chính xác |
| Gửi SOS | Danh mục loại sự cố từ server, GPS thiết bị hoặc điểm được chọn trên bản đồ, mô tả và visibility; chỉ điều hướng sau khi tạo thành công; 409 tải lại ca đang hoạt động |
| Theo dõi SOS | Chi tiết ca, hai vị trí hiện tại, trail của cứu hộ; đổi visibility/hủy qua API; thông tin đội chỉ hiển thị theo dữ liệu trả về |
| Kết quả SOS | Đọc lại chi tiết, trạng thái đóng, `updated_at`, `end_reason` và nhật ký `/logs`; không tự suy đoán hoàn thành khi thiếu dữ liệu |
| Trang chủ cứu hộ | Hàng đợi, số lượng công khai/riêng tư, ca đang xử lý; lọc danh sách đã tải, nhận ca qua API và xử lý 409 |
| Chi tiết SOS | Đọc chi tiết theo `SOS_ID`; liên hệ nạn nhân, nhận ca nếu đang WAITING; không tự nhận ca khi mở màn hình |
| Điều phối realtime | Xác minh ca hiện tại của đội, GPS thật và sự kiện WS; cập nhật ACCEPTED → RESCUING → ARRIVED; nhả ca qua server |
| Xác nhận hoàn thành | Kiểm tra ca đang xử lý phải ARRIVED, người dùng tích xác nhận, POST complete rồi trở lại hàng đợi |
| Hồ sơ | GET profile, PUT full-name; chỉ cập nhật UI sau khi server xác nhận; đăng xuất xóa JWT |
| Đội cứu hộ | Hiển thị danh sách đội và thành viên từ `/rescuer/teams` |
| Thông báo | GET notifications, đánh dấu đã đọc bằng API, mở ca liên quan qua SOS_ID |

## GPS, bản đồ và vòng đời

- `DeviceLocation` xin quyền vị trí và dùng Fused Location Provider. Thiếu quyền/GPS thì
  hiển thị trạng thái tương ứng. Không thay bằng một tọa độ có sẵn.
- Chọn điểm trên màn hình Gửi SOS là lựa chọn chủ động của người dùng; GPS mới không ghi đè
  điểm đã chọn. Nút Lấy lại GPS quay về vị trí thiết bị.
- GPS chỉ được gửi khi màn hình theo dõi đang hiển thị, đã đọc lại ca còn hoạt động và
  (với cứu hộ) đã xác minh đây là ca của đội. Chu kỳ gửi tối thiểu 6 giây; location request
  mong muốn khoảng 7 giây. Dừng khi rời màn hình hoặc ca kết thúc. Chưa có theo dõi nền.
- WebSocket cập nhật vị trí và trạng thái; reconnect và mỗi 15 giây tải lại chi tiết/vị trí/trail
  để đồng bộ nếu bỏ lỡ sự kiện. COMPLETED/CANCELLED dừng gửi GPS, đóng kết nối và mở kết quả.
- `SosMapData` vẽ marker và polyline từ tọa độ/lịch sử API. Không dùng đường phố giả hoặc
  tự dịch chuyển marker. Khoảng cách hiển thị là Haversine (đường thẳng), không phải ETA
  hay khoảng cách di chuyển thực tế.
- `ScreenRequests` giữ request qua xoay màn hình, chặn gửi trùng khi đang chờ và đánh dấu
  mỗi kết quả thao tác chỉ xử lý một lần. Không tự khởi chạy POST khi khôi phục Activity.

## Kiểm thử

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

Unit test kiểm tra hợp đồng HTTP/WS, lỗi và phiên. Android instrumentation test kiểm tra
luồng đăng nhập, dữ liệu trang chủ, hồ sơ, thông báo, gửi SOS, realtime, hủy/nhật ký,
chuyển tiến trình và hoàn thành ca. Phản hồi kiểm thử chỉ có trong `androidTest`,
không nằm trong APK chính và không gửi OTP hoặc tạo ca trên backend thật.

Hai endpoint `/health` và `dev-link-telegram` chưa có schema đầy đủ trong tài liệu API;
giới hạn đó được mô tả trong hướng dẫn ViewModel. Đăng nhập thật cần tài khoản đã liên kết
Telegram với backend; app không tự liên kết tài khoản DEV.
