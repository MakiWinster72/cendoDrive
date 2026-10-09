"""事件、状态与分块的领域模型。

事件结构严格对应 `docs/AI索引事件接口契约.md` 第 4 节；
状态取值对应 `docs/文档索引服务对接说明.md` 第 9 节（`IGNORED` 为实现补充，见 README 假设清单）。
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from pydantic import BaseModel, Field, model_validator


class Operation(str, Enum):
    """契约 第 4.2 节：UPSERT、DEACTIVATE、DELETE。"""

    UPSERT = "UPSERT"
    DEACTIVATE = "DEACTIVATE"
    DELETE = "DELETE"


class EventStatus(str, Enum):
    """对接说明 第 9 节的状态模型。"""

    RECEIVED = "RECEIVED"  # 已受理，等待处理（含重试等待）
    PARSING = "PARSING"
    CHUNKED = "CHUNKED"  # 文本提取与分块完成（Milvus 写入待接入）
    EMBEDDED = "EMBEDDED"  # 预留：待 Maki 的模型与集合结构
    INDEXED = "INDEXED"  # 预留：已可搜索
    DEACTIVATED = "DEACTIVATED"
    DELETED = "DELETED"
    UNSUPPORTED = "UNSUPPORTED"
    EMPTY_TEXT = "EMPTY_TEXT"
    FAILED = "FAILED"
    FILE_MISSING = "FILE_MISSING"
    IGNORED = "IGNORED"  # 实现补充：写入时发现已被更高 revision 取代


class Decision(str, Enum):
    """事件受理结论，用于映射 HTTP 响应码。"""

    ACCEPTED = "ACCEPTED"  # 202
    DUPLICATE = "DUPLICATE"  # 200 ignored
    OLD = "OLD"  # 200 ignored
    SAME_REVISION = "SAME_REVISION"  # 200 ignored


class IndexEvent(BaseModel):
    """契约 第 4 节的事件请求体。"""

    eventId: str = Field(min_length=1, max_length=128)
    operation: Operation
    fileId: str = Field(min_length=1, max_length=32)
    ownerId: str = Field(min_length=1, max_length=32)
    revision: int = Field(ge=1, description="契约要求必须大于 0")
    fileName: str | None = None
    storageBackend: str | None = None
    storageKey: str | None = None

    @model_validator(mode="after")
    def _require_upsert_payload(self) -> "IndexEvent":
        if self.operation is Operation.UPSERT:
            missing = [
                name
                for name in ("fileName", "storageBackend", "storageKey")
                if not getattr(self, name)
            ]
            if missing:
                raise ValueError("UPSERT 缺少必填字段: " + ", ".join(missing))
        return self


@dataclass(frozen=True, slots=True)
class StoredEvent:
    """从本地任务表读出的待处理事件（不做模型校验，避免历史数据抛错）。"""

    event_id: str
    operation: Operation
    file_id: str
    owner_id: str
    revision: int
    file_name: str | None
    storage_backend: str | None
    storage_key: str | None
    status: EventStatus
    attempts: int


@dataclass(frozen=True, slots=True)
class Chunk:
    """分块的临时结构。

    TODO(contract): 待 Maki 给出 Milvus 集合结构后增加适配层；
    ``char_start``/``char_end`` 为该分块在原文中的精确区间，满足
    ``text[char_start:char_end] == chunk.text``。
    """

    index: int
    text: str
    char_start: int
    char_end: int
