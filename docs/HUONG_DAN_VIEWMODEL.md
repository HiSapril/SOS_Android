# Tầng ViewModel gọi API

Đã bổ sung `com.javafinal.viewmodel` cho toàn bộ **32 API** trong
`BANG_API_VA_LUONG_HOAT_DONG.md`, cùng `PUT /api/users/profile` tương thích ngược.
Các DTO trong `model` được tái sử dụng. `network` dùng OkHttp và Gson đã có trong project.

## Khởi tạo và gọi từ Activity/Fragment

```java
ApiViewModelFactory factory = ApiViewModelFactory.getDefault(getApplicationContext());
SosViewModel vm = new ViewModelProvider(this, factory).get(SosViewModel.class);

vm.loadMyActive().observe(this, state -> {
    switch (state.getStatus()) {
        case LOADING:
            // Hiện loading, khóa nút gửi nếu cần.
            break;
        case SUCCESS:
            if (state.getData().isActive()) {
                SosRequest request = state.getData().getSos();
                // Render ca đang hoạt động.
            } else {
                // Người dùng chưa có SOS đang hoạt động.
            }
            break;
        case ERROR:
            ApiError error = state.getError();
            // error.getMessage(): hiển thị thông báo từ backend.
            // error.isUnauthorized(): chuyển sang đăng nhập.
            // error.isConflict(): tải lại ca/hàng đợi để lấy trạng thái mới.
            break;
    }
});
```

Import `androidx.lifecycle.ViewModelProvider`, `com.javafinal.viewmodel.*`,
`com.javafinal.network.ApiError` và DTO cần sử dụng từ `com.javafinal.model`.
Gọi các method và observe từ **main thread**. Mỗi lần gọi trả về một `LiveData<ApiState<T>>`
riêng, tránh kết quả API khác ghi đè nhau. Request được khởi chạy ngay khi gọi method.
`ViewModelProvider` giữ ViewModel qua lần xoay màn hình; UI nên giữ tham chiếu LiveData của
request đang cần theo dõi trong ViewModel của màn hình nếu muốn bind lại sau lần tạo Activity mới.
Không gọi mutation tự động trong mỗi lần `onCreate()` vì có thể gửi trùng khi xoay màn hình.

Factory mặc định dùng `http://10.0.2.2:8000/` cho Android Emulator. Khi chạy máy thật,
tạo **một factory dùng chung toàn app** với địa chỉ LAN của backend:

```java
ApiViewModelFactory factory = new ApiViewModelFactory(applicationContext, "http://192.168.1.10:8000/");
```

Không tạo factory mới cho từng request hoặc từng màn hình: các ViewModel cần dùng chung
`SessionStore` để cập nhật token và tài khoản nhất quán. Có constructor nhận `ApiClient`
để tiêm transport/session khi kiểm thử.

## Ánh xạ toàn bộ endpoint

