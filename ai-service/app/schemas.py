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

    # 用途：校验文件名不携带路径且格式受支持。
    # 输入：value：文件名；cls：由 Pydantic 提供的模型类。
    # 输出：原文件名；非法时抛 ValueError。
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

    # 用途：校验 Base64 文件内容非空且不超过 10 MB。
    # 输入：value：Base64 字符串；cls：模型类。
    # 输出：原 Base64 字符串；编码或大小非法时抛 ValueError。
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

    # 用途：校验批准决定与 Java 工单结果一致。
    # 输入：self：已解析的确认请求模型。
    # 输出：校验后的请求自身；批准缺工单或拒绝带工单时抛 ValueError。
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
