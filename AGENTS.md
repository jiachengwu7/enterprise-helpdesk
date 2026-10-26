# Enterprise Helpdesk

Independent user-authorized project. Never modify ../enterprise-knowledge-helpdesk.
Java owns identity, permission, document metadata, conversations and ticket transactions; Python owns indexing, retrieval, model calls and LangGraph checkpoints. React calls Java only. Do not trust user/department/role values from browsers or models.
Use typed DTOs, Controller -> Service -> Repository in Java, database migrations, explicit error statuses, no returned ORM entities. All browser JSON responses use {code:200,message:"success",data:...}; errors use the actual HTTP status and the same envelope. Python internal APIs use plain JSON.
No credentials in source, test fixtures, logs or responses. Test model boundaries with fakes; do not send paid requests during tests. Bind localhost. No fake AI answers in production; missing model may return explicitly labelled retrieval-only evidence, and the UI must show this mode.
Each agent edits only its assigned directory and does not spawn subagents. Preserve other agents' files. Follow docs/contract.md exactly; report any needed contract change to root before changing it.
Verify meaningful permission, lifecycle, idempotency and failure paths; run Java tests, Python tests and frontend build as applicable.
