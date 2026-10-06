#!/bin/sh
# Backs up the production PostgreSQL database on the EC2 server as a compressed custom-format dump. Run it on the server,
# nightly from cron (see deploy/README.md). pg_dump runs inside the postgres container and reads the credentials from that
# container's own environment, so nothing secret is printed or stored here. Dumps older than BACKUP_KEEP_DAYS (7) are
# deleted.
#
#   sh deploy/backup.sh
#
# Restore: deploy/restore.sh.
set -eu
cd "$(dirname "$0")"
mkdir -p backups
chmod 700 backups
file="backups/engiens-$(date -u +%Y%m%dT%H%M%SZ).dump"
docker compose -f docker-compose.prod.yml --env-file "${ENV_FILE:-.env}" exec -T postgres \
  sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --no-owner' > "$file.partial"
mv "$file.partial" "$file"
chmod 600 "$file"
find backups -name 'engiens-*.dump' -mtime +"${BACKUP_KEEP_DAYS:-7}" -delete
echo "backup written: $file ($(wc -c < "$file" | tr -d " ") bytes)"
