"""
verify_deep.py
Kịch bản kiểm tra chuyên sâu, kiểm thử biên (boundary test), phân quyền (security test),
xử lý tranh chấp đồng thời (race condition / concurrency) và kiểm tra toàn vẹn nghiệp vụ.
"""

import sys
import os
import asyncio
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone, timedelta
from fastapi.testclient import TestClient
import psycopg

# Đảm bảo in tiếng Việt chuẩn trên Windows console
if sys.stdout.encoding != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

from main import app
import config
from database.connection import get_db_connection
from services.auth_service import normalize_phone
from services.otp_service import otp_store
from services.scheduler import check_expired_sos

client = TestClient(app)

passed_checks = 0
failed_checks = 0

def check(assertion, message):
    global passed_checks, failed_checks
    if assertion:
        passed_checks += 1
        print(f"  [PASS] {message}")
    else:
        failed_checks += 1
        print(f"  [FAIL] {message}")
        assert False, message

def get_auth_token(phone: str, full_name: str = None, role: str = "USER") -> tuple[str, dict]:
    """Helper lấy token nhanh cho test"""
    # Đảm bảo liên kết telegram và gán role cho test
    client.post("/api/auth/dev-link-telegram", json={"phone": phone, "telegram_chat_id": abs(hash(phone)) % 100000000, "role": role})
    # Reset OTP cũ trong bộ nhớ RAM
    otp_store.clear(normalize_phone(phone))
    
    req = client.post("/api/auth/request-otp", json={"phone": phone, "role": role})
    otp = req.json().get("dev_otp")
    verify = client.post("/api/auth/verify-otp", json={"phone": phone, "otp": otp})
    data = verify.json()
    token = data["access_token"]
    user = data["user"]
    if full_name:
        prof_res = client.put("/api/users/full-name", json={"full_name": full_name}, headers={"Authorization": f"Bearer {token}"})
        if prof_res.status_code == 200:
            user = prof_res.json()["user"]
    return token, user

