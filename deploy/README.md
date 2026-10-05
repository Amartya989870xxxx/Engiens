# Deploying Engiens

Zero-cost production setup:

```
Browser ──HTTPS──▶ Vercel (Hobby)            frontend: static React build
   │
   └──HTTPS──▶ Oracle Cloud Always Free VM (Ubuntu, Ampere A1 / ARM64)
                 Caddy :443 (automatic Let's Encrypt)
                   └─▶ backend :8080 (Spring Boot, prod profile, non-root, read-only filesystem)
                         ├─▶ PostgreSQL 17 (compose network only, persistent volume)
                         ├─▶ GitHub, Gemini, Groq (keys server-side only)
                         └─▶ host Docker daemon ─▶ Scenario Lab sandbox containers
                                                   (--network none, no mounts, read-only, non-root, CPU/memory/PID limits)
```

Why a VM: Scenario Lab runs user code in Docker containers that the backend starts. Serverless and PaaS platforms
can't start containers, so the backend, its database and the sandbox share one Docker host.

Files in this folder:

| File | Purpose |
|---|---|
| `docker-compose.prod.yml` | Caddy, backend, PostgreSQL: restart policies, health checks, log rotation, no public database |
| `Caddyfile` | HTTPS and reverse proxy for `API_HOST` |
| `.env.production.example` | Every production variable, documented, without values |
| `sandbox-images.sh` | Pulls the digest-pinned sandbox images (x86 and ARM64) |
| `backup.sh` | Nightly `pg_dump`, kept 7 days |

All images (build, runtime, PostgreSQL, Caddy, sandbox) are pinned by digest and publish ARM64 builds; the whole stack
was built and tested on ARM64.

---

## 1. Create the VM (Oracle Cloud, Always Free)

1. Compute → Instances → Create instance.
   - Image: **Canonical Ubuntu 24.04**. Shape: **VM.Standard.A1.Flex** (Ampere), e.g. 2 OCPU / 12 GB
     (Always Free covers up to 4 OCPU / 24 GB in total). Boot volume: 50 GB is plenty.
   - Add your SSH public key. Note the public IP.
   - "Out of capacity" for A1 is common: retry later or pick another availability domain.
2. Networking → the instance's VCN → Security List → add **ingress** rules from `0.0.0.0/0`:
   TCP 80, TCP 443 (and UDP 443 for HTTP/3). Port 22 is open by default. Do **not** open 5432 or 8080.

Note: Oracle may reclaim Always Free instances that stay almost idle for 7 days. Keep the app in use during the
evaluation period; upgrading the account to Pay As You Go removes that rule and is still free within Always Free limits.

## 2. Prepare the server

```bash
ssh ubuntu@<VM_PUBLIC_IP>

# Updates and automatic security patches
sudo apt-get update && sudo apt-get -y upgrade
sudo apt-get install -y unattended-upgrades git

# Oracle's Ubuntu image has its own host firewall that blocks everything but SSH: open 80/443 there too.
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p udp --dport 443 -j ACCEPT
sudo netfilter-persistent save

# Docker Engine and the Compose plugin (official repository)
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update && sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo usermod -aG docker ubuntu && newgrp docker
docker version --format '{{.Server.Arch}}'   # arm64
```

## 3. Get the code and configure it

```bash
sudo mkdir -p /opt/engiens && sudo chown ubuntu:ubuntu /opt/engiens
git clone https://github.com/Amartya989870xxxx/Engiens.git /opt/engiens
cd /opt/engiens/deploy
cp .env.production.example .env && chmod 600 .env
stat -c %g /var/run/docker.sock        # put this number in DOCKER_GID
openssl rand -base64 48                # JWT_SECRET (copy into .env; don't paste secrets into chat or tickets)
openssl rand -base64 32 | tr -d '/+=' | head -c 32; echo   # DB_PASSWORD
nano .env                              # fill in every value (section 4)
```

Hostname without buying a domain: `API_HOST=<ip-with-dashes>.sslip.io` (e.g. `129-146-12-34.sslip.io`) resolves to the
VM's IP and gets a real Let's Encrypt certificate. Switching to `api.engiens.in` later: point an `A` record at the VM,
change `API_HOST`, and run `docker compose -f docker-compose.prod.yml --env-file .env up -d caddy`.

## 4. Production variables (deploy/.env)

