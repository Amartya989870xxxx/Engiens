# Deploying Engiens (Vercel + Railway)

```
Browser ──HTTPS──▶ Vercel (Hobby)          frontend: static React build, SPA routing, security headers
   │
   └──HTTPS──▶ Railway project
                 ├─ backend   Spring Boot (backend/Dockerfile, prod profile), public *.up.railway.app domain
                 │    ├─▶ Postgres  Railway managed PostgreSQL ─────────── private network only
                 │    ├─▶ runner    Engiens scenario runner (runner/) ──── private network only, no public domain
                 │    │               python3 3.12 · node 24 · javac/java 21; one temp dir and process group per run
                 │    └─▶ GitHub, Gemini, Groq (keys server-side only)
```

**The whole platform works in production, including code-first Scenario Lab:** Run executes the user's code against
the hidden checks on the runner service, exactly as the local Docker sandbox does.

| File | Purpose |
|---|---|
| `backend/Dockerfile`, `backend/railway.toml` | Backend image and Railway config (Dockerfile build, health check, restart policy, watch paths) |
| `runner/engiens_runner.py`, `runner/Dockerfile`, `runner/railway.toml` | The runner service: Python standard library only, plus the three language runtimes |
| `deploy/railway.env.example` | Every variable for the backend and runner services (placeholders only) |
| `frontend/vercel.json` | SPA fallback for deep links, asset caching, CSP/HSTS and other security headers |
| `deploy/backup.sh` | `pg_dump` of the production database to your machine |
| `deploy/sandbox-images.sh` | Digest-pinned images for the local Docker sandbox (development and CI) |

## How code execution works

The backend builds every run the same way, whatever executes it: the user's workspace, the Engiens language runner
(`engiens_run.py` / `engiens_run.mjs` / `EngiensRunner.java`), the hidden checks and a one-time result marker. Where it
runs is configuration (`scenario.execution.provider`):

| | Local development (default) | Production (prod profile default) |
|---|---|---|
| Provider | `docker`: `DockerSandboxExecutionProvider` | `runner`: `RemoteRunnerExecutionProvider` |
| Where | A throwaway Docker container per run | The runner service, over Railway's private network (`Authorization: Bearer RUNNER_TOKEN`) |
| Isolation | Container: no network, read-only, non-root, memory/CPU/PID limits | Process: own directory (deleted after), own process group (killed on timeout or exit), emptied environment, runs as `nobody`, limits on processes, open files, file size and CPU time |

**Honest limitation:** the runner isolates processes, not containers. Code it runs can reach the network, and memory per
run is bounded by the language flags (`-Xmx256m`, `--max-old-space-size=160`) and the runner's service limit rather
than a per-run cgroup. It can't read the runner's token, environment or files, and it has no credentials for the
database or anything else. This is a deliberate trade-off for a free, Railway-compatible academic deployment; the local
Docker sandbox keeps the stricter container isolation.

---

## 0. Timing and accounts: don't waste the Railway trial

The deployment uses the **new Engiens GitHub account** and its repository, and a **new Railway account** signed in with
that GitHub account. Railway's Free Trial is a one-time **$5 credit that expires 30 days after sign-up**, so create the
Railway account only on deployment day (planned Oct 20–25): the 30 days then cover the evaluation into mid-November.

Before that day:

- [ ] push the code to the new GitHub repository (CI green: backend, runner, frontend)
- [ ] generate and store in a password manager: `JWT_SECRET` (`openssl rand -base64 48`), `RUNNER_TOKEN` (`openssl rand -hex 32`)
- [ ] have `GEMINI_API_KEY`, `GROQ_API_KEY`, `GITHUB_TOKEN` (and GitHub App values if used) ready
- [ ] a Vercel account (free, no expiry) linked to the new GitHub account

