"""Rescuer map integration tests on PostgreSQL in a disposable schema."""
from contextlib import contextmanager
import unittest
from unittest.mock import patch
from uuid import uuid4

import psycopg
from psycopg import sql
from psycopg.rows import dict_row
from fastapi import FastAPI
from fastapi.testclient import TestClient

from database.connection import get_conninfo
from routes import rescuer, sos
from services import auth_service


class RescuerMapTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schema = "test_rescuer_map_" + uuid4().hex
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
                    id UUID PRIMARY KEY, full_name TEXT, phone TEXT, account_status TEXT DEFAULT 'ACTIVE',
                    is_valid BOOLEAN DEFAULT true, created_at TIMESTAMPTZ DEFAULT now()
                );
                CREATE TABLE rescue_team_members (member_id UUID PRIMARY KEY, team_id UUID, user_id UUID, role TEXT);
                CREATE TABLE emergency_types (id UUID PRIMARY KEY, name TEXT);
                CREATE TABLE sos_requests (
                    id UUID PRIMARY KEY, emergency_type_id UUID, description TEXT, visibility TEXT,
                    status TEXT, created_at TIMESTAMPTZ DEFAULT now()
                );
                CREATE TABLE location_current (sos_id UUID, actor TEXT, lat DOUBLE PRECISION, lng DOUBLE PRECISION,
                                               PRIMARY KEY (sos_id, actor));
                CREATE VIEW public_sos_map AS
                    SELECT s.id AS sos_id, et.name AS emergency_type,
                           ROUND(lc.lat::NUMERIC, 2)::DOUBLE PRECISION AS area_lat,
                           ROUND(lc.lng::NUMERIC, 2)::DOUBLE PRECISION AS area_lng, s.created_at
                    FROM sos_requests s JOIN emergency_types et ON et.id = s.emergency_type_id
                    JOIN location_current lc ON lc.sos_id = s.id AND lc.actor = 'VICTIM'
                    WHERE s.visibility = 'PUBLIC' AND s.status IN ('WAITING','ACCEPTED','RESCUING','ARRIVED');
            """)
        for module in (rescuer, sos, auth_service):
            patcher = patch.object(module, "get_db_connection", isolated_connection)
            patcher.start()
            cls.addClassCleanup(patcher.stop)
        cls.app = FastAPI()
        cls.app.include_router(rescuer.router)
        cls.app.include_router(sos.router)
        cls.client = TestClient(cls.app)
        cls.addClassCleanup(cls.client.close)

    @classmethod
    def drop_schema(cls):
        assert cls.schema.startswith("test_rescuer_map_")
        with psycopg.connect(cls.conninfo) as conn:
            conn.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(cls.schema)))

    def setUp(self):
        self.rescuer_id, self.user_id, self.type_id = uuid4(), uuid4(), uuid4()
        with self.db() as conn:
            conn.execute("TRUNCATE location_current, sos_requests, emergency_types, rescue_team_members, users")
            for uid, phone in ((self.rescuer_id, "0900000000"), (self.user_id, "0900000001")):
                conn.execute("INSERT INTO users (id, full_name, phone) VALUES (%s, %s, %s)", (uid, "Test", phone))
            conn.execute("INSERT INTO rescue_team_members VALUES (%s, %s, %s, 'MEMBER')",
                         (uuid4(), uuid4(), self.rescuer_id))
            conn.execute("INSERT INTO emergency_types VALUES (%s, 'TAI_NAN')", (self.type_id,))
        self.ids = {}
        for visibility, state in (("PUBLIC", "WAITING"), ("PRIVATE", "WAITING"),
                                  ("PRIVATE", "ACCEPTED"), ("PUBLIC", "RESCUING"),
                                  ("PRIVATE", "ARRIVED"), ("PRIVATE", "COMPLETED"),
                                  ("PUBLIC", "CANCELLED")):
            sid = uuid4()
            self.ids[(visibility, state)] = str(sid)
            with self.db() as conn:
                conn.execute("INSERT INTO sos_requests (id, emergency_type_id, description, visibility, status) VALUES (%s,%s,%s,%s,%s)",
                             (sid, self.type_id, "Test SOS", visibility, state))
                conn.execute("INSERT INTO location_current VALUES (%s, 'VICTIM', 10.781234, 106.691234)", (sid,))
                conn.execute("INSERT INTO location_current VALUES (%s, 'RESCUER', 11.123456, 107.123456)", (sid,))

    def headers(self, user_id=None, role="RESCUER"):
        token = auth_service.create_access_token(str(user_id or self.rescuer_id), "0900000000", role)
        return {"Authorization": f"Bearer {token}"}

    def test_rescuer_sees_both_visibilities_and_all_active_states_with_exact_victim_location(self):
        response = self.client.get("/api/rescuer/map", headers=self.headers())
        self.assertEqual(200, response.status_code, response.text)
        data = response.json()["data"]
        self.assertEqual(5, response.json()["count"])
        self.assertEqual({"PUBLIC", "PRIVATE"}, {p["visibility"] for p in data})
        self.assertEqual({"WAITING", "ACCEPTED", "RESCUING", "ARRIVED"}, {p["status"] for p in data})
        for point in data:
            self.assertEqual(10.781234, point["initial_lat"])
            self.assertEqual(106.691234, point["initial_lng"])
            self.assertEqual(str(self.type_id), point["emergency_type_id"])
            self.assertEqual("Test SOS", point["description"])

    def test_public_map_still_excludes_private_and_blurs_coordinates(self):
        response = self.client.get("/api/sos/public/map")
        self.assertEqual(200, response.status_code)
        data = response.json()["data"]
        self.assertEqual({self.ids[("PUBLIC", "WAITING")], self.ids[("PUBLIC", "RESCUING")]}, {p["sos_id"] for p in data})
        for point in data:
            self.assertEqual(10.78, point["area_lat"])
            self.assertEqual(106.69, point["area_lng"])
            self.assertNotIn("description", point)

    def test_user_login_cannot_read_private_rescuer_map(self):
        for user_id in (self.user_id, self.rescuer_id):
            self.assertEqual(403, self.client.get("/api/rescuer/map", headers=self.headers(user_id, "USER")).status_code)

    def test_anonymous_and_forged_rescuer_role_without_membership_are_rejected(self):
        self.assertIn(self.client.get("/api/rescuer/map").status_code, (401, 403))
        self.assertEqual(403, self.client.get("/api/rescuer/map", headers=self.headers(self.user_id)).status_code)

    def test_sos_without_victim_location_does_not_use_rescuer_location_as_victim(self):
        sid = self.ids[("PRIVATE", "WAITING")]
        with self.db() as conn:
            conn.execute("DELETE FROM location_current WHERE sos_id = %s AND actor = 'VICTIM'", (sid,))
        response = self.client.get("/api/rescuer/map", headers=self.headers())
        self.assertEqual(200, response.status_code)
        self.assertNotIn(sid, {p["sos_id"] for p in response.json()["data"]})


if __name__ == "__main__":
    unittest.main()
