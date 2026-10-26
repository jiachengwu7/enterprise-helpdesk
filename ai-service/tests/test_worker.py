from app.worker import Worker
from contextlib import nullcontext


class Queue:
    def __init__(self):
        self.pending = {"1-0"}

    def xack(self, stream, group, id):
        self.pending.discard(id)

    def xdel(self, stream, id):
        pass


class Repo:
    def try_lock(self, key):
        return nullcontext(True)

    def __init__(self):
        self.attempts = 0
        self.status = "QUEUED"
        self.result = None
        self.done = False

    def start_job(self, id, version):
        if self.done:
            return None
        self.attempts += 1
        return {"filename": "test.txt", "object_key": "object"}

    def fail_job(self, id, version):
        self.done = self.attempts >= 3
        self.status = "FAILED" if self.done else "QUEUED"
        return self.done

    def publish(self, id, version, chunks, vectors, error):
        self.result = {"chunks": chunks, "vectors": vectors, "error": error}
        self.status = "READY"
        self.done = True


class Storage:
    def __init__(self, body):
        self.body = body

    def get(self, key):
        return self.body


def test_failed_parse_retains_pending_until_three_attempts_then_fails_safely():
    repo, queue = Repo(), Queue()
    worker = Worker(repo, queue, Storage(b"\xff\x00"), None)
    for attempt in range(2):
        worker.process("1-0", {"document_id": "doc", "version": "1"})
        assert repo.status == "QUEUED" and queue.pending
    worker.process("1-0", {"document_id": "doc", "version": "1"})
    assert repo.status == "FAILED"
    assert not queue.pending


def test_embedding_failure_keeps_real_keyword_evidence_and_visible_warning():
    class BrokenEmbedding:
        def embed_documents(self, text):
            raise RuntimeError("provider secret")

    repo, queue = Repo(), Queue()
    worker = Worker(repo, queue, Storage("VPN需要多因素认证".encode()), BrokenEmbedding())
    worker.process("1-0", {"document_id": "doc", "version": "1"})
    assert repo.status == "READY"
    assert repo.result["chunks"] == ["VPN需要多因素认证"]
    assert repo.result["vectors"] is None
    assert "关键词" in repo.result["error"] and "secret" not in repo.result["error"]
    assert not queue.pending


def test_reclaimed_job_does_not_spend_attempt_while_original_consumer_is_alive():
    class BusyRepo(Repo):
        def try_lock(self, key):
            return nullcontext(False)

    repo, queue = BusyRepo(), Queue()
    worker = Worker(repo, queue, Storage(b"original"), None)
    worker.process("1-0", {"document_id": "doc", "version": "1"})
    assert repo.attempts == 0 and repo.result is None
    assert queue.pending
