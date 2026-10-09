"""本地状态存储（SQLite）。

承担三件事，对应"可先假设/先做"清单中的第 5、6、7、8 项：

1. 幂等键 ``eventId``：重复事件不重复处理（契约 第 6.1 节）；
2. ``revision``：按 ``ownerId + fileId`` 记录已生效版本，并在写入时做 CAS（第 6.2 节）；
3. 分块的临时结构与状态记录。

并发约定：单进程、单 worker。所有写操作串行化，使用 ``BEGIN IMMEDIATE`` 保证 CAS 原子性。
"""

from __future__ import annotations

import sqlite3
import threading
import time
from pathlib import Path

from .models import Chunk, EventStatus, IndexEvent, Operation, StoredEvent

SCHEMA = """
CREATE TABLE IF NOT EXISTS index_events (
    event_id        TEXT PRIMARY KEY,
    operation       TEXT NOT NULL,
    file_id         TEXT NOT NULL,
    owner_id        TEXT NOT NULL,
    revision        INTEGER NOT NULL,
    file_name       TEXT,
    storage_backend TEXT,
    storage_key     TEXT,
    status          TEXT NOT NULL,
    attempts        INTEGER NOT NULL DEFAULT 0,
    next_attempt_at REAL NOT NULL,
    last_error      TEXT,
    created_at      REAL NOT NULL,
    updated_at      REAL NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_index_events_due
    ON index_events (status, next_attempt_at);

CREATE TABLE IF NOT EXISTS file_state (
    owner_id         TEXT NOT NULL,
    file_id          TEXT NOT NULL,
    applied_revision INTEGER NOT NULL DEFAULT 0,
    active           INTEGER NOT NULL DEFAULT 0,
    updated_at       REAL NOT NULL,
    PRIMARY KEY (owner_id, file_id)
);

CREATE TABLE IF NOT EXISTS chunks (
    owner_id    TEXT NOT NULL,
    file_id     TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    revision    INTEGER NOT NULL,
    text        TEXT NOT NULL,
    char_start  INTEGER NOT NULL,
    char_end    INTEGER NOT NULL,
    PRIMARY KEY (owner_id, file_id, chunk_index)
);
"""


