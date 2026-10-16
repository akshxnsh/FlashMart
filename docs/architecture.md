# FlashMart — Architecture

> Current design per `docs/decisions.md` through Revision 3. Supersedes the
> PRD wherever they conflict (see `CLAUDE.md`). Design document — Section 8
> is what actually has code behind it: Inventory's four entities and Identity's
> `brands`, all on Flyway migrations and covered by persistence tests.

## 1. System Overview

FlashMart is flash-sale infrastructure for brands (e.g. Puma, Nike) running
their own time-boxed, limited-stock sales — not a multi-vendor marketplace.
Brands manage their own products, pricing, and inventory directly; no
settlement/payout engine exists or is planned. The core engineering problem:
many buyers racing for a small, fixed pool of stock, with order/payment/
inventory state that must reach a consistent outcome without a distributed
transaction.

## 2. Service Boundaries

Drawn by traffic shape (hot/bursty vs. cold/low-volume) and failure blast
radius, not by domain noun.

| Service | Owns | Why this boundary |
|---|---|---|
| **API Gateway** | Nothing (no DB) | Stateless routing, JWT at the edge, coarse rate limiting. |
| **Order Service** | `orders`, `order_items`, `refunds`, `outbox` | Sole authority on an order's fate (§6). Hot/bursty, isolated from cold traffic. |
| **Inventory Service** | `flash_sales`, `flash_sale_items`, `stock_reservations`, `products` | The hottest, most contended row in the system (`flash_sale_items`). Isolated so contention can't lock-starve unrelated services. |
| **Identity Service** | `brands`, `api_keys`, `users`, `outbox` | Brands and buyers share one access pattern — cold, low-volume auth — not because they're the same kind of entity. |
| **Notification Service** | `notification_log`, `brand_contacts`, `buyer_contacts` | Cold, batch-tolerant; owns its own contact caches so it has no runtime dependency on Identity. |
| **Razorpay Clone** (external) | Payment state | Separate project, called via OpenFeign. Not built yet (§9). |

No Settlement Service — permanently out of scope, no money movement to third
parties to justify one. Each service owns its own database; no service reads
another's directly. Cross-service references are plain values, never FKs (§5).

## 3. Communication Patterns

Chosen per-interaction by whether the caller needs the result before
proceeding.

**Synchronous (OpenFeign):**
- **Order → Inventory** — `POST /internal/flash-sale-items/reserve { orderId, flashSaleId, productId, quantity }`, once per item (loop, not batch). Returns `{ success, reservationId, salePrice, brandId, productName }` (money as a string). `200` for buyer-legitimate outcomes (`OUT_OF_STOCK`, `SALE_NOT_LIVE`); non-`200` means something upstream is wrong and should alert (`404 ITEM_NOT_FOUND`, `409 QUANTITY_MISMATCH`, `400 INVALID_QUANTITY`). **Evaluation order is contractual:** an existing reservation for `(orderId, item)` is checked and returned *before* the liveness/stock gates, so a retry arriving after the sale ends still succeeds instead of triggering a wrongful cancellation. Liveness is computed (§4), never read from a stored value. Internal only — never routable through the Gateway.
- **Order → Razorpay Clone** — initiate payment, with an idempotency key derived from the order ID. 202-only response; Order sets `PENDING_PAYMENT`, never `PAID`, off it.
- **Order → Razorpay Clone** — refund a captured payment, and a payment-status lookup (used both to check "already refunded?" and by the reconciliation sweep, §6). Must accept *our* idempotency key or order reference, not only the gateway's own payment ID.
- **Inventory → Order** — `GET /internal/orders/{orderId}/status` → `{ orderId, status }`, used by Inventory's stale-reservation reconciler. `PAID` → confirm; terminal failure → release; `CREATED`/`PENDING_PAYMENT` → do nothing **and alert** (an hours-old reservation on a live order means Order's sweep has died); `404`/unreachable → do nothing and alert, never guess. Safe without locking: every answer acted on is terminal and can't change after being read. Internal only. The reconciler scans a bounded number of rows per pass, so a systemic backlog (e.g. Kafka down for hours) can't flood Order.

> **Cross-project caveat:** Razorpay Clone is not built yet. Idempotency on
> `initiate` and `refund`, a payment-status lookup by our key, and payments and
> webhooks that carry our order id, are
> requirements placed on it — designed, not verified. Correctness never
> depends on them: the one-payment invariant (§6) and the already-refunded
> check catch the failure either way. Prevention and correction are
> deliberately independent.

**Asynchronous — inbound webhook:**
- **Razorpay Clone → Order** — payment result, signature-verified (FR-P2). Carries **our order id**, by which Order loads the row (never by `payment_ref`, which may be `NULL`; §6). Applies the one-payment invariant (§6); returns 200 only after its transaction commits, so a pre-commit crash is replayed by the gateway's own retry.

