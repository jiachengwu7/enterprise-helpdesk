import base64
import hashlib
import hmac
import logging
from contextlib import asynccontextmanager
from uuid import UUID, uuid4
from urllib.parse import quote
from fastapi import FastAPI, APIRouter, Depends, Header, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from psycopg_pool import ConnectionPool
from psycopg.rows import dict_row
from psycopg.errors import LockNotAvailable
from redis import Redis
from langgraph.checkpoint.postgres import PostgresSaver
from .config import Settings
from .schemas import DocumentRequest, IdsRequest, ChatRequest, ConfirmRequest, ChatStateRequest
from .repository import Repository, Conflict
from .storage import ObjectStorage
from .worker import Worker
from .models import create_chat, create_embeddings
from .retrieval import Retriever
from .graph import ChatService, ModelFailure

log = logging.getLogger(__name__)


class Runtime:
    def __init__(self, settings):
        if len(settings.ai_service_token) < 16:
            raise RuntimeError("AI_SERVICE_TOKEN必须配置为至少16字符的随机密钥")
        self.settings = settings
        self.repo = Repository(settings.database_url)
        self.redis = Redis.from_url(
            settings.redis_url, decode_responses=True, socket_connect_timeout=5, socket_timeout=5
        )
        self.redis.ping()
        self.storage = ObjectStorage(settings)
        self.checkpoint_pool = ConnectionPool(
            settings.database_url,
            min_size=1,
            max_size=8,
            timeout=15,
            kwargs={
                "autocommit": True,
                "row_factory": dict_row,
                "options": "-c search_path=ai_graph,public",
            },
            open=True,
        )
        self.checkpoint_pool.wait(15)
        self.saver = PostgresSaver(self.checkpoint_pool)
        self.saver.setup()
        embeddings = create_embeddings(settings)
        self.chat = ChatService(
            self.repo, Retriever(self.repo, embeddings), create_chat(settings), self.saver
        )
        self.worker = Worker(self.repo, self.redis, self.storage, embeddings)
        self.worker.thread.start()

    def close(self):
        self.worker.close()
        self.checkpoint_pool.close()
        self.repo.pool.close()
        self.redis.close()


def create_app(settings=None, initialize=True):
    settings = settings or Settings()

    @asynccontextmanager
    async def lifespan(app):
        if initialize:
            app.state.runtime = Runtime(settings)
        yield
        if initialize:
            app.state.runtime.close()

    app = FastAPI(
        title="Enterprise Helpdesk AI",
        version="1.0.0",
        lifespan=lifespan,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.state.settings = settings

    @app.exception_handler(RequestValidationError)
    async def invalid(request, exc):
        return JSONResponse(
            status_code=422, content={"detail": "请求参数无效，请检查字段、文件格式和内容大小"}
        )

    @app.exception_handler(Conflict)
    async def conflict(request, exc):
        return JSONResponse(status_code=409, content={"detail": str(exc)})

    @app.exception_handler(ModelFailure)
    async def model_error(request, exc):
        return JSONResponse(status_code=502, content={"detail": str(exc)})

    @app.exception_handler(LockNotAvailable)
    async def busy(request, exc):
        return JSONResponse(status_code=409, content={"detail": "当前会话正在处理中，请稍后重试"})

    @app.exception_handler(Exception)
    async def failure(request, exc):
        log.error("Internal operation failed; exception type=%s", type(exc).__name__)
        return JSONResponse(status_code=503, content={"detail": "内部依赖暂不可用，请稍后重试"})

    def auth(x_service_token: str | None = Header(default=None)):
        token = settings.ai_service_token
        if (
            not token
            or not x_service_token
            or not hmac.compare_digest(token.encode(), x_service_token.encode())
        ):
            raise HTTPException(401, "内部服务认证失败")

    def runtime():
        if not hasattr(app.state, "runtime"):
            raise HTTPException(503, "内部服务尚未就绪")
        return app.state.runtime

    @app.get("/health")
    def health():
        result = {
            "status": "degraded",
            "modelConfigured": settings.chat_configured,
            "embeddingConfigured": settings.embedding_configured,
            "indexMode": "hybrid" if settings.embedding_configured else "keyword",
            "database": "unavailable",
            "redis": "unavailable",
            "storage": "unavailable",
            "version": "1.0.0",
        }
        rt = getattr(app.state, "runtime", None)
        if rt:
            try:
                with rt.repo.pool.connection() as c:
                    c.execute("SELECT 1")
                result["database"] = "ok"
            except Exception:
                pass
            try:
                rt.redis.ping()
                result["redis"] = "ok"
            except Exception:
                pass
            try:
                rt.storage.health()
                result["storage"] = "ok"
            except Exception:
                pass
        if all(result[k] == "ok" for k in ("database", "redis", "storage")):
            result["status"] = "ok"
        return result

    router = APIRouter(prefix="/internal", dependencies=[Depends(auth)])

    @router.post("/documents")
    def upload(body: DocumentRequest, rt=Depends(runtime)):
        req = body.model_dump(mode="json")
        data = base64.b64decode(body.contentBase64, validate=True)
        digest = hashlib.sha256(data).hexdigest()
        key = f"documents/{body.id}/{body.version}/{uuid4()}"
        rt.storage.put(key, data)
        try:
            rt.repo.accept_document(req, key, digest)
        except Exception:
            try:
                rt.repo.gc_add(key)
            except Exception:
                try:
                    rt.storage.delete(key)
                except Exception:
                    log.warning("Unreferenced upload cleanup deferred")
            raise
        return {"status": "QUEUED"}

    @router.post("/documents/status")
    def status(body: IdsRequest, rt=Depends(runtime)):
        return {"documents": rt.repo.status([str(x) for x in body.ids])}

    @router.delete("/documents/{id}")
    def delete_document(id: UUID, rt=Depends(runtime)):
        rt.repo.delete_document(str(id))
        return {"deleted": True}

    @router.get("/documents/{id}/download")
    def download(id: UUID, rt=Depends(runtime)):
        doc = rt.repo.document(str(id))
        if not doc:
            raise HTTPException(404, "文档不存在")
        content = rt.storage.get(doc["object_key"])
        current = rt.repo.document(str(id))
        if not current or current["version"] != doc["version"]:
            raise Conflict("文档已更新或删除，请重新下载")
        return Response(
            content,
            media_type="application/octet-stream",
            headers={
                "Content-Disposition": "attachment; filename*=UTF-8''"
                + quote(doc["filename"], safe="")
            },
        )

    @router.post("/chat")
    def chat(body: ChatRequest, rt=Depends(runtime)):
        return rt.chat.chat(body.model_dump(mode="json"))

    @router.post("/chat/confirm")
    def confirm(body: ConfirmRequest, rt=Depends(runtime)):
        return rt.chat.confirm(body.model_dump(mode="json"))

    @router.post("/chat/state")
    def chat_state(body: ChatStateRequest, rt=Depends(runtime)):
        return rt.chat.pending_state(body.model_dump(mode="json"))

    @router.delete("/conversations/{conversationId}")
    def delete_conversation(conversationId: UUID, userId: UUID, rt=Depends(runtime)):
        rt.chat.delete(str(userId), str(conversationId))
        return {"deleted": True}

    app.include_router(router)
    return app


app = create_app()
