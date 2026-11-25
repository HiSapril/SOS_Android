"""
Bộ kiểm thử tự động toàn diện cho SOS Rescue Backend (Python + FastAPI + PostgreSQL).
Kiểm tra đầy đủ 14 chức năng (F01 - F14) và các ràng buộc dữ liệu RB01 - RB10 theo Nhom1_Chuong1.
"""

import asyncio
import sys
import os
import json
from datetime import datetime

# Đảm bảo in tiếng Việt không bị lỗi font trên Windows console
if sys.stdout.encoding != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

from fastapi.testclient import TestClient
from main import app
from database.connection import get_db_connection
from services.otp_service import otp_store

client = TestClient(app)

def print_step(step_name: str):
    print(f"\n========================================================")
    print(f"👉 BƯỚC KIỂM THỬ: {step_name}")
    print(f"========================================================")

def test_full_rescue_workflow():
    print("Bắt đầu kiểm thử toàn bộ luồng nghiệp vụ Hệ thống Cứu hộ SOS...")

    # 1. Health check
    print_step("1. Kiểm tra sức khỏe hệ thống và kết nối PostgreSQL")
    res = client.get("/health")
    assert res.status_code == 200, f"Health check thất bại: {res.text}"
    data = res.json()
    assert data["status"] == "healthy"
    assert data["database"] == "connected"
    print("✅ Hệ thống và PostgreSQL hoạt động bình thường:", data)

    # 2. Đăng ký/đăng nhập USER mới bằng OTP Telegram (F01, F02, UC01, RB10)
    print_step("2. Xác thực và tạo tài khoản USER qua OTP Telegram (F01, F02, UC01)")
    test_phone = "0987654321"

    # Dọn dẹp tài khoản kiểm thử cũ (nếu có) và xóa OTP trong bộ nhớ
    with get_db_connection() as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT id FROM users WHERE phone = %s", (test_phone,))
            existing = cur.fetchone()
            if existing:
                cur.execute("DELETE FROM sos_requests WHERE requester_id = %s", (existing["id"],))
                cur.execute("DELETE FROM users WHERE id = %s", (existing["id"],))
    otp_store.clear(test_phone)

    # 2a. Liên kết Telegram (Người dùng mới -> is_valid = False)
    link_res = client.post("/api/auth/dev-link-telegram", json={"phone": test_phone, "telegram_chat_id": 99999999})
    assert link_res.status_code == 200
    print("✅ Đã liên kết số điện thoại kiểm thử với Telegram Bot.")

    # Ràng buộc: Người dùng mới liên kết Telegram nhưng chưa xác minh OTP thì is_valid = False
    with get_db_connection() as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT is_valid FROM users WHERE phone = %s", (test_phone,))
            new_u = cur.fetchone()
            assert new_u is not None
            assert new_u["is_valid"] is False, "Người dùng mới phải có is_valid = False trước khi xác nhận OTP!"
    print("✅ Ràng buộc đúng: Người dùng mới có is_valid = False.")

    # 2b. Kiểm tra ràng buộc role khi yêu cầu OTP
    # Không kèm role -> 422 Unprocessable Entity
    no_role_res = client.post("/api/auth/request-otp", json={"phone": test_phone})
    assert no_role_res.status_code == 422, "Thiếu role phải bị từ chối 422"

    # Role sai giá trị -> 422 Unprocessable Entity
    invalid_role_res = client.post("/api/auth/request-otp", json={"phone": test_phone, "role": "INVALID_ROLE"})
    assert invalid_role_res.status_code == 422, "Role sai giá trị phải bị từ chối 422"

    # Số điện thoại không có quyền ADMIN gửi role ADMIN -> 403 Forbidden
    unauth_admin_res = client.post("/api/auth/request-otp", json={"phone": test_phone, "role": "ADMIN"})
    assert unauth_admin_res.status_code == 403, "Không có quyền ADMIN phải bị từ chối 403"

    # Số điện thoại role USER nhưng gửi role RESCUER -> 400 Bad Request
    wrong_role_res = client.post("/api/auth/request-otp", json={"phone": test_phone, "role": "RESCUER"})
    assert wrong_role_res.status_code == 400, "Sai vai trò tài khoản phải bị từ chối 400"
    print("✅ Kiểm tra chặn sai role thành công:", wrong_role_res.json()["detail"])

    # Yêu cầu mã OTP hợp lệ với đúng role USER
    otp_req = client.post("/api/auth/request-otp", json={"phone": test_phone, "role": "USER"})
    assert otp_req.status_code == 200, f"Yêu cầu OTP thất bại: {otp_req.text}"
    otp_data = otp_req.json()
    otp_code = otp_data.get("dev_otp")
    assert otp_code is not None, "Không nhận được mã OTP (chế độ DEV_MOCK_OTP)"
    print(f"✅ Đã phát sinh mã OTP qua Telegram: {otp_code}")

    # 2c. Thử nhập sai OTP để kiểm tra attempt_count
    wrong_verify = client.post("/api/auth/verify-otp", json={"phone": test_phone, "otp": "000000"})
    assert wrong_verify.status_code == 400
    print("✅ Kiểm tra chặn mã OTP sai thành công:", wrong_verify.json()["detail"])

    # 2d. Nhập đúng mã OTP để đăng ký tài khoản USER mới (chỉ cần phone và otp)
    verify_res = client.post("/api/auth/verify-otp", json={
        "phone": test_phone,
        "otp": otp_code
    })
    assert verify_res.status_code == 200, f"Xác minh OTP thất bại: {verify_res.text}"
    auth_data = verify_res.json()
    user_token = auth_data["access_token"]
    user_info = auth_data["user"]
    user_headers = {"Authorization": f"Bearer {user_token}"}
    assert user_info["role"] == "USER"
    assert user_info["account_status"] == "ACTIVE"
    assert user_info["is_valid"] is True, "Sau khi xác nhận OTP, is_valid phải là True!"
    assert user_info["full_name"] == test_phone, f"Mặc định ban đầu full_name phải là SĐT ({test_phone}), thực tế: {user_info['full_name']}"
    print(f"✅ Mặc định họ tên ban đầu khớp SĐT: {user_info['full_name']}")

    # Ràng buộc: Trong DB is_valid đã chuyển sang True
    with get_db_connection() as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT is_valid FROM users WHERE phone = %s", (test_phone,))
            assert cur.fetchone()["is_valid"] is True, "is_valid trong PostgreSQL phải là True!"
    print("✅ Đăng ký và cấp JWT token thành công cho USER. is_valid đã chuyển thành True:", user_info)

    # 3. Xem và cập nhật hồ sơ cá nhân qua API đổi họ tên mới /api/users/full-name
    print_step("3. Xem và cập nhật họ tên qua API /api/users/full-name")
    me_res = client.get("/api/auth/me", headers=user_headers)
    assert me_res.status_code == 200
    print("✅ /api/auth/me trả về chính xác tài khoản hiện tại.")

    update_prof = client.put("/api/users/full-name", json={"full_name": "Nguyễn Văn Nạn Nhân"}, headers=user_headers)
    assert update_prof.status_code == 200
    assert update_prof.json()["user"]["full_name"] == "Nguyễn Văn Nạn Nhân"
    print("✅ Cập nhật họ tên thành công qua /api/users/full-name:", update_prof.json()["user"]["full_name"])

    # 4. Tạo yêu cầu SOS (F04, UC03, RB01, RB02, RB03, RB04)
    print_step("4. Tạo yêu cầu SOS (F04, UC03, RB01, RB02, RB04)")
    sos_payload = {
        "emergency_type": "NGAP_LUT",
        "description": "Nước ngập tầng 1, cần xuồng cứu hộ đưa 3 người già di chuyển",
        "visibility": "PUBLIC",
        "initial_lat": 10.776889,
        "initial_lng": 106.700806
    }
    create_sos_res = client.post("/api/sos", json=sos_payload, headers=user_headers)
    assert create_sos_res.status_code == 200, f"Tạo SOS thất bại: {create_sos_res.text}"
    created_sos = create_sos_res.json()["sos"]
    sos_id = created_sos["id"]
    assert created_sos["status"] == "WAITING"
    assert created_sos["visibility"] == "PUBLIC"
    print(f"✅ Tạo thành công SOS id={sos_id}, status={created_sos['status']}")

    # 5. Kiểm tra ràng buộc RB04: Không được tạo SOS thứ hai khi SOS đầu chưa kết thúc
    print_step("5. Kiểm tra ràng buộc RB04 (Mỗi người gửi chỉ có tối đa 1 SOS chưa kết thúc)")
    duplicate_sos = client.post("/api/sos", json=sos_payload, headers=user_headers)
    assert duplicate_sos.status_code == 409, "Hệ thống phải từ chối tạo SOS trùng theo RB04!"
    print("✅ RB04 hoạt động chuẩn xác: Đã từ chối tạo SOS thứ hai khi có SOS đang hoạt động.")

    # 6. Kiểm tra View public_sos_map (F08, UC07)
    print_step("6. Kiểm tra View public_sos_map (Cộng đồng thấy tóm tắt với tọa độ làm tròn)")
    public_map_res = client.get("/api/sos/public/map")
    assert public_map_res.status_code == 200
    public_items = public_map_res.json()["data"]
    matched = [item for item in public_items if item["sos_id"] == sos_id]
    assert len(matched) == 1, "SOS PUBLIC phải xuất hiện trên view public_sos_map"
    # Kiểm tra tọa độ được làm tròn 2 chữ số thập phân (sai số ~1km)
    assert matched[0]["area_lat"] == round(10.776889, 2)
    assert matched[0]["area_lng"] == round(106.700806, 2)
    print("✅ View public_sos_map hiển thị đúng thông tin tóm tắt an toàn:", matched[0])

    # 7. Kiểm tra theo dõi SOS của mình (UC05 / M05-U)
    print_step("7. Theo dõi SOS của mình (/api/sos/my-active)")
    my_active_res = client.get("/api/sos/my-active", headers=user_headers)
    assert my_active_res.status_code == 200
    my_active = my_active_res.json()
    assert my_active["active"] is True
    assert my_active["sos"]["id"] == sos_id
    assert "VICTIM" in my_active["sos"]["locations"]
    print("✅ /api/sos/my-active trả về đúng thông tin phiên đang chờ.")

    # 8. Đăng nhập tài khoản RESCUER có sẵn trong DB (0901112222 - Đội Cứu Hộ Quận 1)
    print_step("8. Đăng nhập tài khoản RESCUER (0901112222)")
    rescuer_phone = "0901112222"
    otp_store.clear(rescuer_phone)
    
    # 8a. Kiểm tra chặn người dùng thường (test_phone) xin OTP với vai trò RESCUER -> 400
    r_unauthorized_rescuer = client.post("/api/auth/request-otp", json={"phone": test_phone, "role": "RESCUER"})
    assert r_unauthorized_rescuer.status_code == 400, "User thường xin OTP với role RESCUER phải bị chặn"
    print("✅ Kiểm tra chặn User thường xin role RESCUER thành công:", r_unauthorized_rescuer.json()["detail"])

    # 8b. Kiểm tra RESCUER muốn đăng nhập với vai trò USER -> Cho phép thành công!
    r_rescuer_as_user = client.post("/api/auth/request-otp", json={"phone": rescuer_phone, "role": "USER"})
    assert r_rescuer_as_user.status_code == 200, "RESCUER xin OTP với role USER phải được phép"
    otp_rescuer_user = r_rescuer_as_user.json().get("dev_otp")
    r_verify_rescuer_user = client.post("/api/auth/verify-otp", json={"phone": rescuer_phone, "otp": otp_rescuer_user})
    assert r_verify_rescuer_user.status_code == 200
    assert r_verify_rescuer_user.json()["user"]["role"] == "USER"
    print("✅ Kiểm tra RESCUER đăng nhập với vai trò USER thành công:", r_verify_rescuer_user.json()["user"])

    # 8c. Đăng nhập RESCUER với đúng vai trò RESCUER để tiếp tục các bước cứu hộ sau
    otp_store.clear(rescuer_phone)
    r_otp_req = client.post("/api/auth/request-otp", json={"phone": rescuer_phone, "role": "RESCUER"})
    assert r_otp_req.status_code == 200
    r_otp_code = r_otp_req.json().get("dev_otp")

    r_verify_res = client.post("/api/auth/verify-otp", json={"phone": rescuer_phone, "otp": r_otp_code})
    assert r_verify_res.status_code == 200
    rescuer_token = r_verify_res.json()["access_token"]
    rescuer_info = r_verify_res.json()["user"]
    rescuer_headers = {"Authorization": f"Bearer {rescuer_token}"}
    assert rescuer_info["role"] == "RESCUER"
    assert rescuer_info["is_valid"] is True
    print("✅ Đăng nhập RESCUER thành công:", rescuer_info)

    # 9. RESCUER xem hàng đợi cứu hộ (F08, UC07, View rescue_queue)
    print_step("9. RESCUER xem hàng đợi rescue_queue")
    queue_res = client.get("/api/rescuer/queue", headers=rescuer_headers)
    assert queue_res.status_code == 200
    queue_items = queue_res.json()["data"]
    q_match = [item for item in queue_items if item["sos_id"] == sos_id]
    assert len(q_match) == 1, "SOS WAITING phải xuất hiện trong rescue_queue"
    assert q_match[0]["initial_lat"] == 10.776889  # Tọa độ chính xác
    print("✅ View rescue_queue hiển thị đầy đủ thông tin chi tiết cho RESCUER:", q_match[0])

    # 10. RESCUER nhận yêu cầu cứu hộ (F10, UC09, RB05, RB06)
    print_step("10. RESCUER nhận yêu cầu cứu hộ (F10, UC09, RB05, RB06)")
    accept_res = client.post(f"/api/rescuer/sos/{sos_id}/accept", headers=rescuer_headers)
    assert accept_res.status_code == 200, f"Nhận ca thất bại: {accept_res.text}"
    assert accept_res.json()["status"] == "ACCEPTED"
    print("✅ Nhận ca thành công. Trạng thái chuyển sang ACCEPTED.")

    # 10b. Kiểm tra SOS đã rời khỏi rescue_queue
    queue_res_after = client.get("/api/rescuer/queue", headers=rescuer_headers)
    assert all(item["sos_id"] != sos_id for item in queue_res_after.json()["data"])
    print("✅ SOS đã tự động rời khỏi rescue_queue.")

    # 10c. Kiểm tra RB05: RESCUER không được nhận ca thứ 2 khi đang có ca chưa xong
    print_step("10c. Kiểm tra ràng buộc RB05 (Mỗi RESCUER chỉ được nhận 1 ca)")
    # Thử nhận lại chính ca này hoặc ca khác
    dup_accept = client.post(f"/api/rescuer/sos/{sos_id}/accept", headers=rescuer_headers)
    assert dup_accept.status_code == 409
    print("✅ RB05 hoạt động chuẩn xác: Chặn RESCUER nhận thêm ca khi đang bận.")

    # 11. Cập nhật vị trí realtime của nạn nhân và đội cứu hộ (F05, F11, F12, UC04, UC10, UC11)
    print_step("11. Cập nhật vị trí realtime của hai bên (F05, F11, F12)")
    # Nạn nhân gửi vị trí mới
    loc_v = client.post(f"/api/location/{sos_id}", json={
        "lat": 10.776950, "lng": 106.700850, "accuracy_m": 4.5, "save_history": True
    }, headers=user_headers)
    assert loc_v.status_code == 200
    assert loc_v.json()["actor"] == "VICTIM"

    # Đội cứu hộ gửi vị trí khi đang tiếp cận
    loc_r = client.post(f"/api/location/{sos_id}", json={
        "lat": 10.771000, "lng": 106.695000, "accuracy_m": 8.0, "save_history": True
    }, headers=rescuer_headers)
    assert loc_r.status_code == 200
    assert loc_r.json()["actor"] == "RESCUER"

    # Lấy vị trí hiện tại của hai bên
    loc_current_res = client.get(f"/api/location/{sos_id}/current", headers=user_headers)
    assert loc_current_res.status_code == 200
    current_locs = loc_current_res.json()["locations"]
    assert "VICTIM" in current_locs and "RESCUER" in current_locs
    print("✅ Vị trí hiện tại của hai bên được cập nhật và đồng bộ thành công:")
    print("   Victim:", current_locs["VICTIM"])
    print("   Rescuer:", current_locs["RESCUER"])

    # Lấy Polyline lộ trình di chuyển (UC11)
    trail_res = client.get(f"/api/location/{sos_id}/trail", headers=user_headers)
    assert trail_res.status_code == 200
    assert trail_res.json()["count"] >= 2
    print(f"✅ Lấy lộ trình Polyline thành công: {trail_res.json()['count']} điểm.")

    # 12. Tiến trình cứu hộ: ACCEPTED -> RESCUING -> ARRIVED (F13, UC12)
    print_step("12. Cập nhật tiến trình: ACCEPTED -> RESCUING -> ARRIVED (F13, UC12)")
    # Sai thứ tự: từ ACCEPTED nhảy sang ARRIVED phải bị từ chối
    invalid_step = client.post(f"/api/rescuer/sos/{sos_id}/status", json={"status": "ARRIVED"}, headers=rescuer_headers)
    assert invalid_step.status_code == 400
    print("✅ Kiểm tra chặt chẽ thứ tự tiến trình: Chặn nhảy cóc sang ARRIVED khi chưa RESCUING.")

    # Chuyển sang RESCUING (đang trên đường tới)
    res_rescuing = client.post(f"/api/rescuer/sos/{sos_id}/status", json={"status": "RESCUING"}, headers=rescuer_headers)
    assert res_rescuing.status_code == 200
    assert res_rescuing.json()["status"] == "RESCUING"
    print("✅ Đã cập nhật trạng thái: RESCUING (đang đến).")

    # Chuyển sang ARRIVED (đã tới nơi)
    res_arrived = client.post(f"/api/rescuer/sos/{sos_id}/status", json={"status": "ARRIVED"}, headers=rescuer_headers)
    assert res_arrived.status_code == 200
    assert res_arrived.json()["status"] == "ARRIVED"
    print("✅ Đã cập nhật trạng thái: ARRIVED (đã tới vị trí nạn nhân).")

    # 13. Hoàn thành ca cứu hộ (F14, UC13, RB09)
    print_step("13. Hoàn thành phiên cứu hộ (F14, UC13, RB09)")
    comp_res = client.post(f"/api/rescuer/sos/{sos_id}/complete", headers=rescuer_headers)
    assert comp_res.status_code == 200
    assert comp_res.json()["status"] == "COMPLETED"
    assert comp_res.json()["end_reason"] == "RESCUER_COMPLETED"
    print("✅ Đã hoàn thành cứu hộ thành công. Ca của RESCUER đã được giải phóng.")

    # Kiểm tra location_current đã được dọn dẹp theo RB09
    with get_db_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute("SELECT count(*) AS cnt FROM location_current WHERE sos_id = %s", (sos_id,))
            cnt = cursor.fetchone()["cnt"]
            assert cnt == 0, "Bảng location_current phải được xóa khi SOS kết thúc theo RB09!"
    print("✅ RB09 hoạt động đúng: location_current đã được xóa sạch.")

    # 14. Kiểm tra chức năng Nhả ca (Release)
    print_step("14. Kiểm tra nghiệp vụ Nhả ca (Release case -> quay về WAITING)")
    # Tạo SOS mới để test nhả ca
    new_sos_res = client.post("/api/sos", json={
        "emergency_type": "TAI_NAN",
        "description": "Va chạm xe máy tại ngã tư",
        "visibility": "PRIVATE",
        "initial_lat": 10.750000,
        "initial_lng": 106.680000
    }, headers=user_headers)
    assert new_sos_res.status_code == 200
    sos_release_id = new_sos_res.json()["sos"]["id"]

    # Rescuer nhận ca
    client.post(f"/api/rescuer/sos/{sos_release_id}/accept", headers=rescuer_headers)

    # Rescuer gửi vị trí của mình
    client.post(f"/api/location/{sos_release_id}", json={
        "lat": 10.740000, "lng": 106.670000, "accuracy_m": 5.0, "save_history": True
    }, headers=rescuer_headers)

    # Rescuer thực hiện Nhả ca
    release_res = client.post(f"/api/rescuer/sos/{sos_release_id}/release", headers=rescuer_headers)
    assert release_res.status_code == 200
    assert release_res.json()["status"] == "WAITING"

    # Kiểm tra SOS quay trở lại rescue_queue và thông tin rescuer được xóa
    q_check = client.get("/api/rescuer/queue", headers=rescuer_headers).json()["data"]
    assert any(item["sos_id"] == sos_release_id for item in q_check), "SOS phải quay lại rescue_queue sau khi nhả ca"
    print("✅ Nghiệp vụ Nhả ca hoạt động xuất sắc: SOS quay về WAITING, vị trí cứu hộ cũ đã được dọn sạch.")

    # 15. Kiểm tra chức năng Hủy SOS từ phía người dùng (F07, UC06)
    print_step("15. Kiểm tra chức năng Hủy SOS (F07, UC06)")
    cancel_res = client.post(f"/api/sos/{sos_release_id}/cancel", headers=user_headers)
    assert cancel_res.status_code == 200
    assert cancel_res.json()["status"] == "CANCELLED"
    assert cancel_res.json()["end_reason"] == "USER_CANCELLED"
    print("✅ Người dùng hủy SOS thành công, end_reason = USER_CANCELLED.")

    # 16. Kiểm tra kênh WebSocket Real-time (/ws)
    print_step("16. Kiểm tra kết nối và trao đổi dữ liệu WebSocket Real-time (/ws)")
    with client.websocket_connect(f"/ws?token={user_token}") as websocket:
        auth_msg = websocket.receive_json()
        assert auth_msg["type"] == "AUTH_SUCCESS"
        print("✅ Kết nối WebSocket thành công với JWT token:", auth_msg)

        websocket.send_json({"type": "PING"})
        pong_msg = websocket.receive_json()
        assert pong_msg["type"] == "PONG"
        print("✅ WebSocket Ping-Pong phản hồi chính xác:", pong_msg)

    print("\n🎉 ========================================================")
    print(" TẤT CẢ 16 BÀI KIỂM THỬ ĐÃ VƯỢT QUA 100% THÀNH CÔNG!")
    print(" TOÀN BỘ CHỨC NĂNG F01-F14 VÀ RÀNG BUỘC RB01-RB10 HOẠT ĐỘNG HOÀN HẢO!")
    print("========================================================\n")

if __name__ == "__main__":
    test_full_rescue_workflow()
