from __future__ import annotations

from contextlib import contextmanager
from pathlib import Path
from typing import Callable, Iterator

from index_service.config import Settings
from index_service.models import Decision, EventStatus
from index_service.pipeline import IndexService
from index_service.sources import LocalFileSource, TransientStorageError
from index_service.store import Store

from helpers import make_lifecycle, make_upsert


class _FailingSource:
    def __init__(self, error: Exception) -> None:
        self._error = error

    @contextmanager
    def materialize(self, **kwargs: object) -> Iterator[Path]:
        raise self._error
        yield Path()


def _write(root: Path, name: str, text: str) -> None:
    (root / name).write_text(text, encoding="utf-8")


def test_upsert_produces_chunks_and_records_state(
    settings: Settings, store: Store, service: IndexService, files_root: Path
) -> None:
    _write(files_root, "notes.txt", "第一段内容。" * 80)
    event = make_upsert()

    assert service.accept(event) is Decision.ACCEPTED
    assert store.event_status(event.eventId) is EventStatus.RECEIVED

    assert service.process_due() == 1

    assert store.event_status(event.eventId) is EventStatus.CHUNKED
    assert store.applied_revision("1", "1") == 1
    assert store.is_active("1", "1") is True
    chunks = store.chunks("1", "1")
    assert len(chunks) > 1
    assert all(chunk.text and chunk.char_end > chunk.char_start for chunk in chunks)


def test_duplicate_event_is_ignored(
    settings: Settings, store: Store, service: IndexService, files_root: Path
) -> None:
    _write(files_root, "notes.txt", "内容。" * 60)
    event = make_upsert()

    assert service.accept(event) is Decision.ACCEPTED
    assert service.accept(event) is Decision.DUPLICATE
    assert service.process_due() == 1
    assert store.chunk_count("1", "1") > 0


def test_old_and_same_revision_are_ignored(settings: Settings, store: Store, service: IndexService) -> None:
    store.apply_revision(owner_id="1", file_id="1", revision=5, active=True)

    assert service.accept(make_upsert(revision=3)) is Decision.OLD
    assert service.accept(make_upsert(revision=5)) is Decision.SAME_REVISION


def test_deactivate_keeps_chunks_and_marks_inactive(
    settings: Settings, store: Store, service: IndexService, files_root: Path
) -> None:
    _write(files_root, "notes.txt", "内容。" * 120)
    service.accept(make_upsert())
    service.process_due()
    before = store.chunk_count("1", "1")
    assert before > 0

    event = make_lifecycle("DEACTIVATE", revision=2)
    assert service.accept(event) is Decision.ACCEPTED
    service.process_due()

    assert store.event_status(event.eventId) is EventStatus.DEACTIVATED
    assert store.is_active("1", "1") is False
    assert store.chunk_count("1", "1") == before
    assert store.applied_revision("1", "1") == 2


def test_delete_drops_chunks(
    settings: Settings, store: Store, service: IndexService, files_root: Path
) -> None:
    _write(files_root, "notes.txt", "内容。" * 120)
    service.accept(make_upsert())
    service.process_due()
    assert store.chunk_count("1", "1") > 0

    event = make_lifecycle("DELETE", revision=2)
    assert service.accept(event) is Decision.ACCEPTED
    service.process_due()

    assert store.event_status(event.eventId) is EventStatus.DELETED
    assert store.chunk_count("1", "1") == 0
    assert store.is_active("1", "1") is False
    assert store.applied_revision("1", "1") == 2


def test_event_superseded_before_write_is_ignored(
    settings: Settings, store: Store, service: IndexService, files_root: Path
) -> None:
    _write(files_root, "notes.txt", "内容。" * 80)
    event = make_upsert()
    assert service.accept(event) is Decision.ACCEPTED

    # 处理期间更高版本的 DEACTIVATE 已经生效
    store.apply_revision(owner_id="1", file_id="1", revision=2, active=False)

    service.process_due()

    assert store.event_status(event.eventId) is EventStatus.IGNORED
    assert store.applied_revision("1", "1") == 2
    assert store.chunk_count("1", "1") == 0


def test_transient_failure_schedules_retry(
    settings: Settings, store: Store
) -> None:
    service = IndexService(
        settings=settings, store=store, source=_FailingSource(TransientStorageError("storage down"))
    )
    event = make_upsert()
    assert service.accept(event) is Decision.ACCEPTED

    assert service.process_due() == 1

    assert store.event_status(event.eventId) is EventStatus.RECEIVED
    assert store.event_attempts(event.eventId) == 1
    assert "storage down" in (store.event_error(event.eventId) or "")


def test_retry_exhaustion_marks_failed(
    make_settings: Callable[..., Settings], store: Store
) -> None:
    settings = make_settings(max_attempts=2, retry_backoff_seconds="0")
    service = IndexService(
        settings=settings, store=store, source=_FailingSource(TransientStorageError("down"))
    )
    event = make_upsert()
    service.accept(event)

    service.process_due()
    service.process_due()

    assert store.event_status(event.eventId) is EventStatus.FAILED
    assert store.event_attempts(event.eventId) == 2


def test_missing_file_stops_after_bounded_retries(
    make_settings: Callable[..., Settings], store: Store
) -> None:
    settings = make_settings(file_missing_max_attempts=1)
    service = IndexService(
        settings=settings, store=store, source=LocalFileSource(settings.source_root)
    )
    event = make_upsert(name="missing.txt")
    service.accept(event)

    service.process_due()

    assert store.event_status(event.eventId) is EventStatus.FILE_MISSING


def test_unsupported_and_empty_documents_are_recorded(
    settings: Settings, store: Store, service: IndexService, files_root: Path
) -> None:
    (files_root / "archive.zip").write_bytes(b"PK\x03\x04")
    _write(files_root, "blank.txt", "   \n  ")

    zip_event = make_upsert(file_id="1", name="archive.zip")
    blank_event = make_upsert(file_id="2", name="blank.txt")
    service.accept(zip_event)
    service.accept(blank_event)
    service.process_due()

    assert store.event_status(zip_event.eventId) is EventStatus.UNSUPPORTED
    assert store.event_status(blank_event.eventId) is EventStatus.EMPTY_TEXT


def test_unreachable_backend_fails_without_retry(
    settings: Settings, store: Store, service: IndexService
) -> None:
    event = make_upsert(name="notes.txt", backend="fastdfs")
    service.accept(event)

    service.process_due()

    assert store.event_status(event.eventId) is EventStatus.FAILED
    assert store.event_attempts(event.eventId) == 1
