-- =============================================================================
-- HỆ THỐNG HỖ TRỢ CỨU HỘ KHẨN CẤP (SOS RESCUE SYSTEM)
-- Database Schema Script: sos_db
-- Thiết kế tối ưu: Xác thực OTP Telegram, lưu chat_id & is_valid trong users
-- (Đã lược bỏ otp_codes, telegram_links và password_hash)
-- =============================================================================

-- 0. Dọn dẹp schema cũ nếu có (thứ tự drop phù hợp khóa ngoại)
DROP VIEW IF EXISTS public_sos_map CASCADE;
DROP VIEW IF EXISTS rescue_queue CASCADE;
DROP TABLE IF EXISTS sos_notifications CASCADE;
DROP TABLE IF EXISTS sos_logs CASCADE;
DROP TABLE IF EXISTS location_history CASCADE;
DROP TABLE IF EXISTS location_current CASCADE;
DROP TABLE IF EXISTS sos_requests CASCADE;
DROP TABLE IF EXISTS emergency_types CASCADE;
DROP TABLE IF EXISTS rescue_team_members CASCADE;
DROP TABLE IF EXISTS rescue_teams CASCADE;
DROP TABLE IF EXISTS otp_codes CASCADE;
DROP TABLE IF EXISTS telegram_links CASCADE;
DROP TABLE IF EXISTS users CASCADE;

-- Kích hoạt pgcrypto để hỗ trợ gen_random_uuid
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- =============================================================================
-- 1. BẢNG users: Hồ sơ tài khoản người dùng và đội cứu hộ
-- Lưu trực tiếp telegram_chat_id và cờ xác thực is_valid (mới: false, sau OTP: true)
-- =============================================================================
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name VARCHAR(100) NOT NULL,
    phone VARCHAR(15) UNIQUE NOT NULL,
    telegram_chat_id BIGINT UNIQUE,
    account_status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (account_status IN ('ACTIVE', 'DISABLED')),
    is_valid BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_phone ON users(phone);
CREATE INDEX idx_users_telegram_chat ON users(telegram_chat_id);

-- =============================================================================
-- 2. BẢNG rescue_teams: Thông tin các đội cứu hộ
-- =============================================================================
CREATE TABLE rescue_teams (
    team_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'BUSY', 'INACTIVE'))
);

