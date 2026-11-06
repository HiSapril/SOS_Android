# HỆ THỐNG CỨU HỘ KHẨN CẤP (SOS RESCUE)

Dự án môn học Lập trình di động: Hệ thống ứng dụng kết nối cứu trợ cứu nạn khẩn cấp gồm **Frontend (Android Native)** và **Backend (FastAPI + PostgreSQL + WebSocket + Telegram Bot)**.

---

## 📁 Cấu Trúc Toàn Bộ Dự Án (Monorepo)

```
FINAL/
├── app/                              # Frontend: Ứng dụng Android Native (Java/Kotlin)
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/javafinal/   # Mã nguồn nghiệp vụ Android
│   │   │   │   ├── model/            # DTOs, Data Models (Auth, SOS, Location, WebSocket...)
│   │   │   │   ├── network/          # ApiClient, WebSocket, SessionStore, Protocol
│   │   │   │   ├── ui/               # Màn hình UI (auth, splash, user, rescuer)
│   │   │   │   ├── util/             # Tiện ích bổ trợ (DateTime, Geocoding...)
│   │   │   │   └── viewmodel/        # ViewModel xử lý logic màn hình
│   │   │   ├── res/                  # Layouts XML, Drawables, Values, Styles, Menus
│   │   │   └── AndroidManifest.xml   # Khai báo Permissions, Activities, App Theme
│   │   ├── test/                     # Unit Tests Frontend
│   │   └── androidTest/              # Instrumented Tests
│   ├── build.gradle.kts              # Cấu hình build & dependencies của app
│   └── proguard-rules.pro
│
├── backend/                          # Backend: FastAPI RESTful API & WebSocket
│   ├── database/                     # Kết nối cơ sở dữ liệu PostgreSQL & Migration
│   ├── routes/                       # Router endpoints (auth, sos, rescuer, location, users...)
│   ├── services/                     # Business services (WebSocket manager, Telegram bot, OTP, scheduler)
│   ├── config.py                     # Cấu hình biến môi trường
│   ├── main.py                       # Điểm khởi chạy FastAPI Server
│   ├── requirements.txt              # Thư viện Python phụ thuộc
│   ├── .env.example                  # Mẫu cấu hình môi trường (.env)
│   └── test_*.py, verify_deep.py     # Bộ kiểm thử tự động toàn diện backend
│
├── docs/                             # Toàn bộ tài liệu kỹ thuật & đồ án
│   ├── BANG_API_VA_LUONG_HOAT_DONG.md           # Đặc tả 100% API & luồng nghiệp vụ
│   ├── KET_QUA_TRIEN_KHAI_BACKEND.md            # Báo cáo kết quả kiểm thử Backend
│   ├── HUONG_DAN_VIEWMODEL.md                   # Hướng dẫn kiến trúc ViewModel cho Android
│   ├── TICH_HOP_API_GIAO_DIEN.md                # Hướng dẫn kết nối API vào giao diện
│   ├── TONG_HOP_TRIEN_KHAI_GIAO_DIEN_ANDROID.md # Tổng kết triển khai giao diện
│   ├── so_do_csdl.jpg                           # Sơ đồ quan hệ cơ sở dữ liệu (ERD)
│   └── Nhom1_Chuong1.docx                       # Thuyết minh báo cáo đồ án
│
├── gradle/                           # Gradle Wrapper & Version Catalog (libs.versions.toml)
├── build.gradle.kts                  # Cấu hình Gradle Root Project
├── settings.gradle.kts               # Cấu hình Project Modules
├── gradle.properties                 # Tham số JVM, AndroidX, API_BASE_URL
├── gradlew / gradlew.bat             # Script chạy Gradle tự động
└── README.md                         # Hướng dẫn cài đặt và sử dụng
```

---

## 🚀 Hướng Dẫn Khởi Chạy

### 1. Khởi Chạy Backend (FastAPI + PostgreSQL)

1. **Chuẩn bị Database**:
   - Đảm bảo PostgreSQL đang chạy (mặc định database `sos_db`, port `5432`).
   - Cấu hình file `backend/.env` (tham khảo `backend/.env.example`):
     ```env
     DATABASE_URL=postgresql://postgres:your_password@127.0.0.1:5432/sos_db
     JWT_SECRET=your_secret_key
     TELEGRAM_BOT_TOKEN=your_bot_token
     ```

2. **Cài đặt thư viện & chạy**:
   ```bash
   cd backend
   pip install -r requirements.txt
   python main.py
   ```
   Server sẽ lắng nghe tại `http://127.0.0.1:8000` (Swagger UI tại `http://127.0.0.1:8000/docs`).

3. **Chạy kiểm thử Backend**:
   ```bash
   python test_backend.py
   python test_emergency_types_crud.py
   python test_erd_10_tables.py
   python verify_deep.py
   ```

---

### 2. Khởi Chạy Frontend (Android App)

1. **Mở dự án trong Android Studio**:
   - Khởi động Android Studio -> **Open** -> Chọn thư mục gốc `FINAL`.
   - Android Studio sẽ tự động nhận diện module `:app` và sync Gradle với SDK đã cấu hình trong `local.properties`.

2. **Cấu hình Địa chỉ API Backend**:
   - Mặc định kết nối tới máy chủ qua `http://10.0.2.2:8000/` (IP nội bộ của Android Emulator nối tới máy tính host).
   - Nếu test trên máy thật (Physical Device), chỉnh sửa IP LAN máy tính trong `gradle.properties`:
     ```properties
     API_BASE_URL=http://<IP_LAN_MAY_TINH>:8000/
     ```

3. **Build APK hoặc Run trực tiếp**:
   - **Chạy trực tiếp từ dòng lệnh**:
     ```bash
     # Compile source code
     ./gradlew compileDebugJavaWithJavac

     # Chạy Unit Tests
     ./gradlew testDebugUnitTest

     # Build gói Debug APK
     ./gradlew assembleDebug
     ```
   - File APK đầu ra được tạo tại: `app/build/outputs/apk/debug/app-debug.apk`.
   - Hoặc nhấn nút **Run 'app'** (`Shift + F10`) trong Android Studio.

---

## 🛠️ Công Nghệ Sử Dụng

- **Frontend**: Java, Android SDK 36 (minSdk 24), Android Jetpack (ViewModel, LiveData, ViewBinding), OkHttp 4, Gson, MapLibre Native SDK, Google Play Services Location.
- **Backend**: Python 3.12, FastAPI, PostgreSQL (asyncpg / psycopg2), WebSocket Realtime, Pydantic, python-jose (JWT), python-telegram-bot.
