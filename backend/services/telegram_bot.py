import asyncio
import logging
import httpx
from telegram import Update, KeyboardButton, ReplyKeyboardMarkup, ReplyKeyboardRemove
from telegram.ext import Application, CommandHandler, MessageHandler, filters, ContextTypes
import config
from database.connection import get_db_connection
from services.auth_service import normalize_phone

logger = logging.getLogger("backend.telegram")

_bot_app: Application | None = None
_bot_task: asyncio.Task | None = None

async def start_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
    """Xử lý lệnh /start - Hiển thị nút chia sẻ số điện thoại"""
    contact_button = KeyboardButton(text="📱 Chia sẻ số điện thoại", request_contact=True)
    reply_markup = ReplyKeyboardMarkup([[contact_button]], resize_keyboard=True, one_time_keyboard=True)
    
    welcome_text = (
        "🚨 *HỆ THỐNG HỖ TRỢ CỨU HỘ KHẨN CẤP SOS*\n\n"
        "Chào mừng bạn! Để xác thực số điện thoại và nhận mã OTP trên ứng dụng di động, "
        "vui lòng nhấn nút *'📱 Chia sẻ số điện thoại'* bên dưới.\n\n"
        "*(Hệ thống cam kết bảo mật số điện thoại và chỉ dùng để gửi mã xác thực cứu hộ)*"
    )
    if update.message:
        await update.message.reply_text(welcome_text, parse_mode="Markdown", reply_markup=reply_markup)

async def help_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
    """Xử lý lệnh /help"""
    help_text = (
        "ℹ️ *HƯỚNG DẪN SỬ DỤNG BOT SOS*\n\n"
        "1️⃣ Gửi lệnh /start để mở bàn phím chia sẻ số điện thoại.\n"
        "2️⃣ Nhấn nút *'📱 Chia sẻ số điện thoại'* để liên kết tài khoản.\n"
        "3️⃣ Mở ứng dụng Android SOS trên điện thoại, nhập số điện thoại để nhận mã OTP xác thực."
    )
    if update.message:
        await update.message.reply_text(help_text, parse_mode="Markdown")

async def non_contact_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    """Nhắc nhở người dùng bấm nút chia sẻ liên hệ nếu họ gõ tin nhắn văn bản"""
    contact_button = KeyboardButton(text="📱 Chia sẻ số điện thoại", request_contact=True)
    reply_markup = ReplyKeyboardMarkup([[contact_button]], resize_keyboard=True, one_time_keyboard=True)
    guide_text = (
        "⚠️ Để bảo mật và tránh mạo danh, hệ thống *không nhận số điện thoại nhập bằng tay*.\n\n"
        "👉 Vui lòng nhấn trực tiếp vào nút *'📱 Chia sẻ số điện thoại'* ở bàn phím bên dưới."
    )
    if update.message:
        await update.message.reply_text(guide_text, parse_mode="Markdown", reply_markup=reply_markup)

async def contact_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    """Xử lý khi người dùng nhấn nút chia sẻ liên hệ trên Telegram"""
    contact = update.message.contact
    if not contact:
        await update.message.reply_text("Không nhận được thông tin liên hệ. Vui lòng thử lại.")
        return

    raw_phone = contact.phone_number
    phone = normalize_phone(raw_phone)
    chat_id = update.effective_chat.id

    try:
        with get_db_connection() as conn:
            with conn.cursor() as cursor:
                # Cập nhật telegram_chat_id trực tiếp vào bảng users
                # Nếu người dùng chưa có tài khoản: tạo mới với is_valid = false
                cursor.execute(
                    """
                    INSERT INTO users (full_name, phone, telegram_chat_id, account_status, is_valid)
                    VALUES (%s, %s, %s, 'ACTIVE', false)
                    ON CONFLICT (phone) DO UPDATE 
                    SET telegram_chat_id = EXCLUDED.telegram_chat_id
                    """,
                    (phone, phone, chat_id)
                )
        
        logger.info(f"Đã liên kết Telegram chat_id={chat_id} với số điện thoại={phone} trong bảng users")
        success_msg = (
            f"✅ *Liên kết thành công!*\n\n"
            f"Số điện thoại *{phone}* đã được liên kết với tài khoản Telegram của bạn.\n"
            f"Bây giờ bạn hãy quay lại ứng dụng SOS trên điện thoại để nhận mã OTP và đăng nhập."
        )
        await update.message.reply_text(success_msg, parse_mode="Markdown", reply_markup=ReplyKeyboardRemove())
    except Exception as e:
        logger.error(f"Lỗi khi lưu telegram_chat_id vào users: {e}")
        await update.message.reply_text("❌ Đã xảy ra lỗi khi lưu thông tin. Vui lòng thử lại sau.")

