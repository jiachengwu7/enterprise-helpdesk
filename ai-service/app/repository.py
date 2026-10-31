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
    # 用途：建立 PostgreSQL 连接池并初始化 AI 表结构。
    # 输入：dsn：数据库连接串。
    # 输出：无返回值；创建连接池并执行表结构初始化。
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

    # 用途：用数据库事务级建议锁串行化同一业务键。
    # 输入：key：会话或文档锁键。
    # 输出：上下文管理器；进入后持锁，退出时释放。
    @contextmanager
    def lock(self, key):
        number = int.from_bytes(sha256(key.encode()).digest()[:8], "big", signed=True)
        with self.pool.connection() as c:
            # Transaction-scoped lock is released even after exceptions or connection loss.
            with c.transaction():
                c.execute("SET LOCAL lock_timeout = '10s'")
                c.execute("SELECT pg_advisory_xact_lock(%s)", (number,))
                yield

    # 用途：尝试获取任务锁，不等待其他消费者。
    # 输入：key：任务业务键。
    # 输出：上下文管理器产出是否获得锁的布尔值。
    @contextmanager
    def try_lock(self, key):
        number = int.from_bytes(sha256(key.encode()).digest()[:8], "big", signed=True)
        with self.pool.connection() as c, c.transaction():
            acquired = c.execute(
                "SELECT pg_try_advisory_xact_lock(%s) AS locked", (number,)
            ).fetchone()["locked"]
            yield acquired

    # 用途：原子接收文档版本和待投递任务。
    # 输入：req：文档元数据；object_key：原件键；digest：内容摘要。
    # 输出：无返回值；更新文档、任务及旧原件回收记录。
    def accept_document(self, req, object_key, digest):
        # 将文档版本和待投递任务放在同一数据库事务中，避免元数据提交后任务丢失。
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

    # 用途：登记待删除的原件。
    # 输入：key：对象存储键。
    # 输出：无返回值；幂等写入回收表。
    def gc_add(self, key):
        with self.pool.connection() as c:
            c.execute("INSERT INTO ai_gc VALUES(%s) ON CONFLICT DO NOTHING", (key,))

    # 用途：查询尚未删除的文档。
    # 输入：id：文档 ID。
    # 输出：文档字典，找不到时为 None。
    def document(self, id):
        with self.pool.connection() as c:
            return c.execute(
                "SELECT * FROM ai_documents WHERE id=%s AND NOT deleted", (id,)
            ).fetchone()

    # 用途：批量读取文档索引进度。
    # 输入：ids：文档 ID 列表。
    # 输出：按输入顺序的状态列表，缺失文档标为 FAILED。
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

    # 用途：记录删除标记并清理索引，阻止旧任务复活文档。
    # 输入：id：文档 ID。
    # 输出：无返回值；删除片段、结束任务、登记原件回收。
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

    # 用途：找出首次或超时后需要重新投递的任务。
    # 输入：无显式参数，读取数据库任务表。
    # 输出：最多 50 条文档 ID 与版本记录。
    def pending_dispatch(self):
        with self.pool.connection() as c:
            return c.execute(
                "SELECT document_id,version FROM ai_jobs WHERE NOT done AND (dispatched_at IS NULL OR dispatched_at < now()-interval '120 seconds') LIMIT 50"
            ).fetchall()

    # 用途：记录本次队列投递时间。
    # 输入：id、version：文档任务标识。
    # 输出：无返回值；更新任务投递时间。
    def dispatched(self, id, version):
        with self.pool.connection() as c:
            c.execute(
                "UPDATE ai_jobs SET dispatched_at=now() WHERE document_id=%s AND version=%s",
                (id, version),
            )

    # 用途：校验任务有效性并消耗一次持久化重试次数。
    # 输入：id、version：文档任务标识。
    # 输出：可执行时返回文档记录，否则 None；更新任务与文档状态。
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

    # 用途：在版本仍有效时原子发布切片和向量。
    # 输入：id、version：目标版本；chunks：文本片段；vectors：可空向量；error：降级说明。
    # 输出：无返回值；写入索引并结束任务。
    def publish(self, id, version, chunks, vectors, error):
        # 锁住文档行后核对版本，再原子替换片段和状态；旧任务不能覆盖新版本的索引。
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

    # 用途：记录失败，决定是否已耗尽重试机会。
    # 输入：id、version：文档任务标识。
    # 输出：是否耗尽重试的布尔值；更新 FAILED 或 QUEUED 状态。
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

    # 用途：取出一批等待清理的原件键。
    # 输入：无显式参数。
    # 输出：最多 100 个对象键的列表。
    def gc_items(self):
        with self.pool.connection() as c:
            return [
                r["object_key"]
                for r in c.execute("SELECT object_key FROM ai_gc LIMIT 100").fetchall()
            ]

    # 用途：在原件删除成功后移除回收记录。
    # 输入：key：已完成删除的对象键。
    # 输出：无返回值；删除数据库回收项。
    def gc_done(self, key):
        with self.pool.connection() as c:
            c.execute("DELETE FROM ai_gc WHERE object_key=%s", (key,))

    # 用途：融合关键词与向量召回，并限定授权及有效版本。
    # 输入：ids：授权 ID；terms：关键词；vector：可空问题向量；department_id、role：身份。
    # 输出：排序候选列表与当前版本映射组成的二元组。
    def search(self, ids, terms, vector, department_id=None, role=None):
        # Java 传入权威授权 ID；这里再按部门、删除状态及当前 READY 版本收窄范围。
        # 权限过滤发生在召回阶段，防止未授权片段进入模型上下文。
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
            # pgvector 的 <=> 是余弦距离，越小越接近；这里采用精确排序，尚未建立 ANN 索引。
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
        # RRF 按名次而非原始分数融合两路结果：每路贡献 1/(60+排名)，排名从 1 开始。
        # 关键词计数与向量距离量纲不同，不能直接相加；同一片段两路命中时累加贡献。
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

    # 用途：复核回答生成期间引用版本是否仍有效。
    # 输入：sources：引用片段列表。
    # 输出：全部仍为当前 READY 版本时为 True；空列表为 True。
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

    # 用途：读取某次人工确认的幂等回执。
    # 输入：key：用户会话键；proposal：草稿 ID。
    # 输出：决定和回答记录，未保存时为 None。
    def confirmation(self, key, proposal):
        with self.pool.connection() as c:
            return c.execute(
                "SELECT decision,answer FROM ai_confirmations WHERE thread_id=%s AND proposal_id=%s",
                (key, proposal),
            ).fetchone()

    # 用途：持久化人工决定与最终回答供重试复用。
    # 输入：key、proposal：会话和草稿；decision：决定；answer：最终回答。
    # 输出：无返回值；幂等写入确认回执。
    def save_confirmation(self, key, proposal, decision, answer):
        with self.pool.connection() as c:
            c.execute(
                "INSERT INTO ai_confirmations VALUES(%s,%s,%s,%s) ON CONFLICT DO NOTHING",
                (key, proposal, Jsonb(decision), answer),
            )

    # 用途：清理会话的确认回执。
    # 输入：key：用户会话键。
    # 输出：无返回值；删除该会话全部回执。
    def delete_confirmations(self, key):
        with self.pool.connection() as c:
            c.execute("DELETE FROM ai_confirmations WHERE thread_id=%s", (key,))
