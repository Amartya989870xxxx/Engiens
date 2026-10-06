# Engiens

A developer-learning platform: build an engineering profile, get a personalized review of your GitHub repository, and practice production/scaling scenarios.

**Current status:** registration, engineering profile, GitHub repository import (public and private via a GitHub App), repository analysis, AI engineering reviews, Scenario Lab (repository-specific scenarios with sandboxed code execution and AI evaluation), PDF export and Progress (evidence-based indicators across reviews and labs) are working. Deployment is next.

## Stack

- Backend: Java 21, Spring Boot 4, Spring Security (stateless JWT), Spring Data JPA, Flyway, PostgreSQL
- Frontend: React, TypeScript, Vite, Tailwind CSS, React Router, TanStack Query

## Run locally

Requirements: JDK 21+, Node 22+, Docker.

```bash
cp .env.example .env            # then edit DB_PASSWORD and JWT_SECRET (and GEMINI_API_KEY for AI reviews)
docker compose up -d            # PostgreSQL

cd backend && ./mvnw spring-boot:run          # API on :8080

cd frontend && cp .env.example .env && npm install && npm run dev   # UI on :5173
```

Or, after the one-time `.env` setup, use the Makefile (run each in its own terminal):

```bash
make sandbox-images  # once: pulls the Scenario Lab code sandbox images
make backend    # starts PostgreSQL, then the API on :8080
make frontend   # installs deps if needed, then the UI on :5173
make test       # backend + frontend tests
make stop       # stops PostgreSQL
```

The backend reads its configuration from environment variables (or the root `.env`). Nothing secret is committed.
`GITHUB_TOKEN` is optional: without it, GitHub allows 60 profile lookups per hour from the server's IP.

## Scenario Lab code sandbox

Locally, Scenario Lab runs user code only inside throwaway Docker containers: no network, no host folders, no
environment variables or secrets, a read-only image, an unprivileged user, and limits on memory, CPU, processes and
time (`make sandbox-images` pulls the digest-pinned Python, Node and Java images once).

Where containers can't be started (Railway), the same runs go to the **scenario runner** service (`runner/`):
`SCENARIO_EXECUTION_PROVIDER=runner` with `SCENARIO_RUNNER_URL` and `SCENARIO_RUNNER_TOKEN`. It runs each command in its
own temporary directory and process group, as an unprivileged user, with an emptied environment, a time limit and
resource limits; it isolates processes rather than containers, so it has no network isolation (see `deploy/README.md`).

Without either, or with `SCENARIO_EXECUTION_ENABLED=false`, Run is unavailable and labs fall back to approach-only
scenarios (the lab setup screen says so). `SCENARIO_EXECUTION_MAX_CONCURRENT` (default 2) limits runs at once.

## GitHub App (private repositories, optional)

Without these settings the app works with public repositories only. With them, onboarding offers
"Include private repositories": the user installs the GitHub App on their account, choosing which
repositories to share (read-only), and GitHub redirects back to `/api/github/callback`.

1. GitHub → Settings → Developer settings → GitHub Apps → New GitHub App.
2. Callback URL `http://localhost:8080/api/github/callback` (add the deployed backend's callback too, up to 10).
   Tick "Request user authorization (OAuth) during installation". Untick Webhook → Active.
3. Repository permissions: Contents → Read-only (Metadata becomes read-only automatically). Nothing else.
4. After creating: note App ID and Client ID, generate a client secret and a private key.
   Save the key as `secrets/github-app.pem` (git-ignored) and fill the `GITHUB_APP_*` values in `.env`.

How it stays safe: the redirect carries a random single-use `state` stored server-side (expires in 10 minutes);
the backend only accepts an installation that GitHub confirms the signed-in user can access; only the installation
id is stored, and short-lived access tokens are minted per request and never persisted.

## Deployment

The frontend is deployed on Vercel; the backend, its managed PostgreSQL and a small **scenario runner** service run on
Railway. Scenario Lab code runs on the runner over Railway's private network (Railway can't start the Docker containers
the local sandbox uses), so the deployed platform supports code-first scenarios with Run and hidden checks. The
step-by-step runbook and configuration are in [`deploy/`](deploy/README.md).

## Tests

```bash
cd backend && ./mvnw test       # uses in-memory H2 (PostgreSQL mode)
cd frontend && npm test
```

## Architecture

Modular monolith (`backend/src/main/java/com/engineeringlens`):

| Package  | Responsibility                                        |
|----------|-------------------------------------------------------|
| `auth`   | registration, login, JWT issuing, security config      |
| `user`   | users and engineering profiles                         |
| `github` | GitHub API clients, GitHub App connection, repository reader |
| `repository` | repository import: metadata + file inventory (`/api/repositories`); moving an imported repository to its latest commit (`POST /api/repositories/{id}/sync`) so it can be reviewed again after changes |
| `analysis` | review preparation without AI: profiler, deterministic rules, context builder (`/api/repositories/{id}/analyses`) |
| `analysis.ai` | provider-neutral AI layer: Gemini and Groq providers, model router with retries, fallback and per-model cooldowns |
| `analysis.review` | AI engineering review: rubric, prompt, output validation, background runs, persistence (`/api/repositories/{id}/reviews`, `/api/reviews`) |
| `scenario` | Scenario Lab: lab lifecycle (`lab`), generation and harness validation (`generation`), code execution (`execution`: local Docker sandbox or the remote runner), workspace/run/submit (`workspace`), evaluation (`evaluation`), history (`history`) |
| `progress` | Progress: deterministic, evidence-based indicators per rubric area from stored reviews and completed labs; no new tables, no AI calls (`/api/progress`, `/api/progress/history`) |
| `export` | server-side PDFs of reviews and lab assessments, from persisted data |
| `common` | shared error model, global exception handler, PDF typesetting |

Progress is calculated on read from the reviews and labs already stored: the 16 review rubric dimensions are the only
engineering areas (every Scenario Lab category maps onto one), reviews of the same commit count once, low-confidence
assessments are shown but not counted, and a better rating at a later commit is reported as a project-level change.
Developer-level "improving" needs Scenario Lab gains across labs or gains in more than one repository. Every
indicator shows its reason and the evidence behind it; there is no score.

Controllers stay thin, business rules live in services, DTOs are used at the API boundary, and every failure returns the same `ApiError` JSON shape. Schema changes go through Flyway migrations in `src/main/resources/db/migration`.

Auth notes: passwords are BCrypt-hashed; login returns the same error for unknown email and wrong password; the JWT is kept in `localStorage` for simplicity in the MVP (a trade-off against XSS exposure that is worth revisiting).