| API | ViewModel.method | Endpoint |
| --- | --- | --- |
| 01 | `AuthViewModel.requestOtp` | `POST /api/auth/request-otp` |
| 02 | `AuthViewModel.verifyOtp` | `POST /api/auth/verify-otp` |
| 03 | `AuthViewModel.loadMe` | `GET /api/auth/me` |
| 04 | `AuthViewModel.devLinkTelegram` | `POST /api/auth/dev-link-telegram` |
| 05 | `UserViewModel.loadProfile` | `GET /api/users/profile` |
| 06 | `UserViewModel.updateFullName` | `PUT /api/users/full-name` |
| tương thích | `UserViewModel.updateProfile` | `PUT /api/users/profile` |
| 07 | `SosViewModel.createSos` | `POST /api/sos` |
| 08 | `SosViewModel.loadMyActive` | `GET /api/sos/my-active` |
| 09 | `SosViewModel.loadPublicMap` | `GET /api/sos/public/map` |
| 10 | `SosViewModel.loadDetail` | `GET /api/sos/{id}` |
| 11 | `SosViewModel.updateVisibility` | `PUT /api/sos/{id}/visibility` |
| 12 | `SosViewModel.cancelSos` | `POST /api/sos/{id}/cancel` |
| 13 | `SosViewModel.loadLogs` | `GET /api/sos/{id}/logs` |
| 14 | `NotificationViewModel.loadMyNotifications` | `GET /api/sos/notifications/my` |
| 15 | `NotificationViewModel.markAsRead` | `PUT /api/sos/notifications/{id}/read` |
| 16 | `RescuerViewModel.loadQueue` | `GET /api/rescuer/queue` |
| 17 | `RescuerViewModel.loadMyActive` | `GET /api/rescuer/my-active` |
| 18 | `RescuerViewModel.acceptSos` | `POST /api/rescuer/sos/{id}/accept` |
| 19 | `RescuerViewModel.updateMissionStatus` | `POST /api/rescuer/sos/{id}/status` |
| 20 | `RescuerViewModel.releaseSos` | `POST /api/rescuer/sos/{id}/release` |
| 21 | `RescuerViewModel.completeSos` | `POST /api/rescuer/sos/{id}/complete` |
| 22 | `RescuerViewModel.loadTeams` | `GET /api/rescuer/teams` |
| 23 | `LocationViewModel.updateLocation` | `POST /api/location/{sos_id}` |
| 24 | `LocationViewModel.loadCurrent` | `GET /api/location/{sos_id}/current` |
| 25 | `LocationViewModel.loadTrail` | `GET /api/location/{sos_id}/trail` |
| 26 | `RealtimeViewModel.connect/subscribeSos/ping/disconnect` | `WS /ws?token={jwt}` |
| 27 | `EmergencyTypeViewModel.loadAll` | `GET /api/emergency-types` |
| 28 | `EmergencyTypeViewModel.loadDetail` | `GET /api/emergency-types/{id}` |
| 29 | `EmergencyTypeViewModel.create` | `POST /api/emergency-types` |
| 30 | `EmergencyTypeViewModel.update` | `PUT /api/emergency-types/{id}` |
| 31 | `EmergencyTypeViewModel.delete` | `DELETE /api/emergency-types/{id}` |
| 32 | `SystemViewModel.checkHealth` | `GET /health` |

## Logic và vòng đời

- OTP kiểm tra số điện thoại, vai trò và mã 6 số. `verifyOtp()` lưu token và tài khoản
  sau phản hồi hợp lệ. Vai trò lấy từ server. Cập nhật họ tên dùng API riêng sau đăng nhập.
  Giới hạn 30 giây/5 lần thử/5 phút do backend quyết định; lỗi và header `Retry-After`
  được trả về UI, không tự động gửi lại OTP.
- `loadMe()` và các API hồ sơ cập nhật tài khoản trong session. `AuthViewModel.logout()`
  hủy request auth đang chờ, xóa session và ngắt WebSocket. Token lưu trong preferences riêng,
  được loại khỏi backup và device transfer. Response 401 của token cũ không xóa phiên mới.
- Validation trước request: trường bắt buộc, visibility, tọa độ hữu hạn trong khoảng hợp lệ,
  accuracy không âm, actor `VICTIM`/`RESCUER`, limit lộ trình từ 1 đến 500 (mặc định 100).
- `SosRules` hỗ trợ UI bật/tắt nhận ca, nhả ca, hoàn thành và chuyển trạng thái.
  Overload `updateMissionStatus(SosRequest, status)` kiểm tra ngay trình tự
  `ACCEPTED → RESCUING → ARRIVED`. Overload nhận ID để server kiểm tra trạng thái mới nhất.
  RB04/RB05/RB06, quyền sở hữu, phân quyền và RB07 được backend kiểm tra;
  ViewModel giữ mã 403/409 để UI xử lý đúng.
