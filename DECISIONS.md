# Engineering decisions

Each entry follows the same structure: the decision, the problem it addresses, the alternatives considered, the
trade-off accepted, and where it lives in the code. Diagrams are in [ARCHITECTURE.md](ARCHITECTURE.md).

---

## 1. Modular monolith, not microservices

- **Decision.** One Spring Boot application, split into packages by business capability (`auth`, `repository`,
  `analysis`, `scenario`, `progress`, …). User code runs outside it, in sandbox containers (or, on the Railway
  fallback, the separate code runner); that split is forced by isolation, not chosen for architecture's sake.
- **Problem.** A single developer with a fixed deadline needs clear boundaries without the operational cost of
  distributed systems.
- **Alternatives.** Microservices per module (independent deploys, but network calls, distributed transactions,
  several deploy pipelines). A layered monolith with no module boundaries (simple, but it turns into one tangle).
- **Trade-off.** All modules scale together and share a database. That is acceptable at this load, and the package
  boundaries mean a module could later be extracted along an existing seam.
- **In code.** `backend/src/main/java/com/engineeringlens/*`. Controllers stay thin, services own rules and
  transactions, and DTO records sit at the boundary.

## 2. Java 21 + Spring Boot

- **Decision.** Java 21 (records for DTOs and documents, switch expressions over enums) and Spring Boot 4 with Spring
  Security, Data JPA and Bean Validation.
- **Problem.** The product needs authentication, validation, transactions, background work and a relational schema.
  All of these are mature, well-tested parts of Spring.
- **Alternatives.** Node/Express (one language with the frontend, but more of these pieces assembled by hand).
  Python/FastAPI (fast to write, weaker static typing for a large domain model).
- **Trade-off.** Higher memory use (about 340 MiB measured with the production JVM options) and slower start-up, in
  exchange for strong typing across a large domain (reviews, labs, evaluations) and well-understood security defaults.

## 3. PostgreSQL

- **Decision.** One relational database for all state, including structured AI output stored as validated JSON
  documents in text columns.
- **Problem.** Ownership, uniqueness and lifecycle rules (one open lab per user, one attempt per scenario, one
  repository per user/owner/name) are best enforced by the database itself.
- **Alternatives.** A document store (natural for JSON reviews, but weaker constraints and joins). Separate stores for
  documents and relations (more moving parts).
- **Trade-off.** JSON documents are not queried field-by-field inside SQL. Progress reads and interprets them in Java
  instead, which is fine at per-user volumes.
- **In code.** `UNIQUE (user_id, github_owner, github_repo_name)`, `scenario_attempts.scenario_id UNIQUE`,
  `scenario_labs.active_user_id UNIQUE`.

## 4. Flyway migrations, Hibernate in `validate` mode

- **Decision.** Every schema change is a versioned SQL migration (V1–V11). Hibernate only *validates* that the entities
  match the schema; it never generates it.
