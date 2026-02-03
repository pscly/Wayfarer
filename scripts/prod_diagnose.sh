#!/bin/sh
# Production diagnosis helper for Wayfarer.
#
# Usage:
#   sh scripts/prod_diagnose.sh
#   TRACE_ID=... sh scripts/prod_diagnose.sh
#   DO_UPGRADE=1 sh scripts/prod_diagnose.sh
#
# Notes:
# - POSIX sh compatible.
# - Does NOT print environment variables to avoid leaking secrets.

set -eu

now_utc() {
  # ISO-8601 UTC timestamp
  date -u '+%Y-%m-%dT%H:%M:%SZ'
}

get_hostname() {
  if command -v hostname >/dev/null 2>&1; then
    hostname
  else
    uname -n
  fi
}

say() {
  # Print a log line with timestamp.
  printf '%s %s\n' "$(now_utc)" "$*"
}

hr() {
  printf '%s\n' '------------------------------------------------------------'
}

need_cmd() {
  cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    say "ERROR: required command not found: $cmd"
    return 1
  fi
  return 0
}

find_backend_container_id() {
  # Best-effort: pick a running container that looks like the backend.
  # We avoid assuming an exact name; match both "wayfarer" and "backend".
  docker ps --format '{{.ID}} {{.Names}}' \
    | (grep -i 'wayfarer' || true) \
    | (grep -i 'backend' || true) \
    | awk 'NR==1{print $1}'
}

print_header() {
  hr
  say "prod_diagnose start"
  say "hostname: $(get_hostname)"
  hr
}

print_docker_ps() {
  hr
  say "docker ps (filtered for wayfarer)"
  # Use grep with || true so no-match doesn't fail under set -e.
  docker ps --format '{{.ID}}\t{{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}' \
    | (grep -i 'wayfarer' || true)
}

grep_logs_for_trace_id() {
  # Only prints matching log lines (does not dump full logs).
  if [ "${TRACE_ID:-}" = "" ]; then
    hr
    say "TRACE_ID not set; skipping log grep"
    return 0
  fi

  hr
  say "TRACE_ID set; grepping backend logs (value redacted)"

  backend_id="$(find_backend_container_id || true)"
  if [ "${backend_id}" = "" ]; then
    say "WARN: could not identify backend container; grepping all wayfarer containers"
    # Grep logs across all running containers that look like wayfarer.
    docker ps --format '{{.ID}} {{.Names}}' \
      | (grep -i 'wayfarer' || true) \
      | awk '{print $1}' \
      | while IFS= read -r cid; do
          [ "${cid}" = "" ] && continue
          say "docker logs --tail 5000 ${cid} | grep TRACE_ID"
          # Print only matching lines; keep command errors non-fatal.
          docker logs --tail 5000 "$cid" 2>&1 | grep -F "$TRACE_ID" || true
        done
    return 0
  fi

  say "docker logs --tail 5000 ${backend_id} | grep TRACE_ID"
  docker logs --tail 5000 "$backend_id" 2>&1 | grep -F "$TRACE_ID" || true
}

run_alembic_checks() {
  hr
  say "alembic status (via uv)"

  backend_id="$(find_backend_container_id || true)"
  if [ "${backend_id}" = "" ]; then
    say "WARN: backend container not found; cannot run uv/alembic inside container"
    return 0
  fi

  say "docker exec ${backend_id}: uv run alembic current"
  docker exec "$backend_id" sh -lc 'uv run alembic current'

  if [ "${DO_UPGRADE:-}" = "1" ]; then
    say "DO_UPGRADE=1; running: uv run alembic upgrade head"
    docker exec "$backend_id" sh -lc 'uv run alembic upgrade head'
  else
    say "DO_UPGRADE not 1; skipping upgrade"
  fi
}

curl_health_endpoints() {
  hr
  say "curl /healthz and /readyz on 127.0.0.1:18000"

  if ! command -v curl >/dev/null 2>&1; then
    say "WARN: curl not found; skipping HTTP checks"
    return 0
  fi

  # Keep output useful for evidence: include headers (-i) and fail on network errors.
  # Use a short timeout so this script does not hang.
  say "GET /healthz"
  curl -i -sS --max-time 5 'http://127.0.0.1:18000/healthz' || true
  hr
  say "GET /readyz"
  curl -i -sS --max-time 5 'http://127.0.0.1:18000/readyz' || true
}

main() {
  print_header

  need_cmd docker
  print_docker_ps
  grep_logs_for_trace_id
  run_alembic_checks
  curl_health_endpoints

  hr
  say "prod_diagnose done"
}

main "$@"