| Variable | Required | What it is |
|---|---|---|
| `API_HOST` | yes | Public API hostname Caddy serves (sslip.io first, `api.engiens.in` later) |
| `ACME_EMAIL` | yes | Email for Let's Encrypt notices |
| `CORS_ALLOWED_ORIGIN` | yes | Exact frontend origin(s), comma-separated, e.g. `https://engiens.vercel.app` |
| `FRONTEND_URL` | yes | Frontend URL GitHub App redirects return to |
| `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | yes | PostgreSQL (only reachable inside the compose network) |
| `JWT_SECRET` | yes | ≥ 32 bytes; signs login tokens. Changing it logs everyone out |
| `JWT_TTL_MINUTES` | no | Login lifetime (default 120) |
| `DOCKER_GID` | yes | Group of `/var/run/docker.sock`, so the non-root backend can start sandboxes |
| `GEMINI_API_KEY`, `GROQ_API_KEY` | for AI | Free-tier keys; without them reviews and labs are unavailable, everything else works |
| `GITHUB_TOKEN` | recommended | Token with no scopes: raises GitHub's public API limit from 60 to 5000/hour |
| `GITHUB_APP_ID`, `_CLIENT_ID`, `_CLIENT_SECRET`, `_SLUG`, `_PRIVATE_KEY` | optional | Private repositories (section 8). Key on one line |
| `SCENARIO_EXECUTION_ENABLED`, `SCENARIO_EXECUTION_MAX_CONCURRENT` | no | Sandbox on/off and parallel runs (default 2) |
| `REVIEWS_PER_DAY`, `LABS_PER_DAY` | no | Per-user daily caps on AI-backed actions (10 / 5; 0 = off) |
| `ENGIENS_VERSION` | no | Tag for the backend image; set to the git SHA when deploying (section 6) |

The frontend gets exactly one variable, `VITE_API_URL`, set in Vercel. No backend secret ever reaches it.

## 5. Start

```bash
cd /opt/engiens
sh deploy/sandbox-images.sh            # once, and after any change to the pinned images
cd deploy
ENGIENS_VERSION=$(git rev-parse --short HEAD) docker compose -f docker-compose.prod.yml --env-file .env up -d --build
```

Order is automatic: PostgreSQL starts and becomes healthy; the backend then starts, Flyway applies any pending
migrations (a fresh database gets all of them), Hibernate validates the schema, and the health check turns green;
only then does Caddy start and obtain the certificate.

Verify:

```bash
docker compose -f docker-compose.prod.yml --env-file .env ps          # all healthy / running
curl -fsS https://<API_HOST>/actuator/health                          # {"status":"UP",...}
docker compose -f docker-compose.prod.yml --env-file .env logs backend | grep -E "Started|Code sandbox|migrat"
#   expect: "Successfully applied N migrations" (first start), "Code sandbox ready: Docker reachable, 3 pinned image(s) present"
docker compose -f docker-compose.prod.yml --env-file .env logs caddy | grep -i certificate   # certificate obtained
```

## 6. Frontend on Vercel (Hobby)

1. vercel.com → Add New → Project → import the GitHub repository.
2. Root Directory: `frontend`. Framework preset: Vite (build `npm run build`, output `dist`).
3. Environment variable (Production): `VITE_API_URL=https://<API_HOST>`. Deploy.
4. Put the resulting URL (e.g. `https://engiens.vercel.app`) into `CORS_ALLOWED_ORIGIN` and `FRONTEND_URL` on the
   VM, then `docker compose -f docker-compose.prod.yml --env-file .env up -d backend`.
5. Optional tightening: in `frontend/vercel.json`, replace `connect-src 'self' https:` with
   `connect-src 'self' https://<API_HOST>` once the hostname is final.

`frontend/vercel.json` provides the SPA fallback (deep links like `/progress` work), long-lived caching for hashed
assets, and the security headers: CSP, HSTS, nosniff, frame-deny, referrer and permissions policies.

## 7. Updating and rolling back

```bash
cd /opt/engiens && git pull
cd deploy && ENGIENS_VERSION=$(git rev-parse --short HEAD) docker compose -f docker-compose.prod.yml --env-file .env up -d --build
```

Each build is tagged with its git SHA, so the previous one stays on the server. To roll back:

```bash
docker image ls engiens-backend                        # find the previous SHA tag
ENGIENS_VERSION=<previous-sha> docker compose -f docker-compose.prod.yml --env-file .env up -d --no-build backend
```

Migrations only move forward: if the release you're leaving added a migration, the older backend may refuse to start
(schema validation). In that case restore the backup taken before the update (section 9), then roll back.
Take a backup before every update: `sh deploy/backup.sh`.

## 8. GitHub App in production (optional: private repositories)

GitHub → Settings → Developer settings → GitHub Apps → your app:
- **Callback URL**: add `https://<API_HOST>/api/github/callback` (keep the localhost one for development).
- Keep "Request user authorization (OAuth) during installation" ticked and webhooks off.
- Put `GITHUB_APP_ID`, `GITHUB_APP_CLIENT_ID`, `GITHUB_APP_CLIENT_SECRET`, `GITHUB_APP_SLUG` and the private key
  (on one line: `awk 'NF {printf "%s", $0}' github-app.pem`) in `.env`, then `up -d backend`.

Public repositories work without any of this.

## 9. Backups

```bash
crontab -e
# every night at 03:15 UTC
15 3 * * * /opt/engiens/deploy/backup.sh >> /opt/engiens/deploy/backups/backup.log 2>&1
```

Dumps go to `deploy/backups/` (git-ignored, owner-only) and are kept 7 days. Copy one off the VM now and then:
`scp ubuntu@<VM_PUBLIC_IP>:/opt/engiens/deploy/backups/<file>.dump .`
Restore instructions are at the top of `backup.sh`.

## 10. Operating

- Logs: `docker compose -f docker-compose.prod.yml --env-file .env logs -f backend` (rotated: 5 × 10 MB per service).
- Restart after a crash or reboot is automatic (`restart: unless-stopped`, Docker starts on boot).
- Interrupted work is cleaned up on start: reviews, preparations and lab generation that were running are marked
  failed with a clear message (ready scenarios are kept); interrupted evaluations and lab assessments are marked failed
  and can be retried from the UI.
- Secrets live only in `deploy/.env` (mode 600). Never commit it, never `cat` it on a shared screen, and never put
  values in commands that end up in shell history.
