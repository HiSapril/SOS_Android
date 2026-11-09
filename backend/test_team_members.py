"""API/RBAC integration tests on PostgreSQL in a disposable schema.

Run: .venv/Scripts/python.exe -m unittest test_team_members -v
The configured database needs CREATE SCHEMA permission; application rows are untouched.
"""
from concurrent.futures import ThreadPoolExecutor
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
from routes import team_members
from services import auth_service


class TeamMembersTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schema = "test_team_members_" + uuid4().hex
        cls.conninfo = get_conninfo()
        with psycopg.connect(cls.conninfo) as conn:
            conn.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(cls.schema)))
        cls.addClassCleanup(cls.drop_schema)
        cls.addClassCleanup(patch.stopall)

        @contextmanager
        def isolated_connection():
            with psycopg.connect(cls.conninfo, options=f"-c search_path={cls.schema}", row_factory=dict_row) as conn:
                yield conn

        cls.db = staticmethod(isolated_connection)
        with cls.db() as conn:
            conn.execute("""
                CREATE TABLE users (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), full_name VARCHAR(100) NOT NULL,
                    phone VARCHAR(15) UNIQUE NOT NULL, account_status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
                    is_valid BOOLEAN NOT NULL DEFAULT true, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                CREATE TABLE rescue_teams (
                    team_id UUID PRIMARY KEY DEFAULT gen_random_uuid(), name VARCHAR(100) NOT NULL,
                    description TEXT, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                CREATE TABLE rescue_team_members (
                    member_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    team_id UUID NOT NULL REFERENCES rescue_teams(team_id),
                    user_id UUID NOT NULL REFERENCES users(id),
                    role VARCHAR(50) NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('LEADER','MEMBER','DRIVER','MEDIC')),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE (team_id, user_id)
                );
            """)
        patch.object(team_members, "get_db_connection", isolated_connection).start()
        patch.object(auth_service, "get_db_connection", isolated_connection).start()
        cls.app = FastAPI()
        cls.app.include_router(team_members.router)
        cls.client = TestClient(cls.app)
        cls.addClassCleanup(cls.client.close)

    @classmethod
    def drop_schema(cls):
        assert cls.schema.startswith("test_team_members_")
        with psycopg.connect(cls.conninfo) as conn:
            conn.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(cls.schema)))

    def setUp(self):
        self.team_a, self.team_b = uuid4(), uuid4()
        self.leader, self.member, self.other_leader, self.candidate, self.disabled = [uuid4() for _ in range(5)]
        self.leader_member, self.regular_member, self.other_member = [uuid4() for _ in range(3)]
        with self.db() as conn:
            conn.execute("TRUNCATE rescue_team_members, rescue_teams, users")
            for index, user_id in enumerate((self.leader, self.member, self.other_leader, self.candidate, self.disabled)):
                conn.execute("INSERT INTO users (id, full_name, phone, account_status) VALUES (%s, %s, %s, %s)",
                             (user_id, f"Test user {index}", f"090000000{index}", "DISABLED" if index == 4 else "ACTIVE"))
            for team_id, name in ((self.team_a, "Team A"), (self.team_b, "Team B")):
                conn.execute("INSERT INTO rescue_teams (team_id, name) VALUES (%s, %s)", (team_id, name))
            for mid, tid, uid, role in (
                (self.leader_member, self.team_a, self.leader, "LEADER"),
                (self.regular_member, self.team_a, self.member, "MEMBER"),
                (self.other_member, self.team_b, self.other_leader, "LEADER"),
            ):
                conn.execute("INSERT INTO rescue_team_members (member_id, team_id, user_id, role) VALUES (%s, %s, %s, %s)",
                             (mid, tid, uid, role))
        self.path = f"/api/rescuer/teams/{self.team_a}/members"

    def headers(self, user_id=None, role="RESCUER"):
        token = auth_service.create_access_token(str(user_id or self.leader), "0900000000", role)
        return {"Authorization": f"Bearer {token}"}

    def request(self, method, path=None, user=None, **kwargs):
        return self.client.request(method, path or self.path, headers=self.headers(user), **kwargs)

    def test_my_teams_and_members_have_server_permissions(self):
        leader = self.request("GET", "/api/rescuer/teams/mine").json()["data"]
        self.assertEqual([str(self.team_a)], [t["team_id"] for t in leader])
        self.assertTrue(leader[0]["can_manage"])
        self.assertEqual("LEADER", leader[0]["current_user_role"])
        regular = self.request("GET", "/api/rescuer/teams/mine", self.member).json()["data"][0]
        self.assertFalse(regular["can_manage"])
        self.assertEqual(2, len(regular["members"]))
        self.assertEqual(str(self.leader_member), regular["members"][0]["member_id"])
        self.assertEqual("0900000000", regular["members"][0]["phone"])
        listed = self.request("GET", user=self.member)
        self.assertEqual(200, listed.status_code)
        self.assertFalse(listed.json()["can_manage"])

    def test_member_cannot_add_update_or_delete(self):
        for method, path, body in (
            ("POST", self.path, {"phone": "0900000003", "role": "LEADER"}),
            ("PUT", f"{self.path}/{self.leader_member}", {"role": "MEMBER"}),
            ("DELETE", f"{self.path}/{self.leader_member}", None),
        ):
            self.assertEqual(403, self.request(method, path, self.member, json=body).status_code)

    def test_management_role_is_specific_to_each_team(self):
        with self.db() as conn:
            conn.execute("INSERT INTO rescue_team_members (team_id, user_id, role) VALUES (%s, %s, 'DRIVER')",
                         (self.team_b, self.leader))
        teams = self.request("GET", "/api/rescuer/teams/mine").json()["data"]
        permissions = {t["team_id"]: t["can_manage"] for t in teams}
        self.assertEqual({str(self.team_a): True, str(self.team_b): False}, permissions)
        path = f"/api/rescuer/teams/{self.team_b}/members"
        self.assertEqual(200, self.request("GET", path).status_code)
        self.assertEqual(403, self.request("POST", path, json={"phone": "0900000003", "role": "MEMBER"}).status_code)

    def test_other_team_leader_cannot_read_or_manage(self):
        self.assertEqual(403, self.request("GET", user=self.other_leader).status_code)
        self.assertEqual(403, self.request("POST", user=self.other_leader,
                                         json={"phone": "0900000003", "role": "MEMBER"}).status_code)
        self.assertEqual(403, self.request("PUT", f"{self.path}/{self.regular_member}", self.other_leader,
                                         json={"role": "LEADER"}).status_code)
        self.assertEqual(403, self.request("DELETE", f"{self.path}/{self.regular_member}", self.other_leader).status_code)

    def test_add_by_phone_normalizes_and_returns_membership_id(self):
        response = self.request("POST", json={"phone": "+84 900 000 003", "role": "MEDIC"})
        self.assertEqual(201, response.status_code, response.text)
        data = response.json()["data"]
        self.assertEqual(str(self.candidate), data["user_id"])
        self.assertEqual("MEDIC", data["role"])
        self.assertEqual(str(self.team_a), data["team_id"])
        self.assertEqual(3, self.request("GET").json()["count"])

    def test_update_by_membership_id_and_scope_target_to_team(self):
        response = self.request("PUT", f"{self.path}/{self.regular_member}", json={"role": "DRIVER"})
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual("DRIVER", response.json()["data"]["role"])
        self.assertEqual(404, self.request("PUT", f"{self.path}/{self.member}", json={"role": "MEDIC"}).status_code)
        self.assertEqual(404, self.request("PUT", f"{self.path}/{self.other_member}", json={"role": "MEMBER"}).status_code)
        self.assertEqual(404, self.request("DELETE", f"{self.path}/{self.other_member}").status_code)

    def test_delete_keeps_user_and_revokes_rescuer_access(self):
        old_headers = self.headers(self.member)
        self.assertEqual(200, self.request("DELETE", f"{self.path}/{self.regular_member}").status_code)
        self.assertEqual(1, self.request("GET").json()["count"])
        self.assertEqual(403, self.client.get("/api/rescuer/teams/mine", headers=old_headers).status_code)
        with self.db() as conn:
            self.assertEqual(1, conn.execute("SELECT count(*) AS count FROM users WHERE id = %s", (self.member,)).fetchone()["count"])

    def test_duplicate_unknown_disabled_and_invalid_inputs(self):
        for phone, expected in (("0900000001", 409), ("0999999999", 404), ("0900000004", 409), ("1234567890", 422)):
            response = self.request("POST", json={"phone": phone, "role": "MEMBER"})
            self.assertEqual(expected, response.status_code, response.text)
        self.assertEqual(422, self.request("POST", json={"phone": "0900000003", "role": "ADMIN"}).status_code)
        self.assertEqual(422, self.request("PUT", f"{self.path}/{self.regular_member}", json={"role": "ADMIN"}).status_code)
        self.assertEqual(422, self.request("DELETE", f"{self.path}/invalid-id").status_code)
        self.assertEqual(404, self.request("GET", f"/api/rescuer/teams/{uuid4()}/members").status_code)

    def test_last_leader_is_protected_then_handover_revokes_management(self):
        self.assertEqual(409, self.request("DELETE", f"{self.path}/{self.leader_member}").status_code)
        self.assertEqual(409, self.request("PUT", f"{self.path}/{self.leader_member}", json={"role": "MEMBER"}).status_code)
        self.assertEqual(200, self.request("PUT", f"{self.path}/{self.regular_member}", json={"role": "LEADER"}).status_code)
        self.assertEqual(200, self.request("PUT", f"{self.path}/{self.leader_member}", json={"role": "MEDIC"}).status_code)
        self.assertFalse(self.request("GET", "/api/rescuer/teams/mine").json()["data"][0]["can_manage"])
        self.assertEqual(403, self.request("POST", json={"phone": "0900000003", "role": "MEMBER"}).status_code)
        self.assertEqual(200, self.request("DELETE", f"{self.path}/{self.leader_member}", self.member).status_code)

    def test_concurrent_leader_demotions_cannot_leave_team_without_leader(self):
        self.assertEqual(200, self.request("PUT", f"{self.path}/{self.regular_member}", json={"role": "LEADER"}).status_code)
        def demote(pair):
            member_id, user_id = pair
            return self.request("PUT", f"{self.path}/{member_id}", user_id, json={"role": "MEMBER"}).status_code
        with ThreadPoolExecutor(max_workers=2) as executor:
            statuses = list(executor.map(demote, ((self.leader_member, self.leader), (self.regular_member, self.member))))
        self.assertEqual([200, 409], sorted(statuses))
        self.assertEqual(1, sum(m["role"] == "LEADER" for m in self.request("GET").json()["data"]))

    def test_authentication_and_login_role_are_required(self):
        self.assertIn(self.client.get(self.path).status_code, (401, 403))
        self.assertEqual(403, self.client.get(self.path, headers=self.headers(role="USER")).status_code)


if __name__ == "__main__":
    unittest.main()
