from contextlib import nullcontext
from uuid import uuid4
import pytest
from langgraph.checkpoint.memory import InMemorySaver
from app.graph import ChatService, Conflict, ModelFailure


class Repo:
    def lock(self, key):
        return nullcontext()

    def confirmation(self, key, proposal):
        return None

    def save_confirmation(self, *args):
        pass


class Retrieval:
    def search(self, question, ids, **scope):
        return (
            []
            if not ids
            else [
                dict(
                    documentId=ids[0],
                    title="VPN",
                    filename="vpn.txt",
                    chunkIndex=0,
                    content="VPN requires MFA",
                    score=1.0,
                    version=1,
                )
            ]
        )


def request(ticket=False, ids=None, user=None):
    return dict(
        conversationId=str(uuid4()),
        userId=user or str(uuid4()),
        departmentId="IT",
        role="EMPLOYEE",
        allowedDocumentIds=ids or [],
        question="How to fix VPN?",
        requestTicket=ticket,
    )


def test_missing_model_is_explicit_and_never_invents_answer():
    service = ChatService(Repo(), Retrieval(), None, InMemorySaver())
    result = service.chat(request(ids=[str(uuid4())]))
    assert result["mode"] == "retrieval-only"
    assert "未调用模型" in result["answer"]
    assert "VPN requires MFA" in result["answer"]


def test_interrupt_resume_replay_and_conflicting_confirmation():
    service = ChatService(Repo(), Retrieval(), None, InMemorySaver())
    req = request(ticket=True)
    result = service.chat(req)
    proposal = result["proposal"]
    assert proposal and "等待人工确认" in result["trace"]
    with pytest.raises(Conflict):
        service.chat(req)
    decision = dict(
        conversationId=req["conversationId"],
        userId=req["userId"],
        proposalId=proposal["id"],
        approved=True,
        ticketId=str(uuid4()),
        ticketNumber="HD-1",
    )
    answer = service.confirm(decision)
    assert "HD-1" in answer["answer"]
    assert service.confirm(decision) == answer
    with pytest.raises(Conflict):
        service.confirm({**decision, "approved": False})
    with pytest.raises(Conflict):
        service.confirm({**decision, "userId": str(uuid4())})


def test_real_provider_failure_does_not_fall_back_to_fake_answer():
    class Failed:
        def invoke(self, messages):
            raise RuntimeError("SECRET provider error")

    service = ChatService(Repo(), Retrieval(), Failed(), InMemorySaver())
    with pytest.raises(ModelFailure, match="模型服务暂不可用"):
        service.chat(request(ids=[str(uuid4())]))


def test_new_turn_never_reuses_previous_authorized_sources():
    service = ChatService(Repo(), Retrieval(), None, InMemorySaver())
    req = request(ids=[str(uuid4())])
    assert service.chat(req)["sources"]
    assert service.chat({**req, "allowedDocumentIds": []})["sources"] == []


def test_configured_model_without_evidence_is_not_labelled_model_generation():
    class UnusedModel:
        def invoke(self, messages):
            raise AssertionError("No evidence must not call a model")

    service = ChatService(Repo(), Retrieval(), UnusedModel(), InMemorySaver())
    result = service.chat(request())
    assert result["mode"] == "retrieval-only"
    assert "未调用模型" in result["answer"]


def test_pending_state_recovers_lost_response_and_is_user_scoped():
    saver = InMemorySaver()
    service = ChatService(Repo(), Retrieval(), None, saver)
    req = request(ticket=True, ids=[str(uuid4())])
    identity = {k: req[k] for k in ("conversationId", "userId")}
    assert service.pending_state(identity) == {"question": None, "pending": None}
    original = service.chat(req)
    restored = ChatService(Repo(), Retrieval(), None, saver)
    recovered = restored.pending_state(identity)
    assert recovered == {"question": "How to fix VPN?", "pending": original}
    assert restored.pending_state({**identity, "userId": str(uuid4())}) == {
        "question": None,
        "pending": None,
    }
    assert restored.pending_state(identity) == recovered
    restored.confirm(
        {
            **identity,
            "proposalId": original["proposal"]["id"],
            "approved": False,
            "ticketId": None,
            "ticketNumber": None,
        }
    )
    assert restored.pending_state(identity) == {"question": None, "pending": None}


def test_completed_non_ticket_turn_is_not_a_pending_confirmation():
    service = ChatService(Repo(), Retrieval(), None, InMemorySaver())
    req = request()
    service.chat(req)
    assert service.pending_state(req) == {"question": None, "pending": None}
