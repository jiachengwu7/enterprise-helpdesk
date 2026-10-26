# Python AI service

This service is independent of the older checkout. It imports no code or environments from that project. Java owns users, authorization, chat history and ticket transactions. Python owns original-file storage, indexing, retrieval, graph checkpoints and confirmation replay receipts.

## Running and testing

Use the root launcher for normal operation. It supplies the PostgreSQL, Redis, S3 and service-token environment. The service entry point is:

```powershell
cd D:\agent_work\enterprise-helpdesk\ai-service
& .\.venv\Scripts\python.exe -m pip install -r requirements.txt
& .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8101
```

The worker runs in the same process. Start infrastructure before starting the service. No cloud credentials are required for keyword indexing and explicitly labelled retrieval-only answers.

```powershell
& .\.venv\Scripts\python.exe -m pytest -q
# With the launcher infrastructure environment already loaded:
$env:AI_INTEGRATION_TESTS = '1'
& .\.venv\Scripts\python.exe -m pytest -q
```

Windows sandbox note: FastAPI TestClient needs asyncio's local socketpair. The restricted tool sandbox can block the socketpair and hang in `socket.accept`; run the tests in a normal terminal or approved unsandboxed process. This is not a cloud network requirement. Unit tests replace external boundaries; integration tests explicitly disable both model keys even if local model configuration exists. Integration documents use fresh UUIDs and are tombstoned afterwards; S3 test objects and graph checkpoints are removed.

`requirements.txt` includes the frozen `requirements.lock.txt`. Verified primary versions: Python 3.10.10, LangChain 1.4.7, langchain-core 1.7.0, langchain-openai 1.7.2, LangGraph 1.2.17 and langgraph-checkpoint-postgres 3.2.0. No older project's virtual environment is used.

## Configuration

`app/config.py` loads only `ai-service/.env`, then environment values override it. Copy `.env.example` for model settings. The root launcher supplies:

| Environment variable | Purpose |
| --- | --- |
| AI_SERVICE_TOKEN | At least 16 random characters; every `/internal` route requires constant-time verification |
| DATABASE_URL | PostgreSQL URI; default infrastructure port 55432 |
| REDIS_URL | Redis URI; default port 56379 |
| S3_ENDPOINT, S3_ACCESS_KEY, S3_SECRET_KEY, S3_BUCKET | RustFS S3 endpoint, credentials and bucket |
| AGENT_LLM_API_KEY, AGENT_LLM_MODEL, AGENT_LLM_BASE_URL | OpenAI-compatible chat configuration |
| AGENT_EMBEDDING_MODEL, AGENT_EMBEDDING_DIMENSIONS | Embedding model; the schema requires exactly 1024 dimensions |
| AGENT_EMBEDDING_API_KEY, AGENT_EMBEDDING_BASE_URL | Optional embedding overrides; otherwise inherit chat key and base URL |

Only `models.py` constructs production model clients. Chat uses LangChain `init_chat_model`; embeddings use `OpenAIEmbeddings`. There are no fake production models. Restart after changing configuration; re-upload documents to populate vectors when enabling embeddings on previously keyword-only documents.

## Durable ingestion and retrieval

1. Validate the UUIDs, filename, supported extension, base64 and 10 MB maximum. Upload the original bytes to a unique S3 object.
2. Transactionally register the active document version and an `ai_jobs` outbox row; remove obsolete chunks immediately. A deleted document ID is permanently tombstoned and cannot be reintroduced by delayed uploads.
3. The worker dispatches outbox rows to Redis Stream `ai:ingest`, group `ai-indexers`. A lost stream delivery is re-dispatched after 120 seconds. Pending messages are reclaimed with `XAUTOCLAIM` after 30 seconds.
4. A PostgreSQL advisory lock prevents simultaneous consumers from spending attempts on one version. Attempts are durable and capped at three; failures remain pending until reclaimed. Completion is recorded before Redis acknowledgment. If the process dies after database completion, replay only acknowledges the completed task.
5. Parse UTF-8 TXT/MD, text-based PDF and DOCX (paragraphs plus tables). Split with LangChain `RecursiveCharacterTextSplitter`, 900 characters with 140 overlap. Encrypted/overlong PDFs, oversized expanded DOCX and empty/unextractable text fail safely.
6. Compute configured vectors or retain exact keyword chunks. Embedding failure leaves the document READY in keyword mode with a visible safe warning. An embedding length other than 1024 is rejected.
7. Under a document row lock, check both version and deletion state, then replace all chunks atomically. An old or deleted job can never publish chunks. Old/deleted originals enter a durable S3 garbage queue.