class Store:
    def __init__(self, path: str | Path) -> None:
        self._path = Path(path)
        if self._path.parent != Path(""):
            self._path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(str(self._path), check_same_thread=False, isolation_level=None)
        self._conn.row_factory = sqlite3.Row
        with self._lock:
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA synchronous=NORMAL")
            self._conn.executescript(SCHEMA)

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    # ------------------------------------------------------------------ 事件

    def event_exists(self, event_id: str) -> bool:
        with self._lock:
            row = self._conn.execute(
                "SELECT 1 FROM index_events WHERE event_id = ?", (event_id,)
            ).fetchone()
        return row is not None

    def create_event(self, event: IndexEvent, *, now: float | None = None) -> None:
        moment = time.time() if now is None else now
        with self._lock:
            self._conn.execute(
                """
                INSERT OR IGNORE INTO index_events (
                    event_id, operation, file_id, owner_id, revision,
                    file_name, storage_backend, storage_key,
                    status, attempts, next_attempt_at, last_error, created_at, updated_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    event.eventId,
                    event.operation.value,
                    event.fileId,
                    event.ownerId,
                    event.revision,
                    event.fileName,
                    event.storageBackend,
                    event.storageKey,
                    EventStatus.RECEIVED.value,
                    0,
                    moment,
                    None,
                    moment,
                    moment,
                ),
            )

    def due_events(self, *, now: float | None = None, limit: int = 20) -> list[StoredEvent]:
        moment = time.time() if now is None else now
        with self._lock:
            rows = self._conn.execute(
                """
                SELECT * FROM index_events
                WHERE status = ? AND next_attempt_at <= ?
                ORDER BY next_attempt_at ASC, rowid ASC
                LIMIT ?
                """,
                (EventStatus.RECEIVED.value, moment, limit),
            ).fetchall()
        return [_to_stored_event(row) for row in rows]

    def mark_status(
        self,
        event_id: str,
        status: EventStatus,
        *,
        error: str | None = None,
        attempts: int | None = None,
        next_attempt_at: float | None = None,
    ) -> None:
        sets = ["status = ?", "last_error = ?", "updated_at = ?"]
        values: list[object] = [status.value, _trim(error), time.time()]
        if attempts is not None:
            sets.append("attempts = ?")
            values.append(attempts)
        if next_attempt_at is not None:
            sets.append("next_attempt_at = ?")
            values.append(next_attempt_at)
        values.append(event_id)
        with self._lock:
            self._conn.execute(f"UPDATE index_events SET {', '.join(sets)} WHERE event_id = ?", values)

    def event_status(self, event_id: str) -> EventStatus | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT status FROM index_events WHERE event_id = ?", (event_id,)
            ).fetchone()
        return EventStatus(row["status"]) if row else None

    def event_error(self, event_id: str) -> str | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT last_error FROM index_events WHERE event_id = ?", (event_id,)
            ).fetchone()
        return row["last_error"] if row else None

    def event_attempts(self, event_id: str) -> int:
        with self._lock:
            row = self._conn.execute(
                "SELECT attempts FROM index_events WHERE event_id = ?", (event_id,)
            ).fetchone()
        return int(row["attempts"]) if row else 0

    # -------------------------------------------------------------- 文件状态

    def applied_revision(self, owner_id: str, file_id: str) -> int:
        with self._lock:
            row = self._conn.execute(
                "SELECT applied_revision FROM file_state WHERE owner_id = ? AND file_id = ?",
                (owner_id, file_id),
            ).fetchone()
        return int(row["applied_revision"]) if row else 0

    def is_active(self, owner_id: str, file_id: str) -> bool:
        with self._lock:
            row = self._conn.execute(
                "SELECT active FROM file_state WHERE owner_id = ? AND file_id = ?",
                (owner_id, file_id),
            ).fetchone()
        return bool(row["active"]) if row else False

    def apply_revision(
        self,
        *,
        owner_id: str,
        file_id: str,
        revision: int,
        active: bool,
        chunks: list[Chunk] | None = None,
        drop_chunks: bool = False,
    ) -> bool:
        """写入时原子版本校验（契约 第 6.2 节）。

        返回 False 表示本次 revision 已被更高版本取代，调用方必须放弃写入。
        """
        with self._lock:
            conn = self._conn
            conn.execute("BEGIN IMMEDIATE")
            try:
                row = conn.execute(
                    "SELECT applied_revision FROM file_state WHERE owner_id = ? AND file_id = ?",
                    (owner_id, file_id),
                ).fetchone()
                applied = int(row["applied_revision"]) if row else 0
                if revision < applied:
                    conn.execute("ROLLBACK")
                    return False
                if drop_chunks or chunks is not None:
                    conn.execute(
                        "DELETE FROM chunks WHERE owner_id = ? AND file_id = ?", (owner_id, file_id)
                    )
                if chunks:
                    conn.executemany(
                        """
                        INSERT INTO chunks (
                            owner_id, file_id, chunk_index, revision, text, char_start, char_end
                        ) VALUES (?,?,?,?,?,?,?)
                        """,
                        [
                            (
                                owner_id,
                                file_id,
                                chunk.index,
                                revision,
                                chunk.text,
                                chunk.char_start,
                                chunk.char_end,
                            )
                            for chunk in chunks
                        ],
                    )
                conn.execute(
                    """
                    INSERT INTO file_state (owner_id, file_id, applied_revision, active, updated_at)
                    VALUES (?,?,?,?,?)
                    ON CONFLICT(owner_id, file_id) DO UPDATE SET
                        applied_revision = excluded.applied_revision,
                        active           = excluded.active,
                        updated_at       = excluded.updated_at
                    """,
                    (owner_id, file_id, revision, 1 if active else 0, time.time()),
                )
                conn.execute("COMMIT")
                return True
            except Exception:
                conn.execute("ROLLBACK")
                raise

    # ------------------------------------------------------------------ 分块

    def chunks(self, owner_id: str, file_id: str) -> list[Chunk]:
        with self._lock:
            rows = self._conn.execute(
                """
                SELECT chunk_index, text, char_start, char_end FROM chunks
                WHERE owner_id = ? AND file_id = ?
                ORDER BY chunk_index ASC
                """,
                (owner_id, file_id),
            ).fetchall()
        return [
            Chunk(
                index=int(row["chunk_index"]),
                text=row["text"],
                char_start=int(row["char_start"]),
                char_end=int(row["char_end"]),
            )
            for row in rows
        ]

    def chunk_count(self, owner_id: str, file_id: str) -> int:
        with self._lock:
            row = self._conn.execute(
                "SELECT COUNT(*) AS total FROM chunks WHERE owner_id = ? AND file_id = ?",
                (owner_id, file_id),
            ).fetchone()
        return int(row["total"])


def _to_stored_event(row: sqlite3.Row) -> StoredEvent:
    return StoredEvent(
        event_id=row["event_id"],
        operation=Operation(row["operation"]),
        file_id=row["file_id"],
        owner_id=row["owner_id"],
        revision=int(row["revision"]),
        file_name=row["file_name"],
        storage_backend=row["storage_backend"],
        storage_key=row["storage_key"],
        status=EventStatus(row["status"]),
        attempts=int(row["attempts"]),
    )


def _trim(message: str | None, limit: int = 1000) -> str | None:
    if message is None:
        return None
    return message[:limit]