-- =============================================================================
-- 3. BẢNG rescue_team_members: Thành viên của từng đội cứu hộ
-- =============================================================================
CREATE TABLE rescue_team_members (
    member_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id UUID NOT NULL REFERENCES rescue_teams(team_id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('ADMIN', 'LEADER', 'MEMBER', 'DRIVER', 'MEDIC')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_team_member UNIQUE (team_id, user_id)
);

CREATE INDEX idx_team_members_team ON rescue_team_members(team_id);
CREATE INDEX idx_team_members_user ON rescue_team_members(user_id);

-- =============================================================================
-- 4. BẢNG emergency_types: Danh mục loại sự cố khẩn cấp
-- =============================================================================
CREATE TABLE emergency_types (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(50) UNIQUE NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_emergency_types_name ON emergency_types(name);

-- =============================================================================
-- 5. BẢNG sos_requests: Yêu cầu cứu hộ khẩn cấp
-- =============================================================================
CREATE TABLE sos_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    requester_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    team_id UUID REFERENCES rescue_teams(team_id) ON DELETE RESTRICT,
    emergency_type_id UUID NOT NULL REFERENCES emergency_types(id) ON DELETE RESTRICT,
    description TEXT,
    visibility VARCHAR(10) NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    status VARCHAR(10) NOT NULL DEFAULT 'WAITING' CHECK (status IN ('WAITING', 'ACCEPTED', 'RESCUING', 'ARRIVED', 'COMPLETED', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Ràng buộc RB04: Mỗi người gửi chỉ có tối đa 1 SOS chưa kết thúc
CREATE UNIQUE INDEX idx_unique_active_requester 
ON sos_requests (requester_id) 
WHERE status IN ('WAITING', 'ACCEPTED', 'RESCUING', 'ARRIVED');

-- Ràng buộc RB05: Mỗi đội cứu hộ chỉ nhận tối đa 1 ca đang xử lý
CREATE UNIQUE INDEX idx_unique_active_team 
ON sos_requests (team_id) 
WHERE status IN ('ACCEPTED', 'RESCUING', 'ARRIVED');

-- Chỉ mục hỗ trợ truy vấn nghiệp vụ
CREATE INDEX idx_sos_status_created ON sos_requests(status, created_at);
CREATE INDEX idx_sos_requester ON sos_requests(requester_id);
CREATE INDEX idx_sos_team ON sos_requests(team_id);

-- =============================================================================
-- 5. BẢNG location_current: Vị trí mới nhất theo thời gian thực (Khớp 100% ERD)
-- =============================================================================
CREATE TABLE location_current (
    loc_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sos_id UUID NOT NULL REFERENCES sos_requests(id) ON DELETE CASCADE,
    actor VARCHAR(10) NOT NULL CHECK (actor IN ('VICTIM', 'RESCUER')),
    lat DOUBLE PRECISION NOT NULL CHECK (lat >= -90.0 AND lat <= 90.0),
    lng DOUBLE PRECISION NOT NULL CHECK (lng >= -180.0 AND lng <= 180.0),
    accuracy_m REAL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_location_current_sos_actor UNIQUE (sos_id, actor)
);

-- =============================================================================
-- 6. BẢNG location_history: Các điểm lộ trình phục vụ vẽ Polyline (Khớp 100% ERD)
-- =============================================================================
CREATE TABLE location_history (
    history_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sos_id UUID NOT NULL REFERENCES sos_requests(id) ON DELETE CASCADE,
    actor VARCHAR(10) NOT NULL CHECK (actor IN ('VICTIM', 'RESCUER')),
    lat DOUBLE PRECISION NOT NULL CHECK (lat >= -90.0 AND lat <= 90.0),
    lng DOUBLE PRECISION NOT NULL CHECK (lng >= -180.0 AND lng <= 180.0),
    accuracy_m REAL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_location_history_sos_actor_time 
ON location_history(sos_id, actor, created_at DESC);

-- =============================================================================
-- 7. BẢNG sos_notifications: Thông báo hệ thống cho các bên
-- =============================================================================
CREATE TABLE sos_notifications (
    notify_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sos_id UUID NOT NULL REFERENCES sos_requests(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    message TEXT NOT NULL,
    type VARCHAR(20) NOT NULL DEFAULT 'SYSTEM' CHECK (type IN ('SYSTEM', 'UPDATE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_read BOOLEAN NOT NULL DEFAULT false
);

CREATE INDEX idx_notifications_user ON sos_notifications(user_id, created_at DESC);
CREATE INDEX idx_notifications_sos ON sos_notifications(sos_id);

-- =============================================================================
-- 8. BẢNG sos_logs: Nhật ký hoạt động chi tiết của phiên cứu hộ
-- =============================================================================
CREATE TABLE sos_logs (
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sos_id UUID NOT NULL REFERENCES sos_requests(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(50) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_logs_sos ON sos_logs(sos_id, created_at ASC);

-- =============================================================================
-- 9. VIEW public_sos_map: Bản tóm tắt cho bản đồ cộng đồng (USER)
-- Lấy tọa độ từ location_current (actor='VICTIM') và làm tròn an toàn
-- =============================================================================
CREATE OR REPLACE VIEW public_sos_map AS
SELECT
    r.id AS sos_id,
    et.name AS emergency_type,
    r.emergency_type_id,
    ROUND(CAST(lc.lat AS NUMERIC), 2)::DOUBLE PRECISION AS area_lat,
    ROUND(CAST(lc.lng AS NUMERIC), 2)::DOUBLE PRECISION AS area_lng,
    r.created_at
FROM sos_requests r
JOIN emergency_types et ON et.id = r.emergency_type_id
JOIN location_current lc ON lc.sos_id = r.id AND lc.actor = 'VICTIM'
WHERE r.visibility = 'PUBLIC'
  AND r.status IN ('WAITING', 'ACCEPTED', 'RESCUING', 'ARRIVED');

-- =============================================================================
-- 10. VIEW rescue_queue: Hàng đợi cứu hộ dành cho RESCUER
-- Lấy tọa độ từ location_current (actor='VICTIM')
-- =============================================================================
CREATE OR REPLACE VIEW rescue_queue AS
SELECT
    r.id AS sos_id,
    et.name AS emergency_type,
    r.emergency_type_id,
    r.visibility,
    lc.lat AS initial_lat,
    lc.lng AS initial_lng,
    r.created_at
FROM sos_requests r
JOIN emergency_types et ON et.id = r.emergency_type_id
JOIN location_current lc ON lc.sos_id = r.id AND lc.actor = 'VICTIM'
WHERE r.status = 'WAITING'
  AND (now() - r.created_at <= INTERVAL '2 hours');

-- =============================================================================
-- DỮ LIỆU KHỞI TẠO (SEED DATA)
-- Khởi tạo Emergency Types, Users, các đội cứu hộ
-- =============================================================================

-- 0. Tạo các loại sự cố khẩn cấp (emergency_types)
INSERT INTO emergency_types (id, name, description) VALUES
('eeeeeeee-0001-0000-0000-000000000001', 'NGAP_LUT', 'Ngập lụt, triều cường dâng cao cô lập nhà cửa khu dân cư'),
('eeeeeeee-0002-0000-0000-000000000002', 'LO_DAT', 'Sạt lở đất đá, chia cắt giao thông và nguy cơ sập nhà'),
('eeeeeeee-0003-0000-0000-000000000003', 'TAI_NAN', 'Tai nạn giao thông, chấn thương nghiêm trọng cần cứu nạn'),
('eeeeeeee-0004-0000-0000-000000000004', 'CHAY_NO', 'Hỏa hoạn, cháy nổ nhà dân, xưởng sản xuất cần dập lửa'),
('eeeeeeee-0005-0000-0000-000000000005', 'MAC_KET', 'Nạn nhân bị mắc kẹt trong đống đổ nát hoặc vùng nguy hiểm'),
('eeeeeeee-0006-0000-0000-000000000006', 'Y_TE_KHAN_CAP', 'Cấp cứu y tế khẩn cấp, bệnh nhân nguy kịch cần hỗ trợ y bác sĩ'),
('eeeeeeee-0007-0000-0000-000000000007', 'KHAC', 'Các tình huống khẩn cấp khác cần đội phản ứng nhanh');

-- 1. Tạo Users mẫu (bao gồm telegram_chat_id và is_valid, full_name mặc định là phone)
INSERT INTO users (id, full_name, phone, telegram_chat_id, account_status, is_valid) VALUES
('11111111-1111-1111-1111-111111111111', '0901112222', '0901112222', 100000001, 'ACTIVE', true),
('22222222-2222-2222-2222-222222222222', '0903334444', '0903334444', 100000002, 'ACTIVE', true),
('33333333-3333-3333-3333-333333333333', '0901234567', '0901234567', 100000003, 'ACTIVE', true),
('44444444-4444-4444-4444-444444444444', '0912345678', '0912345678', 100000004, 'ACTIVE', true);

-- 2. Tạo Đội Cứu Hộ (rescue_teams)
INSERT INTO rescue_teams (team_id, name, description, status) VALUES
('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Đội Cứu Hộ Phản Ứng Nhanh Quận 1', 'Đội cứu hộ chuyên nghiệp ứng trực khu vực trung tâm', 'ACTIVE'),
('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'Đội Cứu Hộ Đội 2 TP.HCM', 'Đội cứu hộ chuyên trách đường bộ và cứu nạn ngập lụt', 'ACTIVE');

-- 3. Gán thành viên cho Đội (rescue_team_members)
INSERT INTO rescue_team_members (member_id, team_id, user_id, role) VALUES
('11111111-aaaa-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', 'LEADER'),
('22222222-bbbb-2222-2222-222222222222', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '22222222-2222-2222-2222-222222222222', 'LEADER');
