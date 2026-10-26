"""Opt-in integration tests use real PostgreSQL, Redis and S3; never cloud models."""

import os
from uuid import uuid4
import pytest
from app.config import Settings
from app.main import Runtime
from app.graph import ChatService, Conflict
from app.retrieval import Retriever

pytestmark = pytest.mark.skipif(
    os.getenv("AI_INTEGRATION_TESTS") != "1",
    reason="Requires running local infrastructure and launcher environment",
)


@pytest.fixture(scope="module")
def rt():
    runtime = Runtime(Settings(agent_llm_api_key="", agent_embedding_api_key=""))
    yield runtime
    runtime.close()


def doc(id, version=1):
    return dict(
        id=id,
        title="Integration policy",
        filename="integration.txt",
        departmentId="IT",
        visibility="DEPARTMENT",
        version=version,
    )


def test_atomic_replace_delete_and_late_worker_cannot_resurrect(rt):
    id = str(uuid4())
    try:
        rt.repo.accept_document(doc(id), "tests/" + id + "/v1", "digest1")
        rt.repo.publish(id, 1, ["obsolete VPN secret"], None, None)
        assert Retriever(rt.repo, None).search("VPN", [id])[0]["content"] == "obsolete VPN secret"
        assert Retriever(rt.repo, None).search("VPN", []) == []
        assert (
            Retriever(rt.repo, None).search("VPN", [id], department_id="HR", role="EMPLOYEE") == []
        )
        assert Retriever(rt.repo, None).search("VPN", [id], department_id="IT", role="EMPLOYEE")
        assert Retriever(rt.repo, None).search("VPN", [id], department_id="HR", role="ADMIN")
        rt.repo.accept_document(doc(id, 2), "tests/" + id + "/v2", "digest2")
        rt.repo.publish(id, 1, ["old job must not win"], None, None)
        assert Retriever(rt.repo, None).search("obsolete", [id]) == []
        rt.repo.publish(id, 2, ["current VPN instructions"], [[1.0] + [0.0] * 1023], None)
        sources, _ = rt.repo.search([id], ["VPN"], [1.0] + [0.0] * 1023)
        assert sources[0]["version"] == 2
        assert sources[0]["content"] == "current VPN instructions"
        rt.repo.delete_document(id)
        rt.repo.publish(id, 2, ["deleted late worker content"], None, None)
        assert Retriever(rt.repo, None).search("content", [id]) == []
        with pytest.raises(Conflict):
            rt.repo.accept_document(doc(id, 3), "tests/" + id + "/v3", "digest3")
    finally:
        rt.repo.delete_document(id)


def test_postgres_checkpoint_survives_service_reconstruction_and_replay(rt):
    request = dict(
        userId=str(uuid4()),
        conversationId=str(uuid4()),
        departmentId="IT",
        role="EMPLOYEE",
        question="VPN无法连接，需要协助",
        allowedDocumentIds=[],
        requestTicket=True,
    )
    try:
        first = rt.chat.chat(request)
        restored = ChatService(rt.repo, Retriever(rt.repo, None), None, rt.saver)
        recovered = restored.pending_state(request)
        assert recovered == {"question": request["question"], "pending": first}
        with pytest.raises(Conflict):
            restored.chat(request)
        decision = dict(
            userId=request["userId"],
            conversationId=request["conversationId"],
            proposalId=first["proposal"]["id"],
            approved=True,
            ticketId=str(uuid4()),
            ticketNumber="TEST-HD-001",
        )
        assert "TEST-HD-001" in restored.confirm(decision)["answer"]
        assert restored.pending_state(request) == {"question": None, "pending": None}
        next_service = ChatService(rt.repo, Retriever(rt.repo, None), None, rt.saver)
        assert next_service.confirm(decision) == restored.confirm(decision)
        next_service.chat({**request, "requestTicket": False})
        assert "TEST-HD-001" in next_service.confirm(decision)["answer"]
    finally:
        rt.chat.delete(request["userId"], request["conversationId"])


def test_s3_original_round_trip(rt):
    key = "tests/" + str(uuid4())
    body = "原文完整保存，不由模型重写".encode()
    try:
        rt.storage.put(key, body)
        assert rt.storage.get(key) == body
        assert rt.redis.ping()
    finally:
        rt.storage.delete(key)
