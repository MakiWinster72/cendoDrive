from __future__ import annotations

from pathlib import Path
from typing import Callable

import pytest

from index_service.config import Settings
from index_service.pipeline import IndexService
from index_service.sources import LocalFileSource
from index_service.store import Store


@pytest.fixture
def make_settings(tmp_path: Path) -> Callable[..., Settings]:
    def factory(**overrides: object) -> Settings:
        base: dict[str, object] = {
            "_env_file": None,
            "database_path": str(tmp_path / "index.sqlite3"),
            "source_kind": "local",
            "local_source_root": str(tmp_path / "files"),
            "chunk_size": 200,
            "chunk_overlap": 20,
            "worker_poll_seconds": 0.01,
        }
        base.update(overrides)
        return Settings(**base)

    return factory


@pytest.fixture
def settings(make_settings: Callable[..., Settings]) -> Settings:
    return make_settings()


@pytest.fixture
def files_root(settings: Settings) -> Path:
    root = settings.source_root
    root.mkdir(parents=True, exist_ok=True)
    return root


@pytest.fixture
def store(settings: Settings) -> Store:
    instance = Store(settings.database_file)
    yield instance
    instance.close()


@pytest.fixture
def service(settings: Settings, store: Store) -> IndexService:
    return IndexService(settings=settings, store=store, source=LocalFileSource(settings.source_root))
