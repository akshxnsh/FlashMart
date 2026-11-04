#!/usr/bin/env bash
# Checks the inventory database after a load run, for flash_sale_items id 1.
#
# Needs DB_USERNAME and DB_PASSWORD in the environment (never committed).
# Optional: DB_HOST (default localhost), DB_PORT (default 5432), ITEM_ID (default 1).
#
# Prints the item's stock and its reservations, then exits non-zero unless ALL hold:
#   available_stock >= 0
#   sum(reserved quantity) <= total_stock
#   available_stock = total_stock - sum(reserved quantity)
set -euo pipefail

: "${DB_USERNAME:?DB_USERNAME is not set}"
: "${DB_PASSWORD:?DB_PASSWORD is not set}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
ITEM_ID="${ITEM_ID:-1}"
INVENTORY_DB="inventory-service"

export PGPASSWORD="$DB_PASSWORD"

# One row: total_stock|available_stock|version|reservations|reserved_qty
row="$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -d "$INVENTORY_DB" -At -v ON_ERROR_STOP=1 -c "
select i.total_stock, i.available_stock, i.version,
       (select count(*) from stock_reservations r where r.flash_sale_item_id = i.id),
       (select coalesce(sum(r.quantity), 0) from stock_reservations r where r.flash_sale_item_id = i.id)
from flash_sale_items i where i.id = ${ITEM_ID}")"

if [ -z "$row" ]; then
  echo "verify: flash_sale_items id ${ITEM_ID} not found" >&2
  exit 2
fi

IFS='|' read -r total available version res_count res_qty <<<"$row"

echo "item_id=${ITEM_ID}"
echo "total_stock=${total}"
echo "available_stock=${available}"
echo "version=${version}"
echo "stock_reservations count=${res_count} sum(quantity)=${res_qty}"

fail=0
if [ "$available" -lt 0 ]; then
  echo "FAIL: available_stock < 0 (oversold)"; fail=1
fi
if [ "$res_qty" -gt "$total" ]; then
  echo "FAIL: sum(quantity) ${res_qty} > total_stock ${total} (oversold)"; fail=1
fi
if [ "$available" -ne $((total - res_qty)) ]; then
  echo "FAIL: available_stock ${available} != total_stock ${total} - sum(quantity) ${res_qty}"; fail=1
fi

if [ "$fail" -eq 0 ]; then
  echo "verify: OK"
fi
exit "$fail"