**Cost estimate** (measured locally; check Railway's pricing on the day): backend ≈ 340 MiB with the JVM options in
`railway.env.example`, runner ≈ 12 MiB idle (a Java run briefly uses a few hundred MB), PostgreSQL ≈ 50 MiB, all
nearly idle on CPU. That is a few dollars a month in total, so **$5 is about one month of light use**. Check
Project → Usage weekly; if the credit runs low, enable Serverless (app sleeping) on the runner first (it wakes on the
first Run, adding a short delay), then on the backend (a ~15–30 s start after idle), or move to the Hobby plan.

## 1. Create the Railway project and the backend service

1. railway.com → sign in with the **new** GitHub account → **New Project → Deploy from GitHub repo** → authorize
   Railway for the new `Engiens` repository → select it. Rename the created service to `backend`.
2. `backend` → **Settings**:
   - **Source → Root Directory**: `/backend`
   - **Config-as-code → Railway Config File**: `/backend/railway.toml`
   - **Deploy → Branch**: `main`

## 2. Add the runner service

Project canvas → **+ New → GitHub Repo** → the same repository → rename the service to **`runner`** (the backend's
variables refer to it by this name).

- **Settings → Source → Root Directory**: `/runner`; **Config-as-code**: `/runner/railway.toml`
- **Settings → Networking**: **do not** generate a public domain. Its private domain is shown there
  (`runner.railway.internal`); the backend uses it through `${{runner.RAILWAY_PRIVATE_DOMAIN}}`.
- **Variables** (Raw Editor): `RUNNER_TOKEN=<from your password manager>`, `PORT=8090`, `RUNNER_MAX_CONCURRENT=2`.
  Nothing else: the runner has no database, AI or GitHub access.

Its deploy log shows `Engiens runner listening on port 8090; runtimes: {python…, node v24…, java 21…}; running code as nobody`.

## 3. Add the managed PostgreSQL

Project canvas → **+ New → Database → Add PostgreSQL**; keep the name `Postgres`. Flyway creates the schema on the
backend's first start.

## 4. Backend variables

`backend` → **Variables → Raw Editor** → paste the backend part of `deploy/railway.env.example` → fill the empty values
in Railway's UI (never in a committed file) → **Update Variables**.

| Variable | What it is |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | References to `Postgres` (`${{Postgres.PGHOST}}` …), over the private network |
| `CORS_ALLOWED_ORIGIN`, `FRONTEND_URL` | The Vercel URL (step 7). Use `https://placeholder.invalid` until it exists |
| `JWT_SECRET` | ≥ 32 bytes; signs logins. Changing it logs everyone out. `JWT_TTL_MINUTES` optional (120) |
| `GEMINI_API_KEY`, `GROQ_API_KEY` | Free-tier AI keys. Without them reviews and labs can't be generated |
| `GITHUB_TOKEN` | Recommended (no scopes): public API limit 60 → 5000 requests/hour |
| `GITHUB_APP_*` | Optional: private repositories (step 8) |
| `SCENARIO_EXECUTION_ENABLED` | `true` |
| `SCENARIO_EXECUTION_PROVIDER` | `runner` (also the prod profile's default) |
| `SCENARIO_RUNNER_URL` | `http://${{runner.RAILWAY_PRIVATE_DOMAIN}}:8090` |
| `SCENARIO_RUNNER_TOKEN` | `${{runner.RUNNER_TOKEN}}`: the same secret, referenced, never typed twice |
| `REVIEWS_PER_DAY`, `LABS_PER_DAY` | Per-user daily caps on AI-backed actions (10 / 5) |
| `JAVA_TOOL_OPTIONS` | Leaner JVM: ~340 MiB instead of ~430 MiB |
| `PORT` | Set by Railway; the backend listens on it |

## 5. Public domain for the API

`backend` → **Settings → Networking → Generate Domain** → e.g. `engiens-backend-production.up.railway.app` (HTTPS
automatic). The deploy log should show `Successfully applied 11 migrations` (first start), `Code execution ready:
runner service at http://runner.railway.internal:8090`, and `Started BackendApplication`.

```bash
curl -fsS https://<RAILWAY_DOMAIN>/actuator/health      # {"status":"UP",...}
```

If the log says `Code execution unavailable (runner service …)`, check that the runner is deployed and that
`SCENARIO_RUNNER_TOKEN` resolves to its `RUNNER_TOKEN`. A custom domain later: **Networking → Custom Domain** + CNAME.

## 6. Frontend on Vercel

1. vercel.com (signed in with the new GitHub account) → **Add New → Project** → import the repository.
2. **Root Directory**: `frontend`; framework **Vite** (detected).
3. **Environment Variables** (Production): `VITE_API_URL` = `https://<RAILWAY_DOMAIN>`. This is the API base URL;
   the code reads `VITE_API_URL` (the only frontend variable, no secrets). Vite builds it in: redeploy after changing it.
4. **Deploy**. `vercel.json` handles SPA routes (`/progress`, `/reviews/:id`, `/scenario-lab`, …), caching and
   security headers. Optional: tighten its CSP `connect-src 'self' https:` to `https://<RAILWAY_DOMAIN>`.

## 7. Connect them: CORS and FRONTEND_URL

Set `CORS_ALLOWED_ORIGIN` and `FRONTEND_URL` on `backend` to the production Vercel URL (e.g.
`https://engiens.vercel.app`; comma-separate several). Railway redeploys. Check:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X OPTIONS https://<RAILWAY_DOMAIN>/api/auth/login \
  -H 'Origin: https://engiens.vercel.app' -H 'Access-Control-Request-Method: POST'     # 200
```

## 8. GitHub App (optional: private repositories)

Create or reuse a GitHub App under the **new** account → add the Callback URL
`https://<RAILWAY_DOMAIN>/api/github/callback` → put its `GITHUB_APP_*` values on `backend` (private key on one line).
Public repositories work without it.

## 9. Production smoke test

On the Vercel URL, in a private window:

1. Landing page; `/progress` opened directly also loads.
2. Register, log out, log in; after five wrong passwords the next attempt says "Too many login attempts".
3. Import a public repository; an invalid link is rejected clearly.
4. Review → review PDF.
5. Review → Scenario Lab (5 scenarios): the setup screen shows **no** "can't run code" note; scenarios are code-first.
6. Open a scenario → edit code → **Run**: real check results from the runner (Python, and a TypeScript or Java one
   if the lab has one). A deliberately infinite loop reports "Took longer than …".
7. Submit → feedback; submitting again is refused.
8. Finish → historical report → lab PDF.
9. Progress shows review and lab evidence.
10. Check for new commits (after pushing to a test repository) → review again → Progress shows a project-level change.
11. Another user's review id in the URL → "not found".
12. `curl -sI https://<RAILWAY_DOMAIN>/actuator/health`: HSTS and CSP present; the Vercel site has its CSP and HSTS.
13. The runner is not public: `runner` → Settings → Networking shows no public domain, only the private one.

Real client IPs: login/sign-up limits use the client address Railway's proxy forwards. Check once that sign-ups from
two networks (laptop and phone hotspot) are limited separately; if everyone shares one limit, set
`SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` on `backend` to a regex matching Railway's proxy addresses.

## 10. Backups

```bash
# Railway → Postgres → Variables → copy DATABASE_PUBLIC_URL. The leading space keeps it out of shell history.
 DATABASE_URL='<DATABASE_PUBLIC_URL>' sh deploy/backup.sh
```

Dumps go to `deploy/backups/` (git-ignored, owner-only); restore instructions are in `backup.sh`. Take one before any
deploy that adds a migration.

## 11. Updating and rolling back

- **Update:** push to `main`. Railway rebuilds `backend` when `backend/**` changes and `runner` when `runner/**`
  changes; Vercel rebuilds the frontend.
- **Rollback:** Railway → service → Deployments → an earlier one → **Redeploy**; Vercel → **Promote to Production**.
  Migrations only move forward: restore the pre-deploy backup first if the release you're leaving added one.

## Local development (unchanged)

`make db`, `make sandbox-images`, `make backend`, `make frontend`. Locally the backend uses the Docker sandbox
(`scenario.execution.provider=docker`). To try the runner locally instead:
`RUNNER_TOKEN=<16+ chars> python3 runner/engiens_runner.py`, then start the backend with
`SCENARIO_EXECUTION_PROVIDER=runner SCENARIO_RUNNER_URL=http://localhost:8090 SCENARIO_RUNNER_TOKEN=<same>`.
