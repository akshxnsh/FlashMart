#!/usr/bin/env bash
# Order-side checks after a PlaceOrders run (verify.sh is the Inventory side).
# Run it once the run has settled: payments answered, webhooks delivered.
#
# Needs DB_USERNAME and DB_PASSWORD in the environment (never committed).
# Optional: DB_HOST (default localhost), DB_PORT (default 5432),
#           FLASH_SALE_ID (default 1), PRODUCT_ID (default 1), ITEM_ID (default 1,
#           the flash_sale_items id of that product in that sale).
#
# Prints order counts by status for the sale, then exits non-zero unless ALL hold:
#   no buyer has two CREATED / PENDING_PAYMENT / PAID orders for the sale
#   no order for the sale is still CREATED (a stuck order)
#   units the live-or-paid orders hold for the product (PENDING_PAYMENT + PAID)
#     = units Inventory holds for the item (RESERVED + CONFIRMED)
# The last one compares two databases at one moment; run it when nothing is in
# flight, or a reservation and its order can be seen half-way.
# Must also run inside the payment window (6 min after the last order): after it,
# the expiry sweep cancels unpaid orders and Inventory releases their stock.
set -euo pipefail

: "${DB_USERNAME:?DB_USERNAME is not set}"
: "${DB_PASSWORD:?DB_PASSWORD is not set}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
FLASH_SALE_ID="${FLASH_SALE_ID:-1}"
PRODUCT_ID="${PRODUCT_ID:-1}"
ITEM_ID="${ITEM_ID:-1}"

export PGPASSWORD="$DB_PASSWORD"
q() { psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -d "$1" -At -v ON_ERROR_STOP=1 -c "$2"; }

echo "flash_sale_id=${FLASH_SALE_ID} product_id=${PRODUCT_ID} item_id=${ITEM_ID}"
echo "orders by status (whole sale):"
q order-service "select status, count(*) from orders where flash_sale_id = ${FLASH_SALE_ID} group by status order by status" \
  | sed 's/^/  /'

dup_buyers="$(q order-service "
select count(*) from (
  select buyer_id from orders
  where flash_sale_id = ${FLASH_SALE_ID} and status in ('CREATED', 'PENDING_PAYMENT', 'PAID')
  group by buyer_id having count(*) > 1) d")"
stuck="$(q order-service "select count(*) from orders where flash_sale_id = ${FLASH_SALE_ID} and status = 'CREATED'")"
order_units="$(q order-service "
select coalesce(sum(i.quantity), 0) from orders o join order_items i on i.order_id = o.id
where o.flash_sale_id = ${FLASH_SALE_ID} and i.product_id = ${PRODUCT_ID}
  and o.status in ('PENDING_PAYMENT', 'PAID')")"
held_units="$(q inventory-service "
select coalesce(sum(quantity), 0) from stock_reservations
where flash_sale_item_id = ${ITEM_ID} and status in ('RESERVED', 'CONFIRMED')")"

echo "buyers with more than one live-or-paid order: ${dup_buyers}"
echo "orders still CREATED: ${stuck}"
echo "units in PENDING_PAYMENT + PAID orders for product ${PRODUCT_ID}: ${order_units}"
echo "units RESERVED + CONFIRMED in Inventory for item ${ITEM_ID}: ${held_units}"

fail=0
if [ "$dup_buyers" -ne 0 ]; then echo "FAIL: ${dup_buyers} buyer(s) hold two live-or-paid orders"; fail=1; fi
if [ "$stuck" -ne 0 ]; then echo "FAIL: ${stuck} order(s) still CREATED"; fail=1; fi
if [ "$order_units" -ne "$held_units" ]; then
  echo "FAIL: orders hold ${order_units} units but Inventory holds ${held_units}"; fail=1
fi
if [ "$fail" -eq 0 ]; then echo "check-orders: OK"; fi
exit "$fail"
