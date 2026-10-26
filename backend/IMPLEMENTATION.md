# Java backend implementation

This is a standalone Spring Boot 4.1.2 / Java 25 Maven application. It implements the entire browser API in `../docs/contract.md`; the browser never supplies identity or document scopes to Python.

## Run and build

The root launcher supplies DB_URL, DB_USERNAME, DB_PASSWORD, AI_BASE_URL, AI_SERVICE_TOKEN, ADMIN_PASSWORD, AGENT_PASSWORD, EMPLOYEE_PASSWORD and HR_PASSWORD. Default HTTP binding is `127.0.0.1:8180`. The output is `target/helpdesk.jar`.

From the project root in PowerShell:

```powershell
$env:JAVA_HOME = 'D:\agent_work\.tools\jdk-25.0.4.1+1'
& 'E:\maven\apache-maven-3.9.11-bin\apache-maven-3.9.11\bin\mvn.cmd' -s backend/maven-settings.xml -f backend/pom.xml '-Dmaven.repo.local=D:\agent_work\enterprise-helpdesk\.local\m2' package
& "$env:JAVA_HOME\bin\java.exe" -jar backend/target/helpdesk.jar
```

`maven-settings.xml` overrides this machine's legacy global `alimaven` mirror using the same mirror ID, pointing to Maven Central over HTTPS. Merely adding a differently named wildcard mirror does not override a global exact-match mirror.

Initial accounts are created only if absent. There are no default passwords: the four seed environment values must contain at least 12 characters and at most 72 UTF-8 bytes. Existing accounts are not reset on startup. BCrypt cost is 12. Login issues 256-bit opaque tokens, stores only SHA-256 hashes, expires sessions after 12 hours, and logout removes the session. Spring's generated-password/basic-login path is disabled.

## Reading the code

- `Api.java`: typed request, response, enum, validation and envelope contracts.
- `ApiController.java`: HTTP routes, JSON envelopes and safe binary downloads.
- `SecurityConfig.java`, `AuthService.java`: bearer authentication, identity, user creation, seed users and CORS.
- `TicketService.java`: scope rules, pessimistic database locks, unique idempotency keys, assignment, strict state transitions, resolution, comments and ratings.
- `DocumentService.java`: authorization, accepted-version metadata, upload/status/download and authoritative tombstones. Failed replacement immediately removes an ID from retrieval even when old Python chunks still exist.
- `ConversationService.java`: owner-only history, current document whitelists, pending proposals, confirmation transactions and graph resume.
- `AiClient.java`: token-authenticated internal HTTP calls with connection/request timeouts; only safe health keys are forwarded.
- `BusinessRepository.java`, `Entities.java`, `db/migration`: JPA persistence and schema ownership (`hd_` tables and `hd_flyway_history` inside dedicated `hd_business`). Repository transactions never surround Python HTTP requests.
- `Locks.java`: bounded, separate monitor pools for chat/document/ticket operations in this single-instance local deployment.

## Confirmation and failure semantics

The conversation owner confirms an exact persisted proposal. A database transaction locks the conversation and creator, creates at most one ticket under a unique creator/idempotency constraint, and records the approval decision. Only after commit does Java resume Python. If resume fails, the HTTP response is 503 while both the ticket and pending decision remain durable. Retrying the same approval resumes the same ticket; changing the decision is 409. Successful replays return the stored answer and ticket without another Python call. GET conversation detail and new-message submission reconcile /internal/chat/state under the chat lock when Java has no pending proposal. A missed Python draft response restores the original user/assistant turns and pending proposal atomically, once per proposal ID. Stale recovered evidence masks the entire answer and sources while retaining the user-question-based draft. History still loads if the state lookup is unavailable.

Java does not return an AI answer if any returned citation fails the original whitelist or current visibility/active/version checks: generated answer prose could otherwise contain excluded evidence. It discards that Python checkpoint and returns 409 for a fresh question. History citations are rechecked at read time. If any stored citation becomes unauthorized, inactive, or a different version, the entire assistant answer and source list are masked; user-authored messages remain intact. Pending proposal displays are also sanitized when their original evidence is revoked.

Existing but unauthorized documents, conversations and tickets return 403. Missing/deleted resources return 404. Invalid transitions/pending confirmation conflicts return 409; malformed requests return 400. Downstream parser/provider/URL details never enter browser errors.

The optional requestTicket field uses a normalized Boolean because Jackson 3's primitive-null behavior rejects missing primitive values; absent/null requestTicket correctly means false.

## Verification and limits

The full Maven suite uses real Spring MVC, Security, JPA and Flyway with H2 PostgreSQL compatibility mode; the external AI boundary uses an actual loopback HTTP server with generated ephemeral test credentials. No paid models or Docker are used by this suite. MigrationIsolationTest starts with populated foreign `public.ai_*` data, runs migrations twice, and verifies the foreign row survives and all business tables stay in `hd_business`. Flyway owns only that schema; auto-baseline is not enabled. Tests cover auth envelopes/logout, cross-department scope, employee denial, owner-only conversations even for administrators, valid and invalid lifecycle, duplicate ticket creation, failed graph-resume recovery, document ACL/download/tombstones, failed replacement, whole-answer rejection for unauthorized/stale evidence, nested-lock namespace isolation, whole-history redaction after visibility change/deletion, durable deletion retries, and exactly-once recovery after a committed graph response is lost.

The root agent owns real PostgreSQL/Python/S3/Redis smoke and browser validation. H2 results do not claim those integration checks have run. The application is designed for one local Java process; horizontal scaling would require a distributed conversation lease in addition to the existing ticket/approval row locks. Document deletion immediately revokes Java access even if Python is down. V2 adds durable cleanup_done metadata; a scheduler retries inactive unacknowledged tombstones every 60 seconds, including after restart, and marks completion only after Python acknowledges. Each external deletion happens outside database transactions. List endpoints are intentionally unpaginated for the local learning dataset.
