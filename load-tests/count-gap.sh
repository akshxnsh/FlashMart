#!/usr/bin/env bash
# Counts the "Version B" late-reservation gap after a load run (decisions.md,
# "Day 7", point 5): Inventory still holds a RESERVED row for an order that
# Order has already ended in a failure state. Nothing will ever release that
# stock (the republish cannot see it; the reconciler is not built).
#
# Run it AFTER the Kafka compensation has drained (Order's outbox fully
# published and Inventory's consumer caught up), or in-flight releases are
# counted as gaps.
#
# Two databases, no cross-database join: the RESERVED order ids are read from
# Inventory and looked up in Order by id.
#
# Needs DB_USERNAME and DB_PASSWORD. Optional: DB_HOST, DB_PORT.
# Prints the RESERVED rows grouped by the order's status in Order
# ("MISSING" = no such order). Exits 1 if any gap row exists.
set -euo pipefail

: "${DB_USERNAME:?DB_USERNAME is not set}"
: "${DB_PASSWORD:?DB_PASSWORD is not set}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
export PGPASSWORD="$DB_PASSWORD"
q() { psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -d "$1" -At -v ON_ERROR_STOP=1 -c "$2"; }

ids="$(q inventory-service "select coalesce(string_agg(order_id::text, ',' order by order_id), '') from stock_reservations where status = 'RESERVED'")"
echo "inventory RESERVED rows: $( [ -z "$ids" ] && echo 0 || echo "$ids" | tr ',' '\n' | wc -l | tr -d ' ')"
if [ -z "$ids" ]; then
  echo "count-gap: gap rows (RESERVED, order in a failure state) = 0"
  exit 0
fi

by_status="$(q order-service "
select coalesce(o.status, 'MISSING'), count(*)
from unnest(array[${ids}]::bigint[]) as r(order_id)
left join orders o on o.id = r.order_id
group by 1 order by 1")"
echo "RESERVED rows by the order's status in Order:"
echo "$by_status" | sed 's/^/  /'

gap="$(echo "$by_status" | awk -F'|' '$1 ~ /^(RESERVATION_FAILED|PAYMENT_UNAVAILABLE|ORDER_EXPIRED|PAYMENT_FAILED)$/ {s += $2} END {print s + 0}')"
pending="$(echo "$by_status" | awk -F'|' '$1 == "PENDING_PAYMENT" {s += $2} END {print s + 0}')"
echo "count-gap: gap rows (RESERVED, order in a failure state) = $gap; still PENDING_PAYMENT = $pending"
[ "$gap" -eq 0 ]
