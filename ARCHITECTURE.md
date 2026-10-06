# Engiens architecture

Every diagram below is drawn from the code in this repository (class and table names are real). For the reasons
behind each choice, see [DECISIONS.md](DECISIONS.md). For setup, see [README.md](README.md).

1. [High-level system](#1-high-level-system)
2. [Backend request flow and modules](#2-backend-request-flow-and-modules)
3. [Repository review pipeline](#3-repository-review-pipeline)
4. [Scenario Lab pipeline](#4-scenario-lab-pipeline)
5. [Code execution in production (runner)](#5-code-execution-in-production-runner)
6. [Authentication](#6-authentication)
7. [Data model](#7-data-model)
8. [Deployment](#8-deployment)

---

## 1. High-level system

```mermaid
flowchart LR
    U["Developer<br/>(browser)"] --> FE["Frontend<br/>React 19 + TypeScript + Vite<br/>TanStack Query"]
    FE -- "JSON over HTTPS<br/>Authorization: Bearer JWT" --> BE["Backend<br/>Spring Boot 4 / Java 21<br/>modular monolith"]
    BE --> DB[("PostgreSQL<br/>Flyway V1–V11")]
    BE -- "repository metadata,<br/>file tree, file contents" --> GH["GitHub API<br/>+ raw.githubusercontent.com"]
    BE -- "structured JSON prompts" --> AI["AI providers<br/>Gemini → Groq fallback"]
    BE -- "user code + hidden checks" --> EX{"ExecutionProvider"}
    EX -- "provider=docker (local)" --> DK["Docker sandbox<br/>one container per run"]
    EX -- "provider=runner (production)" --> RN["Engiens runner service<br/>runner/engiens_runner.py"]
```

The browser only talks to the backend. GitHub tokens, AI keys and the runner token stay server-side.

## 2. Backend request flow and modules

Every request takes the same path. A thin controller validates the DTO and passes the user id from the JWT to a
service. The service enforces ownership and business rules. A Spring Data repository reaches PostgreSQL, and DTOs come
back out. Long work (review, lab generation, evaluation) is handed to a worker on a bounded executor and polled by
the frontend.

```mermaid
flowchart TB
    REQ["HTTP request"] --> SEC["Spring Security filter chain<br/>JWT validation (HS256) · CORS · security headers"]
    SEC --> CTRL["Controller<br/>@Valid request DTO · user id from JWT"]
    CTRL --> SVC["Service<br/>ownership check · business rules · @Transactional"]
    SVC --> REPO["Spring Data JPA repository<br/>findByIdAndUserId(...)"]
    REPO --> PG[("PostgreSQL")]
    SVC -. "long work" .-> WK["Worker on a bounded executor<br/>ReviewWorker · ScenarioGenerationWorker · EvaluationWorker"]
    WK --> PG
    SVC --> DTO["Response DTO / record"]
    SVC -- "ApiException" --> GEH["GlobalExceptionHandler<br/>ApiError {status, code, message, fieldErrors}"]

    subgraph Modules["Packages under com.engineeringlens"]
        direction LR
        auth --- user --- github --- repository
        analysis --- review["analysis.review"] --- ai["analysis.ai"]
        scenario --- progress --- export --- common
    end
```

| Package | Responsibility |
|---|---|
| `auth` | registration, login, JWT issuing, login/sign-up throttling, security configuration |
| `user` | users and engineering profiles |
| `github` | GitHub REST client, optional GitHub App (private repositories), repository reader |
| `repository` | import (metadata + file inventory), commit sync, per-repository work guard |
| `analysis` | review preparation without AI: profiler → deterministic rules → context selection |
| `analysis.ai` | provider-neutral AI layer: Gemini and Groq providers, model router, cooldowns |
| `analysis.review` | AI review: rubric, prompts, validation, assess/teach orchestration, persistence |
| `scenario` | Scenario Lab: `lab`, `generation`, `execution`, `workspace`, `evaluation`, `history` |
| `progress` | deterministic indicators computed on read from stored reviews and labs |
| `export` | server-side PDFs of reviews and lab assessments |
| `common` | `ApiError`, `ApiException`, `GlobalExceptionHandler`, `DailyLimit`, PDF typesetting |

The Java package is still `com.engineeringlens`, the project's working title; the product is Engiens.

## 3. Repository review pipeline

A review is two steps. The first, **preparation** (`AnalysisPreparationService`), uses no AI and runs at
`POST /api/repositories/{id}/analyses`. The second is the **AI review** (`ReviewService` → `ReviewWorker` →
`ReviewOrchestrator`), started at `POST /api/repositories/{id}/reviews` and polled until it completes.

```mermaid
flowchart TB
    IMP["Import<br/>GitHubRepoUrl validation · metadata · file tree<br/>FileClassifier drops node_modules, build output, binaries, .git"] --> PREP

    subgraph PREP["Preparation: deterministic, no AI (AnalysisPreparationService)"]
        P1["RepositoryProfiler<br/>languages, frameworks, manifests"] --> P2["DeterministicAnalyzer<br/>Structure · Testing · Security · SecretScanner<br/>Persistence · ProductionReadiness rules"]
        P2 --> P3["ContextSelector<br/>files per dimension, with reasons"]
        P3 --> P4["ContextBuilder<br/>downloads only selected files at the pinned commit<br/>size limits · credentials withheld"]
    end
    PREP --> ART[("analysis_runs + analysis_artifacts<br/>profile, signals, context manifest<br/>(no file contents)")]

    ART --> REV
    subgraph REV["AI review (ReviewWorker → ReviewOrchestrator)"]
        R1["AnalysisContextLoader<br/>re-reads selected files by manifest"] --> R2["ASSESS<br/>ReviewPromptBuilder: rubric + code + signals<br/>no developer profile"]
        R2 --> R3["AiModelRouter<br/>Gemini models → Groq models<br/>retries · cooldowns · one repair attempt"]
        R3 --> R4["ReviewValidator<br/>schema v1 · 16 dimensions · evidence paths exist"]
        R4 --> R5["TEACH (ReviewPersonalizer)<br/>profile + finished verdicts, no code<br/>writes PersonalizedTeaching only"]
    end
    REV --> ST[("review_runs + reviews<br/>ReviewDocument JSON")]
    ST --> UI["Report UI · PDF export · Progress"]
```

Assessment never sees the developer profile, so the same commit gets the same verdicts whoever submits it. Teaching
sees the profile but no code, and it can only add `PersonalizedTeaching`; it cannot change a verdict. If teaching
fails, the review is still saved without personalised advice.

## 4. Scenario Lab pipeline

```mermaid
flowchart TB
    START["POST /api/scenario-labs<br/>from a review, a repository or a GitHub link<br/>roles · seniority · 5 / 10 / 20 scenarios"] --> LAB[("scenario_labs<br/>status GENERATING")]
    LAB --> GEN

    subgraph GEN["ScenarioGenerationWorker → ScenarioGenerator"]
        G1["ScenarioContextBuilder<br/>repository code + review findings"] --> G2["Plan in batches of 14<br/>+ 40% spares · ALREADY PLANNED list"]
        G2 --> G3["ScenarioOutputValidator + ScenarioFit<br/>role / seniority / category / file layer<br/>diversity cap · no duplicate (file, category)"]
        G3 --> G4["Build each scenario (up to 3 in parallel)<br/>statement · evidence · workspace · hidden checks · reference"]
        G4 --> G5{"Executable?"}
        G5 -- "yes" --> G6["HarnessValidator in the sandbox<br/>starter fails ≥1 check · checks match the declared ones<br/>reference passes all"]
        G5 -- "no / no code for these roles" --> G7["APPROACH_ONLY scenario"]
        G6 -- "fails → repair once, else drop" --> G4
    end
    G6 --> SAVE[("scenarios<br/>saved as each one is proven")]
    G7 --> SAVE
    SAVE --> WS["Workspace<br/>CodeMirror editor · autosave draft<br/>Run = hidden checks, nothing submitted"]
    WS --> SUB["Submit once<br/>scenario_attempts (UNIQUE scenario_id)"]
    SUB --> EV["EvaluationWorker<br/>ASSESS attempt (no profile) → verdict on the review's scale<br/>EvaluationValidator"]
    EV --> FIN{"All scenarios<br/>evaluated?"}
    FIN -- "yes (optimistic lock: one caller)" --> AS["Lab summary (no profile) → teaching (profile, no code)<br/>scenario_lab_assessments"]
    AS --> DONE[("lab COMPLETED<br/>read-only history · PDF · Progress")]
```

Lab statuses are `GENERATING → ACTIVE → FINALIZING → COMPLETED`, with `FAILED` and `CANCELLED` as the other end
states. A user has at most one open lab, enforced by the `active_user_id UNIQUE` column.

## 5. Code execution in production (runner)

`ScenarioExecutionService` builds every run the same way, whatever executes it. Only the `ExecutionProvider`
implementation differs.

```mermaid
sequenceDiagram
    autonumber
    participant UI as Browser (workspace)
    participant BE as Backend<br/>ScenarioWorkspaceService
    participant SX as ScenarioExecutionService
    participant RP as RemoteRunnerExecutionProvider
    participant RN as Runner service<br/>(private network)
    UI->>BE: POST …/scenarios/{id}/run  (files)
    BE->>BE: ownership · one run per user at a time
    BE->>SX: run(language, files, hidden checks)
    SX->>SX: add language runner + checks<br/>+ one-time result marker
    SX->>RP: execute(ExecutionRequest)
    RP->>RN: POST /run  Authorization: Bearer RUNNER_TOKEN<br/>{command, files, timeoutMs, memoryMb, outputLimitBytes, resultMarker}
    RN->>RN: validate paths · temp dir (work/ tmp/ home/)<br/>own process group · emptied env · rlimits · user nobody
    RN->>RN: run python3 / node / javac+java with time limit<br/>kill the whole group on timeout and after exit
    RN-->>RP: {exitCode, timedOut, stdout, stderr, outputTruncated, resultLines, durationMs}
    RP-->>SX: ExecutionResult (same shape as the Docker sandbox)
    SX-->>BE: RunResult: PASSED / FAILED / COMPILE_ERROR / RUNTIME_ERROR / LIMIT_EXCEEDED<br/>per-check results, hidden check source never shown
    BE-->>UI: run result
    Note over RP,RN: Non-200 (401, 400, 429, 5xx) or unreachable →<br/>SCENARIO_EXECUTION_UNAVAILABLE, never blamed on the user's code
```

Locally, `DockerSandboxExecutionProvider` receives the same `ExecutionRequest` and runs it in a throwaway container:
`--network none`, read-only image, user 65534, all capabilities dropped, memory/CPU/PID limits, digest-pinned images.

## 6. Authentication

```mermaid
sequenceDiagram
    autonumber
    participant B as Browser
    participant A as AuthController / AuthService
    participant T as AuthThrottle
    participant DB as PostgreSQL
    participant J as JwtService
    B->>A: POST /api/auth/register {name, email, password}
    A->>T: sign-ups per IP (5 / hour)
    A->>DB: save user (BCrypt password hash)
    A->>J: issue JWT (HS256, sub = user id, 120 min)
    A-->>B: {token, user}
    B->>B: localStorage["lens.token"]
    B->>A: POST /api/auth/login
    A->>T: 20 attempts per IP, 5 failures per email / 15 min
    A->>DB: find by email · BCrypt match
    A-->>B: token, or one generic "invalid email or password"
    B->>A: GET /api/... Authorization: Bearer token
    Note over A: Spring Security resource server verifies<br/>signature + expiry, then services load data<br/>only with findByIdAndUserId(id, userId)
    A-->>B: 200, or 401 → frontend clears the token and shows login
```

Another user's resource returns **404**, not 403, so the response doesn't reveal that the id exists. Private
repositories use a separate, optional GitHub App flow with a single-use `state` (README → GitHub App).

## 7. Data model

There are no JPA associations (`@ManyToOne` etc.): entities hold foreign-key ids, and services load what they need
explicitly. This avoids lazy-loading surprises, and the relationships live in the Flyway schema:

```mermaid
erDiagram
    users ||--o| user_profiles : "has"
    users ||--o| github_connections : "optional App install"
    users ||--o{ github_connect_states : "pending connect"
    users ||--o{ repositories : "imports"
    repositories ||--o{ repository_files : "inventory (paths, sizes)"
    repositories ||--o{ analysis_runs : "prepared at a commit"
    analysis_runs ||--o| analysis_artifacts : "profile, signals, manifest"
    analysis_runs ||--o{ review_runs : "reviewed by"
    review_runs ||--o| reviews : "ReviewDocument JSON"
    repositories ||--o{ scenario_labs : "practised in"
    reviews |o--o{ scenario_labs : "optional source review"
    analysis_runs ||--o{ scenario_labs : "code snapshot"
    scenario_labs ||--o{ scenarios : "position-ordered"
    scenarios ||--o| scenario_attempts : "submitted once (UNIQUE)"
    scenario_labs ||--o| scenario_lab_assessments : "final assessment"
    ai_model_health {
        string id PK "provider:model"
        string state
        int failure_count
        timestamp cooldown_until
    }
```

| Table | Entity | Notes |
|---|---|---|
| `users`, `user_profiles` | `User`, `UserProfile` | email unique; profile keyed by user id |
| `github_connections`, `github_connect_states` | `GitHubConnection`, `GitHubConnectState` | installation id only; state expires in 10 min |
| `repositories`, `repository_files` | `ImportedRepo`, `RepoFile` | unique (user, owner, name); commit SHA pinned |
| `analysis_runs`, `analysis_artifacts` | `AnalysisRun`, `AnalysisArtifact` | manifest, never file contents |
| `review_runs`, `reviews` | `ReviewRun`, `StoredReview` | run status + validated document |
| `scenario_labs`, `scenarios`, `scenario_attempts`, `scenario_lab_assessments` | same names | attempts copy what they need, so history never depends on the workspace |
| `ai_model_health` | `AiModelHealth` | per-model cooldowns that survive restarts |

All foreign keys to `users` and `repositories` are `ON DELETE CASCADE`. A lab's link to its source review is
`ON DELETE SET NULL`, so a lab outlives the review it came from.

## 8. Deployment

This is the planned production topology; the step-by-step runbook is in [deploy/README.md](deploy/README.md).

```mermaid
flowchart LR
    B["Browser"] -- HTTPS --> V["Vercel<br/>static React build<br/>SPA rewrites · CSP · HSTS<br/>(frontend/vercel.json)"]
    B -- "HTTPS API calls<br/>VITE_API_URL" --> BE
    subgraph R["Railway project"]
        BE["backend service<br/>backend/Dockerfile · prod profile<br/>/actuator/health"]
        PG[("Railway PostgreSQL")]
        RN["runner service<br/>runner/Dockerfile<br/>no public domain"]
        BE -- "private network" --> PG
        BE -- "private network<br/>Bearer RUNNER_TOKEN" --> RN
    end
    BE --> GH["GitHub"]
    BE --> AI["Gemini / Groq"]
    CI["GitHub Actions CI<br/>backend · runner · frontend jobs"] -. "on push" .-> GHR["GitHub repository"]
    GHR -. "deploy on push" .-> V
    GHR -. "deploy on push<br/>(watch paths)" .-> R
```