- Không cache/sửa giả lập ca cứu hộ trên client sau mutation. UI dùng response và tải lại
  chi tiết, ca đang hoạt động, hàng đợi, thông báo hoặc vị trí khi cần. Khi nhận trạng thái
  `COMPLETED`/`CANCELLED`, UI/service phải dừng chu kỳ gửi GPS và xóa marker hiện tại (RB09).
  ViewModel không tự khởi chạy GPS hoặc background service.
- Mọi HTTP callback được chuyển về main thread. Mỗi request có `LOADING/SUCCESS/ERROR`.
  Lỗi gồm HTTP (kể cả `detail` dạng mảng của FastAPI 422), mạng, JSON, nghiệp vụ và hủy.
  `active=false, sos=null` là thành công. DELETE trả 204 được chấp nhận.
- `cancelRequests()` hủy các request của ViewModel, trả lỗi `CANCELLED`; `onCleared()` tự gọi.
  Không giữ tham chiếu Activity và không tự retry các HTTP mutation để tránh gửi trùng.

## WebSocket

```java
RealtimeViewModel realtime = new ViewModelProvider(this, factory).get(RealtimeViewModel.class);
realtime.getConnectionState().observe(this, state -> { /* render trạng thái kết nối */ });
realtime.getErrors().observe(this, error -> { /* error có thể null sau reconnect thành công */ });
realtime.getMessages().observe(this, message -> {
    if (message == null) return;
    switch (message.getEvent()) {
        case WebSocketMessage.EVENT_LOCATION_BROADCAST:
            // Cập nhật marker từ message.getLat()/getLng(), phân biệt getActor().
            break;
        case WebSocketMessage.EVENT_STATUS_CHANGED:
        case WebSocketMessage.EVENT_SOS_COMPLETED:
        case WebSocketMessage.EVENT_SOS_CANCELLED:
            // Tải lại chi tiết và xử lý kết thúc theo trạng thái server.
            break;
        default:
            // NEW_SOS/QUEUE_UPDATED: tải lại hàng đợi khi cần.
            break;
    }
});
realtime.subscribeSos(sosId);
realtime.connect();
```

Gói gửi dùng `type` theo mục 3.5 của đặc tả. Gói nhận chấp nhận cả `type` và `event`,
chuẩn hóa vào `WebSocketMessage.getEvent()` mà không sửa model hiện có.
Heartbeat JSON mỗi 25 giây, reconnect với thời gian chờ 1/2/4/8/16/30 giây và đăng ký lại phòng.
Phòng kết thúc được loại khỏi danh sách đăng ký lại. Không reconnect khi lỗi xác thực/phân quyền;
session thay đổi sẽ đóng socket và xóa thông điệp cũ. Gọi `disconnect()` khi không cần theo dõi;
`onCleared()` tự đóng socket và giải phóng scheduler. `getMessages()` là LiveData giữ gói mới nhất,
nên UI nên render/tải lại dữ liệu một cách idempotent khi observer được gắn lại.

## Phạm vi đặc tả và kiểm thử

`dev-link-telegram` chưa có body/response schema trong tài liệu: hiện gửi `phone` và
`telegram_chat_id` (suy ra từ bảng `users`), trả `JsonObject`. `/health` cũng trả `JsonObject`
để giữ đầy đủ các trường khi schema chưa được mô tả. Hai hợp đồng này cần đối chiếu backend
thật khi tích hợp. Phần còn lại dùng DTO đã có trong project.

Các Activity hiện đã gọi ViewModel để hiển thị dữ liệu API. `MockDataRepository` đã được xóa.
Xem [TICH_HOP_API_GIAO_DIEN.md](TICH_HOP_API_GIAO_DIEN.md) để cấu hình backend và kiểm thử các màn hình.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --console=plain
```

Test HTTP kiểm tra 32 route (31 HTTP API + route tương thích), method, header, JSON body,
query, validation, lỗi và session. Test WebSocket dùng server loopback để kiểm tra handshake,
token query, gói đăng ký/PING/PONG, các sự kiện, reconnect, đăng ký lại phòng và logout.
Không gọi backend thật hoặc gửi OTP thật trong test.