Retrieval intersects Java's current whitelist with active READY index rows and checks indexed department/visibility for non-admin callers. Keyword scoring supports Latin terms and Chinese bigrams; vector cosine candidates and keyword candidates are combined with reciprocal rank fusion. Sources contain exact stored chunk text, version, document identity and chunk index. Final graph output rechecks active versions, and Java performs its own authoritative post-response access/version check.

All own tables have the `ai_` prefix. Upstream PostgresSaver tables keep their official names inside the dedicated `ai_graph` schema via a separate connection pool search path; this intentional exception avoids rewriting upstream SQL. No `hd_` business table is written.

## Graph and confirmation

The persisted graph is `retrieve -> answer -> draft -> approval -> finish`, with the last three nodes only entered for an explicit ticket request. Every new turn replaces the prior question, scope and evidence. Historical assistant text is never fed to retrieval or model authorization. The checkpoint key is `userId:conversationId`.

The LangChain `@tool` creates a structured draft from the user's request. It has no business-write capability. The approval node calls real LangGraph `interrupt()`. Java validates ownership and creates/rejects a ticket in its own transaction, then supplies the outcome to Python. Python resumes with `Command(resume=...)` and stores an immutable confirmation receipt. Replays across process reconstruction and later conversation turns return the same answer; conflicting decisions are rejected. PostgreSQL advisory locks serialize requests, confirmation and deletion for one user-qualified conversation.

If the original chat HTTP response is lost after the interrupt is committed, Java recovers it through authenticated `POST /internal/chat/state` with `{conversationId,userId}`. Under the same conversation advisory lock, Python reads the persisted checkpoint and returns `{question,pending}`; `pending` is the complete original Answer only while a proposal and pending graph node exist. Otherwise both fields are null. The read does not run the graph or a model and does not create tickets. Java validates ownership and current citations before reconstructing its pending UI/history, masking obsolete evidence as necessary.

Missing chat configuration produces exact retrieved excerpts and the explicit label `仅检索，未调用模型`. Configured chat with no evidence also uses `retrieval-only` and does not call the provider. Provider failures or malformed/out-of-range citations produce a safe 502, not fabricated success. Model answers must reference only numbered current sources; citation validation verifies numbering and source provenance, not semantic truth of every generated claim.

## Verification and limitations

Verified on 2026-10-26: default suite **19 passed, 3 skipped**; with `AI_INTEGRATION_TESTS=1`, **22 passed** against the independent local infrastructure. This includes lost-response pending-state recovery from PostgreSQL checkpoints. Python compile checks also passed. Formatting used Black with a 100-character line length.

Tests cover HTTP authentication and safe validation errors; empty/unlisted ACLs; indexed department isolation; current versions and delete/late-worker behavior; parsing; real vector(1024) storage/query; retrieval-only mode; provider failure; fresh-turn source clearing; real graph interrupt/resume, conflicting confirmation, and replay after service reconstruction; retry exhaustion and embedding degradation; S3 originals and Redis availability. No paid model request is made in tests. Root integration owns full browser/Java workflows and process-restart acceptance.

Scanned PDFs require OCR outside this release; images are not interpreted. Old S3 objects are cleaned asynchronously. A process crash between initial S3 upload and database registration can leave an unreferenced object; an operational bucket inventory/lifecycle cleanup is appropriate for longer-lived deployments. Health reports dependency readiness and configuration, while per-document status reports actual keyword/hybrid index mode. Large collections may benefit from indexed full-text search or ANN indexes; this implementation deliberately keeps exact SQL vector search and bounded candidates for the local demonstration dataset.

Upstream references: [LangGraph interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts), [LangGraph persistence](https://docs.langchain.com/oss/python/langgraph/persistence).
