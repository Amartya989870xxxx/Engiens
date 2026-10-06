#!/bin/sh
# Backs up the production PostgreSQL database as a compressed custom-format dump. Nothing secret is printed or stored.
#
# EC2 (default): run on the server, from cron (see deploy/README.md). pg_dump runs inside the postgres container and
# reads the credentials from that container's own environment. Dumps older than BACKUP_KEEP_DAYS (7) are deleted.
#   sh deploy/backup.sh
#
# Railway fallback: run on your own machine against the database's public URL (Postgres service → Variables →
# DATABASE_PUBLIC_URL). Prefix the command with a space so the URL (it contains the password) isn't saved in history.
#    DATABASE_URL='<paste DATABASE_PUBLIC_URL>' sh deploy/backup.sh
#
# Restore: deploy/restore.sh.
set -eu
cd "$(dirname "$0")"
mkdir -p backups
chmod 700 backups
file="backups/engiens-$(date -u +%Y%m%dT%H%M%SZ).dump"
if [ -n "${DATABASE_URL:-}" ]; then
  docker run --rm -e DATABASE_URL postgres:17@sha256:d74eeac9a635390a49bc21bd49fccd973de707e2a53a76ac49b552b8712ec46f \
    sh -c 'pg_dump --format=custom --no-owner "$DATABASE_URL"' > "$file.partial"
else
  docker compose -f docker-compose.prod.yml --env-file "${ENV_FILE:-.env}" exec -T postgres \
    sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --no-owner' > "$file.partial"
  find backups -name 'engiens-*.dump' -mtime +"${BACKUP_KEEP_DAYS:-7}" -delete
fi
mv "$file.partial" "$file"
chmod 600 "$file"
echo "backup written: $file ($(wc -c < "$file" | tr -d " ") bytes)"
