import os
from pathlib import Path
from dotenv import load_dotenv

# Nạp file .env trong cùng thư mục backend
env_path = Path(__file__).resolve().parent / ".env"
load_dotenv(dotenv_path=env_path, override=False)

PGHOST = os.getenv("PGHOST", "127.0.0.1")
PGPORT = int(os.getenv("PGPORT", "5432"))
PGDATABASE = os.getenv("PGDATABASE", "sos_db")
PGUSER = os.getenv("PGUSER", "postgres")
PGPASSWORD = os.getenv("PGPASSWORD", "postgres")

JWT_SECRET = os.getenv("JWT_SECRET", "super_secret_sos_rescue_key_2026_antigravity_deepmind")
JWT_ALGORITHM = os.getenv("JWT_ALGORITHM", "HS256")
JWT_EXPIRE_MINUTES = int(os.getenv("JWT_EXPIRE_MINUTES", str(60 * 24)))  # 1 ngày (24 giờ)

TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
DEV_MOCK_OTP = os.getenv("DEV_MOCK_OTP", "true").lower() in ("true", "1", "yes")

OTP_EXPIRE_MINUTES = int(os.getenv("OTP_EXPIRE_MINUTES", "5"))
OTP_MAX_ATTEMPTS = int(os.getenv("OTP_MAX_ATTEMPTS", "5"))
SOS_TIMEOUT_HOURS = int(os.getenv("SOS_TIMEOUT_HOURS", "2"))

# Optional built React admin, served by FastAPI at /admin/.
ADMIN_DIST_DIR = os.getenv("ADMIN_DIST_DIR", str((Path(__file__).resolve().parent / "../../../Admin/dist").resolve()))
