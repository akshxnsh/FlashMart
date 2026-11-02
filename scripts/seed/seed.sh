#!/usr/bin/env bash
# Seeds the FlashMart databases. Written, not run.
#
# Needs DB_USERNAME and DB_PASSWORD in the environment (never committed; see
# .env.example). Optional: DB_HOST (default localhost), DB_PORT (default 5432).
#
# Safe to run twice: it resets first, then every seed file upserts by id.
set -euo pipefail

: "${DB_USERNAME:?DB_USERNAME is not set}"
: "${DB_PASSWORD:?DB_PASSWORD is not set}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"

# Database names are the ones in each service's application.yml.
IDENTITY_DB="identity-service"
INVENTORY_DB="inventory-service"
ORDER_DB="order-service"
NOTIFICATION_DB="notification-service"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# psql reads the password from PGPASSWORD, so it never appears on a command line.
export PGPASSWORD="$DB_PASSWORD"

run() { # run <database> <file> [extra psql args]
  local db="$1" file="$2"
  shift 2
  echo "==> $file -> $db"
  psql -v ON_ERROR_STOP=1 -q -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -d "$db" -f "$HERE/$file" "$@"
}

# reset.sql switches between the inventory and order databases itself.
run "$INVENTORY_DB" reset.sql -v "inventory_db=$INVENTORY_DB" -v "order_db=$ORDER_DB"

run "$IDENTITY_DB" identity.sql
run "$INVENTORY_DB" inventory.sql
run "$NOTIFICATION_DB" notification.sql

echo "Seed finished."
