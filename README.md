# HỆ THỐNG CỨU HỘ KHẨN CẤP (SOS RESCUE)

Dự án môn học Lập trình di động: Hệ thống ứng dụng kết nối cứu hộ cứu nạn khẩn cấp.

## Phân Chia Thành Phần Dự Án

```
FINAL/
├── app/                  # Module Ứng dụng Android (Khung Android Studio chuẩn để người làm Frontend code)
│   ├── src/main/java/com/example/myapplication/MainActivity.java
│   └── src/main/res/layout/activity_main.xml
├── backend/              # Mã nguồn Backend API (Phần bạn phụ trách: FastAPI + PostgreSQL + Telegram Bot)
│   ├── routes/           # Các router API (auth, sos, rescuer, location, users)
│   ├── services/         # Nghiệp vụ backend, WebSocket, Telegram Bot
│   ├── database/         # Kết nối cơ sở dữ liệu PostgreSQL
│   ├── main.py           # Điểm khởi chạy FastAPI Server
│   └── requirements.txt  # Thư viện Python
├── docs/                 # Tài liệu đặc tả API và báo cáo phục vụ cả nhóm
│   ├── BANG_API_VA_LUONG_HOAT_DONG.md   # Đặc tả toàn bộ API để Frontend gọi
│   ├── KET_QUA_TRIEN_KHAI_BACKEND.md    # Kết quả kiểm thử backend
│   └── Nhom1_Chuong1.docx               # Báo cáo đồ án
├── gradle/               # Gradle wrapper của Android Studio
└── README.md
```
