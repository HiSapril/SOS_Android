"""Add ADMIN role; optionally grant an existing rescuer access with --phone."""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from psycopg import sql
from database.connection import get_db_connection, close_db_pool
from services.auth_service import normalize_phone


def migrate(cursor):
    cursor.execute("""
        SELECT c.conname FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
        WHERE c.conrelid = 'rescue_team_members'::regclass AND c.contype = 'c' AND a.attname = 'role'
    """)
    for row in cursor.fetchall():
        cursor.execute(sql.SQL("ALTER TABLE rescue_team_members DROP CONSTRAINT {}").format(sql.Identifier(row["conname"])))
    cursor.execute("""ALTER TABLE rescue_team_members ADD CONSTRAINT rescue_team_members_role_check
        CHECK (role IN ('ADMIN', 'LEADER', 'MEMBER', 'DRIVER', 'MEDIC'))""")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phone", help="Grant ADMIN to an existing active rescuer; no account is created.")
    parser.add_argument("--team-id", help="Select membership if the rescuer belongs to multiple teams.")
    args = parser.parse_args()
    try:
        with get_db_connection() as conn:
            with conn.cursor() as cursor:
                migrate(cursor)
                if args.phone:
                    cursor.execute("""SELECT m.member_id FROM rescue_team_members m JOIN users u ON u.id = m.user_id
                        WHERE u.phone = %s AND u.account_status = 'ACTIVE'
                        AND (%s::uuid IS NULL OR m.team_id = %s::uuid) ORDER BY m.created_at, m.member_id""",
                        (normalize_phone(args.phone), args.team_id, args.team_id))
                    rows = cursor.fetchall()
                    if len(rows) != 1:
                        raise ValueError("Expected one active rescuer membership. Check phone or provide --team-id.")
                    cursor.execute("UPDATE rescue_team_members SET role = 'ADMIN' WHERE member_id = %s", (rows[0]["member_id"],))
        print("ADMIN migration completed." + (" Rescuer access granted." if args.phone else " No accounts changed."))
    finally:
        close_db_pool()


if __name__ == "__main__":
    main()
