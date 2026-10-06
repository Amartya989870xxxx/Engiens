# Deploying Engiens on AWS EC2 (+ Vercel)

One Linux virtual machine on AWS EC2 runs the whole backend side: Caddy (HTTPS), the Spring Boot API, PostgreSQL and
Docker Engine, which runs Scenario Lab code in the same throwaway sandbox containers as local development. The React
frontend is on Vercel. This is Engiens' production deployment.

```
Browser ──HTTPS──▶ Vercel                     React build, SPA routing, security headers (frontend/vercel.json)
   │
   └──HTTPS──▶ EC2 instance (Ubuntu 24.04, one Elastic IP)        security group: 80, 443 open; 22 from your IP only
                 ├─ caddy      :80/:443   automatic Let's Encrypt certificate, HTTP → HTTPS, reverse proxy
                 ├─ backend    :8080      Spring Boot, prod profile (not published: only Caddy reaches it)
                 │    ├─▶ postgres :5432  data on a Docker volume (not published: only the backend reaches it)
                 │    ├─▶ /var/run/docker.sock ─▶ Docker Engine ─▶ engiens-run-* sandbox container per Run/Submit
                 │    │                          --network none · read-only · user nobody · memory/CPU/PID limits
                 │    └─▶ GitHub, Gemini, Groq (keys stay on the server)
                 └─ EBS volume (disk): OS, images, the database volume, local backups
```

| File | Purpose |
|---|---|
| `deploy/docker-compose.prod.yml` | The stack: postgres, backend, caddy. Only Caddy publishes ports; only the backend gets the Docker socket |
| `deploy/Caddyfile` | HTTPS for `API_HOST`, proxy to `backend:8080` |
| `deploy/.env.production.example` | Every variable, with how to generate secrets. Copied to `deploy/.env` on the server |
| `backend/Dockerfile` | Backend image (multi-stage, Java 21 JRE, non-root, Docker CLI, health check) |
| `deploy/sandbox-images.sh` | Pulls the digest-pinned Python, Node and Java sandbox images (the sandbox never pulls during a Run) |
| `deploy/backup.sh`, `deploy/restore.sh` | Database dump with 7-day retention; restore with confirmation |
| `deploy/smoke-test.sh` | Checks the public API from your machine after each deploy |
| `backend/src/test/.../ProductionStackTest.java` | CI test: no published database/backend port, code execution always on, socket only in the backend |

---

## 1. Cost safety — read this first

AWS bills by the hour for what **exists**, not for what you use. Nothing in this guide needs paid extras, but the
console makes it easy to create them. Prices and free offers change: **the AWS console and the AWS pricing pages on the
day you deploy are the source of truth, not this file.**

**Free offers, as understood when this was written (October 2026), so verify them:**

- AWS accounts created from **15 July 2025** choose a **Free plan** or a **Paid plan** at sign-up. The Free plan gives
  promotional **credits** (AWS describes up to USD 200: part at sign-up, part for completing introductory activities)
  that pay for usage of eligible services. It ends when the credits are used up or after **6 months**, whichever comes
  first; to keep the account and its resources running after that, you must upgrade to the Paid plan.
- Older accounts may still be on the **legacy 12-month Free Tier** (750 hours/month of a small 1 GiB instance). 1 GiB
  is **not enough** for Engiens (see §2), so on such an account the instance below is paid usage.
- The EC2 launch wizard labels instance types **"Free tier eligible"** for your account. That label, not this guide,
  decides what the Free plan covers. Credits are not unlimited: a 4 GiB instance running 24/7 uses them steadily.

**Do these before launching anything:**

1. **Budget alerts:** Billing and Cost Management → **Budgets** → Create budget → *Cost budget* → e.g. USD 10 per
   month, with email alerts at 50%, 80% and 100% of actual cost and at 100% of forecasted cost. (On the Free plan,
   also open the Free plan / credits page and note the remaining credits and the end date.)
2. **Pick one region** (e.g. Asia Pacific (Mumbai) `ap-south-1`) and always check the region selector in the console's
   top bar. Resources in another region are billed but invisible while you look at this one.

**What costs money here, and how to stop it:**

