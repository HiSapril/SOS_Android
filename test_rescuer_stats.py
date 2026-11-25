"""Rescuer statistics integration tests on PostgreSQL in a disposable schema."""
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import unittest
from unittest.mock import patch
from uuid import uuid4

import psycopg
from psycopg import sql
from psycopg.rows import dict_row
from fastapi import FastAPI
from fastapi.testclient import TestClient

from database.connection import get_conninfo
from routes import rescuer
from services import auth_service


_VN_TZ = timezone(timedelta(hours=7))


class RescuerStatsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schema = "test_rescuer_stats_" + uuid4().hex
        cls.conninfo = get_conninfo()
        with psycopg.connect(cls.conninfo) as conn:
            conn.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(cls.schema)))
        cls.addClassCleanup(cls.drop_schema)

        @contextmanager
        def isolated_connection():
            with psycopg.connect(cls.conninfo, options=f"-c search_path={cls.schema}", row_factory=dict_row) as conn:
                yield conn

        cls.db = staticmethod(isolated_connection)
        with cls.db() as conn:
            conn.execute("""
                CREATE TABLE users (
                    id UUID PRIMARY KEY, full_name TEXT, phone TEXT,
                    account_status TEXT DEFAULT 'ACTIVE', is_valid BOOLEAN DEFAULT true,
                    created_at TIMESTAMPTZ DEFAULT now()
                );
                CREATE TABLE rescue_team_members (
                    member_id UUID PRIMARY KEY, team_id UUID, user_id UUID, role TEXT
                );
                CREATE TABLE emergency_types (
                    id UUID PRIMARY KEY, name TEXT, description TEXT, created_at TIMESTAMPTZ DEFAULT now()
                );
                CREATE TABLE sos_requests (
                    id UUID PRIMARY KEY, requester_id UUID, team_id UUID, emergency_type_id UUID,
                    description TEXT, visibility TEXT, status TEXT,
                    created_at TIMESTAMPTZ DEFAULT now(), updated_at TIMESTAMPTZ DEFAULT now()
                );
                CREATE TABLE location_history (
                    history_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    sos_id UUID NOT NULL, actor TEXT NOT NULL,
                    lat DOUBLE PRECISION NOT NULL, lng DOUBLE PRECISION NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
            """)
        for module in (rescuer, auth_service):
            patcher = patch.object(module, "get_db_connection", isolated_connection)
            patcher.start()
            cls.addClassCleanup(patcher.stop)
        cls.app = FastAPI()
        cls.app.include_router(rescuer.router)
        cls.client = TestClient(cls.app)
        cls.addClassCleanup(cls.client.close)

    @classmethod
    def drop_schema(cls):
        assert cls.schema.startswith("test_rescuer_stats_")
        with psycopg.connect(cls.conninfo) as conn:
            conn.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(cls.schema)))

    def setUp(self):
        self.rescuer_id = uuid4()
        self.other_rescuer_id = uuid4()
        self.victim_id = uuid4()
        self.my_team_id = uuid4()
        self.other_team_id = uuid4()
        self.flood_id = uuid4()
        self.accident_id = uuid4()
        self.fire_id = uuid4()

        with self.db() as conn:
            conn.execute("TRUNCATE location_history, sos_requests, emergency_types, rescue_team_members, users")
            for uid, phone in (
                (self.rescuer_id, "0900000001"),
                (self.other_rescuer_id, "0900000002"),
                (self.victim_id, "0900000003"),
            ):
                conn.execute("INSERT INTO users (id, full_name, phone) VALUES (%s, %s, %s)", (uid, "User", phone))
            conn.execute(
                "INSERT INTO rescue_team_members VALUES (%s, %s, %s, 'LEADER'), (%s, %s, %s, 'MEMBER')",
                (uuid4(), self.my_team_id, self.rescuer_id, uuid4(), self.other_team_id, self.other_rescuer_id),
            )
            conn.execute(
                "INSERT INTO emergency_types (id, name, description) VALUES (%s, 'NGAP_LUT', 'Ngập lụt'), (%s, 'TAI_NAN', 'Tai nạn giao thông'), (%s, 'CHAY_NO', 'Hỏa hoạn')",
                (self.flood_id, self.accident_id, self.fire_id),
            )

            # Day 1: 2026-11-25 (2 completed cases: 1 NGAP_LUT with GPS track, 1 TAI_NAN without GPS track)
            t_day1 = datetime(2026, 11, 25, 10, 0, tzinfo=_VN_TZ)
            sos1 = uuid4()
            sos2 = uuid4()
            # Day 2 in same month: 2026-11-10 (1 completed NGAP_LUT)
            t_day2 = datetime(2026, 11, 10, 14, 0, tzinfo=_VN_TZ)
            sos3 = uuid4()
            # Previous month: 2026-10-15 (1 completed CHAY_NO)
            t_prev_month = datetime(2026, 10, 15, 9, 0, tzinfo=_VN_TZ)
            sos4 = uuid4()
            # Non-completed or other team (must NOT be counted)
            sos_active = uuid4()
            sos_cancelled = uuid4()
            sos_other_team = uuid4()

            for sid, tid, eid, status_val, ts in (
                (sos1, self.my_team_id, self.flood_id, "COMPLETED", t_day1),
                (sos2, self.my_team_id, self.accident_id, "COMPLETED", t_day1),
                (sos3, self.my_team_id, self.flood_id, "COMPLETED", t_day2),
                (sos4, self.my_team_id, self.fire_id, "COMPLETED", t_prev_month),
                (sos_active, self.my_team_id, self.flood_id, "RESCUING", t_day1),
                (sos_cancelled, self.my_team_id, self.flood_id, "CANCELLED", t_day1),
                (sos_other_team, self.other_team_id, self.flood_id, "COMPLETED", t_day1),
            ):
                conn.execute(
                    """
                    INSERT INTO sos_requests (id, requester_id, team_id, emergency_type_id, visibility, status, created_at, updated_at)
                    VALUES (%s, %s, %s, %s, 'PRIVATE', %s, %s, %s)
                    """,
                    (sid, self.victim_id, tid, eid, status_val, ts, ts),
                )

            # GPS trail for sos1 (~1.11 km north + ~1.09 km east) + VICTIM noise point (must be ignored)
            conn.execute(
                """
                INSERT INTO location_history (sos_id, actor, lat, lng, created_at) VALUES
                (%s, 'RESCUER', 10.7700, 106.7000, %s),
                (%s, 'RESCUER', 10.7800, 106.7000, %s),
                (%s, 'VICTIM',  11.9999, 107.9999, %s),
                (%s, 'RESCUER', 10.7800, 106.7100, %s)
                """,
                (
                    sos1, t_day1,
                    sos1, t_day1 + timedelta(minutes=2),
                    sos1, t_day1 + timedelta(minutes=3),
                    sos1, t_day1 + timedelta(minutes=5),
                ),
            )

    def headers(self, user_id=None, role="RESCUER"):
        token = auth_service.create_access_token(str(user_id or self.rescuer_id), "0900000001", role)
        return {"Authorization": f"Bearer {token}"}

    def test_all_time_and_type_breakdown_with_distance(self):
        res = self.client.get("/api/rescuer/stats?period=ALL", headers=self.headers())
        self.assertEqual(200, res.status_code, res.text)
        body = res.json()
        self.assertEqual(4, body["overall"]["rescued_count"])
        self.assertEqual(1, body["overall"]["tracked_missions"])
        self.assertGreater(body["overall"]["distance_km"], 2.0)
        self.assertLess(body["overall"]["distance_km"], 2.4)

        by_type = {item["emergency_type"]: item for item in body["by_emergency_type"]}
        self.assertEqual(2, by_type["NGAP_LUT"]["rescued_count"])
        self.assertEqual(1, by_type["NGAP_LUT"]["tracked_missions"])
        self.assertEqual(1, by_type["TAI_NAN"]["rescued_count"])
        self.assertEqual(0.0, by_type["TAI_NAN"]["distance_km"])
        self.assertEqual(1, by_type["CHAY_NO"]["rescued_count"])

    def test_day_and_month_and_emergency_type_filters(self):
        day_res = self.client.get("/api/rescuer/stats?period=DAY&date=2026-11-25", headers=self.headers())
        self.assertEqual(200, day_res.status_code, day_res.text)
        day_body = day_res.json()
        self.assertEqual(2, day_body["summary"]["rescued_count"])
        self.assertEqual("2026-11-25", day_body["date"])

        month_res = self.client.get(
            f"/api/rescuer/stats?period=MONTH&month=2026-11&emergency_type_id={self.flood_id}",
            headers=self.headers(),
        )
        self.assertEqual(200, month_res.status_code, month_res.text)
        month_body = month_res.json()
        self.assertEqual(3, month_body["overall"]["rescued_count"])
        self.assertEqual(2, month_body["summary"]["rescued_count"])

    def test_invalid_dates_and_non_rescuer_rejected(self):
        bad_day = self.client.get("/api/rescuer/stats?period=DAY&date=25-11-2026", headers=self.headers())
        self.assertEqual(400, bad_day.status_code)

        bad_month = self.client.get("/api/rescuer/stats?period=MONTH&month=2026/11", headers=self.headers())
        self.assertEqual(400, bad_month.status_code)

        forbidden = self.client.get("/api/rescuer/stats", headers=self.headers(user_id=self.victim_id, role="USER"))
        self.assertEqual(403, forbidden.status_code)


if __name__ == "__main__":
    unittest.main()
