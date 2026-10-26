"""Python-owned durable index, outbox and replay records. No hd_ writes."""

from contextlib import contextmanager
from hashlib import sha256
import json
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool
from .ingestion import can_publish

SCHEMA = """
CREATE EXTENSION IF NOT EXISTS vector;
CREATE SCHEMA IF NOT EXISTS ai_graph;
CREATE TABLE IF NOT EXISTS ai_documents (
 id text PRIMARY KEY, title text NOT NULL, filename text NOT NULL,
 department_id text NOT NULL, visibility text NOT NULL, version integer NOT NULL,
 object_key text, content_hash text, deleted boolean NOT NULL DEFAULT false,
 status text NOT NULL DEFAULT 'QUEUED', chunk_count integer NOT NULL DEFAULT 0,
 error text, index_mode text NOT NULL DEFAULT 'keyword', updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS ai_chunks (
 document_id text NOT NULL REFERENCES ai_documents(id), version integer NOT NULL,
 chunk_index integer NOT NULL, content text NOT NULL, embedding vector(1024),
 PRIMARY KEY(document_id,version,chunk_index)
);
CREATE INDEX IF NOT EXISTS ai_chunks_doc_idx ON ai_chunks(document_id);
CREATE TABLE IF NOT EXISTS ai_jobs (
 document_id text NOT NULL, version integer NOT NULL, attempts integer NOT NULL DEFAULT 0,
 done boolean NOT NULL DEFAULT false, dispatched_at timestamptz,
 PRIMARY KEY(document_id,version)
);
CREATE TABLE IF NOT EXISTS ai_gc (object_key text PRIMARY KEY);
CREATE TABLE IF NOT EXISTS ai_confirmations (
 thread_id text NOT NULL, proposal_id text NOT NULL, decision jsonb NOT NULL, answer text NOT NULL,
 PRIMARY KEY(thread_id,proposal_id)
);
"""


class Conflict(Exception):
    pass


