"""HTTP 接口。

契约 第 4、7 节：``POST /internal/ai/index-events``，无鉴权（仅内网）。

响应码约定（对接说明 第 4 节）：
- 首次受理 ``202``；重复或旧事件 ``200`` + ``ignored``；
- 请求内容错误 ``400``（不使用其它 4xx，避免被 Lucky 当作可重试）；
- 内部异常 ``503``，交由 Lucky 稍后重试。
"""

from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager
from typing import AsyncIterator

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .config import Settings, get_settings
from .models import Decision, IndexEvent
from .pipeline import IndexService
from .sources import build_source
from .store import Store

LOG = logging.getLogger(__name__)


def _first_message(exc: RequestValidationError) -> str:
    errors = exc.errors()
    if not errors:
        return "请求内容不合法"
    first = errors[0]
    location = ".".join(str(part) for part in first.get("loc", ()) if part != "body")
    message = str(first.get("msg", "请求内容不合法"))
    return f"{location}: {message}" if location else message


async def _worker_loop(service: IndexService, settings: Settings, stop: asyncio.Event) -> None:
    while not stop.is_set():
        try:
            processed = await asyncio.to_thread(service.process_due)
        except Exception:  # pragma: no cover - 轮询异常只记录
            LOG.exception("索引 worker 轮询失败")
            processed = 0
        if processed:
            continue
        try:
            await asyncio.wait_for(stop.wait(), timeout=settings.worker_poll_seconds)
        except asyncio.TimeoutError:
            continue


def create_app(*, settings: Settings | None = None, service: IndexService | None = None) -> FastAPI:
    resolved = settings or get_settings()
    index_service = service or IndexService(
        settings=resolved,
        store=Store(resolved.database_file),
        source=build_source(resolved),
    )

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        stop = asyncio.Event()
        task = asyncio.create_task(_worker_loop(index_service, resolved, stop))
        app.state.worker = task
        try:
            yield
        finally:
            stop.set()
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass

    app = FastAPI(title="CendoDrive 文档索引服务", version="0.1.0", lifespan=lifespan)

    @app.exception_handler(RequestValidationError)
    async def _invalid_event(request: Request, exc: RequestValidationError) -> JSONResponse:
        return JSONResponse(
            status_code=400,
            content={
                "code": "INVALID_INDEX_EVENT",
                "message": _first_message(exc),
                "retryable": False,
            },
        )

    @app.exception_handler(Exception)
    async def _unavailable(request: Request, exc: Exception) -> JSONResponse:
        LOG.exception("索引事件处理失败")
        return JSONResponse(
            status_code=503,
            content={
                "code": "INDEX_SERVICE_UNAVAILABLE",
                "message": "索引服务内部错误，请稍后重试",
                "retryable": True,
            },
        )

    @app.post("/internal/ai/index-events")
    async def receive(event: IndexEvent) -> JSONResponse:
        decision = await asyncio.to_thread(index_service.accept, event)
        if decision is Decision.ACCEPTED:
            return JSONResponse(
                status_code=202, content={"eventId": event.eventId, "accepted": True}
            )
        return JSONResponse(
            status_code=200,
            content={"eventId": event.eventId, "accepted": True, "ignored": True},
        )

    @app.get("/healthz")
    async def healthz() -> dict[str, str]:
        return {"status": "ok", "source": resolved.source_kind}

    return app
