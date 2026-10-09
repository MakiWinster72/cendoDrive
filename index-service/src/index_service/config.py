"""运行配置。

所有取值都可在部署时通过环境变量覆盖（统一前缀 ``INDEX_``）。
默认值与 `docs/文档索引服务对接说明.md` 第 6、7 节保持一致。
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

DEFAULT_BACKOFF_SECONDS = "30,60,300,900,1800,3600,7200,21600,43200,43200,43200"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="INDEX_", env_file=".env", extra="ignore")

    # 本地状态存储：幂等键、revision、分块中间结构
    database_path: str = "./data/index-service.sqlite3"

    # 文件来源：local 仅用于自测；fastdfs 待 Lucky 提供连接信息（TODO(contract)）
    source_kind: Literal["local", "fastdfs"] = "local"
    local_source_root: str = "./data/files"

    # 解析与分块（对接说明 第 7 节）
    chunk_size: int = 800
    chunk_overlap: int = 100
    max_file_bytes: int = 50 * 1024 * 1024
    max_pdf_pages: int = 1000
    parse_timeout_seconds: int = 120

    # 内部重试（对接说明 第 6 节）
    max_attempts: int = 12
    file_missing_max_attempts: int = 3
    retry_backoff_seconds: str = DEFAULT_BACKOFF_SECONDS

    # worker
    worker_poll_seconds: float = 1.0
    worker_batch: int = 20

    @model_validator(mode="after")
    def _validate(self) -> "Settings":
        if self.chunk_size <= 0:
            raise ValueError("chunk_size 必须为正整数")
        if not 0 <= self.chunk_overlap < self.chunk_size:
            raise ValueError("chunk_overlap 必须满足 0 <= overlap < chunk_size")
        if self.max_file_bytes <= 0:
            raise ValueError("max_file_bytes 必须为正整数")
        if self.max_pdf_pages <= 0:
            raise ValueError("max_pdf_pages 必须为正整数")
        if self.max_attempts <= 0:
            raise ValueError("max_attempts 必须为正整数")
        if not self.backoff_seconds:
            raise ValueError("retry_backoff_seconds 不能为空")
        if self.worker_batch <= 0:
            raise ValueError("worker_batch 必须为正整数")
        return self

    @property
    def backoff_seconds(self) -> list[int]:
        values: list[int] = []
        for part in self.retry_backoff_seconds.split(","):
            part = part.strip()
            if part:
                values.append(int(part))
        if any(value < 0 for value in values):
            raise ValueError("retry_backoff_seconds 不能为负数")
        return values

    @property
    def database_file(self) -> Path:
        return Path(self.database_path)

    @property
    def source_root(self) -> Path:
        return Path(self.local_source_root)


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
