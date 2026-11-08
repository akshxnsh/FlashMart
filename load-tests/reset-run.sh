#!/usr/bin/env bash
# Reset before every load run: re-seeds the databases, then PROVES Order's
# tables are empty and stock is full. Refuses to touch anything but local Postgres.
#
# Why: a leftover PAID order makes the same test buyer's next order a
# 409 ORDER_ALREADY_OPEN (one live-or-paid order per buyer per sale), which would
# taint the next run's status counts.
#
# The deletes themselves are in scripts/seed/reset.sql (run by seed.sh), which
# already empties refunds, order_items, outbox and orders. This script does not
# repeat them; it adds the guard before and the check after.
#
# Needs DB_USERNAME and DB_PASSWORD. DB_HOST must be local (default localhost).
set -euo pipefail

: "${DB_USERNAME:?DB_USERNAME is not set}"
: "${DB_PASSWORD:?DB_PASSWORD is not set}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

case "$DB_HOST" in
  localhost|127.0.0.1|::1) ;;
  *) echo "reset-run: REFUSED, DB_HOST=$DB_HOST is not local" >&2; exit 2 ;;
esac

export PGPASSWORD="$DB_PASSWORD"
q() { psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -d "$1" -At -v ON_ERROR_STOP=1 -c "$2"; }

# The server must say it is the order-service database on a loopback (or socket) address.
who="$(q order-service "select current_database(), coalesce(host(inet_server_addr()), 'local-socket')")"
case "$who" in
  "order-service|127.0.0.1"|"order-service|::1"|"order-service|local-socket") ;;
  *) echo "reset-run: REFUSED, connected to '$who', not the local order-service database" >&2; exit 2 ;;
esac

DB_HOST="$DB_HOST" DB_PORT="$DB_PORT" bash "$HERE/../scripts/seed/seed.sh" > /dev/null

left="$(q order-service "select (select count(*) from orders), (select count(*) from order_items), (select count(*) from outbox), (select count(*) from refunds)")"
stock="$(q inventory-service "select (select count(*) from stock_reservations), (select count(*) from flash_sale_items where available_stock <> total_stock)")"
echo "reset-run: order tables orders|order_items|outbox|refunds = $left; reservations|items-not-full = $stock"
if [ "$left" != "0|0|0|0" ] || [ "$stock" != "0|0" ]; then
  echo "reset-run: FAIL, not clean" >&2; exit 1
fi
echo "reset-run: OK"
