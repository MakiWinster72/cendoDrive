from __future__ import annotations

from typing import Any

from fastapi.testclient import TestClient

from index_service.api import create_app
from index_service.config import Settings
from index_service.pipeline import IndexService
from index_service.store import Store

ENDPOINT = "/internal/ai/index-events"


def _valid_payload(**overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "eventId": "file-1-1-UPSERT",
        "operation": "UPSERT",
        "fileId": "1",
        "ownerId": "1",
        "revision": 1,
        "fileName": "notes.txt",
        "storageBackend": "local",
        "storageKey": "notes.txt",
    }
    payload.update(overrides)
    return payload


def _client(settings: Settings, service: IndexService) -> TestClient:
    """不进入 lifespan：接口测试只验证受理语义，不启动后台 worker。"""
    return TestClient(create_app(settings=settings, service=service))


def test_first_delivery_returns_202(settings: Settings, service: IndexService) -> None:
    response = _client(settings, service).post(ENDPOINT, json=_valid_payload())

    assert response.status_code == 202
    assert response.json() == {"eventId": "file-1-1-UPSERT", "accepted": True}


def test_duplicate_delivery_returns_200_ignored(
    settings: Settings, service: IndexService
) -> None:
    client = _client(settings, service)
    assert client.post(ENDPOINT, json=_valid_payload()).status_code == 202
    response = client.post(ENDPOINT, json=_valid_payload())

    assert response.status_code == 200
    assert response.json() == {
        "eventId": "file-1-1-UPSERT",
        "accepted": True,
        "ignored": True,
    }


def test_old_revision_returns_200_ignored(
    settings: Settings, service: IndexService, store: Store
) -> None:
    # revision 5 已生效后，迟到的 revision 3 直接按旧事件忽略
    store.apply_revision(owner_id="1", file_id="1", revision=5, active=True)

    response = _client(settings, service).post(
        ENDPOINT, json=_valid_payload(revision=3, eventId="file-1-3-UPSERT")
    )

    assert response.status_code == 200
    assert response.json()["ignored"] is True


def test_upsert_without_storage_key_returns_400(
    settings: Settings, service: IndexService
) -> None:
    payload = _valid_payload()
    payload.pop("storageKey")

    response = _client(settings, service).post(ENDPOINT, json=payload)

    assert response.status_code == 400
    body = response.json()
    assert body["code"] == "INVALID_INDEX_EVENT"
    assert body["retryable"] is False


def test_zero_revision_returns_400(settings: Settings, service: IndexService) -> None:
    response = _client(settings, service).post(ENDPOINT, json=_valid_payload(revision=0))

    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_INDEX_EVENT"


def test_unknown_operation_returns_400(settings: Settings, service: IndexService) -> None:
    response = _client(settings, service).post(ENDPOINT, json=_valid_payload(operation="PATCH"))

    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_INDEX_EVENT"


def test_malformed_json_returns_400(settings: Settings, service: IndexService) -> None:
    response = _client(settings, service).post(
        ENDPOINT, content=b"{", headers={"Content-Type": "application/json"}
    )

    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_INDEX_EVENT"


def test_deactivate_without_storage_fields_is_accepted(
    settings: Settings, service: IndexService
) -> None:
    payload = {
        "eventId": "file-1-2-DEACTIVATE",
        "operation": "DEACTIVATE",
        "fileId": "1",
        "ownerId": "1",
        "revision": 2,
    }

    response = _client(settings, service).post(ENDPOINT, json=payload)

    assert response.status_code == 202


def test_internal_error_returns_503(settings: Settings) -> None:
    class BrokenService:
        def accept(self, event: object) -> None:
            raise RuntimeError("boom")

    app = create_app(settings=settings, service=BrokenService())  # type: ignore[arg-type]
    client = TestClient(app, raise_server_exceptions=False)
    response = client.post(ENDPOINT, json=_valid_payload())

    assert response.status_code == 503
    body = response.json()
    assert body["code"] == "INDEX_SERVICE_UNAVAILABLE"
    assert body["retryable"] is True


def test_healthz(settings: Settings, service: IndexService) -> None:
    response = _client(settings, service).get("/healthz")

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "source": "local"}


def test_worker_lifespan_starts_and_stops(settings: Settings, service: IndexService) -> None:
    """进入 lifespan 会启动后台 worker，退出时应干净结束。"""
    with _client(settings, service) as client:
        assert client.get("/healthz").status_code == 200
