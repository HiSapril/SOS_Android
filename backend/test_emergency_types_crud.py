"""
test_emergency_types_crud.py
Kiểm thử toàn diện 100% các chức năng CRUD của bảng emergency_types:
1. GET /api/emergency-types (Danh sách & Tìm kiếm)
2. GET /api/emergency-types/{id} (Chi tiết)
3. POST /api/emergency-types (Tạo mới)
4. PUT /api/emergency-types/{id} (Cập nhật)
5. DELETE /api/emergency-types/{id} (Xóa & Ràng buộc an toàn)
6. Tạo SOS bằng emergency_type_id (UUID)
"""
import sys
from fastapi.testclient import TestClient
from main import app
from database.connection import get_db_connection
from services.auth_service import normalize_phone
from services.otp_service import otp_store

if sys.stdout.encoding != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

import config
config.DEV_MOCK_OTP = True

client = TestClient(app)

def test_emergency_types_crud():
    print("\n" + "=" * 70)
    print(" BẮT ĐẦU KIỂM THỬ CRUD CHO BẢNG EMERGENCY_TYPES")
    print("=" * 70)

    # 1. Đăng nhập tài khoản test để lấy Bearer Token
    test_phone = "0901234567"
    otp_store.clear(test_phone)
    client.post("/api/auth/dev-link-telegram", json={"phone": test_phone, "telegram_chat_id": 123456789, "role": "USER"})
    otp_res = client.post("/api/auth/request-otp", json={"phone": test_phone, "role": "USER"}).json()
    dev_otp = otp_res.get("dev_otp")
    auth_res = client.post("/api/auth/verify-otp", json={"phone": test_phone, "otp": dev_otp}).json()
    token = auth_res["access_token"]
    headers = {"Authorization": f"Bearer {token}"}
    print("👉 1. Đăng nhập thành công, nhận JWT token.")

    # 2. GET /api/emergency-types - Lấy danh sách
    print("\n👉 2. Kiểm tra GET /api/emergency-types")
    list_res = client.get("/api/emergency-types")
    assert list_res.status_code == 200, f"Lỗi list: {list_res.text}"
    items = list_res.json()["data"]
    assert len(items) >= 7, "Phải có ít nhất 7 loại sự cố mặc định đã seed"
    print(f"  [PASS] Tìm thấy {len(items)} loại sự cố trong hệ thống:")
    for it in items[:4]:
        print(f"         - [{it['name']}]: {it['description']}")

    # Tìm kiếm theo từ khóa search
    search_res = client.get("/api/emergency-types?search=LUT")
    assert search_res.status_code == 200
    search_items = search_res.json()["data"]
    assert any("NGAP_LUT" in x["name"] for x in search_items), "Tìm kiếm 'LUT' phải trả về NGAP_LUT"
    print(f"  [PASS] Tìm kiếm search='LUT' trả về đúng {len(search_items)} kết quả.")

    # 3. POST /api/emergency-types - Tạo mới
    print("\n👉 3. Kiểm tra POST /api/emergency-types (Tạo mới)")
    test_type_name = "SU_CO_HOA_CHAT"
    test_type_desc = "Rò rỉ hóa chất độc hại, cần mặt nạ chống độc và xe tẩy độc"
    
    # Xóa sạch nếu đã có từ lần chạy trước
    with get_db_connection() as conn:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM emergency_types WHERE name = %s", (test_type_name,))

    create_res = client.post("/api/emergency-types", json={
        "name": test_type_name,
        "description": test_type_desc
    }, headers=headers)
    assert create_res.status_code == 201, f"Lỗi tạo mới: {create_res.text}"
    created_data = create_res.json()["data"]
    new_id = created_data["id"]
    assert created_data["name"] == test_type_name
    print(f"  [PASS] Tạo mới thành công ID={new_id}, Name={created_data['name']}")

    # Thử tạo trùng tên -> 409 Conflict
    dup_res = client.post("/api/emergency-types", json={
        "name": test_type_name,
        "description": "Thử tạo trùng lặp"
    }, headers=headers)
    assert dup_res.status_code == 409, f"Trùng tên phải bị từ chối 409: {dup_res.text}"
    print(f"  [PASS] Chặn tạo trùng lặp thành công (409 Conflict): {dup_res.json()['detail']}")

    # 4. GET /api/emergency-types/{id} - Chi tiết
    print("\n👉 4. Kiểm tra GET /api/emergency-types/{id} (Xem chi tiết)")
    detail_res = client.get(f"/api/emergency-types/{new_id}")
    assert detail_res.status_code == 200
    assert detail_res.json()["data"]["id"] == new_id
    assert detail_res.json()["data"]["name"] == test_type_name
    print(f"  [PASS] Lấy chi tiết thành công: {detail_res.json()['data']['name']}")

    # Thử ID không tồn tại -> 404
    not_found_res = client.get("/api/emergency-types/00000000-0000-0000-0000-000000000000")
    assert not_found_res.status_code == 404
    print("  [PASS] Tra cứu ID không tồn tại trả về 404 Not Found chuẩn xác.")

    # 5. PUT /api/emergency-types/{id} - Cập nhật
    print("\n👉 5. Kiểm tra PUT /api/emergency-types/{id} (Cập nhật)")
    updated_desc = "Rò rỉ hóa chất độc hại cấp độ đặc biệt nguy hiểm"
    update_res = client.put(f"/api/emergency-types/{new_id}", json={
        "description": updated_desc
    }, headers=headers)
    assert update_res.status_code == 200
    assert update_res.json()["data"]["description"] == updated_desc
    print(f"  [PASS] Cập nhật mô tả thành công: {update_res.json()['data']['description']}")

    # 6. Kiểm tra tạo SOS bằng emergency_type_id (UUID mới tạo)
    print("\n👉 6. Kiểm tra tạo SOS mới sử dụng emergency_type_id (UUID)")
    # Dọn dẹp SOS cũ của user
    with get_db_connection() as conn:
        with conn.cursor() as cur:
            cur.execute("UPDATE sos_requests SET status = 'CANCELLED' WHERE requester_id = (SELECT id FROM users WHERE phone = %s) AND status NOT IN ('COMPLETED', 'CANCELLED')", (test_phone,))

    sos_create_res = client.post("/api/sos", json={
        "emergency_type_id": new_id,
        "description": "Khu công nghiệp rò rỉ hóa chất cần sơ tán",
        "visibility": "PUBLIC",
        "initial_lat": 10.820000,
        "initial_lng": 106.650000
    }, headers=headers)
    assert sos_create_res.status_code == 200, f"Lỗi tạo SOS: {sos_create_res.text}"
    sos_data = sos_create_res.json()["sos"]
    sos_id = sos_data["id"]
    assert sos_data["emergency_type"] == test_type_name
    assert sos_data["emergency_type_id"] == new_id
    print(f"  [PASS] Đã tạo SOS thành công với emergency_type_id: {sos_id}")

    # 7. DELETE /api/emergency-types/{id} - Ràng buộc khóa ngoại an toàn
    print("\n👉 7. Kiểm tra DELETE /api/emergency-types/{id} (Ràng buộc an toàn)")
    # Loại sự cố đang được SOS sử dụng -> Không được xóa (400 Bad Request)
    del_blocked_res = client.delete(f"/api/emergency-types/{new_id}", headers=headers)
    assert del_blocked_res.status_code == 400, "Loại sự cố đang dùng không được phép xóa"
    print(f"  [PASS] Chặn xóa loại sự cố đang được SOS sử dụng thành công: {del_blocked_res.json()['detail']}")

    # Hủy SOS đang sử dụng
    client.post(f"/api/sos/{sos_id}/cancel", headers=headers)
    # Xóa bản ghi SOS để kiểm tra xóa emergency_type
    with get_db_connection() as conn:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM sos_requests WHERE id = %s", (sos_id,))

    # Xóa khi không còn ai dùng -> 200 OK
    del_ok_res = client.delete(f"/api/emergency-types/{new_id}", headers=headers)
    assert del_ok_res.status_code == 200
    print(f"  [PASS] Xóa thành công loại sự cố khi không còn ràng buộc: {del_ok_res.json()['message']}")

    # Tra cứu lại -> 404
    after_del_res = client.get(f"/api/emergency-types/{new_id}")
    assert after_del_res.status_code == 404
    print("  [PASS] Bản ghi đã được xóa hoàn toàn khỏi DB (404 Not Found).")

    print("\n" + "=" * 70)
    print(" 🎉 TOÀN BỘ CÁC CHỨC NĂNG CRUD EMERGENCY_TYPES HOẠT ĐỘNG HOÀN HẢO 100%!")
    print("=" * 70)

if __name__ == "__main__":
    test_emergency_types_crud()
