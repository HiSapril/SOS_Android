"""Admin authentication/CRUD integration tests in an isolated PostgreSQL schema.

Run: .venv/Scripts/python.exe -m unittest test_admin -v
Application records are never changed. Telegram delivery is mocked.
"""
from contextlib import contextmanager
from pathlib import Path
import unittest
from unittest.mock import AsyncMock, patch
from uuid import uuid4

import psycopg
from psycopg import sql
from psycopg.rows import dict_row
from fastapi import FastAPI
from fastapi.testclient import TestClient

from database.connection import get_conninfo
from database.migrate_admin import migrate
from routes import admin, auth, team_members
from services import auth_service
from services.otp_service import otp_store


class AdminTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schema = "test_admin_" + uuid4().hex
        cls.conninfo = get_conninfo()
        with psycopg.connect(cls.conninfo) as conn:
            conn.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(cls.schema)))
        cls.addClassCleanup(cls.drop_schema)

        @contextmanager
        def db():
            with psycopg.connect(cls.conninfo, options=f"-c search_path={cls.schema}", row_factory=dict_row) as conn:
                yield conn

        cls.db = staticmethod(db)
        schema = (Path(__file__).resolve().parent / "database/init_db.sql").read_text(encoding="utf-8")
        # Skip destructive initialization and extension management entirely.
        with cls.db() as conn:
            conn.execute(schema[schema.index("CREATE TABLE users"):])
        for module in (admin, auth, auth_service, team_members):
            patcher = patch.object(module, "get_db_connection", db)
            patcher.start()
            cls.addClassCleanup(patcher.stop)
        for patcher in (patch.object(auth.config, "DEV_MOCK_OTP", True),
                        patch.object(auth, "send_otp_telegram", AsyncMock(return_value=True))):
            patcher.start()
            cls.addClassCleanup(patcher.stop)
        app = FastAPI()
        app.include_router(auth.router)
        app.include_router(admin.router)
        app.include_router(team_members.router)
        cls.client = TestClient(app)
        cls.addClassCleanup(cls.client.close)

    @classmethod
    def drop_schema(cls):
        assert cls.schema.startswith("test_admin_")
        with psycopg.connect(cls.conninfo) as conn:
            conn.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(cls.schema)))

    def setUp(self):
        otp_store.clear()
        self.admin_id, self.member_id, self.user_id, self.leader_id = [uuid4() for _ in range(4)]
        self.team_id, self.empty_team = uuid4(), uuid4()
        self.admin_membership, self.member_membership, self.leader_membership = [uuid4() for _ in range(3)]
        self.type_id = uuid4()
        with self.db() as conn:
            conn.execute("TRUNCATE users, rescue_teams, emergency_types CASCADE")
            for uid, phone, name in ((self.admin_id, "0900000000", "Admin Test"), (self.member_id, "0900000001", "Member Test"),
                                     (self.user_id, "0900000002", "User Test"), (self.leader_id, "0900000003", "Leader Test")):
                conn.execute("INSERT INTO users (id, full_name, phone, is_valid) VALUES (%s, %s, %s, true)", (uid, name, phone))
            for tid, name in ((self.team_id, "Team A"), (self.empty_team, "Team B")):
                conn.execute("INSERT INTO rescue_teams (team_id, name) VALUES (%s, %s)", (tid, name))
            for mid, uid, role in ((self.admin_membership, self.admin_id, "ADMIN"), (self.member_membership, self.member_id, "MEMBER"),
                                    (self.leader_membership, self.leader_id, "LEADER")):
                conn.execute("INSERT INTO rescue_team_members (member_id, team_id, user_id, role) VALUES (%s, %s, %s, %s)",
                             (mid, self.team_id, uid, role))
            conn.execute("INSERT INTO emergency_types (id, name) VALUES (%s, 'TAI_NAN')", (self.type_id,))
        self.headers = self.token(self.admin_id, "ADMIN")

    def token(self, uid, role):
        return {"Authorization": "Bearer " + auth_service.create_access_token(str(uid), "0900000000", role)}

    def call(self, method, path, **kwargs):
        return getattr(self.client, method)("/api/admin" + path, headers=self.headers, **kwargs)

    def profile(self, **changes):
        return {"full_name": "Test Person", "phone": "0909999999", "account_status": "ACTIVE", **changes}

    def membership(self, **changes):
        return {**self.profile(), "team_id": str(self.empty_team), "role": "MEMBER", **changes}

    def test_admin_phone_otp_login_single_use_and_role_binding(self):
        response = self.client.post("/api/auth/request-otp", json={"phone": "+84900000000", "role": "ADMIN"})
        self.assertEqual(response.status_code, 200, response.text)
        otp = response.json()["dev_otp"]
        credentials = {"phone": "0900000000", "otp": otp, "role": "ADMIN"}
        response = self.client.post("/api/auth/verify-otp", json=credentials)
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json()["user"]["role"], "ADMIN")
        token = response.json()["access_token"]
        self.assertEqual(self.client.get("/api/admin/me", headers={"Authorization": f"Bearer {token}"}).status_code, 200)
        self.assertEqual(self.client.post("/api/auth/verify-otp", json=credentials).status_code, 400)
        otp_store.set_otp("0900000000", "123456", role="USER")
        credentials["otp"] = "123456"
        self.assertEqual(self.client.post("/api/auth/verify-otp", json=credentials).status_code, 400)

    def test_only_active_admin_rescuer_can_request_admin_otp(self):
        for phone in ("0900000001", "0900000002", "0900000003", "0908888888"):
            response = self.client.post("/api/auth/request-otp", json={"phone": phone, "role": "ADMIN"})
            self.assertEqual(response.status_code, 403)
        with self.db() as conn:
            conn.execute("UPDATE users SET account_status = 'DISABLED' WHERE id = %s", (self.admin_id,))
        self.assertEqual(self.client.post("/api/auth/request-otp", json={"phone": "0900000000", "role": "ADMIN"}).status_code, 403)

    def test_ordinary_tokens_cannot_read_or_mutate_any_admin_resource(self):
        for uid, role in ((self.user_id, "USER"), (self.member_id, "RESCUER"), (self.user_id, "ADMIN")):
            for resource in ("me", "overview", "sos", "users", "rescuers", "teams"):
                self.assertEqual(self.client.get(f"/api/admin/{resource}", headers=self.token(uid, role)).status_code, 403)
            self.assertEqual(self.client.post("/api/admin/users", headers=self.token(uid, role), json=self.profile()).status_code, 403)
        self.assertIn(self.client.get("/api/admin/overview").status_code, (401, 403))

    def test_revocation_and_disabled_account_invalidate_existing_token(self):
        with self.db() as conn:
            conn.execute("UPDATE rescue_team_members SET role = 'MEMBER' WHERE member_id = %s", (self.admin_membership,))
        self.assertEqual(self.call("get", "/overview").status_code, 403)
        with self.db() as conn:
            conn.execute("UPDATE rescue_team_members SET role = 'ADMIN' WHERE member_id = %s", (self.admin_membership,))
            conn.execute("UPDATE users SET account_status = 'DISABLED' WHERE id = %s", (self.admin_id,))
        self.assertEqual(self.call("get", "/overview").status_code, 403)

    def test_admin_revoked_between_request_and_verification_is_denied(self):
        otp_store.set_otp("0900000000", "123456", role="ADMIN")
        with self.db() as conn:
            conn.execute("UPDATE rescue_team_members SET role = 'MEMBER' WHERE member_id = %s", (self.admin_membership,))
        response = self.client.post("/api/auth/verify-otp", json={"phone": "0900000000", "otp": "123456", "role": "ADMIN"})
        self.assertEqual(response.status_code, 403)

    def test_users_crud_search_pagination_and_duplicate_phone(self):
        response = self.call("post", "/users", json=self.profile())
        self.assertEqual(response.status_code, 201, response.text)
        uid = response.json()["data"]["id"]
        self.assertFalse(response.json()["data"]["is_valid"])
        response = self.call("get", "/users?q=0909999999&page_size=1")
        self.assertEqual(response.json()["total"], 1)
        self.assertEqual(len(response.json()["data"]), 1)
        self.assertEqual(self.call("post", "/users", json=self.profile()).status_code, 409)
        response = self.call("put", f"/users/{uid}", json=self.profile(full_name="Changed", account_status="DISABLED"))
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json()["data"]["account_status"], "DISABLED")
        self.assertEqual(self.call("delete", f"/users/{uid}").status_code, 200)
        self.assertEqual(self.call("delete", f"/users/{uid}").status_code, 404)

    def test_teams_and_rescuers_crud_preserves_user_on_member_delete(self):
        response = self.call("post", "/teams", json={"name": "New Team", "description": "New"})
        self.assertEqual(response.status_code, 201, response.text)
        tid = response.json()["data"]["team_id"]
        self.assertEqual(self.call("put", f"/teams/{tid}", json={"name": "Changed Team", "status": "INACTIVE"}).status_code, 200)
        response = self.call("post", "/rescuers", json=self.membership(team_id=tid))
        self.assertEqual(response.status_code, 201, response.text)
        mid = response.json()["data"]["member_id"]
        uid = response.json()["data"]["user_id"]
        self.assertEqual(self.call("get", f"/rescuers?team_id={tid}").json()["total"], 1)
        self.assertEqual(self.call("post", "/rescuers", json=self.membership(team_id=tid)).status_code, 409)
        response = self.call("put", f"/rescuers/{mid}", json=self.membership(team_id=tid, role="MEDIC"))
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json()["data"]["role"], "MEDIC")
        self.assertEqual(self.call("get", f"/rescuers?team_id={tid}&role=MEDIC").json()["total"], 1)
        self.assertEqual(self.call("delete", f"/rescuers/{mid}").status_code, 200)
        with self.db() as conn:
            self.assertIsNotNone(conn.execute("SELECT id FROM users WHERE id = %s", (uid,)).fetchone())
        self.assertEqual(self.call("delete", f"/teams/{tid}").status_code, 200)

    def test_self_lockout_and_last_leader_are_blocked(self):
        self.assertEqual(self.call("delete", f"/rescuers/{self.admin_membership}").status_code, 409)
        self.assertEqual(self.call("delete", f"/users/{self.admin_id}").status_code, 409)
        self.assertEqual(self.call("put", f"/users/{self.admin_id}", json=self.profile(phone="0900000000", account_status="DISABLED")).status_code, 409)
        self.assertEqual(self.call("put", f"/rescuers/{self.admin_membership}", json=self.membership(phone="0900000000", team_id=str(self.team_id))).status_code, 409)
        with self.db() as conn:
            conn.execute("UPDATE rescue_team_members SET team_id = %s WHERE member_id = %s", (self.empty_team, self.leader_membership))
        self.assertEqual(self.call("delete", f"/rescuers/{self.leader_membership}").status_code, 409)

    def test_delete_unused_team_removes_memberships_and_keeps_accounts(self):
        response = self.call("post", "/rescuers", json=self.membership(role="LEADER"))
        uid = response.json()["data"]["user_id"]
        self.assertEqual(self.call("delete", f"/teams/{self.empty_team}").status_code, 200)
        with self.db() as conn:
            self.assertIsNotNone(conn.execute("SELECT id FROM users WHERE id = %s", (uid,)).fetchone())
            self.assertEqual(conn.execute("SELECT count(*) AS total FROM rescue_team_members WHERE user_id = %s", (uid,)).fetchone()["total"], 0)

    def make_sos(self, status="WAITING", with_location=True):
        sid = uuid4()
        with self.db() as conn:
            conn.execute("""INSERT INTO sos_requests (id, requester_id, team_id, emergency_type_id, description, visibility, status)
                VALUES (%s, %s, %s, %s, 'Private test SOS', 'PRIVATE', %s)""",
                (sid, self.user_id, self.team_id if status != "WAITING" else None, self.type_id, status))
            if with_location:
                conn.execute("INSERT INTO location_current (sos_id, actor, lat, lng) VALUES (%s, 'VICTIM', 10.776891, 106.700812)", (sid,))
        return sid

    def test_map_shows_exact_private_sos_and_missing_coordinates(self):
        sid = self.make_sos()
        response = self.call("get", "/sos")
        self.assertEqual(response.status_code, 200, response.text)
        item = response.json()["data"][0]
        self.assertEqual(item["sos_id"], str(sid))
        self.assertEqual(item["visibility"], "PRIVATE")
        self.assertEqual(item["lat"], 10.776891)
        self.assertEqual(item["requester_phone"], "0900000002")
        self.assertEqual(self.call("get", "/sos?status=COMPLETED").json()["count"], 0)
        with self.db() as conn:
            conn.execute("DELETE FROM location_current WHERE sos_id = %s", (sid,))
        self.assertIsNone(self.call("get", "/sos").json()["data"][0]["lat"])
        self.assertEqual(self.call("get", "/overview").json()["data"]["waiting_sos"], 1)

    def test_sos_history_filters_pagination_summary_and_logs(self):
        sid = self.make_sos(status="COMPLETED", with_location=False)
        with self.db() as conn:
            conn.execute(
                "INSERT INTO location_history (sos_id, actor, lat, lng) VALUES (%s, 'VICTIM', 10.776891, 106.700812)",
                (sid,),
            )
            conn.execute(
                "INSERT INTO sos_logs (sos_id, user_id, action, description) VALUES (%s, %s, 'CREATE_SOS', 'Khởi tạo SOS'), (%s, %s, 'COMPLETE_SOS', 'Hoàn thành')",
                (sid, self.user_id, sid, self.admin_id),
            )
        hist = self.call("get", f"/sos/history?status=COMPLETED&team_id={self.team_id}&emergency_type_id={self.type_id}&q=User%20Test&page=1&page_size=10")
        self.assertEqual(hist.status_code, 200, hist.text)
        body = hist.json()
        self.assertEqual(body["total"], 1)
        self.assertEqual(body["data"][0]["sos_id"], str(sid))
        self.assertEqual(body["data"][0]["logs_count"], 2)
        self.assertEqual(body["data"][0]["lat"], 10.776891)

        summary = self.call("get", "/sos/history/summary")
        self.assertEqual(summary.status_code, 200, summary.text)
        self.assertEqual(summary.json()["data"]["total_sos"], 1)
        self.assertEqual(summary.json()["data"]["completed_count"], 1)
        self.assertEqual(summary.json()["data"]["success_rate_percent"], 100.0)

        logs = self.call("get", f"/sos/{sid}/logs")
        self.assertEqual(logs.status_code, 200, logs.text)
        self.assertEqual(logs.json()["count"], 2)
        self.assertEqual(logs.json()["data"][0]["actor_name"], "User Test")
        self.assertEqual(self.call("get", f"/sos/{uuid4()}/logs").status_code, 404)

    def test_active_case_blocks_member_removal_and_team_disabling(self):
        self.make_sos(status="ACCEPTED")
        self.assertEqual(self.call("delete", f"/rescuers/{self.member_membership}").status_code, 409)
        self.assertEqual(self.call("put", f"/teams/{self.team_id}", json={"name": "Team A", "status": "INACTIVE"}).status_code, 409)
        self.assertEqual(self.call("delete", f"/users/{self.member_id}").status_code, 409)
        self.assertEqual(self.call("delete", f"/users/{self.user_id}").status_code, 409)

    def test_inputs_and_invalid_ids_are_validated(self):
        for changes in ({"phone": "000"}, {"full_name": "   "}, {"account_status": "FAKE"}):
            self.assertEqual(self.call("post", "/users", json=self.profile(**changes)).status_code, 422)
        self.assertEqual(self.call("post", "/rescuers", json=self.membership(role="ROOT")).status_code, 422)
        self.assertEqual(self.call("delete", "/users/not-a-uuid").status_code, 422)
        self.assertEqual(self.call("get", "/users?page=0").status_code, 422)
        self.assertEqual(self.call("get", "/sos?status=FAKE").status_code, 422)
        self.assertEqual(self.call("get", "/users?q=%27%3BDROP%20TABLE%20users%3B--").status_code, 200)

    def test_migration_is_idempotent_and_does_not_grant_accounts(self):
        with self.db() as conn:
            with conn.cursor() as cursor:
                migrate(cursor)
                migrate(cursor)
            self.assertEqual(conn.execute("SELECT count(*) AS total FROM rescue_team_members WHERE role = 'ADMIN'").fetchone()["total"], 1)

    def test_team_leader_cannot_demote_or_remove_admin_via_android_api(self):
        path = f"/api/rescuer/teams/{self.team_id}/members/{self.admin_membership}"
        headers = self.token(self.leader_id, "RESCUER")
        self.assertEqual(self.client.put(path, headers=headers, json={"role": "MEMBER"}).status_code, 403)
        self.assertEqual(self.client.delete(path, headers=headers).status_code, 403)

    def test_admin_keeps_team_management_when_logged_in_as_android_rescuer(self):
        headers = self.token(self.admin_id, "RESCUER")
        response = self.client.get("/api/rescuer/teams/mine", headers=headers)
        self.assertEqual(response.status_code, 200, response.text)
        self.assertTrue(response.json()["data"][0]["can_manage"])
        response = self.client.post(f"/api/rescuer/teams/{self.team_id}/members", headers=headers,
                                    json={"phone": "0900000002", "role": "MEDIC"})
        self.assertEqual(response.status_code, 201, response.text)

    def test_production_otp_and_dev_helper_fail_closed(self):
        with patch.object(auth.config, "DEV_MOCK_OTP", False), patch.object(auth.config, "TELEGRAM_BOT_TOKEN", ""):
            self.assertEqual(self.client.post("/api/auth/request-otp", json={"phone": "0900000000", "role": "ADMIN"}).status_code, 503)
            self.assertEqual(self.client.post("/api/auth/dev-link-telegram", json={"phone": "0900000000"}).status_code, 404)
        with self.db() as conn:
            conn.execute("UPDATE users SET telegram_chat_id = 10001 WHERE id = %s", (self.admin_id,))
        with patch.object(auth.config, "DEV_MOCK_OTP", False), patch.object(auth.config, "TELEGRAM_BOT_TOKEN", "test-only"), \
                patch.object(auth, "send_otp_telegram", AsyncMock(return_value=False)):
            self.assertEqual(self.client.post("/api/auth/request-otp", json={"phone": "0900000000", "role": "ADMIN"}).status_code, 503)
            self.assertIsNone(otp_store.get_last_created_time("0900000000"))


if __name__ == "__main__":
    unittest.main()
