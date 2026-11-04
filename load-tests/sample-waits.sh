#!/usr/bin/env bash
# Samples what the active Postgres sessions of the inventory database are
# waiting on, about every 500 ms, until you stop it (Ctrl-C or kill).
#
# Each sample appends one line per (wait_event_type, wait_event) group:
#   <timestamp>|<wait_event_type>|<wait_event>|<count>
# An empty wait (shown as "none") means the session is active and not waiting
# on anything Postgres tracks, i.e. it is running.
#
# Needs DB_USERNAME and DB_PASSWORD in the environment (never committed).
# Optional: DB_HOST (default localhost), DB_PORT (default 5432),
#           OUT (default load-tests/target/waits.log).
#
# Limits: each sample opens a new psql connection, so the real gap between
# samples is 500 ms plus the connect and query time, and a wait shorter than
# that can be missed. The sampler's own session is excluded.
set -euo pipefail

: "${DB_USERNAME:?DB_USERNAME is not set}"
: "${DB_PASSWORD:?DB_PASSWORD is not set}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${OUT:-$HERE/target/waits.log}"
mkdir -p "$(dirname "$OUT")"

# Database name comes from Inventory's own datasource URL, not a copy of it.
YML="$HERE/../inventory-service/src/main/resources/application.yml"
DB_NAME="$(sed -n 's#.*jdbc:postgresql://[^/]*/\([^?[:space:]]*\).*#\1#p' "$YML" | head -n 1)"
: "${DB_NAME:?could not read the database name from $YML}"

export PGPASSWORD="$DB_PASSWORD"

echo "sampling datname=${DB_NAME} every 500 ms into ${OUT}" >&2
while true; do
  ts="$(date +%H:%M:%S.%3N)"
  rows="$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -d "$DB_NAME" -At -F '|' -c "
    select coalesce(wait_event_type, 'none'), coalesce(wait_event, 'none'), count(*)
    from pg_stat_activity
    where datname = '${DB_NAME}' and state = 'active' and pid <> pg_backend_pid()
    group by 1, 2" 2>/dev/null)" || rows="sample_failed|sample_failed|0"
  # An empty answer means no session was active at that instant. Write a marker
  # line so the number of samples taken stays countable (zero lines would look
  # the same as "the sampler did not run").
  [ -n "$rows" ] || rows="no_active_session|no_active_session|0"
  printf '%s\n' "$rows" | sed "s/^/${ts}|/" >> "$OUT"
  sleep 0.5
done
