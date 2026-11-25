import logging
from datetime import datetime, timezone, timedelta
import config
from services.auth_service import hash_otp, verify_otp_hash

logger = logging.getLogger("backend.otp")

class InMemoryOtpStore:
    """
    Quản lý mã OTP trong bộ nhớ RAM thay thế bảng otp_codes:
    - Lưu mã OTP dạng băm SHA-256 an toàn.
    - Hạn sử dụng (mặc định 5 phút).
    - Giới hạn số lần nhập sai (mặc định tối đa 5 lần).
    - Mỗi mã chỉ dùng 1 lần (tự động xóa ngay khi xác thực thành công).
    """
    def __init__(self):
        # Cấu trúc: {phone: {"code_hash": str, "expires_at": datetime, "attempt_count": int, "created_at": datetime}}
        self._otps: dict[str, dict] = {}

    def set_otp(self, phone: str, otp_code: str, role: str = "USER"):
        now = datetime.now(timezone.utc)
        self._otps[phone] = {
            "code_hash": hash_otp(otp_code),
            "role": role.strip().upper(),
            "expires_at": now + timedelta(minutes=config.OTP_EXPIRE_MINUTES),
            "attempt_count": 0,
            "created_at": now
        }
        logger.info(f"Đã lưu OTP trong bộ nhớ cho số điện thoại: {phone} (role={role})")

    def get_role(self, phone: str) -> str:
        record = self._otps.get(phone)
        if record:
            return record.get("role", "USER")
        return "USER"

    def get_last_created_time(self, phone: str) -> datetime | None:
        record = self._otps.get(phone)
        if record:
            return record.get("created_at")
        return None

    def verify_otp(self, phone: str, input_otp: str) -> tuple[bool, str]:
        record = self._otps.get(phone)
        if not record:
            return False, "Mã OTP không hợp lệ hoặc đã được sử dụng. Vui lòng yêu cầu mã mới."

        now = datetime.now(timezone.utc)
        if now > record["expires_at"]:
            self._otps.pop(phone, None)
            return False, "Mã OTP đã hết hiệu lực. Vui lòng yêu cầu mã mới."

        if record["attempt_count"] >= config.OTP_MAX_ATTEMPTS:
            self._otps.pop(phone, None)
            return False, "Mã OTP đã bị vô hiệu do nhập sai quá 5 lần. Vui lòng yêu cầu mã mới."

        if not verify_otp_hash(input_otp, record["code_hash"]):
            record["attempt_count"] += 1
            remaining = config.OTP_MAX_ATTEMPTS - record["attempt_count"]
            if remaining <= 0:
                self._otps.pop(phone, None)
                return False, "Mã OTP đã bị vô hiệu do nhập sai quá 5 lần. Vui lòng yêu cầu mã mới."
            return False, f"Mã OTP không chính xác. Bạn còn {remaining} lần thử."

        # Xác thực thành công: xóa ngay khỏi bộ nhớ để mã chỉ dùng đúng 1 lần
        self._otps.pop(phone, None)
        return True, "Xác minh thành công."

    def clear(self, phone: str = None):
        """Xóa OTP của 1 số hoặc toàn bộ (dùng trong test)"""
        if phone:
            self._otps.pop(phone, None)
        else:
            self._otps.clear()

otp_store = InMemoryOtpStore()
