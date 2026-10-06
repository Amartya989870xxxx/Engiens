#!/bin/sh
# Checks a deployed Engiens API from the outside, the way a browser reaches it. Run from your own machine after every
# deploy. Read-only: it creates nothing and needs no credentials.
#
#   sh deploy/smoke-test.sh https://<API_HOST> https://<frontend origin>
#   Local rehearsal only: SMOKE_INSECURE=1 accepts a self-signed certificate, SMOKE_HTTP_PORT=<port> is where plain
#   HTTP is published when it isn't port 80.
set -u
api="${1:?usage: sh deploy/smoke-test.sh https://<API_HOST> https://<frontend origin>}"; api="${api%/}"
origin="${2:?usage: sh deploy/smoke-test.sh https://<API_HOST> https://<frontend origin>}"
k=""; [ "${SMOKE_INSECURE:-0}" = 1 ] && k="-k"
failures=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1 (expected $3, got $2)"; failures=$((failures + 1)); fi; }
contains() { case "$2" in *"$3"*) echo "ok   $1" ;; *) echo "FAIL $1 (missing $3)"; failures=$((failures + 1)) ;; esac; }

check "health is UP" "$(curl -s $k "$api/actuator/health" | grep -o '"status":"UP"' | head -1)" '"status":"UP"'
host="${api#https://}"; host="${host%%/*}"; host="${host%:*}"
plain="http://$host${SMOKE_HTTP_PORT:+:$SMOKE_HTTP_PORT}"
check "plain HTTP redirects to HTTPS" "$(curl -s -o /dev/null -w '%{http_code}' "$plain/actuator/health")" "308"
headers="$(curl -s $k -D - -o /dev/null "$api/api/auth/me")"
contains "protected endpoint needs a login (401)" "$headers" " 401"
contains "HSTS header (HTTPS seen through the proxy)" "$(echo "$headers" | tr 'A-Z' 'a-z')" "strict-transport-security"
contains "nosniff header" "$(echo "$headers" | tr 'A-Z' 'a-z')" "x-content-type-options: nosniff"
check "401 body uses the API error shape" "$(curl -s $k "$api/api/auth/me" | grep -o '"code":"UNAUTHENTICATED"')" '"code":"UNAUTHENTICATED"'
check "only /actuator/health is public" "$(curl -s $k -o /dev/null -w '%{http_code}' "$api/actuator/env")" "401"
cors="$(curl -s $k -D - -o /dev/null -X OPTIONS "$api/api/auth/login" -H "Origin: $origin" \
  -H 'Access-Control-Request-Method: POST' -H 'Access-Control-Request-Headers: content-type' | tr 'A-Z' 'a-z')"
contains "CORS allows the frontend origin" "$cors" "access-control-allow-origin: $(echo "$origin" | tr 'A-Z' 'a-z')"
check "CORS refuses other origins" "$(curl -s $k -o /dev/null -w '%{http_code}' -X OPTIONS "$api/api/auth/login" \
  -H 'Origin: https://not-engiens.example' -H 'Access-Control-Request-Method: POST')" "403"

if [ "$failures" -eq 0 ]; then echo "all checks passed"; else echo "$failures check(s) failed"; exit 1; fi
