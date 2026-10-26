import base64
from typing import Literal
from uuid import UUID
from pydantic import BaseModel, Field, field_validator, model_validator


class StrictModel(BaseModel):
    model_config = {"extra": "forbid", "str_strip_whitespace": True}


class DocumentRequest(StrictModel):
    id: UUID
    title: str = Field(min_length=1, max_length=200)
    filename: str = Field(min_length=1, max_length=255)
    departmentId: Literal["IT", "HR"]
    visibility: Literal["PUBLIC", "DEPARTMENT"]
    version: int = Field(ge=1)
    contentBase64: str = Field(max_length=14_000_000)

    @field_validator("filename")
    @classmethod
    def filename_safe(cls, value):
        if any(c in value for c in "/\\\x00\r\n") or value.rsplit(".", 1)[-1].lower() not in (
            "txt",
            "md",
            "pdf",
            "docx",
        ):
            raise ValueError("不支持的文件名或格式")
        return value

    @field_validator("contentBase64")
    @classmethod
    def valid_content(cls, value):
        try:
            data = base64.b64decode(value, validate=True)
        except Exception:
            raise ValueError("文件编码无效")
        if not data or len(data) > 10 * 1024 * 1024:
            raise ValueError("文件应为1字节至10MB")
        return value


class IdsRequest(StrictModel):
    ids: list[UUID] = Field(max_length=10000)


class ChatRequest(StrictModel):
    conversationId: UUID
    userId: UUID
    departmentId: Literal["IT", "HR"]
    role: Literal["ADMIN", "AGENT", "EMPLOYEE"]
    allowedDocumentIds: list[UUID] = Field(max_length=10000)
    question: str = Field(min_length=1, max_length=4000)
    requestTicket: bool = False


class ConfirmRequest(StrictModel):
    conversationId: UUID
    userId: UUID
    proposalId: UUID
    approved: bool
    ticketId: UUID | None = None
    ticketNumber: str | None = Field(default=None, max_length=100)

    @model_validator(mode="after")
    def decision_valid(self):
        if self.approved and (not self.ticketId or not self.ticketNumber):
            raise ValueError("确认需要Java工单结果")
        if not self.approved and (self.ticketId or self.ticketNumber):
            raise ValueError("拒绝不能携带工单")
        return self


class ChatStateRequest(StrictModel):
    conversationId: UUID
    userId: UUID
