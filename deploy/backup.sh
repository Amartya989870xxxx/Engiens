#!/bin/sh
# Backs up the production database to a local file, from your own machine. Railway's managed PostgreSQL is reached
# through its public URL (Postgres service → Variables → DATABASE_PUBLIC_URL). pg_dump runs in the same PostgreSQL 17
# image as production, so nothing needs installing locally except Docker.
#
#   DATABASE_URL='<paste DATABASE_PUBLIC_URL>' sh deploy/backup.sh
#   (prefix the command with a space so it isn't saved in shell history; the URL contains the password)
#
# Restore into a database (replaces its contents):
#   docker run --rm -i -e DATABASE_URL postgres:17 sh -c 'pg_restore --clean --if-exists --no-owner -d "$DATABASE_URL"' < <file>.dump
set -eu
: "${DATABASE_URL:?set DATABASE_URL to the public connection URL of the database}"
cd "$(dirname "$0")"
mkdir -p backups
chmod 700 backups
file="backups/engiens-$(date -u +%Y%m%dT%H%M%SZ).dump"
docker run --rm -e DATABASE_URL postgres:17@sha256:d74eeac9a635390a49bc21bd49fccd973de707e2a53a76ac49b552b8712ec46f \
  sh -c 'pg_dump --format=custom --no-owner "$DATABASE_URL"' > "$file.partial"
mv "$file.partial" "$file"
chmod 600 "$file"
echo "backup written: $file ($(wc -c < "$file") bytes)"
