import base64
import uuid
import pytest
from pydantic import ValidationError
from app.schemas import ChatRequest, DocumentRequest
from app.retrieval import select_sources, validate_citations
from app.ingestion import can_publish, parse_document

U = str(uuid.uuid4())


def source(id=U, version=1):
    return dict(
        documentId=id,
        title="Policy",
        filename="p.txt",
        chunkIndex=0,
        content="VPN requires MFA",
        score=1.0,
        version=version,
    )


def test_acl_empty_and_unlisted_are_denied():
    assert select_sources([source()], [], {U: 1}) == []
    assert select_sources([source()], [str(uuid.uuid4())], {U: 1}) == []


def test_deleted_and_superseded_versions_are_denied():
    assert select_sources([source()], [U], {}) == []
    assert select_sources([source()], [U], {U: 2}) == []
    assert select_sources([source(version=2)], [U], {U: 2})[0]["version"] == 2
    assert not can_publish({"version": 2, "deleted": False}, 1)
    assert not can_publish({"version": 2, "deleted": True}, 2)


def test_hallucinated_citation_is_rejected():
    assert validate_citations("MFA [1]", [source()])
    assert not validate_citations("MFA [2]", [source()])
    assert not validate_citations("MFA without source", [source()])


def test_blank_question_and_path_filename_are_rejected():
    with pytest.raises(ValidationError):
        ChatRequest(
            conversationId=U,
            userId=U,
            departmentId="IT",
            role="EMPLOYEE",
            allowedDocumentIds=[],
            question="   ",
            requestTicket=False,
        )
    with pytest.raises(ValidationError):
        DocumentRequest(
            id=U,
            title="test",
            filename="../secret.txt",
            departmentId="IT",
            visibility="PUBLIC",
            version=1,
            contentBase64="eA==",
        )


def test_parser_rejects_binary_text_and_reads_utf8():
    assert parse_document("readme.md", "你好".encode()) == "你好"
    with pytest.raises(ValueError):
        parse_document("readme.txt", b"\x00\xff")