def run_deep_verification():
    print("\n" + "=" * 70)
    print(" BẮT ĐẦU KIỂM TRA CHUYÊN SÂU TOÀN DIỆN BACKEND")
    print("=" * 70)

    # ---------------------------------------------------------
    # 1. KIỂM TRA CHUẨN HÓA SỐ ĐIỆN THOẠI
    # ---------------------------------------------------------
    print("\n👉 PHẦN 1: Kiểm tra hàm chuẩn hóa số điện thoại (normalize_phone)")
    check(normalize_phone("+84912345678") == "0912345678", "Đầu +84 chuyển thành 0")
    check(normalize_phone("84912345678") == "0912345678", "Đầu 84 chuyển thành 0")
    check(normalize_phone("091-234-5678") == "0912345678", "Loại bỏ gạch nối")
    check(normalize_phone("(091) 234 5678") == "0912345678", "Loại bỏ ngoặc và khoảng trắng")

    # ---------------------------------------------------------
    # 2. KIỂM TRA BẢO MẬT & PHÂN QUYỀN (RBAC & ACCESS CONTROL)
    # ---------------------------------------------------------
    print("\n👉 PHẦN 2: Kiểm tra bảo mật & Phân quyền (RBAC & Access Control)")
    token_user_a, user_a = get_auth_token("0971000001", "User A")
    token_user_b, user_b = get_auth_token("0972000002", "User B")
    token_rescuer_1, rescuer_1 = get_auth_token("0901112222", role="RESCUER") # Rescuer Q1
    token_rescuer_2, rescuer_2 = get_auth_token("0903334444", role="RESCUER") # Rescuer Q2

    check(user_a["is_valid"] is True, "User A xác thực OTP thành công, có is_valid = True")
    check(rescuer_1["is_valid"] is True, "Rescuer 1 xác thực OTP thành công, có is_valid = True")

    headers_user_a = {"Authorization": f"Bearer {token_user_a}"}
    headers_user_b = {"Authorization": f"Bearer {token_user_b}"}
    headers_r1 = {"Authorization": f"Bearer {token_rescuer_1}"}
    headers_r2 = {"Authorization": f"Bearer {token_rescuer_2}"}

    # 2a. USER gọi API của RESCUER -> Bắt buộc 403 Forbidden
    r_queue_user = client.get("/api/rescuer/queue", headers=headers_user_a)
    check(r_queue_user.status_code == 403, "USER không được xem hàng đợi cứu hộ (403 Forbidden)")

    r_accept_user = client.post("/api/rescuer/sos/00000000-0000-0000-0000-000000000000/accept", headers=headers_user_a)
    check(r_accept_user.status_code == 403, "USER không được gọi API nhận ca (403 Forbidden)")

    # 2b. Token giả mạo hoặc không hợp lệ -> 401 Unauthorized
    bad_token = client.get("/api/auth/me", headers={"Authorization": "Bearer bad.token.here"})
    check(bad_token.status_code == 401, "Token giả mạo bị từ chối 401 Unauthorized")

    # 2c. Không gửi Token -> 403/401
    no_token = client.get("/api/auth/me")
    check(no_token.status_code in (401, 403), "Không có token bị từ chối")

    # ---------------------------------------------------------
    # 3. KIỂM TRA RÀNG BUỘC TỌA ĐỘ VÀ DỮ LIỆU ĐẦU VÀO
    # ---------------------------------------------------------
    print("\n👉 PHẦN 3: Kiểm tra ràng buộc hợp lệ dữ liệu (Pydantic & DB Check)")
    invalid_lat = client.post("/api/sos", json={
        "emergency_type": "TAI_NAN", "initial_lat": 95.0, "initial_lng": 106.0
    }, headers=headers_user_a)
    check(invalid_lat.status_code == 422, "Vĩ độ vượt quá 90 độ bị từ chối (422)")

    invalid_lng = client.post("/api/sos", json={
        "emergency_type": "TAI_NAN", "initial_lat": 10.0, "initial_lng": 200.0
    }, headers=headers_user_a)
    check(invalid_lng.status_code == 422, "Kinh độ vượt quá 180 độ bị từ chối (422)")

    empty_type = client.post("/api/sos", json={
        "emergency_type": "", "initial_lat": 10.0, "initial_lng": 106.0
    }, headers=headers_user_a)
    check(empty_type.status_code == 422, "Loại sự cố rỗng bị từ chối (422)")

    # ---------------------------------------------------------
    # 4. KIỂM TRA TẦN SUẤT GỬI MÃ (RATE LIMITING) & KHÓA OTP (LOCKOUT)
    # ---------------------------------------------------------
    print("\n👉 PHẦN 4: Kiểm tra giới hạn tần suất gửi OTP và khóa sau 5 lần nhập sai")
    test_rate_phone = "0988776655"
    client.post("/api/auth/dev-link-telegram", json={"phone": test_rate_phone, "telegram_chat_id": 88776655})
    # Xóa OTP cũ trong bộ nhớ RAM của số này để kiểm tra chính xác
    otp_store.clear(test_rate_phone)
    # Lần 1: Thành công
    otp1 = client.post("/api/auth/request-otp", json={"phone": test_rate_phone, "role": "USER"})
    check(otp1.status_code == 200, "Gửi OTP lần 1 thành công")

    # Lần 2 gửi liền: Bị 429 Too Many Requests
    otp2 = client.post("/api/auth/request-otp", json={"phone": test_rate_phone, "role": "USER"})
    check(otp2.status_code == 429, "Gửi OTP liên tiếp trong 30s bị chặn 429 Too Many Requests")

    # Thử nhập sai 5 lần để khóa OTP
    code_wrong = "999999"
    for i in range(4):
        res_fail = client.post("/api/auth/verify-otp", json={"phone": test_rate_phone, "otp": code_wrong})
        check(res_fail.status_code == 400, f"Nhập sai lần {i+1} bị từ chối")
    
    # Lần thứ 5 sai -> Vô hiệu hóa mã
    res_fail_5 = client.post("/api/auth/verify-otp", json={"phone": test_rate_phone, "otp": code_wrong})
    check("vô hiệu" in res_fail_5.json().get("detail", "").lower() or "5 lần" in res_fail_5.json().get("detail", "").lower(),
          "Nhập sai quá 5 lần: Mã OTP bị vô hiệu hóa hoàn toàn")

    # ---------------------------------------------------------
    # 5. KIỂM TRA TRANH CHẤP ĐỒNG THỜI (CONCURRENCY / RACE CONDITION)
    # ---------------------------------------------------------
    print("\n👉 PHẦN 5: Kiểm tra xử lý tranh chấp đồng thời (Concurrency / Race Condition)")
    
    # Dọn dẹp SOS cũ nếu có
    with get_db_connection() as conn:
        with conn.cursor() as cur:
            cur.execute("UPDATE sos_requests SET status = 'CANCELLED' WHERE requester_id = %s AND status NOT IN ('COMPLETED', 'CANCELLED')", (user_a["id"],))
            cur.execute("UPDATE sos_requests SET status = 'CANCELLED' WHERE status NOT IN ('COMPLETED', 'CANCELLED')")

    # 5a. User A gửi 2 yêu cầu SOS đồng thời -> Chỉ đúng 1 yêu cầu được ghi (RB04)
    def create_sos_task():
        return client.post("/api/sos", json={
            "emergency_type": "CHAY_NO",
            "description": "Cháy tầng trệt chung cư",
            "visibility": "PUBLIC",
            "initial_lat": 10.760000,
            "initial_lng": 106.690000
        }, headers=headers_user_a)

    with ThreadPoolExecutor(max_workers=2) as executor:
        f1 = executor.submit(create_sos_task)
        f2 = executor.submit(create_sos_task)
        res1 = f1.result()
        res2 = f2.result()

    statuses = [res1.status_code, res2.status_code]
    check(statuses.count(200) == 1 and statuses.count(409) == 1,
          "Tranh chấp tạo SOS đồng thời: Đúng 1 thành công (200) và 1 bị từ chối (409 RB04)")

    created_sos_id = (res1.json() if res1.status_code == 200 else res2.json())["sos"]["id"]

    # 5b. Hai đội cứu hộ (R1 và R2) cùng bấm "Nhận cứu hộ" đồng thời vào 1 ca -> Chỉ đúng 1 đội nhận được (RB06)
    def accept_sos_task(headers):
        return client.post(f"/api/rescuer/sos/{created_sos_id}/accept", headers=headers)

    with ThreadPoolExecutor(max_workers=2) as executor:
        f_r1 = executor.submit(accept_sos_task, headers_r1)
        f_r2 = executor.submit(accept_sos_task, headers_r2)
        res_r1 = f_r1.result()
        res_r2 = f_r2.result()

    accept_statuses = [res_r1.status_code, res_r2.status_code]
    check(accept_statuses.count(200) == 1 and accept_statuses.count(409) == 1,
          "Tranh chấp nhận ca đồng thời (Atomic Update RB06): Đúng 1 đội nhận được (200), đội kia báo 409")

    winning_rescuer_headers = headers_r1 if res_r1.status_code == 200 else headers_r2
    losing_rescuer_headers = headers_r2 if res_r1.status_code == 200 else headers_r1

    # ---------------------------------------------------------
    # 6. KIỂM TRA BẢO MẬT DỮ LIỆU RIÊNG TƯ & QUYỀN TRUY CẬP PHIÊN
    # ---------------------------------------------------------
    print("\n👉 PHẦN 6: Kiểm tra bảo mật dữ liệu riêng tư & Quyền xem chi tiết")
    # User B (không phải chủ SOS) cố tình xem chi tiết SOS của User A
    leak_test = client.get(f"/api/sos/{created_sos_id}", headers=headers_user_b)
    check(leak_test.status_code == 403, "User B không được xem thông tin chi tiết SOS của User A (403)")

    # User B cố tình gửi yêu cầu hủy SOS của User A
    cancel_hack = client.post(f"/api/sos/{created_sos_id}/cancel", headers=headers_user_b)
    check(cancel_hack.status_code == 404 or cancel_hack.status_code == 403, "User B không thể hủy SOS của User A")

    # Đội cứu hộ thua (losing rescuer) cố tình cập nhật trạng thái
    status_hack = client.post(f"/api/rescuer/sos/{created_sos_id}/status", json={"status": "RESCUING"}, headers=losing_rescuer_headers)
    check(status_hack.status_code == 403, "Đội cứu hộ không được gán không thể cập nhật tiến trình (403)")

    # ---------------------------------------------------------
    # 7. KIỂM TRA MÁY TRẠNG THÁI (STATE MACHINE) CHẶT CHẼ
    # ---------------------------------------------------------
    print("\n👉 PHẦN 7: Kiểm tra máy trạng thái tuần tự (State Machine Rules)")
    # Đang ACCEPTED mà nhảy cóc sang ARRIVED (phải qua RESCUING)
    skip_err = client.post(f"/api/rescuer/sos/{created_sos_id}/status", json={"status": "ARRIVED"}, headers=winning_rescuer_headers)
    check(skip_err.status_code == 400, "Chặn nhảy cóc trạng thái từ ACCEPTED sang ARRIVED (400)")

    # Chuyển đúng sang RESCUING
    step1 = client.post(f"/api/rescuer/sos/{created_sos_id}/status", json={"status": "RESCUING"}, headers=winning_rescuer_headers)
    check(step1.status_code == 200 and step1.json()["status"] == "RESCUING", "Cập nhật thành công sang RESCUING")

    # Đang RESCUING: Thử nhả ca (được phép nhả khi ACCEPTED hoặc RESCUING)
    rel_test = client.post(f"/api/rescuer/sos/{created_sos_id}/release", headers=winning_rescuer_headers)
    check(rel_test.status_code == 200 and rel_test.json()["status"] == "WAITING", "Nhả ca thành công khi đang RESCUING, trạng thái về WAITING")

    # Đội 2 nhận lại ca đã nhả
    re_accept = client.post(f"/api/rescuer/sos/{created_sos_id}/accept", headers=headers_r2)
    check(re_accept.status_code == 200, "Đội 2 nhận lại thành công ca đã nhả")

    # Đưa ca tới ARRIVED
    client.post(f"/api/rescuer/sos/{created_sos_id}/status", json={"status": "RESCUING"}, headers=headers_r2)
    step_arrived = client.post(f"/api/rescuer/sos/{created_sos_id}/status", json={"status": "ARRIVED"}, headers=headers_r2)
    check(step_arrived.status_code == 200 and step_arrived.json()["status"] == "ARRIVED", "Cập nhật thành công sang ARRIVED")

    # Sau khi đã ARRIVED: Nghiêm cấm Nhả ca (đã tới nơi rồi thì không được bỏ ca)
    rel_forbidden = client.post(f"/api/rescuer/sos/{created_sos_id}/release", headers=headers_r2)
    check(rel_forbidden.status_code == 400, "Không được phép nhả ca sau khi đã ARRIVED (400)")

    # Hoàn thành ca
    comp = client.post(f"/api/rescuer/sos/{created_sos_id}/complete", headers=headers_r2)
    check(comp.status_code == 200 and comp.json()["status"] == "COMPLETED", "Hoàn thành ca thành công")

    # Thử hoàn thành lần 2
    comp_dup = client.post(f"/api/rescuer/sos/{created_sos_id}/complete", headers=headers_r2)
    check(comp_dup.status_code in (400, 404), "Không thể hoàn thành lại phiên đã đóng")

    # ---------------------------------------------------------
    # 8. KIỂM TRA TÁC VỤ NỀN TỰ ĐỘNG HẾT HẠN SOS WAITING > 2H (RB07)
    # ---------------------------------------------------------
    print("\n👉 PHẦN 8: Kiểm tra tác vụ nền tự động hủy SOS WAITING > 2 giờ (RB07)")
    # Giả lập tạo 1 SOS cũ hơn 2 giờ (created_at = now() - 3 hours)
    with get_db_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO sos_requests (
                    requester_id, emergency_type_id, visibility,
                    status, created_at
                )
                VALUES (%s, (SELECT id FROM emergency_types WHERE name = 'NGAP_LUT' LIMIT 1), 'PUBLIC', 'WAITING', now() - INTERVAL '3 hours')
                RETURNING id
                """,
                (user_b["id"],)
            )
            old_sos_id = cur.fetchone()["id"]

            # Thêm location_current giả
            cur.execute(
                "INSERT INTO location_current (sos_id, actor, lat, lng, updated_at) VALUES (%s, 'VICTIM', 10.8, 106.7, now())",
                (old_sos_id,)
            )

    # Chạy tác vụ nền check_expired_sos
    asyncio.run(check_expired_sos())

    with get_db_connection() as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT status FROM sos_requests WHERE id = %s", (old_sos_id,))
            expired_row = cur.fetchone()
            check(expired_row["status"] == "CANCELLED",
                  "SOS > 2h tự động chuyển sang CANCELLED (RB07)")

            cur.execute("SELECT count(*) as cnt FROM location_current WHERE sos_id = %s", (old_sos_id,))
            loc_cnt = cur.fetchone()["cnt"]
            check(loc_cnt == 0, "Dòng location_current của SOS hết hạn đã được xóa sạch (RB09)")

    # ---------------------------------------------------------
    # 9. KIỂM TRA BẢO VỆ PHÒNG WEBSOCKET (UNAUTHORIZED SUBSCRIPTION)
    # ---------------------------------------------------------
    print("\n👉 PHẦN 9: Kiểm tra bảo vệ quyền tham gia phòng WebSocket (Room Authorization)")
    # Tạo 1 SOS mới của User A
    sos_res = client.post("/api/sos", json={
        "emergency_type": "MAC_KET", "visibility": "PRIVATE", "initial_lat": 10.75, "initial_lng": 106.68
    }, headers=headers_user_a)
    private_sos_id = sos_res.json()["sos"]["id"]

    # User B kết nối WebSocket và cố tình SUBSCRIBE vào phòng của User A
    with client.websocket_connect(f"/ws?token={token_user_b}") as ws:
        init = ws.receive_json()
        check(init["type"] == "AUTH_SUCCESS", "User B kết nối WebSocket thành công")

        # Gửi SUBSCRIBE_SOS vào phòng của User A
        ws.send_json({"type": "SUBSCRIBE_SOS", "sos_id": private_sos_id})
        res_sub = ws.receive_json()
        check(res_sub["type"] == "ERROR", "WebSocket từ chối User B tham gia phòng SOS của User A (Unauthorized Room)")

    # Dọn dẹp SOS test
    client.post(f"/api/sos/{private_sos_id}/cancel", headers=headers_user_a)

    print("\n" + "=" * 70)
    print(f" TỔNG KẾT KIỂM TRA: {passed_checks} KIỂM TRA ĐẠT, {failed_checks} LỖI")
    if failed_checks == 0:
        print(" 🎉 TOÀN BỘ CƠ CHẾ BẢO MẬT, RÀNG BUỘC VÀ NGHIỆP VỤ ĐẠT TIÊU CHUẨN 100%!")
    print("=" * 70 + "\n")

if __name__ == "__main__":
    run_deep_verification()
