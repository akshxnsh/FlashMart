# FlashMart — Architecture

> Reflects the current design as of the latest entries in `docs/decisions.md`
> and the FlashMart Notion log. Supersedes the original PRD wherever the two
> conflict (see `CLAUDE.md`). This is a **design document** — see Section 7
> for what actually has code behind it.

## 1. System Overview

FlashMart is flash-sale infrastructure for brands (e.g. Puma, Nike) running
their own time-boxed, limited-stock sales — not a multi-vendor marketplace.
Brands manage their own products, pricing, and inventory directly; FlashMart
does not aggregate independent sellers and does not compute commission or
handle payouts (that scope was deliberately removed — see "Product vision
pivot" in `docs/decisions.md`). The core engineering problem the system is
built to solve is real concurrency and consistency under scarcity: many
buyers racing for a small, fixed pool of stock, with order/payment/inventory
state that must reach a consistent outcome without a distributed transaction.

## 2. Service Boundaries

Boundaries are drawn by traffic shape (hot/bursty vs. cold/low-volume) and
failure blast radius, not by domain noun or convention — see "Service
boundaries drawn by traffic shape + failure blast radius" in
`docs/decisions.md`.

| Service | Owns | Boundary justification |
|---|---|---|
| **API Gateway** | Nothing (no DB) | Stateless routing, JWT validation at the edge, coarse rate limiting. Kept dataless so it has no state to be a blast-radius source itself. |
| **Order Service** | `orders`, `order_items`, `saga_state`, `outbox` | Owns saga orchestration for the purchase flow. Hot/bursty path (every buy attempt), isolated from cold identity/notification traffic so an incident there can't starve checkout. |
| **Inventory Service** | `flash_sales`, `flash_sale_items`, `stock_reservations`, `products` | The hottest, most contended path in the system — thousands of buyers racing to decrement the same `flash_sale_items` row during a sale. Isolated so this contention can't lock-starve unrelated services. |
| **Identity Service** | `brands`, `api_keys`, `users` (buyers) | Brands and buyers are grouped together *not* because they're the same kind of entity, but because they share one access pattern: cold, low-volume signup/login, never on the hot checkout path. See "Identity Service" decision. |
| **Notification Service** | `notification_log`, `brand_contacts`, `buyer_contacts` | Cold, batch-tolerant consumer of purchase events; can lag without affecting checkout. Owns its own contact caches so it has no runtime dependency on Identity for the emails it sends. |
| **Razorpay Clone** (external) | Payment state | Built separately, called via OpenFeign. Not part of this codebase's service boundaries, but its 202-Accepted-then-webhook behavior directly shaped the Order state machine (Section 5). |

**No Settlement Service.** The original PRD's vendor-payout/commission engine
is permanently out of scope under the brand-platform model — there's no
platform-computed money movement to third parties to justify it.

Each service owns its own database; no service reads another's DB directly.
Cross-service references (e.g. `flash_sales.brand_id → brands.id`) are plain
values, not enforced FKs — referential integrity across services is an
application-level concern by design.

## 3. Communication Patterns

Chosen per-interaction based on whether the caller needs the result before
it can proceed — not defaulted to REST-everywhere or Kafka-everywhere. See
"Communication pattern per service interaction" in `docs/decisions.md`.

**Synchronous (OpenFeign):**
- Order Service → Inventory Service: `reserve(flash_sale_id, product_id, qty)`, once per unique item in the order (a loop, not a batch call — see Section 4). Order cannot proceed without the outcome.
- Order Service → Razorpay Clone: initiate payment. Response is 202 Accepted only — confirms receipt, not outcome — so Order Service sets `PENDING_PAYMENT`, never `PAID`, off this call.

**Asynchronous — inbound webhook:**
- Razorpay Clone → Order Service: payment result. Timing is outside Order Service's control, so it can't be a blocking call. Must be signature-verified before acting on it (FR-P2).

**Asynchronous — Kafka (each publisher owns an `outbox`, written in the same local transaction as the business change):**
- **Order Service** publishes `OrderCreated`, `PaymentConfirmed` (payload carries the full item list, plus `brand_id` and `buyer_id` for Notification's enrichment — never persisted as columns on `orders`).
- **Inventory Service** publishes `ReservationExpired` (one event per order, from the TTL sweep over `stock_reservations`).
- **Identity Service** publishes `BrandUpserted` and `UserUpserted` (on creation *and* email change — fires on creation specifically so a new brand/buyer isn't missing from Notification's contact cache).
- **Notification Service** publishes nothing and owns no outbox.

`StockReserved` was considered and dropped — reservation is already a
synchronous Feign call whose response Order Service uses directly, so no
consumer needs a duplicate event on the hottest write path.

Kafka's at-least-once delivery means every consumer must be an idempotent
consumer (check current state before applying a transition), not "exactly
once" — enforced via unique constraints (`notification_log`,
`stock_reservations`) plus the order state machine's terminal-state guard.

## 4. Data Model Per Service

### Order Service — finalized
```
orders(id, buyer_id, flash_sale_id, amount, status,
       idempotency_key, payment_ref, created_at, updated_at)
  -- flash_sale_id: order-level — one order = exactly one brand's sale
  -- amount: SUM(order_items.subtotal), snapshotted at creation
  -- idempotency_key: UNIQUE(buyer_id, idempotency_key)
  -- payment_ref: Razorpay Clone's payment reference, nullable until initiate succeeds

order_items(id, order_id, product_id, quantity, unit_price, subtotal)
  -- UNIQUE(order_id, product_id) — one line per product per order

saga_state(order_id, step, status, last_error, updated_at)
  -- status: IN_PROGRESS | SUCCESS | FAILED | COMPENSATING

outbox(id, aggregate_id, event_type, payload_json, published, created_at)
```

### Inventory Service — finalized
```
flash_sales(id, brand_id, name, start_time, end_time, status)
  -- status: SCHEDULED | LIVE | ENDED | CANCELLED (stored, not derived)
  -- OPEN: mechanism to move status SCHEDULED -> LIVE -> ENDED not yet designed

flash_sale_items(id, flash_sale_id, product_id, sale_price,
                  total_stock, available_stock, version)
  -- UNIQUE(flash_sale_id, product_id)
  -- total_stock fixed (reporting); available_stock is the decremented counter
  -- version: optimistic lock — this row is where concurrent decrement contention lives
  -- OPEN: whether optimistic locking alone is sufficient under heavy contention,
  --   vs. pessimistic locking or a Redis lock in front, is not yet decided/load-tested

stock_reservations(id, order_id, flash_sale_item_id, status, expires_at, ...)
  -- UNIQUE(order_id, flash_sale_item_id) — idempotent reserve on retry
  -- insert-only per buyer, no version column (no concurrent-write contention here)

products(...)
outbox(id, aggregate_id, event_type, payload_json, published, created_at)
```

### Identity Service — finalized
```
brands(id, name, email UNIQUE, password_hash, created_at, updated_at)
api_keys(id, brand_id, key_hash UNIQUE, status ACTIVE|REVOKED, created_at)
  -- separate from JWT session auth: long-lived machine credential vs short-lived human session
users(id, name, email UNIQUE, password_hash, created_at, updated_at)
outbox(id, aggregate_id, event_type, payload_json, published, created_at)
```

### Notification Service — finalized
```
notification_log(..., order_id, event_type, recipient_type, channel, status, sent_at)
  -- UNIQUE(order_id, event_type, recipient_type, channel) — makes at-least-once
  --   Kafka delivery idempotent at the consumer

brand_contacts(brand_id, email, updated_at)     -- fed by BrandUpserted
buyer_contacts(user_id, email, updated_at)      -- fed by UserUpserted
  -- OPEN (deliberately deferred): out-of-order upsert events could overwrite a
  --   newer email with a stale one; retry_count/next_retry_at for FAILED sends
  --   not yet designed
```

No service's schema currently has code behind it — see Section 7.

## 5. Order State Machine

States: `CREATED`, `PENDING_PAYMENT` (transient) → `PAID`, `PAYMENT_FAILED`,
`ORDER_EXPIRED`, `PAYMENT_UNAVAILABLE` (four terminal states).

- `CREATED → PENDING_PAYMENT`: gateway returns 202 Accepted.
- `CREATED → PAYMENT_UNAVAILABLE`: circuit breaker open, gateway call never completes.
- `PENDING_PAYMENT → PAID`: webhook reports payment success.
- `PENDING_PAYMENT → PAYMENT_FAILED`: webhook reports payment failure.
- `PENDING_PAYMENT → ORDER_EXPIRED`: TTL sweep (via Inventory's `ReservationExpired` event), buyer never paid in time.

`PAID` is terminal by design — Order Service never waits on Notification or
any other downstream consumer to acknowledge before considering the order
done, to preserve failure-independence between services. `CONFIRMED` and
`RESERVED` from the original PRD sketch were dropped: nothing in any
sequence diagram sets `CONFIRMED`, and `RESERVED` belongs to Inventory's
`stock_reservations`, not to an Order-level state.

Every terminal state has an idempotent guard against late-arriving events: a
late `FAILED`/`PAYMENT_UNAVAILABLE` webhook after any terminal state is a
no-op; a late `SUCCESS` webhook arriving after `ORDER_EXPIRED` triggers a
refund call to Razorpay Clone (via `orders.payment_ref`) as a compensating
side effect, without changing the order's status back.

## 6. Key Architectural Decisions

Full context, options considered, and trade-offs for each of these live in
`docs/decisions.md` — this section only names them and their consequence.

- **Saga (orchestrated), not 2PC** — Order/Inventory/Payment consistency without holding cross-service locks open across a slow external HTTP call.
- **TTL polling sweep, not Kafka delayed messages** — safety net for a payment webhook that never arrives.
- **All-or-nothing reservation via a sequential per-item Feign loop** — not a batch endpoint — for multi-item orders; keeps Inventory's API unaware of multi-item concepts entirely.
- **Optimistic-lock `version` on `flash_sale_items`, not `stock_reservations`** — the contended row is the shared stock counter, not a per-buyer insert-only row.
- **Every event-publishing service owns an `outbox`** — Order, Inventory, and Identity each have one, written in the same local transaction as the business change, to avoid the dual-write problem.
- **Idempotent consumers via unique constraints**, not application-level check-then-insert — closes races that check-then-insert can't.
- **Brand/buyer email delivered to Notification via local contact caches fed by Kafka events**, not a synchronous call to Identity and not carried as dead-weight data through Inventory/Order.

## 7. Current Status

**Designed** (schema, diagrams, and state machines agreed; no code yet):
- Everything in Sections 2–6 above, in full. This includes all five service
  schemas, the order state machine, all communication-pattern decisions, and
  every architectural decision in `docs/decisions.md`.
- Sequence diagrams for: purchase happy path, payment-failure compensation,
  reservation TTL expiry, circuit-breaker-open (confirmed present in the
  FlashMart Notion log, Section 3).
- Deferred/not-yet-drawn: outbound brand-webhook retry sequence, and
  partial-item-reservation-failure compensation sequence. No
  settlement/payout sequence exists (scope removed, not deferred).

**Implemented** (code exists): **None.** The repository currently contains
only `docs/decisions.md`, `docs/requirements.md`, and `CLAUDE.md` — no
service modules, entities, migrations, or build files were found anywhere
in the repo as of this writing.

**Tested:** None — there is no code to test yet.

**Load-tested / verified under failure:** None. The NFR-P1–P4 performance
targets (zero overselling under 5,000 concurrent buyers vs. 500 units, p99 <
500ms buy-endpoint latency, zero duplicate charges, 100% eventual webhook
delivery) remain unverified targets, per `docs/decisions.md`'s explicit
decision to document them as such rather than as met-by-design.

**Notion log cross-check:** the FlashMart Notion page's own section
headers for "API & Event Contracts," "JPA Entities & Repositories," "Feature
Implementation," "Infra Wiring," "Testing Per Feature," and "Load Testing &
Verification" are all present but empty. This agrees with the repo state —
no discrepancy between what Notion claims and what the code shows.

## 8. Known Open Items / Next Steps

Pulled directly from `docs/decisions.md`'s "Open Items" section — nothing
below is invented:

- Regenerate the four service ERDs from the issued prompts and re-check them against `docs/decisions.md`.
- Update the PRD: drop `StockReserved`; add `ReservationExpired`, `BrandUpserted`, `UserUpserted`; Identity Service replaces Brand Service; three outboxes.
- Define event payload shapes for `ReservationExpired`, `BrandUpserted`, `UserUpserted`, and the enriched `OrderCreated`/`PaymentConfirmed`.
- Finalize Inventory's `reserve`/`release` API contract, including returning `brand_id` and translating the reservation UNIQUE violation into a success response.
- Decide the locking strategy for the hot `flash_sale_items` row — optimistic alone, pessimistic, or a Redis lock in front — and load-test it, not assume it.
- Design the mechanism that moves `flash_sales.status` through `SCHEDULED → LIVE → ENDED`.
- Draw the partial-item-reservation-failure sequence diagram.
- Decide the TTL sweep interval.
- Design a webhook-never-arrives reconciliation path with Razorpay Clone.
- Deliberately deferred: out-of-order upsert events overwriting newer emails in the Notification contact caches; retry tracking (`retry_count`, `next_retry_at`) for `FAILED` notifications.

No PDF matching `FlashMart_Project_Plan_v2.pdf` (referenced in `CLAUDE.md`)
was found in this repository at the time of writing — this document was
built from `docs/decisions.md`, `docs/requirements.md`, `CLAUDE.md`, and the
FlashMart Notion engineering log only.
