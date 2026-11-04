# Seed data (run and verified on 2026-11-04)

Seed scripts for local development and load tests. **They were run on
2026-11-04** against local Postgres and verified: counts after seeding were
identity 1|3, inventory 3|3|4|0, notification 1|3; liveness 1|t, 2|f, 3|f;
stock 1|500|500, 2|50|50, 3|100|100, 4|20|20. A second run changed nothing.
With stock set to 1, version set to 5 and one reservation inserted by hand, a
re-run restored full stock, version 0 and an empty `stock_reservations`. A
product inserted by hand got id 4 (the sequence is past the seeded ids);
`reset.sql` does not delete products, so it stayed and was removed by hand.
The load runs in `architecture.md` §8 used these scripts to reset and reseed before each run.

## Why there are several files

Each service owns its own database, so there is no single seed script. The
data below refers across databases by plain id (brand id, buyer id), with no
foreign key, exactly as the services do at runtime.

| File | Database | What it does |
|---|---|---|
| `identity.sql` | `identity-service` | 1 brand, 3 buyers |
| `inventory.sql` | `inventory-service` | 3 products, 3 sales, 4 sale items |
| `notification.sql` | `notification-service` | contact caches for the brand and buyers |
| `reset.sql` | `inventory-service` and `order-service` | between-run reset (see below) |
| `seed.sh` | all | runs reset, then the three seed files |

## How to run

1. Start each service once so Flyway creates its tables (the scripts do not
   create tables).
2. Set the shared credentials (the same pair the services use; never commit
   them):

   ```bash
   export DB_USERNAME=...
   export DB_PASSWORD=...
   ```

3. Run:

   ```bash
   bash scripts/seed/seed.sh
   ```

   Optional: `DB_HOST` (default `localhost`), `DB_PORT` (default `5432`).
   It needs `psql` on the PATH.

Running it twice is safe: it resets first, then every row is an upsert by id.

## Seeded ids

| What | Id | Notes |
|---|---|---|
| Brand | `1` | Zephyr Athletics, `sales@zephyr-athletics.test` |
| Buyers | `1`, `2`, `3` | Asha Verma, Rohan Mehta, Neha Kapoor (`*@buyers.test`) |
| Products | `1`, `2`, `3` | Runner Shoe, Court Tee, Trail Cap (all brand 1) |
| Sale `1` | LIVE | started 1 minute ago, ends in 24 hours |
| Sale `2` | UPCOMING | starts in 1 hour |
| Sale `3` | ENDED | ended 1 hour ago |
| Item `1` | sale 1, product 1 | 500 units, 4999.00 |
| Item `2` | sale 1, product 2 | 50 units, 799.00 |
| Item `3` | sale 2, product 3 | 100 units, 599.00 |
| Item `4` | sale 3, product 1 | 20 units, 4499.00 |

Buyer passwords are a placeholder that cannot log in. Real hashes come with
security on Day 8.

## Things to know

- **Windows drift.** The sale windows are `now() +/- interval` at the moment
  the script runs. The LIVE window is 24 hours from the last seed or reset;
  after that the LIVE sale is no longer live. The UPCOMING sale starts after an
  hour, so it stops being "upcoming" then too. Run `seed.sh` (or `reset.sql`)
  again before each test that needs the windows as described above.
- **No events.** Identity's seed writes no outbox rows, so no `BrandUpserted`
  or `UserUpserted` is published. `notification.sql` fills the contact caches
  by hand with a fixed old `updated_at`, so any real event beats it.
- **Reset** deletes all reservations, restores the three windows, sets
  `available_stock = total_stock` and `version = 0`, and deletes all rows in
  `refunds`, `order_items`, `outbox` and `orders`. It does not touch Identity
  or Notification. It deletes everything in those tables, not just seeded rows.
- **Explicit ids** do not advance a `BIGSERIAL`, so each script moves the
  sequence past the seeded ids. Rows created later get ids after them.

## What to check after running (for later)

```bash
export PGPASSWORD="$DB_PASSWORD"
q() { psql -h localhost -U "$DB_USERNAME" -d "$1" -Atc "$2"; }

q identity-service     "select (select count(*) from brands), (select count(*) from users)"   # 1|3
q inventory-service    "select (select count(*) from products), (select count(*) from flash_sales), (select count(*) from flash_sale_items), (select count(*) from stock_reservations)"  # 3|3|4|0
q notification-service "select (select count(*) from brand_contacts), (select count(*) from buyer_contacts)"  # 1|3

# Right after a seed or reset, the LIVE sale is live (for the next 24 hours);
# UPCOMING and ENDED are not (expect: 1|t, 2|f, 3|f)
q inventory-service "select id, (status <> 'CANCELLED' and start_time <= now() and now() < end_time) from flash_sales order by id"

# Stock is full (expect: 1|500|500, 2|50|50, 3|100|100, 4|20|20)
q inventory-service "select id, total_stock, available_stock from flash_sale_items order by id"
```

To check the reset: decrement a stock row by hand, insert a reservation, run
`seed.sh`, and confirm stock is full again and `stock_reservations` is empty.
Run `seed.sh` twice in a row and confirm the counts above do not change.