class Repository:
    def __init__(self, dsn):
        self.pool = ConnectionPool(
            dsn,
            min_size=1,
            max_size=12,
            timeout=10,
            kwargs={"autocommit": True, "row_factory": dict_row},
            open=True,
        )
        self.pool.wait(15)
        with self.pool.connection() as c:
            c.execute(SCHEMA)

    @contextmanager
    def lock(self, key):
        number = int.from_bytes(sha256(key.encode()).digest()[:8], "big", signed=True)
        with self.pool.connection() as c:
            # Transaction-scoped lock is released even after exceptions or connection loss.
            with c.transaction():
                c.execute("SET LOCAL lock_timeout = '10s'")
                c.execute("SELECT pg_advisory_xact_lock(%s)", (number,))
                yield

    @contextmanager
    def try_lock(self, key):
        number = int.from_bytes(sha256(key.encode()).digest()[:8], "big", signed=True)
        with self.pool.connection() as c, c.transaction():
            acquired = c.execute(
                "SELECT pg_try_advisory_xact_lock(%s) AS locked", (number,)
            ).fetchone()["locked"]
            yield acquired

    def accept_document(self, req, object_key, digest):
        with self.lock("document:" + req["id"]):
            with self.pool.connection() as c, c.transaction():
                old = c.execute(
                    "SELECT * FROM ai_documents WHERE id=%s FOR UPDATE", (req["id"],)
                ).fetchone()
                if old and (old["deleted"] or req["version"] < old["version"]):
                    raise Conflict("文档已删除或版本已过期")
                if old and req["version"] == old["version"]:
                    if old["content_hash"] != digest:
                        raise Conflict("相同版本内容不一致")
                    c.execute("INSERT INTO ai_gc VALUES(%s) ON CONFLICT DO NOTHING", (object_key,))
                    return
                if old and old["object_key"]:
                    c.execute(
                        "INSERT INTO ai_gc VALUES(%s) ON CONFLICT DO NOTHING", (old["object_key"],)
                    )
                c.execute(
                    """INSERT INTO ai_documents(id,title,filename,department_id,visibility,version,object_key,content_hash)
                    VALUES(%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT(id) DO UPDATE SET
                    title=EXCLUDED.title,filename=EXCLUDED.filename,department_id=EXCLUDED.department_id,
                    visibility=EXCLUDED.visibility,version=EXCLUDED.version,object_key=EXCLUDED.object_key,
                    content_hash=EXCLUDED.content_hash,status='QUEUED',chunk_count=0,error=NULL,index_mode='keyword',updated_at=now()""",
                    (
                        req["id"],
                        req["title"],
                        req["filename"],
                        req["departmentId"],
                        req["visibility"],
                        req["version"],
                        object_key,
                        digest,
                    ),
                )
                c.execute("DELETE FROM ai_chunks WHERE document_id=%s", (req["id"],))
                c.execute("UPDATE ai_jobs SET done=true WHERE document_id=%s", (req["id"],))
                c.execute(
                    "INSERT INTO ai_jobs(document_id,version) VALUES(%s,%s) ON CONFLICT DO NOTHING",
                    (req["id"], req["version"]),
                )

    def gc_add(self, key):
        with self.pool.connection() as c:
            c.execute("INSERT INTO ai_gc VALUES(%s) ON CONFLICT DO NOTHING", (key,))

    def document(self, id):
        with self.pool.connection() as c:
            return c.execute(
                "SELECT * FROM ai_documents WHERE id=%s AND NOT deleted", (id,)
            ).fetchone()

    def status(self, ids):
        with self.pool.connection() as c:
            rows = c.execute(
                "SELECT * FROM ai_documents WHERE id=ANY(%s) AND NOT deleted", (ids,)
            ).fetchall()
        found = {r["id"]: r for r in rows}
        return [
            (
                {
                    "id": id,
                    "status": found[id]["status"],
                    "chunkCount": found[id]["chunk_count"],
                    "error": found[id]["error"],
                    "indexMode": found[id]["index_mode"],
                }
                if id in found
                else {
                    "id": id,
                    "status": "FAILED",
                    "chunkCount": 0,
                    "error": "文档不存在",
                    "indexMode": "keyword",
                }
            )
            for id in ids
        ]

    def delete_document(self, id):
        with self.lock("document:" + id):
            with self.pool.connection() as c, c.transaction():
                old = c.execute(
                    "SELECT * FROM ai_documents WHERE id=%s FOR UPDATE", (id,)
                ).fetchone()
                if old and old["object_key"]:
                    c.execute(
                        "INSERT INTO ai_gc VALUES(%s) ON CONFLICT DO NOTHING", (old["object_key"],)
                    )
                c.execute(
                    """INSERT INTO ai_documents(id,title,filename,department_id,visibility,version,deleted,status)
                    VALUES(%s,'','','IT','DEPARTMENT',0,true,'FAILED') ON CONFLICT(id) DO UPDATE
                    SET deleted=true,status='FAILED',object_key=NULL,chunk_count=0,error='文档已删除',updated_at=now()""",
                    (id,),
                )
                c.execute("DELETE FROM ai_chunks WHERE document_id=%s", (id,))
                c.execute("UPDATE ai_jobs SET done=true WHERE document_id=%s", (id,))

    def pending_dispatch(self):
        with self.pool.connection() as c:
            return c.execute(
                "SELECT document_id,version FROM ai_jobs WHERE NOT done AND (dispatched_at IS NULL OR dispatched_at < now()-interval '120 seconds') LIMIT 50"
            ).fetchall()

    def dispatched(self, id, version):
        with self.pool.connection() as c:
            c.execute(
                "UPDATE ai_jobs SET dispatched_at=now() WHERE document_id=%s AND version=%s",
                (id, version),
            )

    def start_job(self, id, version):
        with self.pool.connection() as c, c.transaction():
            doc = c.execute("SELECT * FROM ai_documents WHERE id=%s FOR UPDATE", (id,)).fetchone()
            job = c.execute(
                "SELECT * FROM ai_jobs WHERE document_id=%s AND version=%s FOR UPDATE",
                (id, version),
            ).fetchone()
            if not job or job["done"]:
                return None
            if not can_publish(doc, version):
                c.execute(
                    "UPDATE ai_jobs SET done=true WHERE document_id=%s AND version=%s",
                    (id, version),
                )
                return None
            if job["attempts"] >= 3:
                c.execute(
                    "UPDATE ai_documents SET status='FAILED',error='索引处理失败，请重新上传文档' WHERE id=%s",
                    (id,),
                )
                c.execute(
                    "UPDATE ai_jobs SET done=true WHERE document_id=%s AND version=%s",
                    (id, version),
                )
                return None
            c.execute(
                "UPDATE ai_jobs SET attempts=attempts+1 WHERE document_id=%s AND version=%s",
                (id, version),
            )
            c.execute("UPDATE ai_documents SET status='PROCESSING',error=NULL WHERE id=%s", (id,))
            return doc

    def publish(self, id, version, chunks, vectors, error):
        with self.pool.connection() as c, c.transaction():
            doc = c.execute("SELECT * FROM ai_documents WHERE id=%s FOR UPDATE", (id,)).fetchone()
            if can_publish(doc, version):
                c.execute("DELETE FROM ai_chunks WHERE document_id=%s", (id,))
                for i, content in enumerate(chunks):
                    vector = json.dumps(vectors[i]) if vectors else None
                    c.execute(
                        "INSERT INTO ai_chunks(document_id,version,chunk_index,content,embedding) VALUES(%s,%s,%s,%s,%s::vector)",
                        (id, version, i, content, vector),
                    )
                c.execute(
                    "UPDATE ai_documents SET status='READY',chunk_count=%s,index_mode=%s,error=%s,updated_at=now() WHERE id=%s",
                    (len(chunks), "hybrid" if vectors else "keyword", error, id),
                )
            c.execute(
                "UPDATE ai_jobs SET done=true WHERE document_id=%s AND version=%s", (id, version)
            )

    def fail_job(self, id, version):
        with self.pool.connection() as c, c.transaction():
            job = c.execute(
                "SELECT attempts FROM ai_jobs WHERE document_id=%s AND version=%s", (id, version)
            ).fetchone()
            exhausted = bool(job and job["attempts"] >= 3)
            c.execute(
                "UPDATE ai_documents SET status=%s,error=%s WHERE id=%s AND version=%s AND NOT deleted",
                (
                    "FAILED" if exhausted else "QUEUED",
                    "索引处理失败，请重新上传文档" if exhausted else "索引处理中，等待自动重试",
                    id,
                    version,
                ),
            )
            if exhausted:
                c.execute(
                    "UPDATE ai_jobs SET done=true WHERE document_id=%s AND version=%s",
                    (id, version),
                )
            return exhausted

    def gc_items(self):
        with self.pool.connection() as c:
            return [
                r["object_key"]
                for r in c.execute("SELECT object_key FROM ai_gc LIMIT 100").fetchall()
            ]

    def gc_done(self, key):
        with self.pool.connection() as c:
            c.execute("DELETE FROM ai_gc WHERE object_key=%s", (key,))

    def search(self, ids, terms, vector, department_id=None, role=None):
        with self.pool.connection() as c:
            if department_id and role != "ADMIN":
                ids = [
                    r["id"]
                    for r in c.execute(
                        "SELECT id FROM ai_documents WHERE id=ANY(%s) AND (visibility='PUBLIC' OR department_id=%s) AND NOT deleted",
                        (ids, department_id),
                    ).fetchall()
                ]
            versions = {
                r["id"]: r["version"]
                for r in c.execute(
                    "SELECT id,version FROM ai_documents WHERE id=ANY(%s) AND NOT deleted AND status='READY'",
                    (ids,),
                ).fetchall()
            }
            if not versions:
                return [], {}
            base = " FROM ai_chunks c JOIN ai_documents d ON d.id=c.document_id AND d.version=c.version WHERE d.id=ANY(%s) AND NOT d.deleted AND d.status='READY'"
            fields = "c.document_id,c.version,c.chunk_index,c.content,d.title,d.filename"
            keyword = (
                c.execute(
                    "SELECT "
                    + fields
                    + ", (SELECT count(*) FROM unnest(%s::text[]) t WHERE position(t in lower(c.content))>0) AS rank"
                    + base
                    + " AND EXISTS(SELECT 1 FROM unnest(%s::text[]) t WHERE position(t in lower(c.content))>0) ORDER BY rank DESC,c.document_id,c.chunk_index LIMIT 24",
                    (terms, ids, terms),
                ).fetchall()
                if terms
                else []
            )
            semantic = (
                c.execute(
                    "SELECT "
                    + fields
                    + base
                    + " AND c.embedding IS NOT NULL ORDER BY c.embedding <=> %s::vector LIMIT 24",
                    (ids, json.dumps(vector)),
                ).fetchall()
                if vector
                else []
            )
        fused = {}
        for rows in (keyword, semantic):
            for i, r in enumerate(rows):
                key = (r["document_id"], r["version"], r["chunk_index"])
                if key not in fused:
                    fused[key] = {
                        "documentId": r["document_id"],
                        "title": r["title"],
                        "filename": r["filename"],
                        "version": r["version"],
                        "chunkIndex": r["chunk_index"],
                        "content": r["content"],
                        "score": 0.0,
                    }
                fused[key]["score"] += 1 / (60 + i + 1)
        return sorted(fused.values(), key=lambda x: x["score"], reverse=True), versions

    def sources_current(self, sources):
        if not sources:
            return True
        with self.pool.connection() as c:
            rows = c.execute(
                "SELECT id,version FROM ai_documents WHERE id=ANY(%s) AND NOT deleted AND status='READY'",
                ([s["documentId"] for s in sources],),
            ).fetchall()
        current = {r["id"]: r["version"] for r in rows}
        return all(current.get(s["documentId"]) == s["version"] for s in sources)

    def confirmation(self, key, proposal):
        with self.pool.connection() as c:
            return c.execute(
                "SELECT decision,answer FROM ai_confirmations WHERE thread_id=%s AND proposal_id=%s",
                (key, proposal),
            ).fetchone()

    def save_confirmation(self, key, proposal, decision, answer):
        with self.pool.connection() as c:
            c.execute(
                "INSERT INTO ai_confirmations VALUES(%s,%s,%s,%s) ON CONFLICT DO NOTHING",
                (key, proposal, Jsonb(decision), answer),
            )

    def delete_confirmations(self, key):
        with self.pool.connection() as c:
            c.execute("DELETE FROM ai_confirmations WHERE thread_id=%s", (key,))