**Asynchronous — Kafka** (each publisher owns an `outbox`, same local
transaction as the business change):
- **Order** publishes `PaymentConfirmed { order_id, buyer_id, brand_id, items: [{product_id, product_name, quantity, unit_price, subtotal}], amount }` — consumed by **Inventory** (needs only `order_id`, to confirm reservations) and **Notification** (needs the rest, for the receipt).
- **Order** publishes `OrderCancelled { order_id, reason, buyer_id }`, `reason ∈ {RESERVATION_FAILED, PAYMENT_FAILED, PAYMENT_UNAVAILABLE, ORDER_EXPIRED}` — consumed by **Inventory** (releases every reservation for the order, any reason) and **Notification** (buyer only, and only for `PAYMENT_FAILED`/`ORDER_EXPIRED` — the two reasons determined after the buyer's request has closed; the other two are answered synchronously, and emailing them would give a deliberately cold service a bursty hot-path load).
- **Identity** publishes `BrandUpserted { brand_id, email, updated_at }` / `UserUpserted { user_id, email, updated_at }` on creation and email change. `updated_at` is the **source-side** timestamp — required for Notification's out-of-order-safe upsert (§4).
- **Inventory** and **Notification** publish nothing; Inventory is a pure consumer plus Feign responder.

Every event is a **flat, bare payload** — no envelope, no `event_version`, no
`event_id`. Adding a field is safe by default; the discipline is to only add
optional fields and rename rather than repurpose one. **Every monetary field
crosses as a JSON string** (`"3999.00"`, never a bare number) — applies to
every Feign response too, not just Kafka. Kafka is at-least-once; every
consumer is idempotent via unique constraints or a state-guarded write
(§4), never via a claimed exactly-once guarantee.

**Dropped from the PRD's original seven:** `StockReserved`, `OrderCreated`,
`PaymentInitiated`, `OrderConfirmed` — no consumer ever needed them, and any
could be re-added cleanly. `ReservationExpired`, `PaymentFailed` — absorbed
into `OrderCancelled`; re-adding either means re-splitting a death path that
was deliberately unified.

## 4. Data Model Per Service

### Order Service
```
orders(id, buyer_id, flash_sale_id, amount, status, idempotency_key,
       payment_ref, expires_at, failure_detail, payment_reconciled_at,
       created_at, updated_at)
  -- flash_sale_id: order-level — one order = exactly one brand's sale
  -- amount: SUM(order_items.subtotal), DECIMAL, stored (the price the buyer
  --   agreed to, frozen). NULL until the last item is priced; set once by a CAS
  --   guarded on status = CREATED; a test checks it equals SUM(subtotal)
  -- status: CREATED | PENDING_PAYMENT | PAID | PAYMENT_FAILED
  --         | ORDER_EXPIRED | PAYMENT_UNAVAILABLE | RESERVATION_FAILED
  --         (CHECK on the seven values)
  -- idempotency_key: UNIQUE(buyer_id, idempotency_key)
  -- payment_ref: NULL until initiate is saved; what the one-payment CAS matches on.
  --   NOT how a webhook finds its order — that is the order id (§3, §6).
  --   Partial UNIQUE (payment_ref) WHERE payment_ref IS NOT NULL: one payment
  --   never belongs to two orders (safety net, not a lookup path)
  -- expires_at: NOT NULL; means two things by status — bounds the reserve loop
  --   while CREATED, reset to the payment window on entering PENDING_PAYMENT.
  --   One column, because an order has exactly one live deadline. The move is
  --   ONE statement (status + payment_ref + expires_at), so no gap lets the
  --   sweep expire a healthy order. Written as now() + interval in SQL (DB clock)
  -- failure_detail: nullable, debug only — never drives state
  -- payment_reconciled_at: nullable; set once the reconciliation sweep has
  --   settled this order (DB clock). Bounds and idempotency-guards the sweep
  -- CHECK (status NOT IN ('PENDING_PAYMENT','PAID') OR amount IS NOT NULL)
  -- CHECK (amount IS NULL OR amount >= 0)
  -- created_at: DB default. updated_at: DB trigger (CAS writes are raw SQL and
  --   skip Hibernate's @UpdateTimestamp)
  -- index: (status, expires_at) expiry sweep; (status, payment_reconciled_at) reconciliation sweep

order_items(id, order_id, product_id, product_name, quantity, unit_price, subtotal)
  -- UNIQUE(order_id, product_id)
  -- all rows inserted up front; product_name/unit_price/subtotal (DECIMAL)
  --   stay NULL until that item's reserve() returns them
  -- product_name: snapshotted at reservation time, never resynced — a
  --   receipt shows what was bought, not the product's current name
  -- CHECK (quantity > 0); CHECK unit_price >= 0 AND subtotal >= 0
  -- CHECK product_name, unit_price, subtotal all NULL or all non-NULL
  -- CHECK subtotal = unit_price * quantity when filled

refunds(id, order_id, payment_ref, amount, status, attempts,
        next_retry_at, claimed_at, last_error, created_at, updated_at)
  -- a command outbox (only consumer is Order itself) — not an event outbox
  -- UNIQUE(payment_ref) — refunds are per payment, not per order
  -- status: PENDING | PROCESSING | SUCCEEDED | NEEDS_MANUAL_REVIEW
  -- amount: DECIMAL, from the webhook's reported amount; CHECK amount > 0
  -- CHECK on status (the four values); CHECK attempts >= 0
  -- claimed_at: claim lease + fence token for the worker's finishing write
  --   (DB clock, set inside the claim SQL; so is next_retry_at)
  -- created_at: DB default. updated_at: DB trigger
  -- index: (status, next_retry_at)

outbox(id, aggregate_id, event_type, payload_json, created_at,
       published_at, attempts, last_error)
  -- same shape as Identity's, built there first: published_at (NULL = unsent)
  --   instead of a boolean, attempts + last_error so one unpublishable row
  --   cannot stall the relay, JSONB payload, and a PARTIAL index on
  --   (created_at) WHERE published_at IS NULL. The relay claims rows with
  --   FOR UPDATE SKIP LOCKED
  -- CHECK event_type IN ('PaymentConfirmed','OrderCancelled') — Order's own
  --   vocabulary, as Identity's is pinned (V7); CHECK attempts >= 0
  -- created_at: DB default; published_at: DB clock, set in the UPDATE
```
All of the above guards ship in Order's first migration, not as a later
backfill (see decisions.md, "Order Service's guards ship in its first
migration"). The `updated_at` trigger function is Identity's `bump_updated_at`
body, created again in Order's own database.

### Inventory Service
```
flash_sales(id, brand_id, name, start_time, end_time, status)
  -- status: ACTIVE | CANCELLED only — SCHEDULED/LIVE/ENDED are computed, not
  --   stored, since only cancellation is a fact no timestamp implies
  -- start_time/end_time are TIMESTAMPTZ (Instant): one absolute moment fixed
  --   at write time. A zone-less TIMESTAMP compared against now() is read
  --   using the CONNECTION's TimeZone, so two pods could disagree about the
  --   same sale at the same instant — hence "now is the database's clock"
  --   alone was never sufficient; the column type has to agree too
  -- liveness = status != 'CANCELLED' AND start_time <= now < end_time
  --   half-open (inclusive start, exclusive end — SQL BETWEEN is wrong here);
  --   `now` is the DATABASE's clock, evaluated in the same statement as the
  --   stock decrement — app-side clocks would disagree at the boundary
  -- CANCELLED is pre-start only: a sale cannot be cancelled once live, so
  --   "cancelled sale with live orders" is unreachable, not unhandled
  -- everything about a sale (price, stock quantities, start_time, end_time,
  --   cancellation) is mutable pre-open and frozen after — a live sale always
  --   runs to end_time
  -- index: (start_time, end_time)

flash_sale_items(id, flash_sale_id, product_id, sale_price,
                 total_stock, available_stock, version)
  -- UNIQUE(flash_sale_id, product_id)
  -- sale_price: DECIMAL, immutable once the sale is live
  -- total_stock fixed (reporting); available_stock is the decremented counter
  -- version: optimistic lock — the contended row under concurrent decrement
  -- OPEN: optimistic alone vs. pessimistic vs. Redis lock — decide + load-test

stock_reservations(id, order_id, flash_sale_item_id, quantity, status, reserved_at)
  -- UNIQUE(order_id, flash_sale_item_id) — idempotent reserve on retry
  -- quantity: required by design — release (OrderCancelled carries no items)
  --   must know how many units to add back, and the replay check compares it
  --   to detect QUANTITY_MISMATCH
  -- insert-only per buyer, no version column (no write contention here)
  -- status: RESERVED -> CONFIRMED (on PaymentConfirmed) | RELEASED (on
  --   OrderCancelled). CONFIRMED is never released — a PAID order can't
  --   later produce an OrderCancelled
  -- no expires_at: Inventory holds no deadline of its own — reserved_at
  --   exists only for the reconciler, which asks Order rather than deciding
  -- index: (status, reserved_at) reconciler. No separate (order_id) index for
  --   confirm/release-all: UNIQUE(order_id, flash_sale_item_id) already leads
  --   with order_id, and an extra index only slows every reserve insert

products(...)
```
No `outbox` — Inventory publishes nothing.

### Identity Service
```
brands(id, name, email UNIQUE, password_hash, created_at, updated_at)
  -- email is stored trimmed + lowercased; CHECK (email = lower(btrim(email)))
  --   makes the UNIQUE case-insensitive, and a writer that forgets to
  --   normalise fails loudly instead of creating a duplicate account
  -- that normalisation CHECK alone is not enough: '' satisfies it (an empty
  --   string IS already lowercase and trimmed), so an empty email was
  --   insertable. Three more guards: length(email) > 0, an '@' with something
  --   on each side, and length(btrim(name)) > 0. The '@' check is a minimal
  --   sanity gate, deliberately NOT email validation — real checking is
  --   Bean Validation at the API plus a verification flow
  -- created_at/updated_at are TIMESTAMPTZ, generated by the database, never
  --   written by Java. A BEFORE UPDATE trigger (bump_updated_at) sets
  --   updated_at = GREATEST(clock_timestamp(), old updated_at + 1 microsecond)
  --   so it moves strictly forward on every change: Notification's
  --   conditional upsert (WHERE updated_at < EXCLUDED.updated_at) silently
  --   discards any event that looks older, so a lagging pod clock must never
  --   be able to stamp a newer change with an older time
api_keys(id, brand_id, key_hash UNIQUE, status ACTIVE|REVOKED, created_at,
         revoked_at)
  -- key_hash is a FAST, DETERMINISTIC hash (SHA-256), not bcrypt: the key is
  --   found with WHERE key_hash = ?, so the same key must always hash the
  --   same way. bcrypt salts randomly and the lookup would never match.
  --   Safe because API keys are long random values, unlike passwords
  -- CHECK (key_hash ~ '^[0-9a-f]{64}$') — 64 LOWERCASE hex chars. Postgres
  --   compares text exactly, so an uppercase hash is a different string: the
  --   key would never be found and UNIQUE would never see the duplicate, the
  --   same class of bug as the case-sensitive email. It also rejects a raw
  --   unhashed key and a bcrypt hash ('$2a$…', 60 chars)
  -- CHECK (status IN ('ACTIVE','REVOKED')); revoked rows are kept, not
  --   deleted — the row is the audit trail and keeps the hash reserved
  -- revoked_at: NULL while ACTIVE, set when revoked, tied together by
  --   CHECK ((ACTIVE AND revoked_at IS NULL) OR (REVOKED AND revoked_at IS
  --   NOT NULL)) so status and the timestamp can never disagree
  -- several ACTIVE keys per brand are allowed, so a key can be rotated
  --   (issue the new one, then revoke the old) without downtime
  -- no index on brand_id: listing a brand's keys is rare and this table is
  --   tiny; the hot path is the UNIQUE(key_hash) lookup
  -- AUTH MUST FILTER ON STATUS. The lookup is WHERE key_hash = ?, and the
  --   revoked row is still there, so `AND status = 'ACTIVE'` is what makes
  --   revocation mean anything. No constraint can enforce this — it is a
  --   query-shape rule, and it is the whole point of revocation
  -- separate from JWT session auth: long-lived machine credential vs short-lived session
users(id, name, email UNIQUE, password_hash, created_at, updated_at)
  -- same email/name guards and the same DB-generated, strictly increasing
  --   updated_at as brands (shared bump_updated_at trigger function, attached
  --   per table — Hibernate's validate does NOT check triggers, so a missing
  --   one fails silently and only a test catches it)
  -- UNIQUE(email) is per table, so one address may be both a brand and a
  --   buyer: two accounts, two password hashes, deliberately allowed (a brand
  --   employee may also shop). The consequence is that there can be no single
  --   login endpoint — brand and buyer login must be separate, since one
  --   address could match a row in either table
outbox(id, aggregate_id, event_type, payload_json, created_at,
       published_at, attempts, last_error)
  -- published_at REPLACES the `published` boolean: NULL means not yet sent.
  --   A boolean says whether a row went out but never when, so relay lag is
  --   invisible and the cleanup job has nothing to delete by age
  -- attempts + last_error: a row that can never be published (an oversized
  --   payload) would otherwise be retried forever, silently, holding up
  --   every row behind it. Same shape as refunds and notification_log
  -- payload_json is JSONB: Postgres rejects invalid JSON at write time
  -- index: PARTIAL on (created_at) WHERE published_at IS NULL, not over every
  --   row — published rows pile up forever and the relay never reads them, so
  --   the index stays tiny. Same shape applies to Order's outbox
  -- aggregate_id has no FK: it names a brand OR a user, told apart by
  --   event_type, so a query by aggregate_id alone is wrong
  -- CHECK (event_type IN ('BrandUpserted','UserUpserted')) — the whole
  --   interpretation of aggregate_id rests on event_type, so an unconstrained
  --   string made it uninterpretable on a typo AND produced an event no
  --   consumer recognises, both silently. NOT an aggregate_type column: that
  --   is derivable from event_type, and a second copy of derivable state is
  --   what saga_state was dropped for. Adding an event type now needs a
  --   migration, which keeps that decision explicit
  -- THE RELAY MUST CLAIM WITH `FOR UPDATE SKIP LOCKED`. Two relay instances
  --   is ordinary scaling, and without a claim both read the same unsent rows
  --   and both publish them. SKIP LOCKED makes the second step over rows the
  --   first already holds instead of waiting. A query-shape rule; no
  --   constraint can carry it
  -- At-least-once by construction: a relay that crashes between publishing
  --   and stamping published_at republishes on restart. Unavoidable without a
  --   distributed transaction, and why every consumer is idempotent
  -- Ordering is NOT guaranteed: ids are assigned before commit, so a later id
  --   can commit first. Nothing depends on order — the contact-cache upsert
  --   compares timestamps precisely so it does not have to
```

### Notification Service
```
notification_log(id, order_id, event_type, recipient_type, channel, status,
                 recipient_address, attempts, next_retry_at, sent_at, last_error)
  -- UNIQUE(order_id, event_type, recipient_type, channel) — idempotent
  --   consumer for at-least-once Kafka delivery. `reason` is deliberately NOT
  --   in the key: OrderCancelled can be published twice for one order (the
  --   death path and the late-reservation cleanup both do it), and the second
  --   must dedupe rather than send a second email
  -- recipient_address: the address ACTUALLY used, captured at send time. The
  --   contact caches hold only the current address, so without this the log
  --   cannot answer "where did this go?" once a buyer changes their email —
  --   exactly when the question is asked. A log records what happened, not
  --   what is now true
  -- last_error: why the last attempt failed. Without it "the mail server is
  --   down" and "I have no contact for this buyer yet" are indistinguishable,
  --   and they need different responses. Matches refunds and outbox
  -- CHECKs pin every vocabulary (event_type, recipient_type, channel, status),
  --   as on outbox.event_type: a typo'd recipient_type would log a buyer's
  --   receipt against the brand and defeat the unique key
  -- CHECK ((status='SENT') = (sent_at IS NOT NULL)) and CHECK (status<>'SENT'
  --   OR recipient_address IS NOT NULL) — the second is one-directional on
  --   purpose: a SENT row must say where it went, a FAILED row legitimately
  --   may not know. Same shape as api_keys.revoked_at
  -- index: PARTIAL on (next_retry_at) WHERE status='FAILED' — almost every row
  --   ends SENT, and the worker scans only failures, so it stays small
  -- a missing contact (PaymentConfirmed arriving before UserUpserted) is
  --   treated as an ordinary failed send: FAILED, last_error naming the cause,
  --   picked up by the existing retry. No separate state — accepted cost is
  --   that it burns one of the 5 attempts
  -- status: PENDING | SENT | FAILED | RETRYING. Capped backoff retry (5 attempts), then
  --   stays FAILED permanently — no escalation; a missed courtesy email
  --   needs nobody to act, unlike a stuck refund
  -- claim before send: UPDATE ... SET status='RETRYING' WHERE status='FAILED'
  --   (rows-affected checked) — a bare claim, not the refund worker's fenced
  --   claim+lease. Defends ordinary multi-instance scheduling (a normal-
  --   operation race), not crash recovery, which isn't worth building here

brand_contacts(brand_id, email, updated_at)     -- fed by BrandUpserted
buyer_contacts(user_id, email, updated_at)      -- fed by UserUpserted
  -- PK is the OTHER service's id, deliberately breaking the BIGSERIAL rule:
  --   there is exactly one row per brand, so the brand's id IS the key. A
  --   surrogate id would allow two rows per brand unless a UNIQUE were added
  -- two tables, not one with a type column: brand 7 and user 7 are different
  --   things, so one table would need (type, id) and would reintroduce
  --   outbox.aggregate_id's ambiguity
  -- updated_at is IDENTITY'S timestamp, carried on the event — not when this
  --   row was written
  -- upsert is conditional: ON CONFLICT ... WHERE updated_at < EXCLUDED.updated_at
  --   — correctness never depends on Kafka delivery order. The strict `<` is
  --   right ONLY because bump_updated_at makes updated_at strictly increasing
  --   per row: two real changes can never share a timestamp, so equal always
  --   means redelivery. Demonstrated: with the WHERE, a late stale event is
  --   ignored; without it, the old address overwrites the new one
  -- same email guards as brands/users (normalised, non-empty, an '@'):
  --   Notification is a separate database and cannot assume an event honoured
  --   the publisher's rules
  -- NOT ENFORCEABLE BY SCHEMA: the conditional upsert is a query, so nothing
  --   stops a future consumer writing an unconditional one. Tests pin the
  --   statement's behaviour, not the consumer's choice of statement
  -- DEPLOYMENT GAP: nothing re-sends BrandUpserted/UserUpserted for brands and
  --   buyers that existed before Notification was deployed, so their rows are
  --   simply absent and their first receipt fails. Needs a one-off backfill
  --   republish from Identity — no column fixes it, and falling back to a
  --   synchronous Identity lookup would undo the reason the cache exists
```

Code behind these schemas so far: all four Inventory tables and Identity's
`brands` — see §8 for exactly what is and is not built.

## 5. Cross-Service Relationships: IDs vs. Events

Four services, four databases — every relationship crossing a boundary is
either a plain ID or an event, never a foreign key (ordinary FKs still apply
*within* a service). A **cross-service ID** — `orders.buyer_id`,
`flash_sales.brand_id`, etc. — is a number with no constraint stopping it
from going stale, resolved only when code looks it up (usually a Feign call).
An **event** — `PaymentConfirmed`, `OrderCancelled`, `BrandUpserted` — is a
fact published once and consumed on the consumer's own schedule, no shared
column at all. The rule: an ID when the caller can't proceed without the
answer *now*; an event when the producer doesn't need, or can't wait for, a
reply. Every publisher (now Order and Identity) owns an outbox so the write
and the publish can never come apart.

**5.1 Real FKs (same service):** Identity: `brands`→`api_keys`. Inventory:
`flash_sales`→`flash_sale_items`, `products`→`flash_sale_items`,
`flash_sale_items`→`stock_reservations`. Order: `orders`→`order_items`,
`orders`→`refunds`, `orders`→`outbox`. Notification: none.

**5.2 Cross-service IDs:**

| Column | Points at |
|---|---|
| `orders.buyer_id` | Identity `users.id` |
| `orders.flash_sale_id` | Inventory `flash_sales.id` |
| `order_items.product_id` | Inventory `products.id` |
| `flash_sales.brand_id`, `products.brand_id` | Identity `brands.id` |
| `stock_reservations.order_id`, `notification_log.order_id` | Order `orders.id` |
| `brand_contacts.brand_id` | Identity `brands.id` |
| `buyer_contacts.user_id` | Identity `users.id` |

`orders.payment_ref` / `refunds.payment_ref` are external references to
Razorpay Clone, not FlashMart cross-service IDs.

**5.3 Event links:** `PaymentConfirmed` → Inventory (confirm) + Notification
(receipt). `OrderCancelled` → Inventory (release-all) + Notification (buyer
only, two reasons). `BrandUpserted`/`UserUpserted` → Notification (refresh
caches). Inventory publishes nothing; its only outbound call is the
synchronous status check in §3.

## 6. Order State Machine

`CREATED` and `PENDING_PAYMENT` (transient) → five terminal states: `PAID`,
`PAYMENT_FAILED`, `ORDER_EXPIRED`, `PAYMENT_UNAVAILABLE`, `RESERVATION_FAILED`.

- `CREATED → PENDING_PAYMENT` — every item reserved, `amount` set, gateway returned 202. Saves `payment_ref`, resets `expires_at` to the payment window.
- `CREATED → RESERVATION_FAILED` — an item was out of stock.
- `CREATED → PAYMENT_UNAVAILABLE` — circuit breaker open on initiate.
- `CREATED → ORDER_EXPIRED` — didn't finish the loop + initiate before its deadline.
- `PENDING_PAYMENT → PAID` — webhook success *for the payment this order holds*.
- `PENDING_PAYMENT → PAYMENT_FAILED` — webhook failure.
- `PENDING_PAYMENT → ORDER_EXPIRED` — never paid within the window.

**Every write to `orders.status` is a compare-and-set** — the request thread,
the expiry sweep, the webhook handler, and the circuit-breaker path each
issue a conditional update and check rows-affected, sharing one transaction
with any outbox row produced. This is how races resolve without
coordination: Order is the sole authority on an order's fate, so a race
between the sweep and an arriving payment is decided by one atomic update on
one row, and the loser knows it lost. Inventory never expires anything itself.

`PAID` is terminal by design — Order never waits on a downstream consumer.
`CONFIRMED`/`RESERVED` were dropped from the original PRD sketch (nothing
ever set them); `saga_state.COMPENSATING` was dropped on the same test
(nothing would ever move an order out of it) — `saga_state` itself no longer
exists.

**One invariant governs every late or duplicate payment:** *an order keeps
exactly one payment; every other captured payment is refunded.* For a
`SUCCESS` webhook carrying reference `X` (the handler first loads the order by
the **order id** the webhook carries; `payment_ref` is no lookup key, and is
partial-`UNIQUE` only as a safety net): CAS to `PAID` only if
`status='PENDING_PAYMENT' AND payment_ref=X`; if that fails and the order is
already `PAID` with `payment_ref=X`, it's a redelivery (no-op); otherwise
refund `X` (from the webhook, not `orders.payment_ref`, which may be `NULL`
if Order crashed before saving it). A late `FAILED` webhook needs no such
care — its CAS simply matches zero rows, and nothing was captured. Refunds
are never issued inline: the handler writes a `refunds` row in the same
transaction as its failed CAS, and a worker drains it.

**A payment whose webhook never arrives at all** is caught by a second
producer of `refunds` rows: a reconciliation sweep over unreconciled
terminal-*failure* orders, asking the gateway what exists for that order's
deterministic idempotency key (not `payment_ref`, `NULL` in exactly the crash
window most likely to lack a webhook). `payment_reconciled_at` bounds and
idempotency-guards the scan. `PAID` orders are never scanned — one accepted,
permanent exception: see §9.

## 7. Key Architectural Decisions

Full context and trade-offs live in `docs/decisions.md`.

- **Saga (orchestrated), not 2PC** — no cross-service locks held across a slow external call.
- **Order is the sole authority on an order's fate; every status write is a CAS** — races decided by one atomic update on one row, not by coordinating two services' clocks.
- **One order-death event, `OrderCancelled{reason}`** — replaces a separate expiry event and a synchronous `release()`; Inventory reacts identically to every reason. Compensation is asynchronous — nothing waits on a release completing.
- **`saga_state` dropped** — orchestration state lives on `orders`; a second copy would silently diverge from the four independent writers of `orders.status`.
- **Scheduled polling sweep** for a payment webhook that never arrives — the mechanism survived Revision 3, the owning service (Order, not Inventory) changed.
- **Webhook-never-arrives is closed by a reconciliation sweep** querying the gateway by idempotency key, not `payment_ref` — a second, independent producer of `refunds` rows.
- **All-or-nothing reservation, sequential per-item Feign loop** — not a batch endpoint; Inventory stays unaware of multi-item orders.
- **`reserve`'s evaluation order is contractual** — an existing reservation is returned before the liveness/stock gates, so a post-deadline retry can't wrongly cancel an order whose stock is already held.
- **Optimistic-lock `version` on `flash_sale_items`, not `stock_reservations`** — the contended row is the shared counter, not a per-buyer insert-only row. The column and `@Version` exist; **how `reserve` writes the decrement is still open** — `WHERE version = ?` versus a value-based `WHERE available_stock >= ? AND <liveness>` behave very differently under READ COMMITTED, and two decision entries specify them differently (§9).
- **Sale terms are immutable once live** — price, stock quantities (`total_stock`), `start_time`/`end_time`, and cancellation all share one boundary: mutable pre-open, frozen after. `available_stock` moves only through reserve/release/confirm, never through an edit. A live sale always runs to its end; there is no emergency stop.
- **Liveness is computed, never stored** — `status` shrinks to `ACTIVE|CANCELLED`; the drift problem is removed, not solved, by asking the two timestamps fresh every time, against the database's clock.
- **An order keeps exactly one payment** — one CAS-enforced invariant, replacing an enumerated guard that had a hole.
- **Refunds through a command-outbox table**, not Kafka — the only consumer is Order itself.
- **Notification notifies only for the two asynchronous failure reasons** — the buyer is already told synchronously for the other two, and emailing all of them would give a cold service a hot-path load spike.
- **Every event is a flat payload; money always crosses as a string** — no envelope, no version field, no `event_id`; discipline (add, never repurpose) does the job a version number would.
- **Out-of-order `BrandUpserted`/`UserUpserted` is handled by a timestamp-conditional upsert**, not by requiring Kafka ordering — correctness never depends on delivery order.
- **Notification's own retry is minimal by design** — capped backoff, a bare claim against concurrent instances, no fencing, no escalation; a missed email isn't a stuck refund.
- **The contact caches are keyed by the other service's id, and their conditional upsert's strict `<` rests on that trigger** — equal timestamps are skipped, which is correct only because two real changes can never share one. The upsert is a *query*, so no constraint can force a future consumer to use it, and rows are absent entirely for brands that predate Notification's deployment until a backfill republish (§9).
- **`brands`/`users` `updated_at` is database-generated and strictly increasing** — a `BEFORE UPDATE` trigger stamps `GREATEST(clock_timestamp(), old + 1 µs)`, because Notification's conditional upsert silently discards any event that looks older, so a lagging pod clock must never stamp a newer change with an older time. Emails are stored trimmed and lowercased with a CHECK, making `UNIQUE(email)` case-insensitive.
- **Every time column is `TIMESTAMPTZ`/`Instant`, enforced by a guard test** — a zone-less `TIMESTAMP` compared against `now()` is read using the connection's time zone, so one sale read as open from one zone and closed from another. `NoZoneLessTimestampsTest` in every service fails, naming the column, if one appears.
- **Who stamps a time column depends on who compares it** — Java clock for audit-only columns (`products`); database default with `insertable = false` where the database's `now()` is compared against it (`stock_reservations.reserved_at`); a strictly-increasing trigger where another service orders events by it (`brands`/`users.updated_at`).
- **`CHECK` constraints are the last line of defence, each documented against the guard that comes first** — the ceiling `available_stock <= total_stock` matters more than the floor because only application logic stands before it. A `CHECK` is never removed for "duplicating" application logic.
- **Entity and migration conventions** — `IDENTITY` ids; migrations never edited once applied or pushed; same-service FKs real in the database but plain `Long` in Java; enums as `EnumType.STRING` plus a `CHECK`; chosen constraint names; a database-filled column must be `insertable = false` or Hibernate-generated, because Hibernate sends an explicit `NULL` that overrides a `DEFAULT`; `validate` cannot see triggers, defaults or `CHECK`s.
- **Entity tests use real Postgres, roll back, and must be shown to fail** — each guard was verified by breaking what it protects. No `reserve`, so nothing is load-tested or verified under concurrency.
- **Service scaffolding** — fixed ports 8081–8084 (8080 reserved for the Gateway); one shared credential pair against one Postgres instance but a separate database per service; Kafka, Feign, Resilience4j, Security and Validation deferred to their phases.
- **Every event-publishing service owns an outbox** — now Order and Identity only.
- **Cross-service relationships are always an ID or an event, never a foreign key** — §5.
- **Deadlines and leases are derived from the timeouts they race, never picked** — the `CREATED` deadline and refund claim lease stay open until real Feign/Resilience4j config exists.
- **Outbound brand webhooks dropped, no replacement** — a pre-Revision-3 leftover with no FR behind it; would have been a third implementation of the signed-delivery-with-retry pattern already built twice (inbound payment webhook, refund worker), for a need nothing establishes.

## 8. Current Status

**Designed:** everything in §2–7, in full — all five schemas, the state
machine, every communication pattern, every decision in `docs/decisions.md`
through Revision 3.

**Implemented:**
- **Inventory Service** — all four entities (`Product`, `FlashSale`,
  `FlashSaleItem`, `StockReservation`) on Flyway migrations V1–V7. Every time
  column is `TIMESTAMPTZ`, read as `Instant` (V6 converted the original
  zone-less `TIMESTAMP`; see §9). V7 backfilled the window-ordering and
  non-blank-name `CHECK`s, and `StockReservation.reservedAt` carries
  `@Generated` so the saved instance reads the database's value back.
- **Identity Service** — complete: `Brand`, `User`, `ApiKey` and
  `OutboxEvent` on migrations V1–V7, including the `bump_updated_at` trigger
  attached to both identity tables.
- **Notification Service** — complete: `NotificationLog`, `BrandContact` and
  `BuyerContact` on migrations V1–V2.
- **Order Service** — scaffolded only: a Spring Boot project, own database,
  fixed port, empty `db/migration/`. No entities. **Schema design is closed**
  (§4, plus the two decisions on webhook lookup and on guards and clocks); the
  entities and first migration are the next piece.
- **Not built anywhere:** repositories, services, controllers, the API
  Gateway, Kafka, Redis, security. All four services share one local Postgres
  credential pair but each owns a separate database.

**Tested:** 22 entity and time-zone tests in Inventory, 45 in Identity and 27 in
Notification (24, 47 and 29 including a context test and the zone-less-timestamp
guard that every service carries; Order has just those two). Counted from the
`@Test` methods in the repo, not from a run in this session, each saving
into real Postgres inside a rolled-back transaction; every CHECK, unique key
and FK is verified to reject bad rows, not merely to exist. The tests can
fail: weakening `reserved_at`'s mapping and the `updated_at` trigger each
broke exactly the tests meant to catch it. **Not load-tested, not verified
under concurrency or failure** — no `reserve` exists yet. NFR-P1–P4 remain
unverified targets by explicit decision, not by omission.

**Ports:** identity `8081`, inventory `8082`, order `8083`, notification
`8084`; `8080` is reserved for the API Gateway.

**Diagrams and ERDs: current.** All sequence diagrams (including the two that
were pre-Revision-3 and the two that never existed — reconciliation sweep,
partial-item-failure) and all four ERDs now live in the Notion FlashMart page
as Mermaid blocks, matching the current design. `docs/Flashmart.prd`
(Revision 4) also matches, with one known gap: §6.5 undercounts Razorpay
Clone's requirements (see §9).

**Notion log cross-check:** its "JPA Entities & Repositories" section (§7)
now records every entity written so far, and §9 records the project setup.
Its "API & Event Contracts", "Feature Implementation", "Testing Per Feature"
and "Load Testing & Verification" sections remain present but empty,
consistent with the repo — none of that exists yet. Its design sections
(§1–5) are current as of this update; they are a personal engineering log,
not themselves a second source of truth — `docs/decisions.md` remains
authoritative on conflict.

## 9. Known Open Items / Next Steps

Mirrors `docs/decisions.md`. Nothing below needs more design thinking —
each is blocked on something that doesn't exist yet, or is mechanical work.

- **Accepted, permanently.** A second captured payment can escape correction
  entirely if the initiate idempotency key ever fails to dedupe *and* a
  webhook is separately lost — in the worse of two shapes, the order reaches
  `PAID` and the sweep never examines it again, since it assumes a `PAID`
  order's one payment is applied by definition. Requires three independent
  rare failures to stack; the cost of building "list every payment for this
  order" plus scanning `PAID` orders is judged not worth it against that
  likelihood. This is the one place in the design that leans on prevention
  rather than independent correction.
- Settle the two derived deadlines (`CREATED`, refund claim lease), the
  Order sweep interval, and the payment window — blocked on real
  Feign/Resilience4j config by design.
- Decide the locking strategy for `flash_sale_items` — optimistic alone,
  pessimistic, or Redis — and load-test it. Paired with it: the transaction
  isolation level on the same write path, undecided until now.
- **Inventory's time columns are `TIMESTAMPTZ` — done (V6), recorded here
  because it constrains `reserve`.** `flash_sales.start_time`/`end_time`,
  `stock_reservations.reserved_at` and `products`' audit columns were zone-less
  `TIMESTAMP`. Comparing one against `now()` (a `TIMESTAMPTZ`) makes Postgres
  fill the gap from the *connection's* `TimeZone` setting, so the same row at
  the same instant gave different liveness answers on two connections — a
  sale starting `10:00` read as open in `Asia/Kolkata` and not open in `UTC`.
  That was the two-clocks problem the DB-clock rule exists to remove,
  reintroduced through the column type. Any new time column must be
  `TIMESTAMPTZ`; never compare a zone-less column against `now()`.
- **Settle how `reserve` writes the decrement.** Two decision entries specify
  it incompatibly: `WHERE id = ? AND version = ?` versus one statement
  checking `available_stock` and liveness against the database clock. Under
  READ COMMITTED a value-based clause re-checks fresh stock after a blocked
  UPDATE unblocks and needs no retry; a `version = ?` clause fails after any
  concurrent write. A native `UPDATE` also does not bump `version`, so mixing
  it with an entity save can silently clobber a decrement. Must be settled
  before `reserve` is built; recorded, not decided.
- Enforce "frozen once the sale opens" (price, `total_stock`, times,
  cancellation) in the service layer, against the database clock, in the same
  transaction as the write. A CHECK cannot express it. Not built.
- Identity, for the API phase: a lost signup race must surface as "email
  already taken", not a 500; the JWT must say whether its subject is a brand
  or a user (brand 7 and user 7 share an id); published `outbox` rows need a
  cleanup job.
- Build Razorpay Clone with the three behaviours this design leans on:
  idempotency on `initiate` and `refund`, and a payment-status lookup
  queryable by our idempotency key or order reference. Not built yet;
  designed-not-verified until it is.

The PRD is `docs/Flashmart.prd` (Revision 4, markdown). The earlier
`FlashMart_Project_Plan_v3.pdf` reference was stale — no such file exists or
ever existed in this repository.
