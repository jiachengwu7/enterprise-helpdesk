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
    def __init__(self, repo, redis, storage, embeddings):
        self.repo, self.redis, self.storage, self.embeddings = repo, redis, storage, embeddings
        self.consumer = "worker-" + str(uuid4())
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True, name="ingestion-worker")

    def process(self, message_id, payload):
        id, version = payload["document_id"], int(payload["version"])
        # Reclaim may transfer a slow job while the original consumer is alive.
        # Only one consumer may spend an attempt or publish this version at a time.
        with self.repo.try_lock(f"job:{id}:{version}") as acquired:
            if acquired:
                self._process(message_id, id, version)

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
        self.redis.xack(STREAM, GROUP, message_id)
        self.redis.xdel(STREAM, message_id)

    def run(self):
        while not self.stop.is_set():
            try:
                try:
                    self.redis.xgroup_create(STREAM, GROUP, id="0", mkstream=True)
                except ResponseError as exc:
                    if "BUSYGROUP" not in str(exc):
                        raise
                for job in self.repo.pending_dispatch():
                    self.redis.xadd(STREAM, job)
                    self.repo.dispatched(job["document_id"], job["version"])
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

    def close(self):
        self.stop.set()
        self.thread.join(timeout=5)
