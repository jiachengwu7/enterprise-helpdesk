import logging
import math
import threading
from uuid import uuid4
from redis.exceptions import ResponseError
from .ingestion import parse_document, split_document

log = logging.getLogger(__name__)
STREAM = "ai:ingest"
GROUP = "ai-indexers"


class Worker:
    # Redis Streams 承担异步任务投递，PostgreSQL 记录任务和索引状态，S3 保存原件。
    # HTTP 上传只需完成接收，耗时的解析、切块和向量化由这个后台消费者处理。
    # 用途：准备异步索引消费者及停止信号。
    # 输入：repo、redis、storage、embeddings：仓储、队列、原件存储、可空向量客户端。
    # 输出：无返回值；创建但不启动后台线程。
    def __init__(self, repo, redis, storage, embeddings):
        self.repo, self.redis, self.storage, self.embeddings = repo, redis, storage, embeddings
        self.consumer = "worker-" + str(uuid4())
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True, name="ingestion-worker")

    # 用途：取得任务锁，避免重认领时重复执行同一版本。
    # 输入：message_id：Stream 消息 ID；payload：文档 ID 与版本。
    # 输出：无返回值；拿到锁后执行索引任务。
    def process(self, message_id, payload):
        id, version = payload["document_id"], int(payload["version"])
        # Reclaim may transfer a slow job while the original consumer is alive.
        # Only one consumer may spend an attempt or publish this version at a time.
        with self.repo.try_lock(f"job:{id}:{version}") as acquired:
            if acquired:
                self._process(message_id, id, version)

    # 用途：下载、解析、切块、向量化并发布索引。
    # 输入：message_id：消息 ID；id、version：文档及版本。
    # 输出：无返回值；更新索引和重试状态，完成时确认并删除消息。
    def _process(self, message_id, id, version):
        doc = self.repo.start_job(id, version)
        if not doc:
            self.redis.xack(STREAM, GROUP, message_id)
            self.redis.xdel(STREAM, message_id)
            return
        try:
            chunks = split_document(
                parse_document(doc["filename"], self.storage.get(doc["object_key"]))
            )
            vectors = None
            error = None
            if self.embeddings:
                try:
                    vectors = self.embeddings.embed_documents(chunks)
                    if len(vectors) != len(chunks) or any(
                        len(v) != 1024 or not all(math.isfinite(x) for x in v) for v in vectors
                    ):
                        raise ValueError("invalid embeddings")
                except Exception:
                    vectors = None
                    error = "向量服务暂不可用，当前仅关键词索引"
            self.repo.publish(id, version, chunks, vectors, error)
        except Exception:
            log.warning("Document index attempt failed; details suppressed")
            if not self.repo.fail_job(id, version):
                return
        # 先持久化成功或终止失败结果，再确认消息；中途崩溃的消息可以被重新认领。
        self.redis.xack(STREAM, GROUP, message_id)
        self.redis.xdel(STREAM, message_id)

    # 用途：持续投递、认领和消费任务，同时回收废弃原件。
    # 输入：无显式参数，使用实例依赖与停止信号。
    # 输出：循环到停止；持续更新 Redis、数据库与对象存储。
    def run(self):
        while not self.stop.is_set():
            try:
                try:
                    self.redis.xgroup_create(STREAM, GROUP, id="0", mkstream=True)
                except ResponseError as exc:
                    if "BUSYGROUP" not in str(exc):
                        raise
                for job in self.repo.pending_dispatch():
                    # 数据库 outbox（待投递任务表）可重试投递；XADD 后崩溃可能重复发送，
                    # 因此消费端还要靠任务状态、版本校验和数据库锁处理重复消息。
                    self.redis.xadd(STREAM, job)
                    self.repo.dispatched(job["document_id"], job["version"])
                # 认领超时未确认的消息，接手崩溃消费者遗留的任务；任务锁避免慢任务并发执行。
                claimed = self.redis.xautoclaim(
                    STREAM, GROUP, self.consumer, min_idle_time=30000, start_id="0-0", count=5
                )
                messages = claimed[1]
                if not messages:
                    entries = self.redis.xreadgroup(
                        GROUP, self.consumer, {STREAM: ">"}, count=5, block=1000
                    )
                    messages = [msg for _, batch in entries for msg in batch]
                for mid, payload in messages:
                    if self.stop.is_set():
                        break
                    self.process(mid, payload)
                for key in self.repo.gc_items():
                    self.storage.delete(key)
                    self.repo.gc_done(key)
            except Exception:
                log.warning(
                    "Ingestion dependency unavailable; retrying without exposing credentials"
                )
                self.stop.wait(2)

    # 用途：通知后台线程退出并有限等待。
    # 输入：无显式参数。
    # 输出：无返回值；设置停止信号，最多等待 5 秒。
    def close(self):
        self.stop.set()
        self.thread.join(timeout=5)