| Item | Billed while… | How to stop the charge |
|---|---|---|
| EC2 instance (compute) | it is **running** | *Stop* pauses compute charges; *Terminate* deletes it |
| EBS volume (disk, 30 GiB gp3) | it **exists**, even when the instance is stopped | delete it (terminating the instance deletes it if "Delete on termination" is on, the default for the root volume) |
| Public IPv4 address / Elastic IP | it **exists**, attached or not (AWS charges hourly for public IPv4 addresses) | *Release* the Elastic IP after terminating the instance |
| EBS snapshots (optional backups) | they exist (per GiB-month) | delete old snapshots / lifecycle policy retention |
| Data transfer out to the internet | per GB beyond AWS's monthly free allowance | Engiens' API traffic is small; the frontend is served by Vercel |

**Cost traps to avoid:**

- **T-family "Unlimited" CPU credits.** Burstable `t3`/`t3a`/`t4g` instances launch in *Unlimited* mode by default:
  sustained CPU above the baseline (e.g. many Java sandbox runs) is billed extra. In the launch wizard → *Advanced
  details* → **Credit specification: Standard** (it then throttles instead of charging).
- **NAT Gateway, Load Balancer, RDS, extra Elastic IPs:** not needed. A NAT gateway or load balancer bills every hour
  it exists. Use the default VPC and a public subnet as described below.
- **Forgotten resources after the evaluation:** follow §17 to remove everything.
- **Leaked AWS credentials:** never create access keys for this deployment (nothing here needs them); enable MFA.

## 2. Sizing

Measured on the production stack (local rehearsal, §16): backend ≈ 335 MiB idle (container limit 1 GiB), PostgreSQL
≈ 45 MiB, Caddy ≈ 20 MiB. Each sandbox run is hard-capped by Docker: 256 MiB (Python, JavaScript, TypeScript) or
512 MiB (Java), 1 CPU, at most `SCENARIO_EXECUTION_MAX_CONCURRENT` (2) at once across all users, including the
proving runs during lab generation. Add ~400 MiB for Ubuntu and Docker, and about 1–1.5 GiB while the backend image
is being **built** on the server (Maven).

| Instance memory | Verdict | Settings |
|---|---|---|
| 1 GiB | Too small | — |
| 2 GiB (e.g. `t3.small`, `t4g.small`) | Works for a demo, tight | 2 GiB swap (§7), `SCENARIO_EXECUTION_MAX_CONCURRENT=1`, `BACKEND_MEMORY=768m`; build when nobody is using it |
| **4 GiB, 2 vCPU** (e.g. `t3.medium`, `t4g.medium`, `c7i-flex.large`) | **Recommended** | defaults from `.env.production.example` |

x86 (`t3`, `c7i-flex`) and ARM/Graviton (`t4g`) both work: every image is pinned by a multi-architecture digest. Disk:
**30 GiB gp3** (OS ≈ 3 GiB, images ≈ 3 GiB, build cache, database and local backups).

## 3. Before deployment day

- [ ] Code pushed to the new GitHub repository; CI green (backend, frontend)
- [ ] A password manager entry for: `DB_PASSWORD`, `JWT_SECRET` (generated on the server in §8, or locally), the
      AI keys (`GEMINI_API_KEY`, `GROQ_API_KEY`), `GITHUB_TOKEN`, and the GitHub App values if used
- [ ] A Vercel account linked to the new GitHub account
- [ ] Optional: rehearse the stack locally (§16)

## 4. AWS account (once)

1. Create the AWS account (choose the Free or Paid plan knowingly; see §1). Sign in as the **root user** only now.
2. Root user → Security credentials → **assign an MFA device**.
3. Create a day-to-day admin login: **IAM Identity Center** (recommended by AWS) or IAM → Users → create a user with
   console access and the `AdministratorAccess` policy, with MFA. Sign out of root and use this login from now on.
4. Billing: create the **budget alerts** from §1. Choose your region.