- **Problem.** The schema must be reproducible on a fresh production database and reviewable in Git.
- **Alternatives.** `ddl-auto=update` (convenient, but silent and unreviewable, and it can't express every constraint).
  Liquibase (equivalent; Flyway's plain SQL is simpler).
- **Trade-off.** Migrations are written by hand and, once applied, are never edited (Flyway checksums them).
  Corrections become new migrations.

## 5. Stateless JWT authentication + BCrypt

- **Decision.** Passwords are hashed with BCrypt. Login issues an HS256 JWT (subject = user id, 120-minute lifetime),
  verified by Spring Security's OAuth2 resource-server support. The frontend keeps it in `localStorage`.
- **Problem.** The SPA (on Vercel) and the API (on its own host) are on different origins, so cookies would need cross-site
  configuration and CSRF protection.
- **Alternatives.** Server sessions with cookies (revocable, but stateful and cross-site). An HttpOnly cookie with a
  JWT (better XSS posture, but needs CSRF handling and a same-site domain).
- **Trade-off.** A token in `localStorage` can be read by injected script, and a JWT can't be revoked before it
  expires. Mitigations: a short lifetime, a strict CSP on the frontend (`frontend/vercel.json`), React's escaping, and
  no `dangerouslySetInnerHTML`. Throttling slows guessing: 20 logins per IP and 5 failures per email per 15 minutes,
  and 5 sign-ups per IP per hour (`AuthThrottle`). Login gives the same error for an unknown email and a wrong password.

## 6. GitHub reads: raw content first, API as fallback

- **Decision.** Public file contents are fetched from `raw.githubusercontent.com` at the pinned commit (private ones
  through the contents API with the GitHub App installation token). If the raw host times out, refuses the connection
  or answers 5xx, the same file at the same commit is read through the contents API, and the raw host is skipped for
  a while. An empty body is read as an empty file (e.g. `__init__.py`), not an error.
- **Problem.** The REST API's rate limit (60/hour unauthenticated, 5000 with a token) is spent quickly when a review
  reads dozens of files.
- **Trade-off.** Two code paths to maintain, both tested. Reads are pinned to a commit SHA, so a review is reproducible
  even after the repository changes; "Check for new commits" (`POST /api/repositories/{id}/sync`) moves to the
  latest commit explicitly.

## 7. AI behind a provider abstraction, with routing and validation

- **Decision.** `AiProvider` has two implementations (`GeminiProvider`, `GroqProvider`). `AiModelRouter` tries an
  ordered list of models: several Gemini models, then Groq. Transient errors get 2 retries with backoff and jitter. A
  failing model gets a cooldown (1 min, doubling to at most 30 min) that is stored in `ai_model_health`, so it survives
  restarts. Errors are classified (rate limit, context too large, model not found, …), and API keys are redacted from
  every logged message.
- **Problem.** Free-tier AI is rate-limited and models get retired, so a single model and provider would make reviews
  fail often.
- **Alternatives.** One provider SDK called directly from services (simple, but every outage becomes a product outage,
  and the product is tied to one vendor).
- **Trade-off.** Different models may phrase findings differently. The validator (§9) keeps the structure identical
  whichever model answers, and the run records which model was used.

## 8. Separate ASSESS and TEACH calls

- **Decision.** Assessment sees the code, the rubric and the deterministic signals, but **not** the developer profile.
  Teaching sees the profile and the finished verdicts, but **no code**, and it can only write
  `PersonalizedTeaching`. The same split applies to Scenario Lab evaluation and lab assessments.
- **Problem.** Personalisation must change *advice*, never *verdicts*. Otherwise two developers would get different
  ratings for the same code, and a "beginner" label could inflate scores.
- **Alternatives.** One prompt with the profile included (cheaper, but the verdict could drift with the profile, and
  that can't be verified).
- **Trade-off.** Two AI calls instead of one. Teaching is best-effort: if it fails, the review is still saved without
  personalised advice.
- **In code.** `ReviewOrchestrator`, `ReviewPersonalizer`, `EvaluationWorker`.

## 9. Treat AI output as untrusted: validate, then repair once

- **Decision.** Every AI answer is parsed into a typed document and validated, for example: all 16 dimensions present
  exactly once, enums known, sizes capped. Evidence that points at a file or signal the model wasn't given is removed,
  and line numbers outside the file are cleared, so an invented reference never reaches the user. If validation fails, one repair attempt sends the validator's errors back to the model. If that fails too, the run
  ends with a clear error code rather than storing a malformed review.
- **Problem.** Models invent file paths, skip fields, and return truncated JSON.
- **Trade-off.** Strict validation rejects some answers a human might accept. Repair costs one extra call only when
  needed.
- **In code.** `ReviewValidator`, `ScenarioOutputValidator`, `EvaluationValidator`.

## 10. Immutable history

- **Decision.** A completed review is never edited; regenerating creates a new review run. A scenario can be submitted
  once (`UNIQUE scenario_id`). A completed lab is read-only, and its attempts copy everything needed to display them
  later.
- **Problem.** Progress over time is meaningful only if past evidence can't change, and a double click or second tab
  must not create two attempts.
- **Trade-off.** More storage (snapshots instead of references), and no "edit my answer". Both are intended: the
  record shows what the developer actually did.

## 11. Ownership on every query

- **Decision.** Every user-owned read goes through queries such as `findByIdAndUserId(id, userId)`, with the user id
  taken from the verified JWT and never from the request body. Another user's resource returns 404.
- **Problem.** Insecure direct object references: guessable or leaked UUIDs must not expose someone else's code
  review.
- **Trade-off.** Slightly more repository methods. Integration tests check cross-user access
  (`RepositoryImportFlowTest`, `AnalysisFlowTest`, `ReviewFlowTest`, `ScenarioLabLifecycleTest`,
  `ScenarioWorkspaceFlowTest`, `PdfExportTest`).

## 12. Scenario de-duplication and diversity

- **Decision.** Generation plans in batches of 14 and passes an "already planned" list to each batch. The validator
  rejects a second scenario for the same (main file, category) in larger labs and caps how many scenarios one
  category may take (`diversityCap`). It generates 40% spare scenarios so rejections don't shrink the lab.
- **Problem.** Asked for 20 scenarios, a model tends to produce variations of the same few problems.
- **Trade-off.** More AI calls per lab, bounded by per-scenario call budgets and an overall deadline (10 min + 90 s per
  scenario).

## 13. Suitability validation: role, seniority, executable proof

- **Decision.** `ScenarioFit` maps each selected role to allowed categories and code layers (a frontend-only lab
  doesn't get database-migration scenarios) and each seniority to difficulties. An executable scenario is published
  only after `HarnessValidator` proves three things in the sandbox: the starter code fails at least one check, the
  checks that ran are exactly the declared ones, and the reference solution passes all of them. If the selected roles
  have no matching code (`NO_CODE_FOR_ROLES`), generation says so instead of inventing code.
- **Problem.** Without proof, users would be graded by broken tests ("the user is never the test suite for the
  generator").
- **Trade-off.** Generation is slower (each executable scenario runs at least twice before publication). Scenarios
  appear one by one as they are proven, so the user can start before the lab is complete.

## 14. Code execution: Docker sandbox locally and on EC2, runner service as the Railway fallback

- **Decision.** An `ExecutionProvider` interface has two implementations, selected by
  `scenario.execution.provider`:
  - `docker`: a throwaway container per run with no network, a read-only filesystem, user 65534, all capabilities
    dropped, memory/CPU/PID limits and digest-pinned images. Used locally and in production on one AWS EC2 instance,
    where the backend container reaches the host's Docker Engine through its socket (`deploy/docker-compose.prod.yml`).
  - `runner`: an HTTP call to the Engiens runner service on Railway's private network, for the Railway fallback.
- **Problem.** The deployed product must support code-first scenarios with real isolation. Railway, the first hosting
  choice, can't start Docker containers, which is why the runner was built.
- **Alternatives.** A paid sandbox service (cost, external dependency). Railway + runner (works, weaker isolation:
  kept as the fallback). Approach-only in production (rejected: it removes the core feature). A VM with Docker
  (chosen: more operations work, but production runs the same, fully isolated sandbox as development).
- **Trade-off on EC2.** Whoever controls the Docker socket controls the host. Only the backend container gets it, and
  that container is read-only, non-root, `no-new-privileges`, memory-limited and publishes no port; user code never
  sees the socket (`ProductionStackTest` keeps this in CI). One VM is also a single point of failure, and operating it
  (updates, backups) is our job.
- **Trade-off on the Railway fallback, stated plainly.** The runner isolates *processes*, not containers. Each run gets its own temporary
  directory (deleted afterwards), its own process group (killed on timeout and after exit), an emptied environment,
  the `nobody` user, and limits on processes, open files, file size and CPU time. It does **not** have network
  isolation or a per-run memory cgroup. It holds no credentials worth stealing: the database and AI keys live only in
  the backend. Both providers receive the same `ExecutionRequest` and pass the same integration scenarios
  (`SandboxExecutionTest`, `RunnerExecutionTest`).

## 15. Progress is deterministic and computed on read

- **Decision.** `ProgressCalculator` derives indicators (`IMPROVING`, `RECURRING_GAP`, `CONSISTENT_STRENGTH`, …) from
  stored reviews and completed labs. There are no new tables, no AI calls and no score. Reviews of the same commit
  count once, low-confidence assessments are shown but not counted, and a better rating at a later commit is labelled
  a project-level change. "Improving" for the developer needs gains across labs or across more than one repository.
- **Problem.** A progress chart that conflates "the code got better" with "the developer got better" would mislead.
- **Trade-off.** Indicators need several data points before they say anything ("not enough history"), which is honest
  but less flashy.

## 16. Bounded cost and abuse controls

- **Decision.** Daily per-user caps on reviews (10) and labs (5) protect shared AI quota (`DailyLimit`). Runs are limited
  per user (one at a time) and globally (`SCENARIO_EXECUTION_MAX_CONCURRENT`). Request size and output size are capped.
- **Trade-off.** In-memory limiters reset on restart and are per instance. That is fine for a single backend instance,
  but it would need shared storage (e.g. Redis) to scale horizontally.

---

## Known limitations

- Production is one EC2 instance: a single point of failure, operated by us (updates, backups). The backend container
  holds the host's Docker socket, which is root-equivalent on that host (§14).
- The runner (Railway fallback only) has no network isolation and no per-run memory cgroup (§14). The Docker sandbox,
  used locally and on EC2, does.
- The JWT lives in `localStorage` and can't be revoked before it expires (§5).
- Rate limiters and the "one run per user" guard are in memory, so they apply per backend instance (§16).
- Reviews are AI-assisted judgement against an open rubric. They are not a security audit, a coverage measurement or
  a measure of anyone's professional level.
- Very large repositories are reviewed through a selected context (size-limited per file and in total), not in full.
- Background work runs on in-process executors. Work interrupted by a restart is marked failed on start-up
  (`failInterruptedPreparations`, `failInterruptedGeneration`, …) rather than resumed.
