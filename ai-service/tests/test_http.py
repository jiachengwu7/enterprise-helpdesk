from fastapi.testclient import TestClient
from app.main import create_app
from app.config import Settings
from types import SimpleNamespace
from contextlib import nullcontext
from uuid import uuid4
from langgraph.checkpoint.memory import InMemorySaver
from app.graph import ChatService


def client():
    app = create_app(Settings(ai_service_token="test-service-token"), initialize=False)
    app.state.runtime = SimpleNamespace()
    return TestClient(app)


def test_every_internal_route_rejects_missing_and_wrong_token():
    c = client()
    for path in [
        "/internal/documents/status",
        "/internal/chat",
        "/internal/chat/confirm",
        "/internal/chat/state",
    ]:
        assert c.post(path, json={}).status_code == 401
        assert c.post(path, json={}, headers={"X-Service-Token": "wrong"}).status_code == 401
    assert c.delete("/internal/documents/00000000-0000-0000-0000-000000000001").status_code == 401
    assert (
        c.get("/internal/documents/00000000-0000-0000-0000-000000000001/download").status_code
        == 401
    )


def test_missing_service_secret_fails_closed():
    c = TestClient(create_app(Settings(ai_service_token=""), initialize=False))
    assert (
        c.post(
            "/internal/documents/status", json={"ids": []}, headers={"X-Service-Token": ""}
        ).status_code
        == 401
    )


def test_authenticated_invalid_input_is_safe_and_health_has_no_secrets():
    c = client()
    result = c.post(
        "/internal/chat",
        json={"secret": "DO NOT ECHO"},
        headers={"X-Service-Token": "test-service-token"},
    )
    assert result.status_code == 422
    assert "DO NOT ECHO" not in result.text
    health = c.get("/health")
    assert health.status_code == 200
    assert set(health.json()) == {
        "status",
        "modelConfigured",
        "embeddingConfigured",
        "indexMode",
        "database",
        "redis",
        "storage",
        "version",
    }
    assert "test-service-token" not in health.text


def test_authenticated_state_endpoint_returns_only_user_qualified_pending_answer():
    repo = SimpleNamespace(lock=lambda key: nullcontext())
    retriever = SimpleNamespace(search=lambda *args, **kwargs: [])
    service = ChatService(repo, retriever, None, InMemorySaver())
    app = create_app(Settings(ai_service_token="test-service-token"), initialize=False)
    app.state.runtime = SimpleNamespace(chat=service)
    c = TestClient(app)
    identity = {"userId": str(uuid4()), "conversationId": str(uuid4())}
    headers = {"X-Service-Token": "test-service-token"}
    empty = c.post("/internal/chat/state", json=identity, headers=headers)
    assert empty.status_code == 200
    assert empty.json() == {"question": None, "pending": None}
    original = service.chat(
        {
            **identity,
            "question": "VPN repair",
            "requestTicket": True,
            "allowedDocumentIds": [],
            "departmentId": "IT",
            "role": "EMPLOYEE",
        }
    )
    recovered = c.post("/internal/chat/state", json=identity, headers=headers)
    assert recovered.status_code == 200
    assert recovered.json() == {"question": "VPN repair", "pending": original}
    other = c.post(
        "/internal/chat/state", json={**identity, "userId": str(uuid4())}, headers=headers
    )
    assert other.json() == {"question": None, "pending": None}
    invalid = c.post(
        "/internal/chat/state", json={**identity, "userId": "not-a-uuid"}, headers=headers
    )
    assert invalid.status_code == 422
