import logging
from contextlib import contextmanager
from typing import Generator
import psycopg
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool
import config

logger = logging.getLogger("backend.database")

_pool: ConnectionPool | None = None

def get_conninfo() -> str:
    return (
        f"host={config.PGHOST} port={config.PGPORT} dbname={config.PGDATABASE} "
        f"user={config.PGUSER} password={config.PGPASSWORD} connect_timeout=5"
    )

def init_db_pool():
    global _pool
    if _pool is None:
        conninfo = get_conninfo()
        logger.info(f"Khởi tạo Connection Pool tới PostgreSQL {config.PGHOST}:{config.PGPORT}/{config.PGDATABASE}")
        _pool = ConnectionPool(
            conninfo=conninfo,
            min_size=2,
            max_size=20,
            kwargs={"row_factory": dict_row, "options": "-c statement_timeout=15000"}
        )
        _pool.wait()
        logger.info("Đã kết nối thành công tới PostgreSQL Database!")

def close_db_pool():
    global _pool
    if _pool is not None:
        logger.info("Đóng Connection Pool...")
        _pool.close()
        _pool = None

@contextmanager
def get_db_connection() -> Generator[psycopg.Connection, None, None]:
    """
    Context manager cung cấp connection từ pool.
    Tự động commit nếu không có ngoại lệ, hoặc rollback nếu có lỗi.
    """
    global _pool
    if _pool is None:
        init_db_pool()
    
    with _pool.connection() as conn:
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
