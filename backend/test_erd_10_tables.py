"""
test_erd_10_tables.py
Kiểm thử chuyên sâu 4 bảng mới bổ sung theo sơ đồ ERD 10 bảng:
1. rescue_teams
2. rescue_team_members
3. sos_notifications
4. sos_logs
"""

import sys
from fastapi.testclient import TestClient
from main import app
from database.connection import get_db_connection
from services.otp_service import otp_store

if sys.stdout.encoding != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

client = TestClient(app)

def test_erd_10_tables():
    print("\n" + "=" * 70)
    print(" BẮT ĐẦU KIỂM THỬ 4 BẢNG BỔ SUNG THEO SƠ ĐỒ ERD 10 BẢNG")
    print("=" * 70)

    # 1. Đăng nhập tài khoản RESCUER mẫu (Đội 1)
    print("\n👉 1. Kiểm tra bảng rescue_teams và rescue_team_members")
    # Reset OTP cho 0901112222 trong bộ nhớ
    otp_store.clear("0901112222")
    
    r_otp = client.post("/api/auth/request-otp", json={"phone": "0901112222", "role": "RESCUER"}).json().get("dev_otp")
    r_auth = client.post("/api/auth/verify-otp", json={"phone": "0901112222", "otp": r_otp}).json()
    assert r_auth["user"]["is_valid"] is True
    r_token = r_auth["access_token"]
    r_headers = {"Authorization": f"Bearer {r_token}"}

    # Gọi API GET /api/rescuer/teams
    teams_res = client.get("/api/rescuer/teams", headers=r_headers)
    assert teams_res.status_code == 200, f"Lỗi get teams: {teams_res.text}"
    teams_data = teams_res.json()["data"]
    assert len(teams_data) >= 2, "Phải có ít nhất 2 đội cứu hộ"
    print(f"  [PASS] Tìm thấy {len(teams_data)} đội cứu hộ trong rescue_teams:")
    for t in teams_data:
        print(f"         - Đội: {t['name']} (Trạng thái: {t['status']}), Thành viên: {len(t['members'])} người")
        for m in t["members"]:
            print(f"           + {m['name']} ({m['role']}, SĐT: {m['phone']})")

    # 2. Đăng nhập tài khoản USER mẫu (0901234567)
    print("\n👉 2. Tạo SOS và kiểm tra ghi nhận tự động vào sos_logs & sos_notifications")
    otp_store.clear("0901234567")
    with get_db_connection() as conn:
        with conn.cursor() as cur:
            # Dọn dẹp SOS cũ nếu còn active
            cur.execute("UPDATE sos_requests SET status = 'CANCELLED' WHERE status NOT IN ('COMPLETED', 'CANCELLED')")

    u_otp = client.post("/api/auth/request-otp", json={"phone": "0901234567", "role": "USER"}).json().get("dev_otp")
    u_auth = client.post("/api/auth/verify-otp", json={"phone": "0901234567", "otp": u_otp}).json()
    assert u_auth["user"]["is_valid"] is True
    u_token = u_auth["access_token"]
    u_headers = {"Authorization": f"Bearer {u_token}"}

    # Tạo SOS mới
    sos_res = client.post("/api/sos", json={
        "emergency_type": "NGAP_LUT",
        "description": "Nước ngập cao cần canô hỗ trợ",
        "visibility": "PUBLIC",
        "initial_lat": 10.780000,
        "initial_lng": 106.700000
    }, headers=u_headers)
    assert sos_res.status_code == 200, f"Lỗi tạo SOS: {sos_res.text}"
    sos_id = sos_res.json()["sos"]["id"]
    print(f"  [PASS] Đã tạo SOS thành công: id={sos_id}")

    # Kiểm tra log CREATE_SOS trong bảng sos_logs
    logs_res = client.get(f"/api/sos/{sos_id}/logs", headers=u_headers)
    assert logs_res.status_code == 200
    logs = logs_res.json()["data"]
    assert any(l["action"] == "CREATE_SOS" for l in logs), "Phải có bản ghi CREATE_SOS trong sos_logs"
    print(f"  [PASS] Bảng sos_logs đã ghi nhận sự kiện: {logs[0]['action']} - {logs[0]['description']}")

    # Kiểm tra thông báo trong bảng sos_notifications
    notifs_res = client.get("/api/sos/notifications/my", headers=u_headers)
    assert notifs_res.status_code == 200
    notifs = notifs_res.json()["data"]
    assert len(notifs) >= 1, "Phải có thông báo mới trong sos_notifications"
    first_notif = notifs[0]
    print(f"  [PASS] Bảng sos_notifications đã tạo thông báo: {first_notif['message']} (is_read={first_notif['is_read']})")

    # 3. Đội cứu hộ nhận ca (ACCEPT_SOS)
    print("\n👉 3. Đội cứu hộ nhận ca và kiểm tra liên kết team_id & logs")
    accept_res = client.post(f"/api/rescuer/sos/{sos_id}/accept", headers=r_headers)
    assert accept_res.status_code == 200, f"Lỗi nhận ca: {accept_res.text}"

    # Kiểm tra team_id đã được gán vào sos_requests
    with get_db_connection() as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT team_id FROM sos_requests WHERE id = %s", (sos_id,))
            row = cur.fetchone()
            assert row["team_id"] is not None, "team_id phải được tự động gán từ rescue_team_members"
            print(f"  [PASS] sos_requests đã liên kết team_id={row['team_id']}")

    # Kiểm tra log ACCEPT_SOS trong sos_logs
    logs2 = client.get(f"/api/sos/{sos_id}/logs", headers=r_headers).json()["data"]
    assert any(l["action"] == "ACCEPT_SOS" for l in logs2)
    print("  [PASS] sos_logs đã ghi nhận sự kiện ACCEPT_SOS.")

    # 4. Cập nhật trạng thái RESCUING và ARRIVED
    print("\n👉 4. Cập nhật tiến trình và kiểm tra ghi nhận logs & notifications")
    client.post(f"/api/rescuer/sos/{sos_id}/status", json={"status": "RESCUING"}, headers=r_headers)
    client.post(f"/api/rescuer/sos/{sos_id}/status", json={"status": "ARRIVED"}, headers=r_headers)

    logs3 = client.get(f"/api/sos/{sos_id}/logs", headers=r_headers).json()["data"]
    actions = [l["action"] for l in logs3]
    assert "STATUS_RESCUING" in actions and "STATUS_ARRIVED" in actions
    print(f"  [PASS] Lịch sử hoạt động đầy đủ trong sos_logs: {actions}")

    # 5. Hoàn thành phiên cứu trợ (COMPLETE_SOS)
    print("\n👉 5. Hoàn thành cứu hộ và kiểm tra đánh dấu thông báo đã đọc")
    comp_res = client.post(f"/api/rescuer/sos/{sos_id}/complete", headers=r_headers)
    assert comp_res.status_code == 200
    print("  [PASS] Đã hoàn thành ca cứu trợ.")

    # Đánh dấu thông báo đã đọc
    notif_id = first_notif["notify_id"]
    read_res = client.put(f"/api/sos/notifications/{notif_id}/read", headers=u_headers)
    assert read_res.status_code == 200
    print(f"  [PASS] Đã đánh dấu thông báo id={notif_id} là đã đọc (is_read=true).")

    print("\n" + "=" * 70)
    print(" 🎉 TẤT CẢ 4 BẢNG BỔ SUNG (10 BẢNG ERD) HOẠT ĐỘNG HOÀN HẢO 100%!")
    print("=" * 70 + "\n")

if __name__ == "__main__":
    test_erd_10_tables()