async def send_otp_telegram(phone: str, chat_id: int, otp_code: str) -> bool:
    """
    Gửi mã OTP trực tiếp tới Telegram Chat ID của người dùng.
    """
    if not config.TELEGRAM_BOT_TOKEN:
        logger.info(f"[DEV MOCK] Bỏ qua gửi Telegram vì chưa có token. OTP cho {phone} là: {otp_code}")
        return True

    text = (
        f"🚨 *[HỆ THỐNG CỨU HỘ KHẨN CẤP SOS]*\n\n"
        f"Mã OTP xác thực của bạn là: *{otp_code}*\n\n"
        f"⏱ Thời gian hiệu lực: *{config.OTP_EXPIRE_MINUTES} phút*.\n"
        f"⚠️ Tuyệt đối không chia sẻ mã này cho bất kỳ ai!"
    )
    url = f"https://api.telegram.org/bot{config.TELEGRAM_BOT_TOKEN}/sendMessage"
    
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(url, json={"chat_id": chat_id, "text": text, "parse_mode": "Markdown"})
            if resp.status_code == 200:
                logger.info(f"Đã gửi OTP thành công tới Telegram chat_id={chat_id} (phone={phone})")
                return True
            else:
                logger.error(f"Lỗi từ Telegram API: {resp.status_code} - {resp.text}")
                return False
    except Exception as e:
        logger.error(f"Ngoại lệ khi gửi tin nhắn Telegram: {e}")
        return False

async def start_telegram_bot():
    """Khởi động Telegram Bot polling trong background nếu có token, tự động thử lại nếu mạng chập chờn"""
    global _bot_app, _bot_task
    if not config.TELEGRAM_BOT_TOKEN:
        logger.warning("TELEGRAM_BOT_TOKEN không được cung cấp. Chạy chế độ MOCK OTP cho kiểm thử và demo.")
        return

    max_retries = 5
    for attempt in range(1, max_retries + 1):
        try:
            logger.info(f"Đang khởi tạo Telegram Bot (lần thử {attempt}/{max_retries})...")
            _bot_app = Application.builder().token(config.TELEGRAM_BOT_TOKEN).build()
            _bot_app.add_handler(CommandHandler("start", start_command))
            _bot_app.add_handler(CommandHandler("help", help_command))
            _bot_app.add_handler(MessageHandler(filters.CONTACT, contact_handler))
            _bot_app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, non_contact_handler))

            await _bot_app.initialize()
            await _bot_app.start()
            _bot_task = asyncio.create_task(_bot_app.updater.start_polling())
            logger.info("✅ Telegram Bot đã khởi động và đang lắng nghe tin nhắn!")
            return
        except Exception as e:
            logger.warning(f"Lỗi khi khởi động Telegram Bot (lần {attempt}): {e}")
            if attempt < max_retries:
                await asyncio.sleep(3)
            else:
                logger.error("❌ Không thể kết nối tới Telegram API sau nhiều lần thử. Vui lòng kiểm tra kết nối mạng/proxy/VPN.")

async def stop_telegram_bot():
    """Dừng Telegram Bot an toàn khi tắt server"""
    global _bot_app
    if _bot_app is not None:
        try:
            logger.info("Đang dừng Telegram Bot...")
            if _bot_app.updater and _bot_app.updater.running:
                await _bot_app.updater.stop()
            if _bot_app.running:
                await _bot_app.stop()
            await _bot_app.shutdown()
            logger.info("Telegram Bot đã dừng thành công.")
        except Exception as e:
            logger.error(f"Lỗi khi dừng Telegram Bot: {e}")
