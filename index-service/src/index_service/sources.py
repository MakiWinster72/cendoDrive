"""文件来源。

契约 第 3.1 节要求文档索引服务使用 ``storageBackend + storageKey`` 读取同一套 FastDFS，
连接参数由双方各自通过环境变量维护、不走事件传递。

当前状态：
- ``LocalFileSource``：本地目录直读，仅用于自测（对应 CendoDrive legacy 的 ``local`` 后端）；
- ``FastDfsSource``：TODO(contract)，等待 Lucky 提供 Tracker / Storage 地址后接入，
  在此之前投递来的 ``fastdfs`` 事件会以 ``FAILED`` 终止并保留明确错误信息。

异常分类：
- ``FileMissingError``：文件不存在 → 有界重试后置 ``FILE_MISSING``；
- ``TransientStorageError``：存储暂不可达 → 按退避重试；
- ``StorageNotConfigured``：来源未接通或后端不支持 → 直接 ``FAILED``，不做无意义重试。
"""

from __future__ import annotations

from contextlib import contextmanager
from pathlib import Path
from typing import Iterator, Protocol

from .config import Settings


class StorageError(Exception):
    """读取原始文件的失败基类。"""


class FileMissingError(StorageError):
    """文件在存储中不存在。"""


class TransientStorageError(StorageError):
    """存储暂时不可用，可重试。"""


class StorageNotConfigured(StorageError):
    """来源尚未接通或存储后端不支持，不应重试。"""


class FileSource(Protocol):
    def materialize(
        self, *, storage_backend: str, storage_key: str, file_name: str | None
    ) -> "object":
        """返回一个上下文管理器，``with`` 得到可读取的本地路径。"""
        ...


class LocalFileSource:
    """从本地目录读取文件，仅用于自测与本地联调。"""

    def __init__(self, root: str | Path) -> None:
        self._root = Path(root).resolve()

    @contextmanager
    def materialize(
        self, *, storage_backend: str, storage_key: str, file_name: str | None = None
    ) -> Iterator[Path]:
        if storage_backend != "local":
            raise StorageNotConfigured(
                f"LocalFileSource 只支持 storageBackend=local，收到 {storage_backend}"
            )
        candidate = (self._root / storage_key).resolve()
        if not candidate.is_relative_to(self._root):
            raise StorageNotConfigured(f"storageKey 越出根目录: {storage_key}")
        if not candidate.is_file():
            raise FileMissingError(f"文件不存在: {storage_key}")
        yield candidate


class FastDfsSource:
    """TODO(contract): 等待 Lucky 提供 FastDFS Tracker / Storage 地址后接入。"""

    def __init__(self, tracker: str | None = None) -> None:
        self._tracker = tracker

    @contextmanager
    def materialize(
        self, *, storage_backend: str, storage_key: str, file_name: str | None = None
    ) -> Iterator[Path]:
        raise StorageNotConfigured(
            "FastDFS 读取尚未接入：等待 Lucky 提供 Tracker/Storage 连接信息（契约 AI-11）"
        )
        yield Path()  # pragma: no cover - 仅用于保持上下文管理器语义


def build_source(settings: Settings) -> FileSource:
    if settings.source_kind == "fastdfs":
        return FastDfsSource()
    return LocalFileSource(settings.source_root)
