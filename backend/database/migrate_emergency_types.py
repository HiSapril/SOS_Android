"""
migrate_emergency_types.py
Tách emergency_type thành bảng riêng và liên kết FK với sos_requests.
"""
import os
import sys
if sys.stdout.encoding != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from database.connection import get_db_connection

def migrate():
    with get_db_connection() as conn:
        with conn.cursor() as cur:
            print("1. Tạo bảng emergency_types...")
            cur.execute("""
            CREATE TABLE IF NOT EXISTS emergency_types (
                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                name VARCHAR(50) UNIQUE NOT NULL,
                description TEXT,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            CREATE INDEX IF NOT EXISTS idx_emergency_types_name ON emergency_types(name);
            """)

            print("2. Seed danh mục emergency_types chuẩn...")
            seed_types = [
                ('eeeeeeee-0001-0000-0000-000000000001', 'NGAP_LUT', 'Ngập lụt, triều cường dâng cao cô lập nhà cửa khu dân cư'),
                ('eeeeeeee-0002-0000-0000-000000000002', 'LO_DAT', 'Sạt lở đất đá, chia cắt giao thông và nguy cơ sập nhà'),
                ('eeeeeeee-0003-0000-0000-000000000003', 'TAI_NAN', 'Tai nạn giao thông, chấn thương nghiêm trọng cần cứu nạn'),
                ('eeeeeeee-0004-0000-0000-000000000004', 'CHAY_NO', 'Hỏa hoạn, cháy nổ nhà dân, xưởng sản xuất cần dập lửa'),
                ('eeeeeeee-0005-0000-0000-000000000005', 'MAC_KET', 'Nạn nhân bị mắc kẹt trong đống đổ nát hoặc vùng nguy hiểm'),
                ('eeeeeeee-0006-0000-0000-000000000006', 'Y_TE_KHAN_CAP', 'Cấp cứu y tế khẩn cấp, bệnh nhân nguy kịch cần hỗ trợ y bác sĩ'),
                ('eeeeeeee-0007-0000-0000-000000000007', 'KHAC', 'Các tình huống khẩn cấp khác cần đội phản ứng nhanh')
            ]
            for tid, name, desc in seed_types:
                cur.execute("""
                    INSERT INTO emergency_types (id, name, description)
                    VALUES (%s, %s, %s)
                    ON CONFLICT (name) DO UPDATE SET description = EXCLUDED.description
                """, (tid, name, desc))

            print("3. Kiểm tra và thêm emergency_type_id vào sos_requests...")
            cur.execute("""
                SELECT column_name FROM information_schema.columns 
                WHERE table_name = 'sos_requests' AND column_name = 'emergency_type_id'
            """)
            if not cur.fetchone():
                cur.execute("ALTER TABLE sos_requests ADD COLUMN emergency_type_id UUID;")

            print("4. Chuyển đổi dữ liệu từ emergency_type sang emergency_type_id...")
            cur.execute("""
                SELECT column_name FROM information_schema.columns 
                WHERE table_name = 'sos_requests' AND column_name = 'emergency_type'
            """)
            if cur.fetchone():
                # Gán id tương ứng từ emergency_types
                cur.execute("""
                    UPDATE sos_requests sr
                    SET emergency_type_id = et.id
                    FROM emergency_types et
                    WHERE sr.emergency_type = et.name AND sr.emergency_type_id IS NULL;
                """)
                # Nếu có loại nào còn NULL thì gán 'KHAC'
                cur.execute("""
                    UPDATE sos_requests
                    SET emergency_type_id = 'eeeeeeee-0007-0000-0000-000000000007'
                    WHERE emergency_type_id IS NULL;
                """)

                # Drop views trước khi drop column
                cur.execute("DROP VIEW IF EXISTS public_sos_map CASCADE;")
                cur.execute("DROP VIEW IF EXISTS rescue_queue CASCADE;")

                # Drop cột cũ
                cur.execute("ALTER TABLE sos_requests DROP COLUMN emergency_type;")

            print("5. Thiết lập ràng buộc NOT NULL và FK...")
            cur.execute("""
                ALTER TABLE sos_requests ALTER COLUMN emergency_type_id SET NOT NULL;
            """)
            cur.execute("""
                SELECT constraint_name FROM information_schema.table_constraints 
                WHERE constraint_name = 'fk_sos_emergency_type'
            """)
            if not cur.fetchone():
                cur.execute("""
                    ALTER TABLE sos_requests 
                    ADD CONSTRAINT fk_sos_emergency_type 
                    FOREIGN KEY (emergency_type_id) REFERENCES emergency_types(id) ON DELETE RESTRICT;
                """)
            cur.execute("CREATE INDEX IF NOT EXISTS idx_sos_emergency_type ON sos_requests(emergency_type_id);")

            print("6. Tái tạo Views public_sos_map & rescue_queue...")
            cur.execute("""
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
            """)

            conn.commit()
            print("✅ ĐÃ CHUYỂN ĐỔI DATABASE SANG BẢNG emergency_types THÀNH CÔNG 100%!")

if __name__ == "__main__":
    migrate()
