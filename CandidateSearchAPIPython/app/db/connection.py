import threading
from contextlib import contextmanager

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool


class Db:
    """A small query helper over a connection pool. Named parameters are written %(name)s. Inside `transaction()`
    every call on the same thread shares one connection and commits or rolls back together."""

    def __init__(self, conninfo: str):
        self._pool = ConnectionPool(conninfo, min_size=1, max_size=12, kwargs={"row_factory": dict_row}, open=False)
        self._tx = threading.local()

    def open(self) -> None:
        self._pool.open(wait=True, timeout=60)

    def close(self) -> None:
        self._pool.close()

    @contextmanager
    def _connection(self):
        conn = getattr(self._tx, "conn", None)
        if conn is not None:
            yield conn
        else:
            with self._pool.connection() as conn:
                yield conn

    @contextmanager
    def transaction(self):
        with self._pool.connection() as conn, conn.transaction():
            self._tx.conn = conn
            try:
                yield
            finally:
                self._tx.conn = None

    def rows(self, sql: str, **params) -> list[dict]:
        with self._connection() as conn:
            return conn.execute(sql, params).fetchall()

    def row(self, sql: str, **params) -> dict | None:
        with self._connection() as conn:
            return conn.execute(sql, params).fetchone()

    def column(self, sql: str, **params) -> list:
        return [next(iter(r.values())) for r in self.rows(sql, **params)]

    def scalar(self, sql: str, **params):
        row = self.row(sql, **params)
        return None if row is None else next(iter(row.values()))

    def execute(self, sql: str, **params) -> int:
        with self._connection() as conn:
            return conn.execute(sql, params).rowcount

    def execute_many(self, sql: str, params: list[dict]) -> None:
        if params:
            with self._connection() as conn:
                conn.cursor().executemany(sql, params)