Concepts in one line each: the **root user** can do anything (protect it, don't use it); an **IAM user** is a
login with permissions; a **region** is a data-centre location; a **VPC** is your private network in a region (every
account has a default one, which we use); a **security group** is a firewall attached to the instance.

## 5. Launch the instance

EC2 → **Launch instance**:

| Setting | Value |
|---|---|
| Name | `engiens` |
| AMI (operating system image) | **Ubuntu Server 24.04 LTS**, 64-bit (x86 or Arm to match the instance type) |
| Instance type | 4 GiB / 2 vCPU (§2); check the *Free tier eligible* label if you rely on the Free plan |
| Key pair | **Create new key pair** → `engiens-key`, ED25519, `.pem`. Downloaded once: keep it safe, never commit it |
| Network | default VPC, a public subnet, **Auto-assign public IP: Enable** |
| Security group | **Create**: `engiens-sg` with the inbound rules below |
| Storage | **30 GiB gp3**, **Encrypted: yes** (default AWS-managed key) |
| Advanced → Credit specification | **Standard** (T-family only; §1) |

Inbound rules of `engiens-sg` (outbound: leave "all traffic", needed for GitHub, AI providers, Let's Encrypt, apt):

| Type | Port | Source | Why |
|---|---|---|---|
| SSH | 22 | **My IP** (your current address, `/32`) | administration; never `0.0.0.0/0` |
| HTTP | 80 | `0.0.0.0/0` and `::/0` | Let's Encrypt validation and the redirect to HTTPS |
| HTTPS | 443 | `0.0.0.0/0` and `::/0` | the API |
| Custom UDP | 443 | `0.0.0.0/0` and `::/0` | optional: HTTP/3 |

**Never** add 5432 (PostgreSQL), 8080 (backend) or 2375/2376 (Docker): nothing listens on them publicly, and the
security group should say so too. If your home IP changes, edit the SSH rule (EC2 → Security groups → `engiens-sg`).

## 6. Elastic IP and hostname

1. EC2 → **Elastic IPs → Allocate** → then **Associate** it with the `engiens` instance. The address now stays the same
   across stops and reboots (a plain auto-assigned public IP changes when the instance stops).
2. API hostname, free: the Elastic IP with dashes + `.sslip.io`, e.g. `13.233.10.20` → `13-233-10-20.sslip.io`
   (sslip.io answers DNS with the IP written in the name). Later, a domain you own: an `A` record
   `api.example.com → <Elastic IP>`, then change `API_HOST` (§13).

## 7. First login and the operating system

```bash
chmod 400 ~/Downloads/engiens-key.pem
ssh -i ~/Downloads/engiens-key.pem ubuntu@<ELASTIC_IP>
```

On the server:

```bash
sudo apt-get update && sudo apt-get -y upgrade        # security updates (unattended-upgrades stays on afterwards)
sudo timedatectl set-timezone UTC

# Swap: a safety margin against out-of-memory kills (needed on 2 GiB, cheap insurance on 4 GiB)
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

**Install Docker Engine** from Docker's official repository (the method in Docker's documentation for Ubuntu):

```bash
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker ubuntu          # then log out and back in so the group applies
```

Check that Docker is only reachable through its local socket (no TCP port):

```bash
docker version --format '{{.Server.Version}}'
sudo ss -tlnp | grep -E ':2375|:2376' || echo "no Docker TCP port: good"
```

Do not add a `hosts`/`-H tcp://` setting to Docker; the backend uses the Unix socket.

## 8. Code, sandbox images and configuration

```bash
git clone https://github.com/<new-account>/Engiens.git ~/Engiens   # a private repository: use a read-only deploy key
cd ~/Engiens
sh deploy/sandbox-images.sh            # Python, Node, Java sandbox images, pinned by digest (≈ 1 GB)

cd deploy
cp .env.production.example .env && chmod 600 .env
stat -c %g /var/run/docker.sock        # → DOCKER_GID
openssl rand -base64 48 | tr -d '\n'; echo   # → JWT_SECRET
openssl rand -base64 32 | tr -d '/+=' | head -c 32; echo   # → DB_PASSWORD
nano .env
```

| Variable | Value |
|---|---|
| `API_HOST` | e.g. `13-233-10-20.sslip.io` |
| `ACME_EMAIL` | your email (certificate expiry notices) |
| `CORS_ALLOWED_ORIGIN`, `FRONTEND_URL` | the Vercel URL; use `https://placeholder.invalid` until it exists (§11) |
| `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | `engiens`, `engiens`, the generated password |
| `JWT_SECRET` | the generated value (≥ 32 bytes). Changing it later logs everyone out |
| `GITHUB_TOKEN` | recommended: a token with no scopes raises the GitHub API limit from 60 to 5000 requests/hour |
| `GITHUB_APP_*` | optional, private repositories (§12) |
| `GEMINI_API_KEY`, `GROQ_API_KEY` | AI providers; without both, reviews and labs can't be generated |
| `DOCKER_GID` | from `stat` above |
| `SCENARIO_EXECUTION_MAX_CONCURRENT`, `BACKEND_MEMORY` | per §2 |
| `ENGIENS_VERSION` | `git rev-parse --short HEAD` (tags the image so you can roll back) |

Scenario Lab code always runs in Docker sandbox containers on this host: the compose file fixes
`SCENARIO_EXECUTION_ENABLED=true`, and the Docker sandbox is the backend's only execution provider. Secrets live only in
`deploy/.env` on the server (owner-only, git-ignored).

## 9. Start

```bash
cd ~/Engiens/deploy
alias dc='docker compose -f docker-compose.prod.yml --env-file .env'     # used in the rest of this guide
dc up -d --build --wait --wait-timeout 600     # first build takes several minutes (Maven)
dc ps
dc logs backend | grep -E "Successfully applied|Started BackendApplication|Code sandbox"
dc logs caddy | grep -iE "certificate obtained|error"
```

Expected in the backend log: `Successfully applied 11 migrations` (first start only), `Started BackendApplication`,
and **`Code sandbox ready: Docker reachable, 3 pinned image(s) present`**. If it says the sandbox is unavailable:
check `DOCKER_GID` and that `sandbox-images.sh` ran. If Caddy can't get a certificate: port 80 must be open to
everyone, `API_HOST` must resolve to the Elastic IP, and if Let's Encrypt reports a rate limit for the shared
`sslip.io` name, use a domain you own.

Containers have `restart: unless-stopped` and Docker starts at boot, so the stack comes back after a reboot.

## 10. Verify

From your own machine:

```bash
sh deploy/smoke-test.sh https://<API_HOST> https://<VERCEL_URL>      # before §11 the CORS check fails: expected
```

On the server:

```bash
sudo ss -tlnp            # public listeners: only 22 (sshd), 80 and 443 (docker-proxy for Caddy). No 5432, 8080, 2375
dc ps --format '{{.Service}}: {{.Ports}}'      # postgres and backend publish nothing
```

## 11. Frontend on Vercel

1. vercel.com → **Add New → Project** → the repository → **Root Directory** `frontend` (Vite is detected).
2. **Environment Variables** (Production): `VITE_API_URL = https://<API_HOST>` (the only frontend variable; built in
   at build time, so redeploy after changing it). **Deploy.**
3. On the server, put the Vercel URL into `CORS_ALLOWED_ORIGIN` and `FRONTEND_URL` in `deploy/.env` (exact origin, no
   trailing slash; comma-separate several), then `dc up -d --wait backend`.
4. `sh deploy/smoke-test.sh https://<API_HOST> https://<VERCEL_URL>` → all checks pass.

Optional hardening: in `frontend/vercel.json`, narrow the CSP `connect-src 'self' https:` to `https://<API_HOST>`.

## 12. GitHub App (optional: private repositories)

In the GitHub App's settings (under the new account): **Callback URL** `https://<API_HOST>/api/github/callback`.
Put `GITHUB_APP_ID`, `GITHUB_APP_CLIENT_ID`, `GITHUB_APP_CLIENT_SECRET`, `GITHUB_APP_SLUG` and the private key on one
line (`awk 'NF {printf "%s", $0}' github-app.pem`) into `deploy/.env`; `dc up -d --wait backend`. Public repositories
work without it.

## 13. Product smoke test (in a private browser window, on the Vercel URL)

1. Landing page; `/progress` opened directly also loads.
2. Register, log out, log in; after five wrong passwords: "Too many login attempts".
3. Import a public repository; an invalid link is rejected clearly.
4. Review → report → review PDF.
5. Scenario Lab from that review (5 scenarios): the setup screen shows **no** "can't run code" note.
6. Open a scenario → edit → **Run**: real check results. Repeat with a Python, a TypeScript/JavaScript and a Java lab
   if you have repositories in those languages. An infinite loop reports "Took longer than …".
7. Submit → feedback; a second submit is refused. Finish → report → lab PDF.
8. Progress shows review and lab evidence. Another user's review id in the URL → "not found".

Changing the API hostname later (own domain): update `API_HOST`, `dc up -d caddy`, then `VITE_API_URL` on Vercel
(redeploy) and the GitHub App callback URL.

## 14. Backups and restore

`deploy/backup.sh` dumps the database from inside the postgres container (credentials never leave the container) to
`deploy/backups/` (owner-only, git-ignored) and deletes dumps older than 7 days (`BACKUP_KEEP_DAYS`).

```bash
crontab -e      # nightly at 03:15 UTC
15 3 * * * cd /home/ubuntu/Engiens/deploy && sh backup.sh >> backups/backup.log 2>&1
```

A backup on the same disk is not enough. **Copy dumps off the server** regularly, from your machine:

```bash
scp -i ~/Downloads/engiens-key.pem 'ubuntu@<ELASTIC_IP>:Engiens/deploy/backups/*.dump' ~/engiens-backups/
```

Optional: an **EBS snapshot** schedule (EC2 → Lifecycle Manager → policy for the `engiens` volume, keep e.g. 7).
Snapshots are billed per GiB-month (§1).

**Restore** (replaces the database's contents; take a fresh backup first if the current data matters):

```bash
dc stop backend
sh restore.sh backups/engiens-<timestamp>.dump --yes
dc up -d --wait backend
```

## 15. Updating and rolling back

**Update** (on the server; `dc` is the alias from §9, define it again in a new shell):

```bash
cd ~/Engiens && git checkout main && git pull && cd deploy
sh backup.sh                                              # always, and especially when the release adds a migration
sed -i "s/^ENGIENS_VERSION=.*/ENGIENS_VERSION=$(git rev-parse --short HEAD)/" .env
dc up -d --build --wait backend                           # Flyway migrates before the health check turns healthy
```

Then, from your own machine: `sh deploy/smoke-test.sh https://<API_HOST> https://<VERCEL_URL>`.

If a release changes `deploy/sandbox-images.sh`, run it before restarting the backend.

**Roll back** to the previous release (its image is still on the server, tagged with its git SHA):

```bash
cd ~/Engiens && git checkout <previous-sha> && cd deploy
sed -i "s/^ENGIENS_VERSION=.*/ENGIENS_VERSION=<previous-sha>/" .env
dc up -d --wait backend
```

Migrations only move forward. If the release you are leaving **added a migration**, restore the backup taken just
before that update (§14) as part of the rollback. Return to the branch afterwards with `git checkout main`.
Clean up old images now and then: `docker image ls engiens-backend` and `docker image rm engiens-backend:<old-sha>`.

**Frontend:** Vercel → Deployments → an earlier deployment → **Promote to Production**.

## 16. Local rehearsal (no AWS)

The same stack runs on a laptop with Docker, on high ports and with Caddy's own local certificate for `localhost`.
Use a separate project name so nothing clashes with development (`make db`, `:8080`, `:5173`):

```bash
cd deploy
# a throwaway env file outside the repository: API_HOST=localhost, HTTP_PORT=18080, HTTPS_PORT=18443,
# DOCKER_GID = the socket's group inside containers (0 on Docker Desktop), fresh random DB_PASSWORD and JWT_SECRET
docker compose -p engiens-rehearsal -f docker-compose.prod.yml --env-file /path/to/rehearsal.env up -d --build --wait
SMOKE_INSECURE=1 SMOKE_HTTP_PORT=18080 sh smoke-test.sh https://localhost:18443 https://<origin in the env file>
docker compose -p engiens-rehearsal -f docker-compose.prod.yml --env-file /path/to/rehearsal.env down -v   # removes its data
```

This rehearsal was run before this guide was written: migrations, HTTPS routing, Python/JavaScript/TypeScript/Java
Runs through the public API, the sandbox isolation probe, responsiveness during runs, restart persistence, backup and
restore, and port exposure all checked out.

## 17. Shutting down (stop all charges)

1. Take a final backup and copy it off the server (§14).
2. EC2 → Instances → `engiens` → **Terminate** (the root volume is deleted with it if "Delete on termination" was on;
   check EC2 → Volumes afterwards).
3. EC2 → Elastic IPs → **Release** the address (an unattached Elastic IP is still billed).
4. EC2 → Snapshots and Lifecycle Manager: delete snapshots/policies you created.
5. Check every region you might have used, then Billing → Bills a day later.
6. Vercel: delete or pause the project if it is no longer needed.

## 18. Security notes

- **The Docker socket is powerful:** whoever controls it controls the host. Only the backend gets it, and that container
  is read-only, non-root, `no-new-privileges`, memory-limited, and publishes no port. User code never sees it: sandbox
  containers have no mounts, no network, run as `nobody` on a read-only filesystem (`DockerSandboxExecutionProvider`).
- **Network:** only 80/443 (Caddy) and 22 (your IP) are reachable. PostgreSQL and the backend are on the compose
  network only; Docker has no TCP port.
- **Secrets:** only in `deploy/.env` (chmod 600) and your password manager. The backend never logs them; AI keys are
  redacted from provider errors.
- **Updates:** Ubuntu installs security updates automatically; reboot when `/var/run/reboot-required` exists (the
  stack restarts by itself). Update the application images through §15.
