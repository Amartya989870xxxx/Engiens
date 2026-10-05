# Deploying Engiens (Vercel + Railway)

```
Browser ──HTTPS──▶ Vercel (Hobby)              frontend: static React build, SPA routing, security headers
   │
   └──HTTPS──▶ Railway project
                 ├─ backend   (Spring Boot, Docker image from backend/Dockerfile, prod profile, *.up.railway.app)
                 │     └─▶ GitHub, Gemini, Groq (keys server-side only)
                 └─ Postgres  (Railway managed PostgreSQL, private network only)
```

| File | Purpose |
|---|---|
| `backend/Dockerfile` | The backend image: Java 21 JRE, non-root, prod profile, listens on `$PORT` |
| `backend/railway.toml` | Railway config-as-code: Dockerfile build, health check, restart policy, watch paths |
| `deploy/railway.env.example` | Every backend variable, ready to paste into Railway's Raw Editor (placeholders only) |
| `frontend/vercel.json` | SPA fallback for deep links, asset caching, CSP/HSTS and other security headers |
| `deploy/backup.sh` | `pg_dump` of the production database to your machine |
| `deploy/sandbox-images.sh` | Pulls the digest-pinned code-sandbox images (local development and CI) |

### Scenario Lab on Railway

Scenario Lab runs submitted code in throwaway Docker containers that the backend starts. Railway services can't start
containers, so on Railway the backend runs with `SCENARIO_EXECUTION_ENABLED=false`:

- labs are generated as **approach-only** scenarios: the developer explains how they'd diagnose and fix the problem,
  and the answer is evaluated as usual (assessment, feedback, history, PDF, Progress all work);
- the lab setup screen says so ("Code can't be run on this server…"), and Run is never offered;
- nothing is faked: wherever the backend runs next to a Docker daemon (local development: `make sandbox-images`,
  `make backend`), executable scenarios with Run and hidden checks work exactly as before.

---

## 0. Timing: don't waste the Railway trial

Railway's Free Trial is a one-time **$5 credit that expires 30 days after sign-up**. Create the Railway account only
when you're ready to deploy (planned: Oct 20–25), so the 30 days cover the evaluation period into mid-November.
Prepare everything before that:

- [ ] generate `JWT_SECRET` (`openssl rand -base64 48`) and keep it in a password manager
- [ ] have `GEMINI_API_KEY`, `GROQ_API_KEY`, `GITHUB_TOKEN` (and GitHub App values if used) ready
- [ ] `main` is green in CI and contains everything you want to demo
- [ ] a Vercel account (free, no expiry) linked to GitHub

