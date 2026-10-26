# Complete local platform plan

User already selected architecture and explicitly asks full implementation/start in an independent directory. Execute without further design approval. Use writing-plans and dispatching-parallel-agents for separate contract-bound subsystems; one independent final review.

Goal: working complete local business flow with real infrastructure; cloud model configured by user. Spec: design.md, exact interfaces: contract.md.

## Constraints and review focus
No changes to original checkout. No shared files between implementers. Java authorization authoritative. Missing keys must never produce fake AI. Test cross-user, cross-department, stale index, confirmation retries, missing/degraded external dependencies, restart persistence. No tests invoke paid models.

- [x] Backend: implement contract with Spring Boot/JPA/Security/Flyway, write permission/status/idempotency tests first, execute Maven tests and package.
- [x] Python: durable ingest/Redis/S3/pgvector, LangChain models/tools, LangGraph retrieval/answer/interrupt/resume and PostgreSQL checkpoints. Test ACL, version replacement/delete, model failures and confirm idempotency with external boundaries replaced.
- [x] Frontend: independent React/Vite app, polished Chinese workbench implementing every endpoint and role-aware actions. Build and meaningful UI workflow tests.
- [x] Root: independent Compose/env/bootstrap/start/stop with no .ps1 requirement; create random credentials privately, reuse downloaded image layers not old volumes; install and start all services.
- [x] Integration: real API/browser login, uploads, isolation, citation, confirmed ticket lifecycle, duplicate confirm, restart/history; compare configured model mode honestly.
- [x] Independent review, fix important findings, rerun relevant verification, write technology map and operating guide, hand over running URL and credential file path.
