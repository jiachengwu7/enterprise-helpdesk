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
    # 集中装配运行依赖，供 FastAPI 接口复用；连接池避免每个请求重新建立数据库连接。
    # 用途：装配数据库、Redis、S3、模型及检查点，并启动索引线程。
    # 输入：settings：运行配置。
    # 输出：无返回值；初始化连接、表结构及后台任务。
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
        # LangGraph 的持久化检查点位于 ai_graph schema，和业务表、检索表分开管理。
        self.saver = PostgresSaver(self.checkpoint_pool)
        self.saver.setup()
        embeddings = create_embeddings(settings)
        self.chat = ChatService(
            self.repo, Retriever(self.repo, embeddings), create_chat(settings), self.saver
        )
        self.worker = Worker(self.repo, self.redis, self.storage, embeddings)
        self.worker.thread.start()

    # 用途：按顺序关闭后台任务和连接资源。
    # 输入：无显式参数，使用实例资源。
    # 输出：无返回值；停止消费者并关闭连接池。
    def close(self):
        self.worker.close()
        self.checkpoint_pool.close()
        self.repo.pool.close()
        self.redis.close()


# 用途：创建 FastAPI 应用并注册内部接口、认证及异常处理。
# 输入：settings：可选配置；initialize：是否初始化真实依赖。
# 输出：FastAPI 应用实例；测试可关闭真实依赖初始化。
def create_app(settings=None, initialize=True):
    settings = settings or Settings()

    # 用途：管理应用启动和退出时的资源生命周期。
    # 输入：app：FastAPI 应用。
    # 输出：异步上下文管理器；启动装配依赖，退出释放资源。
    @asynccontextmanager
    async def lifespan(app):
        # FastAPI 生命周期：启动时创建连接池和消费者，退出时释放资源。
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

    # 用途：将参数校验异常转换为安全响应。
    # 输入：request：请求；exc：校验异常。
    # 输出：HTTP 422 JSONResponse，不回显请求敏感字段。
    @app.exception_handler(RequestValidationError)
    async def invalid(request, exc):
        return JSONResponse(
            status_code=422, content={"detail": "请求参数无效，请检查字段、文件格式和内容大小"}
        )

    # 用途：向调用方报告业务状态冲突。
    # 输入：request：请求；exc：Conflict 异常。
    # 输出：HTTP 409 JSONResponse。
    @app.exception_handler(Conflict)
    async def conflict(request, exc):
        return JSONResponse(status_code=409, content={"detail": str(exc)})

    # 用途：报告模型调用或引用校验失败。
    # 输入：request：请求；exc：ModelFailure。
    # 输出：HTTP 502 JSONResponse。
    @app.exception_handler(ModelFailure)
    async def model_error(request, exc):
        return JSONResponse(status_code=502, content={"detail": str(exc)})

    # 用途：报告数据库锁竞争导致的会话忙碌。
    # 输入：request：请求；exc：锁不可用异常。
    # 输出：HTTP 409 JSONResponse。
    @app.exception_handler(LockNotAvailable)
    async def busy(request, exc):
        return JSONResponse(status_code=409, content={"detail": "当前会话正在处理中，请稍后重试"})

    # 用途：隐藏未知异常细节并报告依赖不可用。
    # 输入：request：请求；exc：未知异常。
    # 输出：HTTP 503 JSONResponse；日志仅记录异常类型。
    @app.exception_handler(Exception)
    async def failure(request, exc):
        log.error("Internal operation failed; exception type=%s", type(exc).__name__)
        return JSONResponse(status_code=503, content={"detail": "内部依赖暂不可用，请稍后重试"})

    # 用途：验证内部调用令牌，拒绝未授权访问。
    # 输入：x_service_token：请求头中的服务令牌。
    # 输出：成功无返回值；失败抛 HTTPException(401)。
    def auth(x_service_token: str | None = Header(default=None)):
        # 内部接口验证 Java 服务令牌；浏览器不直接访问 Python，也不持有此密钥。
        token = settings.ai_service_token
        if (
            not token
            or not x_service_token
            or not hmac.compare_digest(token.encode(), x_service_token.encode())
        ):
            raise HTTPException(401, "内部服务认证失败")

    # 用途：取得已初始化的运行依赖。
    # 输入：无参数，从应用状态读取。
    # 输出：Runtime 实例；未就绪时抛 HTTPException(503)。
    def runtime():
        if not hasattr(app.state, "runtime"):
            raise HTTPException(503, "内部服务尚未就绪")
        return app.state.runtime

    # 用途：探测本地依赖并报告模型配置是否存在。
    # 输入：无参数，读取运行配置与依赖。
    # 输出：健康状态字典；不调用云端模型。
    @app.get("/health")
    def health():
        # 配置存在不等于云端调用成功；健康检查只探测本地依赖，不发付费模型请求。
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

    # 用途：接收原件并登记异步索引任务。
    # 输入：body：校验后的文档请求；rt：运行依赖。
    # 输出：QUEUED 状态字典；写 S3 和数据库，失败时尝试清理原件。
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

    # 用途：批量查询文档索引状态。
    # 输入：body：文档 ID 列表请求；rt：运行依赖。
    # 输出：含 documents 列表的字典。
    @router.post("/documents/status")
    def status(body: IdsRequest, rt=Depends(runtime)):
        return {"documents": rt.repo.status([str(x) for x in body.ids])}

    # 用途：删除索引并登记原件清理。
    # 输入：id：文档 UUID；rt：运行依赖。
    # 输出：deleted=True 字典；写入删除标记。
    @router.delete("/documents/{id}")
    def delete_document(id: UUID, rt=Depends(runtime)):
        rt.repo.delete_document(str(id))
        return {"deleted": True}

    # 用途：读取有效原件并在读取后复核版本。
    # 输入：id：文档 UUID；rt：运行依赖。
    # 输出：带文件名响应头的字节响应；过期或缺失时抛异常。
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

    # 用途：将校验后的问答请求交给 LangGraph 服务。
    # 输入：body：问答请求；rt：运行依赖。
    # 输出：回答、来源、草稿、模式与轨迹字典。
    @router.post("/chat")
    def chat(body: ChatRequest, rt=Depends(runtime)):
        return rt.chat.chat(body.model_dump(mode="json"))

    # 用途：将 Java 人工确认结果传回流程。
    # 输入：body：确认请求；rt：运行依赖。
    # 输出：最终回答字典；恢复图并保存回执。
    @router.post("/chat/confirm")
    def confirm(body: ConfirmRequest, rt=Depends(runtime)):
        return rt.chat.confirm(body.model_dump(mode="json"))

    # 用途：读取持久化的待确认答案以恢复丢失响应。
    # 输入：body：用户与会话标识；rt：运行依赖。
    # 输出：问题与待确认答案字典；不执行模型调用。
    @router.post("/chat/state")
    def chat_state(body: ChatStateRequest, rt=Depends(runtime)):
        return rt.chat.pending_state(body.model_dump(mode="json"))

    # 用途：清理用户会话的检查点和确认记录。
    # 输入：conversationId、userId：UUID；rt：运行依赖。
    # 输出：deleted=True 字典；删除对应持久化状态。
    @router.delete("/conversations/{conversationId}")
    def delete_conversation(conversationId: UUID, userId: UUID, rt=Depends(runtime)):
        rt.chat.delete(str(userId), str(conversationId))
        return {"deleted": True}

    app.include_router(router)
    return app


app = create_app()
