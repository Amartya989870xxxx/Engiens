#!/bin/sh
# Nightly PostgreSQL backup: a compressed custom-format dump, kept for 7 days. Run from cron (see deploy/README.md).
# Credentials are read inside the postgres container from its own environment; nothing secret is printed or stored here.
#
# Restore (stops nothing; replaces the database's contents):
#   docker compose -f docker-compose.prod.yml --env-file .env exec -T postgres \
#     sh -c 'pg_restore --clean --if-exists -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < backups/<file>.dump
set -eu
cd "$(dirname "$0")"
env_file="${ENV_FILE:-.env}"
mkdir -p backups
chmod 700 backups
file="backups/engiens-$(date -u +%Y%m%dT%H%M%SZ).dump"
docker compose -f docker-compose.prod.yml --env-file "$env_file" exec -T postgres \
  sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --no-owner' > "$file.partial"
mv "$file.partial" "$file"
chmod 600 "$file"
find backups -name 'engiens-*.dump' -mtime +7 -delete
echo "backup written: $file ($(wc -c < "$file") bytes)"
