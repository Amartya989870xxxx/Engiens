#!/bin/sh
# Restores a dump made by deploy/backup.sh into the production database on this server, REPLACING its contents.
# Stop the backend first so nothing writes during the restore, then start it again (deploy/README.md → "Restore").
#
#   sh deploy/restore.sh backups/engiens-<timestamp>.dump --yes
set -eu
dump="${1:?usage: sh deploy/restore.sh <dump file> --yes}"
[ "${2:-}" = "--yes" ] || { echo "This replaces the database's contents. Re-run with --yes to confirm." >&2; exit 2; }
[ -s "$dump" ] || { echo "not a non-empty file: $dump" >&2; exit 1; }
dump="$(cd "$(dirname "$dump")" && pwd)/$(basename "$dump")"
cd "$(dirname "$0")"
docker compose -f docker-compose.prod.yml --env-file "${ENV_FILE:-.env}" exec -T postgres \
  sh -c 'pg_restore --clean --if-exists --no-owner --single-transaction -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < "$dump"
echo "restored: $dump"