**Cost estimate** (measured locally; verify against Railway's pricing page on the day): the backend uses about
340 MiB with the JVM settings in `railway.env.example` (about 430 MiB without them), PostgreSQL about 50 MiB, both
nearly idle on CPU. At Railway's usage-based rates that is a few dollars a month, so **$5 covers roughly a month of
light use, but not much more.** Watch Project → Usage weekly. If the credit runs low before the evaluation ends, the
options are: enable Serverless (app sleeping) on the backend (the first request after idle then waits for the JVM to
start, ~15–30 s), or move to the Hobby plan.

## 1. Create the Railway project from GitHub

1. railway.com → sign in with GitHub → **New Project → Deploy from GitHub repo** → authorize Railway's GitHub app for
   the `Engiens` repository → select it. This creates a service; rename it to `backend`.
2. Service **Settings**:
   - **Source → Root Directory**: `/backend`
   - **Config-as-code → Railway Config File**: `/backend/railway.toml`
     (Dockerfile build, `/actuator/health` health check, restart on failure, rebuild only when `backend/**` changes)
   - **Deploy → Branch**: `main` (each push to `main` that touches `backend/` redeploys)
3. Don't let the first build start without variables (step 3); if it does, it fails fast and redeploys once they're set.

## 2. Add the managed PostgreSQL

Project canvas → **+ New → Database → Add PostgreSQL**. Keep the service name `Postgres` (the reference variables in
`railway.env.example` use it). Nothing else to configure: the backend reaches it over Railway's private network, and
Flyway creates the schema on the backend's first start.

## 3. Backend variables

`backend` service → **Variables → Raw Editor** → paste `deploy/railway.env.example` → fill the empty values in the
Railway UI (never in a file in the repository) → **Update Variables**.

| Variable | Required | What it is |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | yes | `prod` (also the image default) |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | yes | References to the `Postgres` service (`${{Postgres.PGHOST}}` …): private network, no secrets typed by hand |
| `CORS_ALLOWED_ORIGIN` | yes | Exact frontend origin(s), e.g. `https://engiens.vercel.app` (step 6). The backend won't start without it |
| `FRONTEND_URL` | yes | Same URL; GitHub App redirects return there |
| `JWT_SECRET` | yes | ≥ 32 bytes; signs login tokens. Changing it logs everyone out |
| `JWT_TTL_MINUTES` | no | Login lifetime (default 120) |
| `GEMINI_API_KEY`, `GROQ_API_KEY` | for AI | Free-tier keys. Without them, reviews and labs can't be generated; everything else works |
| `GITHUB_TOKEN` | recommended | No scopes needed; raises GitHub's public API limit from 60 to 5000 requests/hour |
| `GITHUB_APP_*` | optional | Private repositories (step 7). Private key on one line |
| `SCENARIO_EXECUTION_ENABLED` | yes | `false` on Railway (no Docker); see above |
| `REVIEWS_PER_DAY`, `LABS_PER_DAY` | no | Per-user daily caps on AI-backed actions (10 / 5) |
| `JAVA_TOOL_OPTIONS` | recommended | Leaner JVM (serial GC, ~300 MB heap ceiling): ~340 MiB instead of ~430 MiB |
| `PORT` | — | Set by Railway; the backend listens on it automatically |

`CORS_ALLOWED_ORIGIN` and `FRONTEND_URL` need the Vercel URL. For the very first deploy, set both to
`https://placeholder.invalid`, then replace them in step 6.

## 4. Public domain for the API

`backend` → **Settings → Networking → Public Networking → Generate Domain**. Railway gives an HTTPS hostname such as
`engiens-backend-production.up.railway.app` (TLS is automatic). If asked for a port, use the one the deploy logs show
in `Tomcat started on port …` (Railway's `PORT`).

Check the deployment: **Deployments → View logs** should show `Successfully applied 11 migrations` on the first start,
`Code sandbox unavailable … approach-only` (expected on Railway), and `Started BackendApplication`. Then:

```bash
curl -fsS https://<RAILWAY_DOMAIN>/actuator/health     # {"status":"UP",...}
```

Later, a custom domain (e.g. `api.engiens.in`) is **Settings → Networking → Custom Domain** plus a CNAME record.

## 5. Frontend on Vercel

1. vercel.com → **Add New → Project** → import the `Engiens` repository.
2. **Root Directory**: `frontend`. Framework preset: **Vite** (build `npm run build`, output `dist`), detected automatically.
3. **Environment Variables** (Production): `VITE_API_URL` = `https://<RAILWAY_DOMAIN>` (no trailing slash).
   This is the frontend's API base URL. The code reads `VITE_API_URL`; it is the only frontend variable and contains no
   secret. Vite bakes it in at build time, so after changing it, redeploy.
4. **Deploy**. `frontend/vercel.json` gives SPA routing (`/progress`, `/reviews/:id`, `/scenario-lab`, … load directly),
   long caching for hashed assets, and the security headers. Its CSP allows API calls to any HTTPS origin; once the
   Railway domain is final you can tighten `connect-src 'self' https:` to `connect-src 'self' https://<RAILWAY_DOMAIN>`.

## 6. Connect the two: CORS and FRONTEND_URL

Copy the production Vercel URL (e.g. `https://engiens.vercel.app`; preview URLs are different and won't be allowed).
In Railway, set `CORS_ALLOWED_ORIGIN` and `FRONTEND_URL` to it (several origins: comma-separated) → the backend
redeploys. Check that the browser may call the API:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X OPTIONS https://<RAILWAY_DOMAIN>/api/auth/login \
  -H 'Origin: https://engiens.vercel.app' -H 'Access-Control-Request-Method: POST'     # 200
```

## 7. GitHub App (optional: private repositories)

GitHub → Settings → Developer settings → GitHub Apps → your app → add the **Callback URL**
`https://<RAILWAY_DOMAIN>/api/github/callback` (keep the localhost one for development). Put the `GITHUB_APP_*`
values in Railway. Public repositories work without any of this.

## 8. Production smoke test

On the Vercel URL, in a private window:

1. Landing page loads; `/progress` opened directly loads too (SPA routing).
2. Register, log out, log in. After five wrong passwords for one account, the next attempt says "Too many login attempts".
3. Import a public repository; an invalid link (`https://example.com/x`) is rejected with a clear message.
4. Run a review; open it; export the review PDF.
5. From the review, start a 5-scenario lab: the setup screen shows the "Code can't be run on this server" note;
   scenarios are approach-only.
6. Answer one in writing → Submit → feedback appears; submitting again is refused.
7. Finish the lab → historical report → lab PDF.
8. Progress shows the review and lab evidence; the repository filter works.
9. Repository page → Check for new commits (after pushing to a test repository) → review again → Progress shows a
   project-level change.
10. Open another user's review id in the URL → "not found".
11. Response headers: `curl -sI https://<RAILWAY_DOMAIN>/actuator/health` shows `Strict-Transport-Security` and the CSP;
    the Vercel site shows its CSP and HSTS.

Real client IPs: the login/sign-up limits key on the client address that Railway's proxy forwards. Check once that
sign-ups from two different networks (laptop and phone hotspot) are limited separately. If everyone shares one limit,
add the variable `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` with a regex matching Railway's proxy addresses.

## 9. Backups

From your machine (Docker needed, nothing else):

```bash
# Railway → Postgres → Variables → copy DATABASE_PUBLIC_URL. The leading space keeps it out of shell history.
 DATABASE_URL='<DATABASE_PUBLIC_URL>' sh deploy/backup.sh
```

Dumps go to `deploy/backups/` (git-ignored, owner-only). Take one before every deploy that adds a migration.
Restore instructions are at the top of `backup.sh`.

## 10. Updating and rolling back

- **Update:** push to `main`. Railway builds the backend when `backend/**` changed; Vercel builds the frontend.
- **Rollback:** Railway → backend → Deployments → an earlier deployment → **Redeploy**; Vercel → Deployments →
  an earlier one → **Promote to Production**. Migrations only move forward: if the deploy you're leaving added a
  migration, restore the backup taken before it first.

## Running it yourself with code execution

Any machine with Docker can run the same image with code execution on: pull the sandbox images
(`sh deploy/sandbox-images.sh`), run the backend container with `SCENARIO_EXECUTION_ENABLED=true`, the host's
`/var/run/docker.sock` mounted and the socket's group added (`--group-add $(stat -c %g /var/run/docker.sock)`), plus
the variables above. Every sandbox run then uses the digest-pinned images with no network, no mounts and strict limits.
