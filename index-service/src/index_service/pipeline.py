"""索引事件处理流水线。

对应契约 第 5、6、7 节与对接说明 第 3、4、5、6 节：

- ``accept``：幂等（``eventId``）与乱序（``revision``）的前置判断，只做轻量判断后立即返回；
- ``process_due``：worker 拉取待处理事件，执行"读取 → 提取 → 分块 → 写入"，
  失败按退避重试；
- Milvus 与 Embedding 尚未接入（等 Maki），因此 UPSERT 的终态是 ``CHUNKED``：
  文本与分块已就绪，向量写入作为后续阶段，不谎报 ``INDEXED``。
"""

from __future__ import annotations

import logging
import time

from .chunker import chunk_text
from .config import Settings
from .extractors import (
    CorruptDocument,
    EmptyText,
    ParseTimeout,
    TooLargeFile,
    TooManyPages,
    UnsupportedFormat,
    extract,
)
from .models import Chunk, Decision, EventStatus, IndexEvent, Operation, StoredEvent
from .sources import (
    FileMissingError,
    FileSource,
    StorageNotConfigured,
    TransientStorageError,
)
from .store import Store

LOG = logging.getLogger(__name__)


class IndexService:
    def __init__(self, *, settings: Settings, store: Store, source: FileSource) -> None:
        self._settings = settings
        self._store = store
        self._source = source

    # ---------------------------------------------------------------- 受理

    def accept(self, event: IndexEvent) -> Decision:
        """契约 第 6 节：重复事件与旧事件都返回成功，但不产生新的处理任务。"""
        if self._store.event_exists(event.eventId):
            return Decision.DUPLICATE

        applied = self._store.applied_revision(event.ownerId, event.fileId)
        if event.revision < applied:
            return Decision.OLD
        if event.revision == applied:
            return Decision.SAME_REVISION

        self._store.create_event(event)
        return Decision.ACCEPTED

    # ---------------------------------------------------------------- 处理

    def process_due(self, *, limit: int | None = None) -> int:
        batch = self._settings.worker_batch if limit is None else limit
        records = self._store.due_events(limit=batch)
        for record in records:
            try:
                self._process(record)
            except Exception:  # 兜底：保证单个事件的问题不会拖垮 worker
                LOG.exception("处理事件 %s 时出现未预期错误", record.event_id)
                self._safe_retry(record, "处理时出现未预期错误")
        return len(records)

    def _process(self, record: StoredEvent) -> EventStatus:
        if record.operation is Operation.DEACTIVATE:
            return self._apply(record, status=EventStatus.DEACTIVATED, active=False)
        if record.operation is Operation.DELETE:
            return self._apply(
                record, status=EventStatus.DELETED, active=False, drop_chunks=True
            )
        return self._process_upsert(record)

    def _process_upsert(self, record: StoredEvent) -> EventStatus:
        self._store.mark_status(record.event_id, EventStatus.PARSING)
        try:
            with self._source.materialize(
                storage_backend=record.storage_backend or "",
                storage_key=record.storage_key or "",
                file_name=record.file_name,
            ) as path:
                result = extract(path, record.file_name or "", self._settings)

            chunks: list[Chunk] = chunk_text(
                result.text,
                chunk_size=self._settings.chunk_size,
                chunk_overlap=self._settings.chunk_overlap,
            )
            if not chunks:
                return self._terminal(record, EventStatus.EMPTY_TEXT, "未产生任何分块")
            return self._apply(record, status=EventStatus.CHUNKED, active=True, chunks=chunks)

        except UnsupportedFormat as exc:
            return self._terminal(record, EventStatus.UNSUPPORTED, str(exc))
        except (TooLargeFile, TooManyPages) as exc:
            return self._terminal(record, EventStatus.UNSUPPORTED, str(exc))
        except (CorruptDocument, ParseTimeout) as exc:
            return self._terminal(record, EventStatus.FAILED, str(exc))
        except EmptyText as exc:
            return self._terminal(record, EventStatus.EMPTY_TEXT, str(exc))
        except StorageNotConfigured as exc:
            return self._terminal(record, EventStatus.FAILED, str(exc))
        except FileMissingError as exc:
            return self._retry(
                record,
                exc,
                max_attempts=self._settings.file_missing_max_attempts,
                final_status=EventStatus.FILE_MISSING,
            )
        except (TransientStorageError, TimeoutError, OSError) as exc:
            return self._retry(record, exc)
        except Exception as exc:  # 兜底按可重试处理
            return self._retry(record, exc)

    def _apply(
        self,
        record: StoredEvent,
        *,
        status: EventStatus,
        active: bool,
        chunks: list[Chunk] | None = None,
        drop_chunks: bool = False,
    ) -> EventStatus:
        applied = self._store.apply_revision(
            owner_id=record.owner_id,
            file_id=record.file_id,
            revision=record.revision,
            active=active,
            chunks=chunks,
            drop_chunks=drop_chunks,
        )
        if not applied:
            LOG.info("事件 %s 已被更高 revision 取代，放弃写入", record.event_id)
            self._store.mark_status(
                record.event_id, EventStatus.IGNORED, error="已被更高 revision 取代"
            )
            return EventStatus.IGNORED
        self._store.mark_status(record.event_id, status)
        return status

    def _terminal(self, record: StoredEvent, status: EventStatus, message: str) -> EventStatus:
        self._store.mark_status(
            record.event_id, status, error=message, attempts=record.attempts + 1
        )
        LOG.info("事件 %s 终止为 %s：%s", record.event_id, status.value, message)
        return status

    def _retry(
        self,
        record: StoredEvent,
        exc: Exception,
        *,
        max_attempts: int | None = None,
        final_status: EventStatus = EventStatus.FAILED,
    ) -> EventStatus:
        limit = self._settings.max_attempts if max_attempts is None else max_attempts
        attempts = record.attempts + 1
        message = f"{type(exc).__name__}: {exc}"
        if attempts >= limit:
            self._store.mark_status(
                record.event_id,
                final_status,
                error=f"达到最大尝试次数 {limit}：{message}",
                attempts=attempts,
            )
            LOG.warning("事件 %s 达到最大尝试次数 %s，终止为 %s", record.event_id, limit, final_status.value)
            return final_status

        backoff = self._settings.backoff_seconds
        delay = backoff[min(attempts - 1, len(backoff) - 1)]
        self._store.mark_status(
            record.event_id,
            EventStatus.RECEIVED,
            error=message,
            attempts=attempts,
            next_attempt_at=time.time() + delay,
        )
        LOG.info("事件 %s 第 %s 次失败，%s 秒后重试：%s", record.event_id, attempts, delay, message)
        return EventStatus.RECEIVED

    def _safe_retry(self, record: StoredEvent, message: str) -> None:
        try:
            self._store.mark_status(
                record.event_id,
                EventStatus.RECEIVED,
                error=message,
                attempts=record.attempts + 1,
                next_attempt_at=time.time() + self._settings.backoff_seconds[0],
            )
        except Exception:  # pragma: no cover - 存储同时故障时只能记录日志
            LOG.exception("重试登记失败，事件 %s 保持原状态", record.event_id)
