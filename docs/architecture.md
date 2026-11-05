# FlashMart — Architecture

> Current design per every entry in `docs/decisions.md` (Revision 3 and the
> implementation-phase entries after it). Supersedes the PRD wherever they
> conflict (see `CLAUDE.md`). Design document — Section 8 is what actually has
> code behind it: every service's entities on Flyway migrations, covered by
> persistence tests, plus Inventory's API contract layer (DTOs and the reserve
> HTTP endpoint) and Inventory's `reserve` logic, implemented, tested
> single-threaded, and raced in a contention test (at most 50 threads, one
> JVM; see §8). **First laptop load runs only (see §8); the 5,000-buyer / p99 target is not reached.**

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
- **Order → Inventory** — `POST /internal/flash-sale-items/reserve { orderId, flashSaleId, productId, quantity }`, once per item (loop, not batch). The idempotency key is `(orderId, flashSaleId, productId)`; `quantity` is deliberately not part of it. `quantity` is 1..5 per item (`MAX_QUANTITY_PER_ITEM`, enforced at the API only, no `CHECK`; decisions.md, "Per-item quantity cap of 5"). Every `200` carries `outcome` ∈ `RESERVED | OUT_OF_STOCK | SALE_NOT_LIVE | RESERVATION_RELEASED | ITEM_NOT_IN_SALE`. `ITEM_NOT_IN_SALE` (no sale item for `(flashSaleId, productId)`) is an outcome, not an error: Order has no way to check it in advance (it cannot read Inventory's database and no endpoint exists), so a buyer or bot can trigger it at will, and Order answers it with `RESERVATION_FAILED`. `reservationId`, `salePrice`, `brandId` and `productName` are present **only** when `outcome = RESERVED` and are omitted (not `null`) otherwise. `salePrice` is a string with exactly two decimals in plain notation (`"3999.00"`). There is no `success` and no "replayed" flag: a replay looks exactly like a first call. Business outcomes are always `200`, because a non-2xx could trip the circuit breaker in front of Inventory during a drop. Non-`200` means something upstream is wrong and should alert, with body `{ code, message }` (`message` is for logs only): `400 INVALID_QUANTITY` (any problem with `quantity`, including a fractional one or one above 5; Jackson's float-to-int truncation is turned off), `INVALID_REQUEST` (any other malformed field or body, with `400`; or a body that is not JSON, with `415`), `409 QUANTITY_MISMATCH`. The `415` is mapped because the controller-scoped handler can see it: the mapping declares no `consumes`, so the controller is chosen first and the `415` is raised while reading the body. One exception answers with **Spring's default body, not `{ code, message }`**: `405` (wrong method), which is raised before any controller is chosen, so the scoped handler cannot see it. Accepted: a global handler would leak reserve's codes onto future controllers, and a `405` means a broken caller, not a buyer outcome. **Evaluation order is contractual:** an existing reservation for `(orderId, item)` is checked and returned *before* the liveness/stock gates, so a retry arriving after the sale ends still succeeds instead of triggering a wrongful cancellation. Liveness is computed (§4), never read from a stored value. Internal only — never routable through the Gateway.

  *Implementation rules — implemented in `ReservationServiceImpl` / `ReservationTransaction`, tested single-threaded and raced in a contention test (see §8); first laptop load runs only (see §8); the 5,000-buyer / p99 target is not reached:*
  - **Replay path, in this order:** find the existing reservation for `(orderId, item)` in **any** status → compare `quantity` (different → `409 QUANTITY_MISMATCH`) → if `RELEASED`, return `200 RESERVATION_RELEASED` and never fall through to a new reservation → otherwise return the stored reservation as `RESERVED`. A replay skips the liveness and stock gates.
  - **`ITEM_NOT_IN_SALE` is logged:** Inventory logs a WARN each time it returns this outcome, so a spike (a bot, or an Order bug sending the wrong sale id) is visible. A plain log line until the observability phase brings metrics.
  - **Concurrent first calls:** when two first calls for the same `(orderId, item)` race, the loser must end on the replay path, never a `500`, and its stock change must not survive. The unique violation rolls the loser back before it touches stock, and `ReservationServiceImpl` re-reads in a fresh transaction (decided in decisions.md, "Reserve write path").
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
- **Inventory** and **Notification** publish nothing. Inventory consumes events and answers Feign calls; its only outbound call is the status check above.

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
  --   guarded on status = CREATED; a test checks it equals SUM(subtotal).
  --   Computed once from SUM(order_items.subtotal) after the last item is
  --   priced, never accumulated item by item (recorded, not built)
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
  --   sweep expire a healthy order. V2 says "written as now() + interval"; superseded
  --   (designed, not built): clock_timestamp() + duration, DB-stamped by a native
  --   INSERT ... RETURNING id (decisions.md, "Order creation: retries, conflicts,
  --   deadlines and Inventory failures")
  -- failure_detail: nullable, debug only — never drives state
  -- payment_reconciled_at: nullable; set once the reconciliation sweep has
  --   settled this order (DB clock). Bounds and idempotency-guards the sweep
  -- CHECK (status NOT IN ('PENDING_PAYMENT','PAID') OR amount IS NOT NULL)
  -- CHECK (amount IS NULL OR amount >= 0)
  -- created_at: DB default. updated_at: DB trigger (CAS writes are raw SQL and
  --   skip Hibernate's @UpdateTimestamp)
  -- index: (status, expires_at) expiry sweep; (status, payment_reconciled_at) reconciliation sweep
  -- V7 (hardening, from an independent static review of V1-V6, Oct 29):
  --   CHECK (status NOT IN ('PENDING_PAYMENT','PAID') OR payment_ref IS NOT NULL)
  --   (ck_orders_payment_ref_present_when_payable: a status written without the
  --   ref makes an order no webhook can ever pay); non-blank CHECKs on
  --   idempotency_key and on payment_ref (NULL stays allowed there);
  --   amount widened to NUMERIC(14,2). V7's header also corrects two V2
  --   comments: the partial payment_ref index IS distinguishable from a total
  --   one by structure (pg_indexes shows the predicate; only behaviour is
  --   identical), and the "7x the size at 90% NULL" figure has no script in
  --   the repo, so it is an expectation, not a measurement
  -- partial UNIQUE (buyer_id, flash_sale_id) WHERE status IN ('CREATED',
  --   'PENDING_PAYMENT','PAID') (uk_orders_buyer_sale_live_or_paid, V6): a buyer
  --   has at most one live-or-paid order per sale. The four failure statuses are
  --   outside it, so a failed buyer can try again. In the database, not in code,
  --   because two tabs submitted together both pass a code check. Not "PAID only":
  --   a buyer could open many orders and hold stock for the whole payment window,
  --   and a second payment would be taken before the unique rule fired
  --   (decisions.md, "One live-or-paid order per buyer per sale")

order_items(id, order_id, product_id, product_name, quantity, unit_price, subtotal)
  -- UNIQUE(order_id, product_id)
  -- all rows inserted up front; product_name/unit_price/subtotal (DECIMAL)
  --   stay NULL until that item's reserve() returns them
  -- product_name: snapshotted at reservation time, never resynced — a
  --   receipt shows what was bought, not the product's current name
  -- CHECK (quantity > 0); CHECK unit_price >= 0 AND subtotal >= 0
  -- CHECK product_name, unit_price, subtotal all NULL or all non-NULL
  -- CHECK subtotal = unit_price * quantity when filled
  -- subtotal is NUMERIC(14,2) (V7), unit_price stays (12,2): a subtotal can
  --   exceed the price's range
  -- RECORDED, NOT BUILT: product_name/unit_price/subtotal are written once —
  --   the pricing UPDATE carries WHERE unit_price IS NULL, on top of the
  --   orders.status = 'CREATED' guard
  -- RECORDED, NOT BUILT: recovery was abort (decisions.md, "saga_state is
  --   dropped"); amended 2026-11-05: abort by expiry OR resume on a retried
  --   create while CREATED ("Order creation: retries, conflicts, deadlines and
  --   Inventory failures"). The resume runs only over rows WHERE unit_price IS NULL

refunds(id, order_id, payment_ref, amount, status, attempts,
        next_retry_at, claimed_at, last_error, created_at, updated_at)
  -- a command outbox (only consumer is Order itself) — not an event outbox
  -- UNIQUE(payment_ref) — refunds are per payment, not per order
  -- status: PENDING | PROCESSING | SUCCEEDED | NEEDS_MANUAL_REVIEW
  -- amount: NUMERIC(14,2) (V7), from the webhook's reported amount; CHECK amount > 0
  -- CHECK payment_ref non-blank (V7, ck_refunds_payment_ref_present)
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
  -- sale_price: DECIMAL, immutable once the sale is live. The API refuses any
  --   third decimal, even a zero ("3999.000"): Postgres would silently round
  --   it on insert (3999.005::numeric(12,2) = 3999.01, measured)
  -- total_stock fixed (reporting); available_stock is the decremented counter
  -- version: optimistic lock — the contended row under concurrent decrement
  -- DECIDED (implemented; tested single-threaded and in a contention test of at most 50 threads; first laptop load runs only (see §8); the 5,000-buyer / p99 target is not reached): see decisions.md, "Reserve write path: READ COMMITTED, one atomic conditional UPDATE, one short transaction"

stock_reservations(id, order_id, flash_sale_item_id, quantity, status, reserved_at)
  -- UNIQUE(order_id, flash_sale_item_id) — idempotent reserve on retry
  -- quantity: required by design — release (OrderCancelled carries no items)
  --   must know how many units to add back, and the replay check compares it
  --   to detect QUANTITY_MISMATCH. The API caps it at 5 per item (§3); the
  --   cap is deliberately NOT a CHECK, since it is policy that may change —
  --   only CHECK (quantity > 0) is in the schema
  -- insert-only per buyer, no version column (no write contention here)
  -- status: RESERVED -> CONFIRMED (on PaymentConfirmed) | RELEASED (on
  --   OrderCancelled). CONFIRMED is never released — a PAID order can't
  --   later produce an OrderCancelled
  -- no expires_at: Inventory holds no deadline of its own — reserved_at
  --   exists only for the reconciler, which asks Order rather than deciding
  -- index: (status, reserved_at) reconciler. No separate (order_id) index for
  --   confirm/release-all: UNIQUE(order_id, flash_sale_item_id) already leads
  --   with order_id, and an extra index only slows every reserve insert

products(id, brand_id, name, created_at, updated_at)
  -- UNIQUE(brand_id, name); CHECK (length(btrim(name)) > 0) — the name is
  --   snapshotted onto receipts, so a blank one would survive into a
  --   permanent record
  -- created_at/updated_at: TIMESTAMPTZ, Java clock (@CreationTimestamp /
  --   @UpdateTimestamp) — audit-only, nothing compares them
  -- a product's name is frozen while a live sale references it: reserve
  --   returns productName on every replay, so a rename between a first call
  --   and its retry would give one reservation two names. Same reason a live
  --   sale's price is frozen. Service-layer rule, not built (§9)
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

Code behind these schemas so far: every table above, as entities on Flyway
migrations, in all four services — see §8 for exactly what is and is not built.

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
- `CREATED → RESERVATION_FAILED` — an item came back `OUT_OF_STOCK`, `SALE_NOT_LIVE` or `ITEM_NOT_IN_SALE` (decided; no new state, no migration). A state exists only if something downstream reacts differently to it, and every reserve "no" gets the same reaction: release everything, send no email, tell the buyer in the reply. Which outcome it was goes in `failure_detail` (debug only) and in the buyer's reply. An Inventory timeout or refusal mid-loop also ends as `RESERVATION_FAILED` (`503 INVENTORY_UNAVAILABLE`, cause in `failure_detail`). On `RESERVATION_RELEASED` Order stops without a CAS or event and reports the real status. Both decided 2026-11-05, designed not built (§9).
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
- **`reserve` answers every business outcome with `200` and an `outcome` enum** — `RESERVED | OUT_OF_STOCK | SALE_NOT_LIVE | RESERVATION_RELEASED | ITEM_NOT_IN_SALE`; non-`200` (`{ code, message }`) only for a broken caller. A non-2xx for a business outcome could trip the circuit breaker in front of Inventory during a drop (reasoned, unverified until the Feign/Resilience4j config exists).
- **An item not in the sale is an outcome, not a `404`** — Order has no way to check it in advance, so a client can trigger it at will; a WARN log per occurrence keeps a spike visible.
- **Per-item quantity cap of 5, at the API only** — a business rule that may change, so no `CHECK`; Order must check the same cap before calling `reserve`.
- **Optimistic-lock `version` on `flash_sale_items`, not `stock_reservations`** — the contended row is the shared counter, not a per-buyer insert-only row. The column and `@Version` exist; **the decrement is decided: one atomic value-based conditional `UPDATE`** (`WHERE available_stock >= ? AND <liveness>`, with `version = version + 1`) under READ COMMITTED; the `WHERE version = ?` form is rejected as the reserve path (decisions.md, "Reserve write path").
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
- **Entity tests use real Postgres, roll back, and must be shown to fail** — each guard was verified by breaking what it protects. The reserve HTTP contract is tested in a web slice with the service mocked, through the application's real Jackson and validator configuration. `reserve` is implemented, tested single-threaded and raced in a contention test (see §8); first laptop load runs only (see §8); the 5,000-buyer / p99 target is not reached.
- **Service scaffolding** — fixed ports 8081–8084 (8080 reserved for the Gateway); one shared credential pair against one Postgres instance but a separate database per service; Kafka, Feign, Resilience4j and Security deferred to their phases; Validation added to Inventory in its API phase, and each sibling adds it in its own.
- **Every event-publishing service owns an outbox** — now Order and Identity only.
- **Cross-service relationships are always an ID or an event, never a foreign key** — §5.
- **Deadlines and leases are derived from the timeouts they race, never picked** — the `CREATED` deadline and refund claim lease stay open until real Feign/Resilience4j config exists.
- **Outbound brand webhooks dropped, no replacement** — a pre-Revision-3 leftover with no FR behind it; would have been a third implementation of the signed-delivery-with-retry pattern already built twice (inbound payment webhook, refund worker), for a need nothing establishes.

## 8. Current Status

**Designed:** everything in §2–7, in full — all five schemas, the state
machine, every communication pattern, every decision in `docs/decisions.md`
(Revision 3 and the implementation-phase entries after it).

**Implemented:**
- **Inventory Service** — all four entities (`Product`, `FlashSale`,
  `FlashSaleItem`, `StockReservation`) on Flyway migrations V1–V7. Every time
  column is `TIMESTAMPTZ`, read as `Instant` (V6 converted the original
  zone-less `TIMESTAMP`; see §9). V7 backfilled the window-ordering and
  non-blank-name `CHECK`s, and `StockReservation.reservedAt` carries
  `@Generated` so the saved instance reads the database's value back.
  **API contract layer:** request/response DTOs as records for reserve,
  products, flash sales and sale items, with Bean Validation
  (`spring-boot-starter-validation`, Inventory only, so far); the reserve
  controller; the `ReservationService` interface; and
  an exception handler scoped to the reserve controller. `application.yml`
  turns off Jackson's float-to-int truncation. No public controllers (they
  wait for security), no browse endpoint. `quantity` is capped at 5 per item
  at the API.
  **`reserve` logic: implemented, tested single-threaded, and raced in a
  contention test (see "Inventory contention tests" below); load-measured
  on one laptop only (see "First load run" and "Second load run" below).**
  `NotImplementedReservationService` is deleted.
  `ReservationServiceImpl` (not transactional) calls `ReservationTransaction`
  (one short `@Transactional`: resolve item, replay check, insert the
  reservation, then one native conditional `UPDATE` last: stock guard,
  liveness on the database clock, `version = version + 1`). A 0-row `UPDATE`
  throws the unchecked `StockNotTaken`, which rolls back the inserted
  reservation; the service then answers `SALE_NOT_LIVE` or `OUT_OF_STOCK`.
  The catch of a unique violation on the insert, with a fresh-transaction
  re-read, is **exercised**: it ran in 100 of 100 rounds of the
  same-order race (below), and its two branches are unit-tested with mocks.
  Service tests commit real data
  (no rolled-back test transaction) and clean up by id. Break-and-restore done
  on the stock guard (`>=`; its boundary `>` is killed by the
  stock-equals-quantity test), the liveness predicate, and the `version` bump.
  With the guard removed, the table's `CHECK available_stock >= 0` is what
  fires, not a clean `OUT_OF_STOCK`.
- **Identity Service** — entity layer complete: `Brand`, `User`, `ApiKey` and
  `OutboxEvent` on migrations V1–V7, including the `bump_updated_at` trigger
  attached to both identity tables.
- **Notification Service** — entity layer complete: `NotificationLog`, `BrandContact` and
  `BuyerContact` on migrations V1–V2.
- **Order Service** — all four tables (`orders`, `order_items`, `refunds`,
  `outbox`) on migrations V1–V7, with `Order`, `OrderItem`, `Refund`,
  `OutboxEvent` and the two status enums. Every guard in §4 shipped in this
  first migration rather than as a backfill, including the partial
  `UNIQUE (payment_ref) WHERE payment_ref IS NOT NULL`, the seven-state
  `orders.status` `CHECK`, `expires_at NOT NULL` with no `DEFAULT`, the
  `order_items` all-or-none pricing and `subtotal = unit_price * quantity`
  guards, and an `outbox.event_type` `CHECK` pinned to Order's two events.
  `bump_updated_at` is recreated in Order's own database and attached to
  `orders` and `refunds`. V6 adds the partial unique index
  `uk_orders_buyer_sale_live_or_paid` (one `CREATED`/`PENDING_PAYMENT`/`PAID`
  order per buyer per sale); the database rule exists and is tested, but nothing
  yet maps its violation to a `409` (§9). V7 (hardening from an independent
  static review) adds `ck_orders_payment_ref_present_when_payable`, three
  non-blank guards (`orders.idempotency_key`, `orders.payment_ref`,
  `refunds.payment_ref`) and widens `orders.amount`, `order_items.subtotal` and
  `refunds.amount` to `NUMERIC(14,2)`; implemented and tested. **No services or controllers** (the only repository is
  the Day-1 `OrderRepository`, below), so nothing in the running application yet
  writes a CAS. **A1:** the CAS-owned columns of `Order`
  (`status`, `amount`, `payment_ref`, `expires_at`, `payment_reconciled_at`) and
  `Refund` (`status`, `attempts`, `next_retry_at`, `claimed_at`, `last_error`) are
  `updatable = false`, as are the set-once columns `Order.buyer_id`,
  `flash_sale_id`, `idempotency_key` and `Refund.order_id`, `payment_ref`,
  `amount`; `@DynamicUpdate` was removed (it protected nothing). Only
  `Order.failure_detail` is writable by `save()`; `Refund` has no writable
  column. Implemented and **run on 2026-11-03**; break-and-restore done: 5 of
  the 6 original guards were killed, `@DynamicUpdate` was the survivor and was
  removed, and each of the six set-once columns is killed by a named test.
  **Test totals:** 260 passing before this change (Order 121, Inventory 63,
  Identity 47, Notification 29, measured on 2026-11-03); Order is now 127, so
  266 in total (the other three services were not re-run after the change).
  **Day-1 repositories:** `OrderRepository` has one native compare-and-set per
  transition (set amount, `CREATED` to `RESERVATION_FAILED` / `PAYMENT_UNAVAILABLE`,
  `CREATED` or `PENDING_PAYMENT` to `ORDER_EXPIRED` with no time condition,
  `PENDING_PAYMENT` to `PAID` only for the held `payment_ref`, `PENDING_PAYMENT`
  to `PAYMENT_FAILED`), plus two finders; written with winner/loser tests,
  **run on 2026-11-03** (part of the 260-test pass above). Break-and-restore on
  three of the CAS guards (`markPaid`'s `payment_ref` condition,
  `markReservationFailed`'s `CREATED` condition, `expire`'s status guard) was
  killed by a test each. **Deliberately not built, blocked on B5 and the derived
  deadlines:** `CREATED` to `PENDING_PAYMENT` (it stamps `expires_at` from a
  database clock). Inventory's Day-1 repositories (`FlashSale`,
  `FlashSaleItem`, `StockReservation`) were finders only, written with finder
  tests, **run on 2026-11-03** (part of the 260-test pass above). `reserve`
  has since added the one native stock `UPDATE` (see above); no `@Lock` and no
  retry exist, by decision (open decisions 1 to 3).
  **Seed scripts** (`scripts/seed/`: Identity, Inventory, Notification seeds, a
  reset for Inventory and Order, `seed.sh`, a README): **run and verified on 2026-11-04** against real Postgres: first run, second run with
  identical counts, reset restores stock and version and clears reservations (checked
  on Inventory only; the Order side of the reset was not exercised), and the sequence
  moves past the seeded ids. `reset.sql` does not delete products.
  **`docker-compose.yml`** (Postgres 16, Redis 7, Kafka KRaft; see
  `docs/development.md`): written, `docker compose config` validated, NOT
  started, NOT tested.
- **Not built anywhere:** repositories beyond the Day-1 ones above (apart from
  the ones `reserve` added), service implementations other than Inventory's
  `reserve`, any controller other than Inventory's reserve endpoint, the API
  Gateway, Kafka, Redis, security. All four services
  share one local Postgres credential pair but each owns a separate database.

**Tested: 266 tests, all passing, 0 failures, 0 errors, 0 skipped** — all four
services run together on `main` on 2026-11-04: Order 127, Inventory 63,
Identity 47, Notification 29. Order's 127 are the 99 from V7 (the earlier 69
plus 30 new for V7 and the review's gaps) plus 28 added since for the
repositories and the entity-updatability tests (the V7 constraint
that fires is now asserted by name; native-SQL `NOT NULL` tests; index
predicates read from `pg_indexes`). Each count includes a context test
and the zone-less-timestamp guard every service carries. The persistence tests
save into real Postgres inside a rolled-back transaction; every CHECK, unique
key and FK is verified to reject bad rows, not merely to exist.

**Inventory after `reserve`: 73 tests, all passing, 0 failures, 0 errors, 0
skipped** (inventory suite only, run on branch `feat/reserve-service` on
2026-11-04; the other three services were not re-run for this count). That is
the 63 above plus 10 in `ReservationServiceTest`. It covers, single-threaded:
first call, replay, quantity mismatch, `RELEASED`, `ITEM_NOT_IN_SALE` with its
WARN, `OUT_OF_STOCK` leaving no row, upcoming and ended sales, replay after the
sale ended, and stock equal to quantity. (That count and its "not
race-tested" wording are as of that run; the contention tests below came after.)

**Inventory contention tests** (`ReservationConcurrencyTest`, plus
`ReservationOrchestratorTest` with no database), run on branch
`test/reserve-concurrency` on 2026-11-04, **inventory suite only: 81 tests, 0
failures, 0 errors, 0 skipped** (the other three services were not re-run).
What was raced, on real Postgres, in one JVM, with the connection pool and
every timeout left at its default; all threads wait on a latch and call
`reserve()` together:
- **Last unit, two buyers:** stock 1, 2 threads (orders 1 and 2), 100 rounds,
  each on a fresh item. Every round: exactly one `RESERVED` and one
  `OUT_OF_STOCK`, no exception, stock 0, version 1, one reservation row, and it
  belongs to the winner.
- **Same order twice:** stock 10, same `orderId`, quantity 2, 2 threads, 100
  rounds. Every round: both `RESERVED` with equal responses, stock 8, version 1,
  one row. The unique-violation catch ran in **100 of 100 rounds** (counted with
  a spy on `findReplay`, which only that catch calls). So in this harness the
  loser always lost on the insert and never found the row at the replay check:
  the other ordering (the replay check finds a committed row) was **not**
  exercised by the race.
- **50 buyers, 5 units:** 50 threads, distinct orders, quantity 1, **one**
  race (with the default connection pool of 10, at most about 10 of the 50
  threads work at once): exactly 5 `RESERVED`, 45 `OUT_OF_STOCK`, stock 0, 5 rows, sum of
  quantity 5, no exception.
- **Liveness edges** (not a race): `start_time = now()` is live, `end_time =
  now()` is not, with `SELECT now()`, the window write and the `UPDATE` in one
  transaction.
- **Break-and-restore, all three killed by the intended test:** `start_time
  <= now()` to `<` (start-inclusive test failed); `now() < end_time` to `<=`
  (end-exclusive test failed); the atomic decrement replaced by an unlocked
  read followed by a write of the value read minus the quantity (a lost update
  that no `CHECK` catches): last-unit test broke in **98 of 100 rounds** (two
  `RESERVED`, two rows, version 2) and the 50-buyer test let **18** buyers win
  5 units.

**Not exercised** by these contention tests: load (measured separately, see
"First load run" and "Second load run" below; the 5,000-concurrent target is
not reached), more than 50 threads, more than one JVM or Inventory instance, a
sale ending or being cancelled mid-race, the pool or timeouts under pressure,
and a race on a non-`RESERVED` replay (`RELEASED`, quantity mismatch). The spy
wraps `ReservationTransaction` for the whole test class, so it adds a small
overhead to every race there. Two buyers on the last unit passing 100 rounds
shows the guard held in those rounds; it is evidence, not a proof.

**Inventory's reserve contract is also tested at the HTTP layer**
(`@WebMvcTest`, with the service mocked), through the application's real
Jackson and validator configuration: the response shape (`salePrice` a
two-decimal string; non-`RESERVED` outcomes carry only `outcome`, all four of
them), validation (`quantity` 1..5, a fractional `quantity` rejected, a numeric
string accepted; a `salePrice` with a third decimal rejected; the sale window),
error mapping (`400`/`415 INVALID_REQUEST`, `400 INVALID_QUANTITY`,
`409 QUANTITY_MISMATCH`, `405` left on Spring's default body), and the
an `UnsupportedOperationException` from the service staying unmapped. Shown to fail
when broken: removing `@JsonFormat` (`salePrice` became a number),
`@JsonInclude(NON_NULL)` (null fields appeared), `@Positive` (`quantity: 0`
returned `200`), `accept-float-as-int: false` (`quantity: 2.5` returned `200`,
truncated) and the `415` handler (no `code` in the body); adding a
`RuntimeException` handler failed the unmapped-exception test. A probe also
showed the controller-scoped handler can see a `415` but not a `405`. One
assertion trap: Spring's `jsonPath(...).doesNotExist()` passes for a key
present with a `null` value, so omission is asserted as "the body is exactly
`{outcome}`". (That layer mocks the service; the logic behind it is covered by
`ReservationServiceTest` above; contention is covered by
`ReservationConcurrencyTest`, not at this layer.)

The tests are shown able to fail, not assumed to be. Order's 30 mutations —
25 applied to the database, 5 stripping an `@Generated` — killed exactly the
intended tests, with three documented exceptions where one constraint masks
another (the two `order_items` non-negative checks mask each other; the
`outbox` blank-`event_type` check is strictly weaker than the known-value
check, as Identity's is). Dropping each masked pair together does fail its
test, so none is dead. Earlier, weakening `reserved_at`'s mapping and the
`updated_at` trigger each broke exactly the tests meant to catch them.

One known limit on `NOT NULL`: an entity-level `nullable = false` makes
Hibernate reject a null before Postgres sees it, so such a test passes with or
without the column constraint. `orders.expires_at` therefore has a native-SQL
test as well; no other `NOT NULL` in any service is guarded this way.

**First load run** (2026-11-04, branch `test/load-first-run`, Gatling 3.16.0,
`load-tests/ReserveOneItem`, `load-tests/verify.sh`). **One laptop running the
load tool, Inventory and Postgres together; default connection pool and
timeouts; first baseline. NOT the 5,000-buyer / p99 target result.** Every
buyer sent one `reserve` for item 1 (stock 500) with its own order id, all at
once. Before each run the databases were reset and reseeded (item 1: stock 500,
version 0, no reservations). Inventory was started once with
`./mvnw spring-boot:run` (its process showed `-XX:TieredStopAtLevel=1`, which
that command adds) and **not restarted between runs**, so run 1 hit a freshly
started JVM and runs 2 and 3 a JVM that had already served earlier runs. A
response counted OK only for HTTP 200 with an `outcome` field; anything else
was KO. The tool did not count responses by outcome; `RESERVED` below comes from
`stock_reservations`.

| Buyers | OK | KO | p50 / p95 / p99 / max (ms), all requests | p50 / p95 / p99 / max (ms), OK only | Requests/s (Gatling mean, all requests) |
|---|---|---|---|---|---|
| 100 | 100 | 0 | 1201 / 1235 / 1257 / 1263 | same (no KO) | 50 |
| 1,000 | 309 | 691 | 1775 / 2150 / 2216 / 2341 | 2047 / 2199 / 2275 / 2341 | 333.33 (OK only: 103) |
| 5,000 | 1,676 | 3,324 | 4398 / 5604 / 5832 / 5884 | 4721 / 5746 / 5863 / 5884 | 625 (OK only: 209.5) |

- **Every KO, in both runs that had any, was the same error:**
  `j.n.ConnectException: Connection refused: getsockopt` (691 of 691, then 3,324
  of 3,324). The TCP connection was not made, so those requests did not reach
  `reserve`. **The cause was not investigated.** Inventory still answered its
  health endpoint after both runs and logged no `ERROR` line. The response times
  of KO requests are the time to the refusal, so the "all requests" percentiles
  for 1,000 and 5,000 mix refusals with real answers; the OK-only columns are the
  answered requests.
- **`verify.sh`, exit 0 after every run** (`available_stock >= 0`,
  `sum(quantity) <= total_stock`, `available_stock = total_stock - sum(quantity)`):

  | Buyers | available_stock | version | reservations | sum(quantity) | `RESERVED` rows |
  |---|---|---|---|---|---|
  | 100 | 400 | 100 | 100 | 100 | 100 |
  | 1,000 | 191 | 309 | 309 | 309 | 309 |
  | 5,000 | 0 | 500 | 500 | 500 | 500 |

- **What this shows:** in the 5,000-buyer run, 500 units were reserved and the
  stock reached 0, with no oversell visible in the database. It does **not**
  show how Inventory behaves when all 5,000 buyers get a connection, because
  3,324 of them never did. One run per size, one machine, one JVM; the numbers
  are not repeated and not compared across configurations.

**Second load run** (2026-11-05, branch `test/load-measurement`, Gatling 3.16.0).
Same laptop, same machine running the load tool, Inventory and Postgres
together, default connection pool and timeouts. **Still a first-baseline setup,
NOT a controlled target run.** What changed against the first load run:
- Inventory ran as the **packaged jar** (`java -jar`, Java 25.0.2, no JVM flags),
  not `spring-boot:run` (which had added `-XX:TieredStopAtLevel=1`).
- A **warm-up** ran first: 200 buyers on product 2 (orders from 9,000,000),
  ramped over 5 s, request named `warmup`. 200 of 200 were OK in all four runs.
  The measured scenario started after it finished. The numbers below are the
  `reserve` request only; the warm-up is not in them.
- Buyers **arrive over a ramp** (`rampSeconds`, 10 s) in (a) and (b); (c) sends
  all at once, as run 1 did.
- Databases reseeded and the jar **restarted before every run** (run 1 reused
  one JVM).
- `sample-waits.sh` sampled `pg_stat_activity` for active sessions of the
  inventory database about every 500 ms (each sample opens a new connection, so
  the real gap is longer).

Response times are OK-only (answered requests). Requests/s is Gatling's mean
count/s for `reserve`; it includes the ramp, so it is not a capacity figure.

| Run | Buyers, ramp | OK / KO | p50 / p95 / p99 / max (ms) | Req/s (OK) | RESERVED / OUT_OF_STOCK / other |
|---|---|---|---|---|---|
| (a) first attempt | 1,000, 10 s | 1,000 / 0 | 7 / 11 / 13 / 25 | 66.67 | 500 / 500 / 0 |
| (a) repeated | 1,000, 10 s | 1,000 / 0 | 9 / 23 / 89 / 200 | 66.67 | 500 / 500 / 0 |
| (b) | 5,000, 10 s | 4,733 / 267 | 2670 / 8241 / 9404 / 10847 | 215.14 | 500 / 4,233 / 0 |
| (c) | 5,000, all at once | 3,449 / 1,551 | 6950 / 11225 / 11603 / 11672 | 191.61 | 500 / 2,949 / 0 |

(a) was run twice because the first attempt's sampler wrote nothing when no
session was active, so its sample count was unknowable; the sampler was fixed
and (a) repeated. Both are listed. Outcomes are counted by the tool from the
response body, for OK responses only.

- **`verify.sh` exit 0 after every run:** `available_stock` 0, `version` 500, 500
  reservations, `sum(quantity)` 500, 500 `RESERVED` rows. 1,000 buyers on 500
  units in (a) and 5,000 in (b) and (c) left no oversell visible in the
  database. Inventory logged no `ERROR` line in any run.
- **`Connection refused` did not recur in (a)** (0 KO, both attempts). **It
  recurred in (b): 267 of 5,000 (5.3%), and in (c): 1,551 of 5,000 (31.0%).** It
  was the only KO message in both: `j.n.ConnectException: Connection refused:
  getsockopt`. Against run 1, the same message hit 691 of 1,000 and 3,324 of
  5,000 buyers.
- **Response times of KO requests** are the time to the refusal. For all
  requests (OK and KO) p50 / p95 / p99 / max were 2706 / 8126 / 9393 / 10847 in
  (b) and 7709 / 11216 / 11561 / 11672 in (c).
- **Against the target:** in (b) and (c) the OK-only p99 was 9,404 ms and
  11,603 ms, far above the 500 ms target, with 5.3% and 31.0% of buyers
  refused. These runs did not meet the target in this setup.
- **Database waits:** (a) repeated took 28 samples, 27 with no active session,
  and saw one `Lock/transactionid` and one `Client/ClientRead`; (b) 22 samples,
  20 with none, one `none/none` (active, no wait) and one `Client/ClientRead`;
  (c) 23 samples, all with none. (The first (a) attempt kept no sample count; it
  saw one `Lock/transactionid` and one `IO/WalWrite`.) So when sampled, almost
  no session was active in Postgres. The sampler can miss waits shorter than its
  interval, and the reserve transaction is short.
- **Hypotheses, not shown by these runs:** (1) the large drop from run 1 to (a)
  (69% refused and about 2 s p50 against 0 refused and 9 ms) came from some mix
  of the jar versus `spring-boot:run`, the warm-up and the ramp; the runs did
  not vary them one at a time, so none of the three is singled out. (2) The
  refusals in (b) and (c) may come from many connections arriving faster than
  Inventory accepts them (for example a full accept queue), since the refusals
  were refusals to connect and the database was mostly idle; the accept queue
  and Tomcat and pool limits were not inspected or changed. (3) The
  multi-second times in (b) and (c) may be queueing before the database for the
  same reason. Each needs its own measurement before it is believed.
- **Not done:** one run per configuration (apart from (a)), one machine, no
  repeat of (b) or (c), nothing changed in Inventory's pool, Tomcat or timeouts.

**Status of `reserve`:** implemented, tested single-threaded, and raced in the
contention tests above. Load-measured (see "First load run" and "Second load
run") on one laptop with the load tool co-located: the p99 target was met at
100 buyers/s (p99 13-89 ms), not met at 500 buyers/s (p99 ~9.4 s, 5.3%
refused), cause not found; the 5,000-concurrent target is not reached. Not
verified under failure.

**Ports:** identity `8081`, inventory `8082`, order `8083`, notification
`8084`; `8080` is reserved for the API Gateway.

**Diagrams and ERDs: current, checked 2026-10-22.** All sequence diagrams
(including reconciliation sweep and partial-item failure), both state diagrams
and all four ERDs live in the Notion FlashMart page as Mermaid blocks. They
were checked against the code and this document on 2026-10-22 and updated
where they lagged: the reserve step now shows the item-in-sale check and the
replay quantity check, Order rejects a quantity above 5, and
`ITEM_NOT_IN_SALE` leads to `RESERVATION_FAILED`. `docs/Flashmart.prd` was
re-synced to this document the same day.

**Notion log:** a personal engineering log, not a source of truth —
`docs/decisions.md` remains authoritative on conflict. Checked and brought up
to date on 2026-10-22 (before `reserve` and the load runs existed): "API &
Event Contracts" records every reserve-contract piece (including
`ITEM_NOT_IN_SALE` and the `415`), and the test totals matched the 193
measured that day. "Feature Implementation" and "Load Testing & Verification"
were empty then. Not re-checked since.

## 9. Known Open Items / Next Steps

Mirrors the Open Items in `docs/decisions.md`. Most items are blocked on
something that doesn't exist yet; those marked "not specified", "not decided"
or "recorded, not decided" still need a design decision.

- **Accepted, permanently.** A second captured payment can escape correction
  entirely if the initiate idempotency key ever fails to dedupe *and* a
  webhook is separately lost — in the worse of two shapes, the order reaches
  `PAID` and the sweep never examines it again, since it assumes a `PAID`
  order's one payment is applied by definition. Requires three independent
  rare failures to stack; the cost of building "list every payment for this
  order" plus scanning `PAID` orders is judged not worth it against that
  likelihood. This is the one place in the design that leans on prevention
  rather than independent correction.
- The `CREATED` deadline is **provisionally derived** (60 s policy, 2 s per-call
  timeout, 5 s margin, `maxLines` 30; decisions.md, "Order creation: retries,
  conflicts, deadlines and Inventory failures"). Still open: the refund claim
  lease, the Order sweep interval and the payment window, plus the retry
  multiplier (Day 5) — blocked on real Feign/Resilience4j config by design.
- **Locking strategy and isolation level for `flash_sale_items`: decided**
  (implemented, tested single-threaded and raced in a contention test; first laptop load runs only (see §8); the 5,000-buyer / p99 target is not reached). See `decisions.md`, "Reserve write path: READ COMMITTED, one atomic conditional UPDATE, one short transaction".
  Pool size and timeout values, and insert-first vs update-first, come from
  the load test.
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
- **Two database clock functions are in use for the same job (recorded; the
  deadline case is decided 2026-11-05: deadlines use `clock_timestamp()`, audit
  stamps may use `now()`, `reserved_at` stays `now()`).** `stock_reservations.reserved_at` defaults to `now()`
  (`transaction_timestamp()` — when the transaction began); Identity's
  `updated_at` trigger and Order's DB-stamped columns use `clock_timestamp()`
  (when the statement runs). Neither is a bug: a strictly increasing
  `updated_at` *must* use `clock_timestamp()`, since `now()` is constant within
  a transaction and two updates would tie. But for `reserved_at`, which the
  reconciler compares against the database's `now()`, a long transaction stamps
  an earlier time than the write — a silent offset nobody chose. "Who stamps a
  time column" fixes which *clock* and not which *function*, so decide it on
  purpose and write it into that rule before the next such column is added.
  Separately, and not a defect: `products` uses
  `@CreationTimestamp`/`@UpdateTimestamp`, so its `DEFAULT now()` is
  unreachable from the app (correct under "Java clock for audit-only", and
  therefore untested). Found by the Order piece's sibling sweep.
- **Settle how `reserve` writes the decrement.** Two decision entries specify
  it incompatibly: `WHERE id = ? AND version = ?` versus one statement
  checking `available_stock` and liveness against the database clock. Under
  READ COMMITTED a value-based clause re-checks fresh stock after a blocked
  UPDATE unblocks and needs no retry; a `version = ?` clause fails after any
  concurrent write. A native `UPDATE` also does not bump `version`, so mixing
  it with an entity save can silently clobber a decrement. Must be settled
  before `reserve` is built. **Decided and since implemented** (tested, raced, load-measured on one laptop; see §8): see `decisions.md`,
  "Reserve write path: READ COMMITTED, one atomic conditional UPDATE, one short transaction".
- Enforce "frozen once the sale opens" (price, `total_stock`, times,
  cancellation) in the service layer, against the database clock, in the same
  transaction as the write. A CHECK cannot express it. Not built.
- Enforce "a product's name is frozen while a live sale references it" in the
  service layer, by the same means as the item above (§4). Not built.
- TODO: add an API check so a brand cannot create a sale that starts in the
  past. Put it on the create request only (on update it would hide the real
  "frozen" reason), with a fixed clock in tests. The authoritative rule stays
  the service-layer check against the DB clock.
- **A zero-amount order is untested.** `sale_price` may be `0.00` (the
  `>= 0` CHECK and the API's `@PositiveOrZero` agree), so an order can total
  `0.00`, and a payment gateway may refuse a zero-amount payment. Decide in
  the payment phase.
- **FR-B1 (buyer browse) has no endpoint.** It waits for security and
  caching. Any future stock hint is a coarse `soldOut` flag, never an exact
  count.
- **Decided 2026-11-05, designed not built:** on `RESERVATION_RELEASED`
  mid-loop Order stops the loop, writes no CAS and no event, re-reads the real
  status and reports it; if still `CREATED`, it alerts and does not guess.
- **Decided, not built:** all three reserve "no" outcomes (`OUT_OF_STOCK`,
  `SALE_NOT_LIVE`, `ITEM_NOT_IN_SALE`) end the order as `RESERVATION_FAILED`.
  No new state, no migration (§6).
- **Decided 2026-11-05, designed not built:** a retried create on a
  `RESERVATION_FAILED` order replays the failure with no reason and never
  calls Inventory; a new attempt needs a new key. `failure_detail` stays
  debug only.
- **Bean Validation exists in Inventory only.** Identity, Order and
  Notification add `spring-boot-starter-validation` in their own API phase.
- **Order's create-order request must reject `quantity > 5`** before any
  `reserve` call, so a buyer's mistake gets a `400` from Order instead of
  firing an Inventory alert. Not built.
- **Decided 2026-11-05, not built:** any unique violation on order insert is
  caught and re-queried in a new transaction (never parsing constraint names):
  same `(buyer_id, idempotency_key)` is a replay; else `409 ORDER_ALREADY_OPEN`
  with the existing order; else `409 CONFLICT_RETRY`. Never a `500`.
- **A buyer cannot start a new order while an unpaid one is open**, until it
  pays or expires. The wait is bounded by the payment window (value still
  open, see the derived deadlines). Accepted cost of the one-live-order rule.
- **One person with two accounts is not covered** by the one-live-order rule:
  the rule is per `buyer_id`, and Identity does not link accounts. The cap of
  5 is also still per item per order.
- **Decided 2026-11-05, not built:** a retried create while `CREATED` re-runs
  the reserve loop (abort by expiry **or** resume on retry), safe only if every
  write is CAS/write-once and the outbox row is written only when the CAS wins.
  A **hypothesis** until a two-thread test proves it. Also: same key with a
  different request is `409 IDEMPOTENCY_KEY_REUSED`, checked before replay.
  See the conditional rule on `order_items` (§4).
- Identity, for the API phase: a lost signup race must surface as "email
  already taken", not a 500; the JWT must say whether its subject is a brand
  or a user (brand 7 and user 7 share an id); published `outbox` rows need a
  cleanup job.
- Build Razorpay Clone with the behaviours this design leans on
  (`docs/requirements.md` §9, `RZP-1`–`RZP-9`), above all: idempotency on
  `initiate` and `refund`, a payment-status lookup queryable by our idempotency
  key or order reference, and payments and webhooks that carry our order id.
  Not built yet; designed-not-verified until it is.
- **Found by independent review, Oct 29, not decided.** An independent static
  review of Order V1-V6 happened on 2026-10-29; the reviewer could not run the
  tests. What it found and V7 fixed is in §4 (V7). The items below were left
  alone on purpose, each waits for a decision. None is built or tested:
  - **A1:** entity updatability and `@DynamicUpdate`. **Decided** (see
    `decisions.md`, "CAS-owned columns are insert-only in the entities"):
    implemented, **run on 2026-11-03**, break-and-restore done; later amended
    (`@DynamicUpdate` removed, six set-once columns locked, see the amendment
    in `decisions.md`).
  - **A2:** `refunds.next_retry_at` NULL handling and the refund claim query.
    Not decided.
  - **A3:** redesign of the sweep and refund indexes. Not decided.
  - **B1:** `ON CONFLICT` for the refund insert. Not decided.
  - **B2, B3:** the retried create (and the `409` it returns) and a request
    fingerprint. **Decided 2026-11-05, designed not built** (no fingerprint
    column; compare against stored `flash_sale_id` and `order_items`).
  - **B4:** a webhook arriving before the order is `PENDING_PAYMENT`. Not
    decided.
  - **B5:** which clock function stamps `expires_at`. **Decided 2026-11-05:**
    `clock_timestamp()`, stamped by the database (native `INSERT ... RETURNING
    id`). Not built.
  - A `CHECK` on `payment_reconciled_at`, and a `CHECK` that `PROCESSING`
    implies `claimed_at`. **Decided 2026-11-05; V8, not written yet.**
- **New, 2026-11-05 ("Order creation: retries, conflicts, deadlines and
  Inventory failures"), all designed not built:** an Inventory timeout or
  refusal mid-loop ends the order as `RESERVATION_FAILED` + `OrderCancelled`,
  `503 INVENTORY_UNAVAILABLE`. **Known gap:** a timed-out call can commit after
  the release and orphan a reservation; covered only by the Day 7 republish and
  the reconciler (designed, not verified). The HTTP client is Spring Cloud
  OpenFeign (2025.1.x train); its compatibility with Boot 4.1.1 is unverified
  until it resolves and starts. The 2 s per-call timeout and 5 s margin are
  provisional; recheck on Day 9.

The PRD is `docs/Flashmart.prd` (Revision 4, markdown). The earlier
`FlashMart_Project_Plan_v3.pdf` reference was stale — no such file exists or
ever existed in this repository.
