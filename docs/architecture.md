# FlashMart — Architecture

> Current design per every entry in `docs/decisions.md` (Revision 3 and the
> implementation-phase entries after it). Supersedes the PRD wherever they
> conflict (see `CLAUDE.md`). Design document — Section 8 is what actually has
> code behind it (entities in every service; Inventory's `reserve` and its two
> Kafka consumers; Order's create path, payment step, payment webhook, outbox
> relay and expiry sweep; the payment stub), each with its own label there.
> **Day 8 (auth, gateway, discovery, rate limiting, browse) is BUILT and tested
> (§8):** Identity auth + V8, the api-gateway (JWT check, signed buyer id, Redis
> rate limiter), Order's signed-header check, the Eureka server and client
> registration, and Inventory's browse endpoint. Smoke-run once on real
> processes (gateway/Eureka part). Not load-tested, one laptop
> (`decisions.md`, "Day 8: auth, gateway identity, rate limiting, browse").
> **First laptop load runs only (see §8); the 5,000-buyer / p99 target is not reached.**

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
| **API Gateway** | Nothing (no DB) | Stateless routing, JWT at the edge, per-buyer / per-IP rate limiting (Redis), signs the buyer id it forwards. Spring Cloud Gateway, reactive (WebFlux); finds services through Eureka. **Built on Day 8, tested, smoke-run once** (§3, "Edge: Gateway"). |
| **Eureka server** (`eureka-server`) | Nothing (in-memory registry) | Service discovery for the Gateway only; standalone, port `8761`. **Built on Day 8, tested, smoke-run once** (§3, "Gateway and discovery"). |
| **Config server** (`config-server`) | Nothing (clones a public Git repo) | Serves tunable numbers (timeouts, breaker, fee, sweep, relay, rate limits) to order, inventory, identity and the Gateway; port `8888`. Each client keeps a local fallback copy; env vars beat both; restart to apply. **Implemented and tested; smoke-run once** (`decisions.md`, "Day 9, Part 0"). |
| **Order Service** | `orders`, `order_items`, `refunds`, `outbox` | Sole authority on an order's fate (§6). Hot/bursty, isolated from cold traffic. |
| **Inventory Service** | `flash_sales`, `flash_sale_items`, `stock_reservations`, `products` | The hottest, most contended row in the system (`flash_sale_items`). Isolated so contention can't lock-starve unrelated services. |
| **Identity Service** | `brands`, `api_keys`, `users`, `outbox`, and `refresh_tokens` (V8, Day 8, implemented, MockMvc-tested) | Brands and buyers share one access pattern — cold, low-volume auth — not because they're the same kind of entity. |
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
- **Inventory → Order** — `GET /internal/orders/{orderId}/status` → `{ orderId, status }`, used by Inventory's stale-reservation reconciler. `PAID` → confirm; terminal failure → release; `CREATED`/`PENDING_PAYMENT` → do nothing **and alert** (an hours-old reservation on a live order means Order's sweep has died); `404`/unreachable → do nothing and alert, never guess. Safe without locking: every answer acted on is terminal and can't change after being read. Internal only. The reconciler scans a bounded number of rows per pass, so a systemic backlog (e.g. Kafka down for hours) can't flood Order. **Implemented and tested on Day 9** (§8, `decisions.md`, "Day 9: Inventory stale-reservation reconciler"): threshold 570.5 s (derived), a pass every 60 s, 50 rows, alert = ERROR log + `inventory.reconciler.alerts{reason}`, the pass stops at the first unreachable Order.

> **Cross-project caveat:** Razorpay Clone is not built yet. Idempotency on
> `initiate` and `refund`, a payment-status lookup by our key, and payments and
> webhooks that carry our order id, are
> requirements placed on it — designed, not verified. Correctness never
> depends on them: the one-payment invariant (§6) and the already-refunded
> check catch the failure either way. Prevention and correction are
> deliberately independent.

**Asynchronous — inbound webhook:**
- **Razorpay Clone → Order** — payment result, signature-verified (FR-P2). Carries **our order id**, by which Order loads the row (never by `payment_ref`, which may be `NULL`; §6). Applies the one-payment invariant (§6); returns 200 only after its transaction commits, so a pre-commit crash is replayed by the gateway's own retry. Wire contract (decisions.md, "Day 6", point 4): `POST /webhooks/payment`, body `{order_id, payment_ref, status, amount}` (amount a string), HMAC-SHA256 hex of the raw body in `X-Signature`; `401` / `400` / `404` / `409` (order still `CREATED`) / `200`. Implemented and tested; sent only by the payment-stub so far (§8).

**Edge: Gateway, identity and rate limiting (Day 8: BUILT and tested; smoke-run once, not load-tested).** Design: `decisions.md`, "Day 8: auth, gateway identity, rate limiting, browse". All parts of the flow below exist in code (§8): Identity, the Gateway (JWT check, signed buyer-id header, rate limiting), Order's check of the signed header, and browse. Order no longer trusts a bare `X-Buyer-Id`: it accepts the header only with a valid, fresh `X-Auth-Signature` (§8, Day 8).
- **Identity flow.** A buyer signs up or logs in at Identity (`/api/auth/**`) and gets an HS256 access JWT (15 min; claims `sub`, `typ` = `user`|`brand`, `iat`, `exp` only) and an opaque refresh token (7 days, stored as a hash in Identity's `refresh_tokens`, Postgres). Only `user` tokens are issued. `JWT_SECRET` is held by Identity and the Gateway only; both refuse a blank value.
- **Refresh.** Each refresh is a compare-and-set on `refresh_tokens` (`revoked_at IS NULL AND expires_at > clock_timestamp()`, both stamped by the database clock): one winner gets a new pair; a revoked token seen again revokes every refresh token of that user (no grace window; a two-tab race logs the buyer out everywhere, accepted). Endpoints: signup, login, refresh, logout. Access tokens are not revocable before they expire.
- **Gateway to Order.** The Gateway validates the JWT once (rejecting `typ != user` on `/api/orders/**`), strips any client `X-Buyer-Id`, and forwards `X-Buyer-Id` plus an HMAC over the buyer id and a timestamp, keyed by `GATEWAY_SIGNING_SECRET` (Gateway and Order only; separate from the JWT secret). Order answers `401` for a missing, bad or more-than-60 s-old signature and refuses a blank secret. The payment webhook keeps its own HMAC; `/internal/**` is never routed. **Limit:** `:8082` and `:8083` are reachable directly, and Inventory has no auth, so this guards the front door only.
- **Gateway and discovery (BUILT and tested; smoke-run once; `decisions.md`, "Day 8", addendum "Gateway and discovery").** Spring Cloud Gateway, **reactive** (WebFlux): it mostly waits on downstream services, and it must never run a blocking call (its only own work is the JWT check and the HMAC). **Eureka is used by the Gateway only**: routes are `lb://identity-service`, `lb://inventory-service`, `lb://order-service`, with the fixed addresses kept as a fallback profile. **Order's Feign clients keep fixed URLs**, because the Day 5 derived timeouts assume one attempt per call and no load-balancer retries. Eureka server: separate module, port `8761`, standalone (no self-registration, no fetch), self-preservation **off** for the demo (on in production), no security locally (anyone reaching `8761` could register a fake service). **Default Eureka timings** (heartbeat 30 s, lease 90 s, which Eureka's own code says really expires after about 2 × 90 s, eviction check 60 s, server response cache 30 s, Gateway fetch 30 s, LoadBalancer cache 35 s): a hard-killed instance can stay in use for minutes (estimate, not measured); discovery is eventually consistent. No instance → `503 SERVICE_UNAVAILABLE` `{code, message}`; Eureka down → the Gateway keeps its cached registry, but a Gateway restarted while Eureka is down has none and answers `503`. **No automatic Gateway retries** (retry safety is the client reusing its `Idempotency-Key`). One instance per service; an optional demo runs two Inventory instances behind `GET /api/flash-sales`; **never two Order instances** (multi-instance races untested; the outbox relay would publish twice). Identity, Inventory and Order register; Notification and the payment-stub do not.
- **Rate limiting.** Spring Cloud Gateway `RedisRateLimiter` (token bucket), provisional values, recheck Day 9: `/api/orders/**` per buyer id 5/s burst 10; `/api/auth/**` per client IP 1/s burst 5; browse unlimited. The JWT filter runs before the limiter. The limiter **fails open** if Redis is down: it is protection, not correctness, and does not prevent overselling (the conditional `UPDATE` does). Day 9 trap: the oversell load test needs many buyers or a raised limit, or it measures `429`s.
- **Browse (FR-B1).** `GET /api/flash-sales?page=N` in Inventory: 0-based, fixed page size 10, not `CANCELLED` and `end_time > now()` (database clock), ordered by `start_time, id`; an `inStock` flag, never an exact count; `hasNext` from an 11-row fetch, no `COUNT`; items in one extra query; a bad page is `400 INVALID_REQUEST`; uncached. Offset paging can skip a sale that ends between two pages (accepted; a cursor is the fix at scale).

**Asynchronous — Kafka** (each publisher owns an `outbox`, same local
transaction as the business change):
- **Order** publishes `PaymentConfirmed { order_id, buyer_id, items: [{product_id, product_name, quantity, unit_price, subtotal}], amount }` — consumed by **Inventory** (needs only `order_id`, to confirm reservations) and **Notification** (needs the rest, for the receipt).
- **Order** publishes `OrderCancelled { order_id, reason, buyer_id }`, `reason ∈ {RESERVATION_FAILED, PAYMENT_FAILED, PAYMENT_UNAVAILABLE, ORDER_EXPIRED}` — consumed by **Inventory** (releases every reservation for the order, any reason) and **Notification** (buyer only, and only for `PAYMENT_FAILED`/`ORDER_EXPIRED` — the two reasons determined after the buyer's request has closed; the other two are answered synchronously, and emailing them would give a deliberately cold service a bursty hot-path load).
- Order's topics: `payment-confirmed` and `order-cancelled`, key = order id, one partition each, created by Order at start-up (`kafka.create-topics`). Identity's topics are not named yet (its relay is not built).
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
  -- amount: SUM(order_items.subtotal) + fee, DECIMAL, stored (the price the buyer
  --   agreed to, frozen). Fee = flat 49.00 per order, config, no column: fee =
  --   amount - SUM(subtotal). IMPLEMENTED and TESTED (Day 5, fee commit; `order.fee`
  --   in config, 49.00, provisional). NULL until the last item is priced; set once by a CAS
  --   guarded on status = CREATED; a test checks it equals SUM(subtotal).
  --   Computed once from SUM(order_items.subtotal) after the last item is
  --   priced, never accumulated item by item (built and tested, Day 4/5)
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
  --   (implemented, tested): clock_timestamp() + duration, DB-stamped by a native
  --   INSERT ... RETURNING id (decisions.md, "Order creation: retries, conflicts,
  --   deadlines and Inventory failures")
  -- failure_detail: nullable, debug only — never drives state
  -- payment_reconciled_at: nullable; set once the reconciliation sweep has
  --   settled this order (DB clock). Bounds and idempotency-guards the sweep
  -- CHECK (status NOT IN ('PENDING_PAYMENT','PAID') OR amount IS NOT NULL)
  -- CHECK (amount IS NULL OR amount >= 0)
  -- created_at: DB default. updated_at: DB trigger (CAS writes are raw SQL and
  --   skip Hibernate's @UpdateTimestamp)
  -- index: idx_orders_expiry_sweep_live ON (expires_at) WHERE status IN
  --   ('CREATED','PENDING_PAYMENT') for the expiry sweep (V9, Day 7: replaced V2's
  --   idx_orders_expiry_sweep (status, expires_at); implemented and tested; EXPLAIN showed
  --   the planner CAN use it for the sweep SELECT, NO speedup was measured);
  --   (status, payment_reconciled_at) reconciliation sweep, unchanged (A3's refund/
  --   reconciliation part is still undecided)
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
  -- BUILT AND TESTED (Day 4): product_name/unit_price/subtotal are written once —
  --   the pricing UPDATE carries WHERE unit_price IS NULL, on top of the
  --   orders.status = 'CREATED' guard
  -- RECORDED (superseded, see the Correction below; the resume path is built): recovery is abort (decisions.md, "saga_state is
  --   dropped"). IF a resume path is ever added (e.g. a retried create while
  --   the order is still CREATED), it runs only over rows WHERE unit_price IS NULL
  -- Correction: amended by "Order creation: retries, conflicts, deadlines and Inventory failures" (2026-11-05)

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
  -- written today only by the webhook handler (Day 6): INSERT ... ON CONFLICT
  --   (payment_ref) DO NOTHING (B1), status PENDING, next_retry_at left NULL.
  --   What a NULL next_retry_at means to the claim query is open (A2, §9)

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
refresh_tokens(id, user_id, token_hash UNIQUE, expires_at, revoked_at, created_at)
  -- IMPLEMENTED and TESTED at MockMvc level (Day 8, Identity migration V8;
  --   see §8). Times are
  --   TIMESTAMPTZ. token_hash is a hash of an opaque random token; the token
  --   itself is never stored. user_id is a same-service FK to users.
  --   Rotation is a compare-and-set on revoked_at IS NULL AND expires_at >
  --   clock_timestamp(); a revoked token seen again revokes all of that user's tokens.
  --   Expired-row cleanup: designed only, no job. See decisions.md, "Day 8"
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
- `CREATED → RESERVATION_FAILED` — an item came back `OUT_OF_STOCK`, `SALE_NOT_LIVE` or `ITEM_NOT_IN_SALE` (decided; no new state, no migration). A state exists only if something downstream reacts differently to it, and every reserve "no" gets the same reaction: release everything, send no email, tell the buyer in the reply. Which outcome it was goes in `failure_detail` (debug only) and in the buyer's reply. An Inventory timeout or refusal mid-loop also ends as `RESERVATION_FAILED` (`503 INVENTORY_UNAVAILABLE`, cause in `failure_detail`). On `RESERVATION_RELEASED` Order stops without a CAS or event and reports the real status. Both decided 2026-11-05, implemented, tested (races mock Inventory) (§9).
- `CREATED → PAYMENT_UNAVAILABLE` — circuit breaker open on initiate. Day 5 (implemented, tested against a stub only) widens this to retries exhausted as well, or a gateway 4xx, with `OrderCancelled{PAYMENT_UNAVAILABLE}` in the same transaction.
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
`status='PENDING_PAYMENT' AND payment_ref=X` and the webhook's amount equals
`orders.amount` (numeric compare; Day 6, point 3); if that fails and the order is
already `PAID` with `payment_ref=X`, it's a redelivery (no-op); otherwise
refund `X` (from the webhook, not `orders.payment_ref`, which may be `NULL`
if Order crashed before saving it). A late `FAILED` webhook needs no refund
(nothing was captured), but its CAS is guarded by `payment_ref` too, so a `FAILED`
for another payment cannot fail an order that holds `X` (decided 2026-11-05;
implemented and tested, §8). A webhook for an order still `CREATED` gets `409`
and changes nothing; the gateway's retry delivers it later (B4). Refunds
are never issued inline: the handler writes a `refunds` row in the same
transaction as its failed CAS, and a worker drains it (the worker is not built).

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
- **Service scaffolding** — fixed ports 8081–8084 (8080 reserved for the Gateway); one shared credential pair against one Postgres instance but a separate database per service; Kafka, Feign, Resilience4j and Security deferred to their phases; Validation added to Inventory in its API phase, and each sibling adds it in its own. (Since added to Order: Validation and Feign on Day 4, Resilience4j on Day 5, spring-kafka on Day 6; Security nowhere.)
- **Every event-publishing service owns an outbox** — now Order and Identity only.
- **Cross-service relationships are always an ID or an event, never a foreign key** — §5.
- **Deadlines and leases are derived from the timeouts they race, never picked** — the `CREATED` deadline was derived on Day 5 from the Feign and Resilience4j config (implemented, tested); the payment window, sweep interval and refund lease were decided as policy (provisional; decisions.md, "Day 5"). Pool size and DB timeouts stay open.
- **Outbound brand webhooks dropped, no replacement** — a pre-Revision-3 leftover with no FR behind it; would have been a third implementation of the signed-delivery-with-retry pattern already designed twice (inbound payment webhook, built on Day 6; refund worker, not built), for a need nothing establishes.

## 8. Current Status

**Designed:** everything in §2–7, in full — all five schemas, the state
machine, every communication pattern, every decision in `docs/decisions.md`
(Revision 3 and the implementation-phase entries after it).

**Day 8 (auth, gateway identity, rate limiting, browse): IMPLEMENTED AND TESTED, merged to `main`.**
See `decisions.md`, "Day 8: auth, gateway identity, rate limiting, browse" and
§3, "Edge: Gateway". Built: Identity's `POST /api/auth/signup`, `login`,
`refresh` and `logout` and migration V8 (`refresh_tokens`), tested at MockMvc
level on real Postgres (`testing.md`, "Day 8 Identity"); the api-gateway (JWT
check, signed `X-Buyer-Id` header, `lb://` routes, Redis rate limiter that fails
open); Order's check of the signed header (the forgeable bare header is gone);
the Eureka server and client registration in Identity, Inventory and Order; and
Inventory's `GET /api/flash-sales?page=N`. Gateway and Eureka were smoke-run once
on real processes, and the Gateway was load-run in Day 9 (`testing.md`).
Test count:
the Identity suite ran **116** tests, all passing, in a Claude Code session
after the three pieces were built, and **117** after a start-up test for a
space-only `JWT_SECRET` was added (run in the same session, 0 failures, 0
errors, 0 skipped). Identity was 47 on 2026-11-06; the other services' counts
below (490 total after Day 7) are superseded by the 2026-11-08 run: identity 117,
inventory 113, order 308, notification 29, payment-stub 22, api-gateway 46,
eureka-server 2 = **637**, all passing, 0 failures (each run once, one laptop;
the limiter tests need the compose Redis and Docker).

**Day 9, Part C (order load-test setup): IMPLEMENTED; SMOKE-RUN ONCE (~50 buyers), not load-tested.** `load-tests/create-users.sh` and `make-tokens.sh` create real buyers and log them in directly on Identity `:8081` (never through the Gateway; test-only emails and password); `PlaceOrders` (Gatling) sends one order per distinct buyer through the Gateway `:8080` after a 200-buyer warm-up on item 2 and counts every HTTP status; `check-orders.sh` is the Order-side check next to `verify.sh`. Measured once on one laptop: 5,200 signups 110.9 s, 5,200 logins 145.5 s (12.6 of the 15 token minutes left). Smoke: 50 measured orders all `201` and `PAID`, `verify.sh` and `check-orders.sh` OK (`docs/testing.md`, "Day 9 Part C smoke"). Suites unchanged at **650**, all passing. Not measured: anything at 1,000+ buyers.

**Day 9, Part D (order load runs): RUN ONCE EACH, ONE LAPTOP, STUB GATEWAY; targets NOT met.** Three runs through the Gateway, auto-success webhook, config unchanged, services not restarted between runs (`docs/testing.md`, "Day 9 Part D"). R1 1,000 buyers over 10 s: 201 × 500, 409 × 500, Gatling p99 4,761 ms. R2 5,000 over 10 s: 201 × 500, 409 × 1,555, 503 × 46, 2,899 connections refused at `:8080`, Gatling p99 12,374 ms. R3 5,000 at once: 201 × 500, 409 × 872, 3,628 refused, Gatling p99 6,539 ms. **Zero oversell held in all three** (`verify.sh`, `check-orders.sh`), and the Version B gap count (`count-gap.sh`) was 0 each time; Inventory never got slow (reserve p99 ≤ 0.2 s), so Version B was not exercised. **p99 < 500 ms: not met; 5,000 concurrent: most requests never got in** (refusal cause not proven). Order's Hikari pool sat at 10/10 with up to 191 waiting, and its max acquire wait matched its server-side p99; with `open-in-view` on (Boot default) Order held one connection for the whole request, including the Inventory and payment calls (library code read, borrow counts matched; not yet fixed). Also seen: 15 Feign cold-start 503s in R1's warm-up (not traced), 987 rate-limiter Redis timeouts (fail open, 500 ms each, cause unknown).

**Day 9, Part F step 2 (open-in-view off, Order's pool wait in the deadline): IMPLEMENTED AND TESTED; LOAD-RUN ONCE EACH (R2F, R3F), ONE LAPTOP, STUB GATEWAY; targets NOT met** (`decisions.md`, "Day 9, Part F"; `docs/testing.md`, "Day 9 Part F step 2"). `spring.jpa.open-in-view: false` in Order, Inventory and Identity (an HTTP test proves no connection is held during the Inventory and payment calls; break-and-restore done). Order only: Hikari `connection-timeout` 1000 ms (policy, provisional), read by `CreateOrderSettings` so the `CREATED` deadline counts the worst-case pool wait (1 + 2 per line + 3 borrows): one item 20.5 s (was 14.5 s), `maxLines` 9 (was 17). A pool timeout answers `503 SERVICE_BUSY` on any Order endpoint (forced-timeout test over HTTP). Inventory and Identity keep Hikari's 30 s default. Pool size unchanged. Measured: Order's longest connection wait 1.21 s (R2F) / 0.50 s (R3F), was 12.95 / 3.26 s; Order's server-side p99 0.51 s in R3F; Gatling p99 still 8.4 s and most connections still refused at `:8080`. R2F on fresh JVMs sold nothing (a Feign cold-start error opened the payment breaker); zero oversell and gap 0 in both, though R2F had 285 late (Version B) reservations that were released only by timing. **Accepted residual risk, not solved:** stacked pool waits past the deadline can leave stock `RESERVED` for an expired order until the reconciler exists. Suites 657 (650 + 7 new).

**Day 9, Inventory stale-reservation reconciler (Version B): IMPLEMENTED AND TESTED; DEMONSTRATED ONCE ON REAL PROCESSES WITH A FORCED LATE RESERVATION, ONE LAPTOP** (`decisions.md`, "Day 9: Inventory stale-reservation reconciler"; `docs/testing.md`, "Day 9: stale-reservation reconciler"). Order: `GET /internal/orders/{id}/status` (`{orderId, status}` or 404; never routed through the Gateway, now checked by the Gateway's route test for `/internal/**`). Inventory: every 60 s up to 50 `RESERVED` rows older than 570.5 s (payment window 360 + longest `CREATED` deadline 60.5 + sweep 30 + Kafka drain margin 120; the first three mirror Order's values by hand) are looked up in Order with no transaction held; `PAID` → confirm, a failure state → release (the existing status-guarded code), anything else (live, 404, unknown, unreachable) → nothing changed, ERROR log + `inventory.reconciler.alerts{reason}`; the pass stops at the first unreachable Order. Tested on real Postgres with Order mocked, raced against the consumer's release (stock back exactly once; break-and-restore on the status guard). **Demo (one run, forced ordering):** a row lock made one reserve outlast Order's 2 s timeout; Order failed the order, the consumer found nothing to release, the reserve then committed (`count-gap.sh` gap 1); with a 60 s demo threshold the reconciler released it and the stock came back. A 50-buyer run with live orders on the real threshold: no action, no alert. **Not proven:** how often Version B happens under real overload, a large backlog, a slow Order, more than one Inventory instance. Suites 670.

**Day 9, cold-start fixes: IMPLEMENTED AND TESTED; MEASURED ON ONE LAPTOP, A FEW RUNS EACH** (`decisions.md`, "Day 9: cold-start fixes"; `docs/testing.md`, "Day 9: cold-start fixes"). (A) Spring Cloud `2025.1.2` → `2025.1.3` in all six modules that declare it: openfeign 5.0.3 fixes the first-burst race in `FeignHttpMessageConverters` (cause proven in the diagnosis); the harness went from 20 / 20 failed rounds to 0 / 20. Boot stays 4.1.1 (the 2025.1.x BOMs name Boot 4.0.x; a mismatch that was already there). (B) Inventory's `StartupWarmup` (`startup-warmup.*`: 20 calls, 10 s timeout, provisional; the flag is local only) runs the real `reserve` in rolled-back transactions as a `SmartInitializingSingleton`, i.e. before the port opens, Eureka registers and the Kafka listeners start. Nothing commits (native-SQL test), except that each start burns 20 `stock_reservations` ids. Cold 200-reserve burst: calls over 2 s 0 in 3 of 3 runs (was 87 to 150); p99 still 1.25 to 1.62 s (0.80 to 0.85 s once warm), because Tomcat, MVC and Jackson cannot be warmed before the port; start-up about 1 s longer. **Not proven:** why a cold JVM is slow (no profile), that 20 is the right number, behaviour with two instances. Suites 681.

**Day 9, R2 re-run on the final build: LOAD-RUN ONCE, ONE LAPTOP, STUB GATEWAY; targets NOT met** (`docs/testing.md`, "Day 9: R2 re-run on the final build"). 5,000 buyers over 10 s through the Gateway on freshly started JVMs (after a 51-order gate), the Feign 5.0.3 and Inventory warm-up fixes and the reconciler in. Measured, one run: 500 of 500 units sold, zero oversell, Version B gap 0, reconciler 0 actions and 0 alerts, payment breaker closed throughout, Order's pool max 3 active and 0 waiting, Inventory's 2 and 0. **Not met:** Gatling p99 6,022 ms (answered only) and 5,349 ms (all 5,000); Order's server-side p99 0.90 s (max of 30 s windows); 3,978 of 5,000 buyers got `Connection refused` at `:8080`, so 5,000 concurrent is not reached. Inventory's reserve p99 0.32 s (server side). Unexplained: the refused connections, the gap between buyer-side and server-side p99, 28 Redis timeouts (all in one second). The jars in `target/` were stale and were rebuilt first. Not repeated; no variance known.

**Day 9, refused connections: DIAGNOSED IN PART (one laptop, one run each)** (`docs/testing.md`, "Day 9: why buyers are refused at `:8080`" and "Day 9: the decisive R2 run").
- **Controls:** the same 5,000-buyer ramp refused 0 against a trivial local server and 0 against the Gateway's own 401 path.
- **Mechanism, reproduced on this laptop:** a server with a queue of 200 that accepts slowly produced R2's exact error.
- **The Gateway's queue (backlog):** 200, Netty's fixed Windows default.
- **In an R2 run:** Windows' IPv6 Failed Connection Attempts rose by exactly the refused count, with no resets.
- **Thread dumps:** up to 5 of the Gateway's 8 event loops were held on one lock in `DefaultRedisScript.getSha1` (the rate limiter's script). The accept thread is one of those 8.
- **Not seen:** the accept queue itself.

**Day 9, the Gateway's own rate-limiter script: IMPLEMENTED AND TESTED; LOAD-RUN ONCE, ONE LAPTOP, STUB GATEWAY** (`decisions.md`, "Day 9: the Gateway's own rate-limiter script"; `docs/testing.md`, same title).
- **The change:** `PreloadedRedisScript` (text read once, SHA1 computed once, no lock, no file check) is wired through our own `redisRateLimiter` bean (same class and constructor as the library). The library's script bean cannot back off: it has no `@ConditionalOnMissingBean`.
- **Unchanged:** rates, burst, keys and fail-open.
- **Tests:**
  - A drift test pins our copy of the Lua script to the library's.
  - A context test proves ours is the only limiter.
  - The drift guard, the context test and fail-open (Redis really stopped) were each broken once and restored.
  - Suites 686.
- **Bench, inside the boot jar:** 8 threads went from 9,942 calls per second with about one exception per call to no lock and no exceptions.
- **One R2 run (R2fix):**
  - 500 of 500 sold, zero oversell, gap 0, breaker closed, reconciler 0 / 0.
  - Refused 947 (was 2,899 to 3,978).
  - But Gatling p99 31.3 s (was 6.0 to 8.0 s), Order p99 9.8 s, Inventory p99 2.2 s.
  - 118 × 503: 63 Inventory read timeouts, 55 Order pool timeouts.
  - 2,938 Redis timeouts.
- **Not proven:** that the lock caused the refusals (runs vary a lot), or that the delay moved downstream. Not repeated.

**Day 9, Part B (observability): IMPLEMENTED and TESTED; smoke-run once.** Order, Inventory, Identity and the Gateway expose `/actuator/prometheus` (plus `health` and `env`, nothing else) on their own ports, with HTTP latency histogram buckets so Prometheus computes p99; Order's payment breaker state is exported (`resilience4j_circuitbreaker_state`). Prometheus and Grafana run from `docker-compose.yml`; the datasource and the single dashboard are files in `monitoring/` (`decisions.md`, "Day 9, Part B"). **Headline p99 is Gatling's (buyer view); Grafana's is server-side and explains where the time goes.** Not authenticated, local only. No outbox gauge. Suites 2026-11-08 after the change: identity 119, inventory 115, order 310, notification 29, payment-stub 22, api-gateway 52, eureka-server 2, config-server 1 = **650**, all passing (baseline 638 + 12 new; one laptop; the payment-stub flake did not occur in this run). Not measured: anything under load.

**Day 9, Part 0 (config server): IMPLEMENTED and TESTED; smoke-run once.** `config-server` (port `8888`, Spring Cloud Config, Git backend on the public `flashmart-config` repo) serves the tunable numbers of order, inventory, identity and the Gateway; each client keeps a local fallback copy, env vars beat both, values apply on restart (`decisions.md`, "Day 9, Part 0"). Suites run 2026-11-08 after the change: identity 117, inventory 113, order 308, notification 29, payment-stub 22, api-gateway 46, eureka-server 2, config-server 1 = **638**, all passing, 0 failures (baseline before the change: 637; one laptop; the payment-stub suite failed in some runs: 2 of 5 reruns, same test each time; cause diagnosed as a test-isolation bug, where `slowBeforeCreatesNothingUntilTheDelayHasPassed` left a delayed payment `k1` that landed in the next test's store; fix: that test now polls until `k1` appears (5000 ms timeout). Tested: payment-stub alone 10 of 10 runs passed 22/22, one laptop, flake not reproduced in 10, not proven fixed; `docs/testing.md`). Not built: a check that the repo and the local copies agree, a timeout for a hung (not stopped) config server, any config-server security.

*Day 8 Identity implementation notes:*
- Login also rejects any password over 72 bytes: bcrypt ignores everything past
  72 bytes, so "stored password + anything" would otherwise match.
- A stored hash without the `{bcrypt}` prefix (the seed's placeholders) gives
  `401`, not `500`; `DelegatingPasswordEncoder` would otherwise throw.
- Dependencies: jjwt 0.13.0 (api compile; impl and jackson runtime) and
  `spring-security-crypto` 7.1.1. **No `spring-boot-starter-security`.**
- `jjwt-jackson` brings Jackson 2, so Jackson 2 and Boot's Jackson 3 are both on
  Identity's classpath.
- Signup writes **no `UserUpserted` outbox row** (Identity's outbox events are
  designed only); users created by signup are covered by the one-off backfill
  republish (§9).
- Refresh and logout: the rotation CAS and revoke-all use `clock_timestamp()`;
  a refresh with a logged-out token counts as reuse (accepted cost, `decisions.md`
  Day 8 point 4). Not tested: see `docs/testing.md`, "Day 8 Identity: built and
  tested, and what was not tested".

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
  attached to both identity tables. **Day 8 (MockMvc-tested, see the Day 8 note
  above):** V8 `refresh_tokens` and `RefreshToken`, plus signup, login,
  refresh and logout.
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
  order per buyer per sale); the database rule exists and is tested, and since
  Day 4 the create endpoint maps its violation to `409 ORDER_ALREADY_OPEN` (below). V7 (hardening from an independent
  static review) adds `ck_orders_payment_ref_present_when_payable`, three
  non-blank guards (`orders.idempotency_key`, `orders.payment_ref`,
  `refunds.payment_ref`) and widens `orders.amount`, `order_items.subtotal` and
  `refunds.amount` to `NUMERIC(14,2)`; implemented and tested. V8 adds
  `ck_refunds_processing_has_claimed_at` and `ck_orders_reconciled_only_when_failed`
  (the four failure states); implemented, tested with native SQL that rejects a bad
  row for each (routine CHECKs, no break-and-restore). The create endpoint (Day 4,
  below) is the first code in the running application that writes a CAS. **A1:** the CAS-owned columns of `Order`
  (`status`, `amount`, `payment_ref`, `expires_at`, `payment_reconciled_at`) and
  `Refund` (`status`, `attempts`, `next_retry_at`, `claimed_at`, `last_error`) are
  `updatable = false`, as are the set-once columns `Order.buyer_id`,
  `flash_sale_id`, `idempotency_key` and `Refund.order_id`, `payment_ref`,
  `amount`; `@DynamicUpdate` was removed (it protected nothing). Only
  `Order.failure_detail` is writable by `save()`; `Refund` has no writable
  column. Implemented and **run on 2026-11-03**; break-and-restore done: 5 of
  the 6 original guards were killed, `@DynamicUpdate` was the survivor and was
  removed, and each of the six set-once columns is killed by a named test.
  **Test totals:** see "Tested" below (637 on 2026-11-08; 490 on 2026-11-06 was after Day 7).
  **Day-1 repositories:** `OrderRepository` has one native compare-and-set per
  transition (set amount, `CREATED` to `RESERVATION_FAILED` / `PAYMENT_UNAVAILABLE`,
  `CREATED` or `PENDING_PAYMENT` to `ORDER_EXPIRED` with no time condition,
  `PENDING_PAYMENT` to `PAID` only for the held `payment_ref`, `PENDING_PAYMENT`
  to `PAYMENT_FAILED`), plus two finders; written with winner/loser tests,
  **run on 2026-11-03** (part of the 260-test pass above). Break-and-restore on
  three of the CAS guards (`markPaid`'s `payment_ref` condition,
  `markReservationFailed`'s `CREATED` condition, `expire`'s status guard) was
  killed by a test each. Since Day 4, `markReservationFailed` also writes
  `failure_detail` in the same statement. `CREATED` to `PENDING_PAYMENT` was
  added on Day 5 (`markPendingPayment`, below); Day 6 added `amount` to
  `markPaid` and `payment_ref` to `markPaymentFailed` (below). Inventory's Day-1 repositories (`FlashSale`,
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
  `docs/development.md`): written, `docker compose config` validated. Only the
  `kafka` service has been started (Day 6 end-to-end run, below); `postgres`
  and `redis` from compose have never been started or tested (the local
  Postgres 18 owns port 5432).
- **Order create path (Day 4, branch `feat/day4-order-create`, 2026-11-05):
  implemented, tested, raced; not run against a real Inventory, not load-tested.**
  Design: `decisions.md`, "Order creation: retries, conflicts, deadlines and Inventory failures".
  - `POST /api/orders` (via the Gateway; Order verifies the signed identity): `Idempotency-Key` (non-blank, max 255)
    and the buyer from the Gateway-signed identity headers (Day 8, built; was a
    forgeable `X-Buyer-Id` until then, see §3, "Edge: Gateway"). Bean Validation added to Order. Quantity 1..5 per line and on the
    merged total of a duplicated product; at most `maxLines` = floor((60 s - 6.5 s) / 3 s) =
    17 distinct products (Day 5: derived in `CreateOrderSettings` from config, not
    typed in; tested: 17 accepted, 18 is a `400`). A bad request is a `400` and Inventory is never called
    (tested).
  - The insert is a native `INSERT ... RETURNING id` with `expires_at =
    clock_timestamp() + 3 s x lines + 6.5 s + 5 s`, and the unpriced items, in one
    transaction (tested: the gap is the deadline length on the database clock;
    3 lines = 20.5 s, 17 lines = 62.5 s, from config). The 6.5 s is the payment
    call's budget (2 attempts x (1 s + 2 s) + 500 ms), from the same `payment.*`
    values the payment client uses (Day 5 payment step, below).
  - Unique-violation losers: any SQLState `23505`, re-queried in a new
    transaction, constraint name never read: (a) same key: `409
    IDEMPOTENCY_KEY_REUSED` if the stored sale or merged items differ (checked
    first), else replay (`RESERVATION_FAILED` replays as `409` with no reason and
    no Inventory call; `CREATED` before the deadline resumes the loop; otherwise
    `200` with the current state); (b) `409 ORDER_ALREADY_OPEN` with the existing
    order; (c) `409 CONFLICT_RETRY` (reached in a test only by forcing (b) to find
    nothing).
  - Reserve loop over the unpriced items, sequential, no transaction open across
    a Feign call: write-once pricing guarded on `status = 'CREATED'` (a subquery,
    not a lock); a reserve "no" or an Inventory failure CASes to
    `RESERVATION_FAILED` with `failure_detail` and writes `OrderCancelled` only if
    the CAS won (`409 RESERVATION_FAILED` with the reason, or `503
    INVENTORY_UNAVAILABLE`); `RESERVATION_RELEASED` writes nothing and, if Order
    still says `CREATED`, logs ERROR and answers `500 INCONSISTENT_STATE` (the Day 7
    reconciler's case, not fixed here); all priced sets the amount through the
    existing `setAmount` CAS. The order stays `CREATED`.
  - Feign: Spring Cloud 2025.1.2 (OpenFeign 5.0.2, feign-core 13.6.1) on Boot
    4.1.1; the application context starts with the client (context test passes).
    The client is **tested against a JDK `HttpServer` stub**, not verified against
    Inventory: decoding with the price as a string, a slow stub hitting the read
    timeout, a refused connection, a `400`. No in-call retries. Values: connect timeout 1 s, reserve (read) timeout 2 s, max-blocking policy 60 s, deadline margin 5 s, all **provisional**, taken from Inventory load runs on one laptop that were not repeated.
    Day 5: the deadline is now re-derived (3 s per call, 6.5 s payment budget).
  - Races, real threads on real Postgres, Inventory mocked at the Feign interface
    or at `InventoryCalls` (no real Inventory process), 50 rounds each, all passed:
    (i) same buyer, sale and key twice at once: one order, one `201` and one `200`
    replay of the same id, and after the loop the order is `CREATED`, every item
    priced once, amount = `SUM(subtotal)`, no outbox row; (ii) same buyer and
    sale, two keys: one order, the other `409 ORDER_ALREADY_OPEN` naming it; (iii)
    two retries resuming one `CREATED` order: each item priced by exactly one loop
    and the amount set by one CAS; with item 2 out of stock and both threads held
    by a barrier right before the fail CAS, exactly one `RESERVATION_FAILED` and
    one `OrderCancelled`.
  - **Transaction boundaries (2 tests, commit `40cc776`):** the fail CAS and its
    outbox row share one transaction (rolled back together by a surrounding
    transaction that throws); no transaction and no pooled connection is held
    across a Feign call (inside the mocked reserve, `isActualTransactionActive()`
    is false and Hikari reports 0 active connections). Called directly, not over
    HTTP. Each was shown to fail when broken (`fail()` as `REQUIRES_NEW`;
    `@Transactional` on `create()`), then restored. **`open-in-view` is enabled
    (the Spring default; Spring logs its warning at startup) and NOT verified
    over a real request; check it first if the Day 9 load test shows pool
    pressure.**
  - Break-and-restore (each test failed, then the code was restored): skipping the
    live-or-paid lookup broke race (ii) in 50 of 50 rounds; skipping the same-key
    lookup broke race (i) in 50 of 50; dropping the `rows == 1` check before the
    outbox insert gave 2 outbox rows in 50 of 50 rounds of race (iii).
  - **Not exercised:** a real Inventory over HTTP, the expiry sweep (not built; *built on Day 7, see §8*),
    the relay and any consumer (Days 6 and 7, so stock release after
    `OrderCancelled` is not tested), any load. The late-reservation gap (a
    timed-out reserve that still commits in Inventory after the order failed) is
    still covered only by Day 7 designs. (The relay was built on Day 6, and the
    sweep, Inventory's consumers and Version A of the republish on Day 7, below;
    stock release after `OrderCancelled` is tested on Inventory's side with
    hand-written messages on an embedded broker, and seen once end to end in the
    manual run (not an automated test through Order's real producer). Version B
    of the gap remains open, see §9.)
- **Day 5, fee and deadline (merged from `feat/day5-fee-deadline`): IMPLEMENTED and
  TESTED** on real Postgres; all values **provisional**, unmeasured, recheck
  Day 9. Design: `decisions.md`, "Day 5: payment step, resilience and derived
  deadlines", points 1 and 6. `amount = SUM(subtotal) + 49.00` in the existing
  CREATED-guarded CAS (no column, no migration; a later fee change does not move
  a priced order; free-only order = 49.00; the `amount > 0` guard rejects zero
  and negative). `maxLines` (17) and the deadline come from `payment.*`,
  `inventory.*` and `order.create.*` config. Break-and-restore: removing
  `status = 'CREATED'` from `setAmount` had left the old test green (it used an
  order that already had an amount); a new test with a NULL-amount, non-CREATED
  order fails without the guard. Not exercised: any payment call.
- **Day 5 payment step (merged from `feat/day5-payment-step`): IMPLEMENTED and
  TESTED against a JDK HttpServer stub and mocks; VERIFIED UNDER FAILURE against
  the real payment-stub on one laptop (the failure demo below); not against a
  real gateway, not load-tested.** Design and the
  loser/replay rules: `decisions.md`, "Day 5: payment step, resilience and
  derived deadlines". Values provisional.
  - Flow after the reserve loop and amount: `requirePayable` → `initiate` (no
    transaction or pooled connection held; tested) → PENDING_PAYMENT CAS →
    answer. `PaymentGatewayClient` (Feign, `payment-gateway.base-url`,
    timeouts from `payment.*`, no Feign retry), `Idempotency-Key = order-<id>`,
    amount as a JSON string.
  - `PaymentCalls`: resilience4j core 2.3.0 (no Boot starter; the context
    starts), retry OUTSIDE the breaker: 2 attempts, fixed wait, only timeouts,
    connect errors and 5xx. Breaker COUNT_BASED 10 / min 5 / 50% / open 10 s /
    3 half-open; 4xx ignored.
  - CAS: `markPendingPayment` sets status, `payment_ref` and `expires_at`
    (payment window 6 min, DB clock) in one statement; `failPaymentUnavailable`
    = CAS with `failure_detail` + `OrderCancelled{PAYMENT_UNAVAILABLE}` in one
    transaction, the row only if the CAS won. Both `status = 'CREATED'`.
  - Outcomes: 201 `{orderId, status, amount, paymentRef, expiresAt}` for the
    CAS winner; 503 PAYMENT_UNAVAILABLE (retries exhausted, breaker open); 500
    PAYMENT_REJECTED + ERROR log on a 4xx, order still PAYMENT_UNAVAILABLE; CAS
    lost to the sweep → 409 ORDER_EXPIRED; lost to a same-key request → 200 with
    the winner's body. Replays: PENDING_PAYMENT → 200 same body;
    PAYMENT_UNAVAILABLE → 503, no new call; CREATED → resume with the same key.
  - Tested (real Postgres): every attempt carries `order-<id>`; a timeout then
    success gives one order and one ref; 5xx retried once; 4xx not retried and
    not counted by the breaker; the breaker opens on the 5th failed call,
    refuses without calling, and closes after 3 half-open successes; CAS losers
    get 0 rows (vs the sweep, vs a second request); the PENDING_PAYMENT CAS sets
    all three columns; the PAYMENT_UNAVAILABLE CAS and its outbox row roll back
    together and the row exists only for the winner; two same-key requests
    raced (real threads, 50 rounds, gateway mocked) give one PENDING_PAYMENT
    and the same ref in both answers; two resumed retries raced (50 rounds) give
    one CAS win; crash with the amount set, then retry, ends PENDING_PAYMENT.
    Break-and-restore: the `status = 'CREATED'` guard of each CAS and the
    4xx-not-retried rule each failed their tests when broken.
  - **Breaker logging:** every state transition is logged at WARN with the
    failure rate and call counts (`PaymentResilienceConfig`); a unit test drives
    the real breaker bean through CLOSED -> OPEN -> HALF_OPEN -> CLOSED. On the
    half-open and closed transitions the logged rate is the new, empty window's
    (`-1.0%`); only `-> OPEN` lines carry the rate the decision used.
  - **Not exercised:** a real gateway (RZP-1 is assumed by the stubs: the JDK
    stubs return `pay_` + key, the payment-stub keys payments by
    `Idempotency-Key`), the webhook (Day 6), the sweep (Day 7), the relay and
    consumers (so stock release after `OrderCancelled{PAYMENT_UNAVAILABLE}` is
    not tested), any load. (The webhook and the relay have since been built on
    Day 6, and the sweep and Inventory's consumers on Day 7, below.)
- **Day 5 failure demo (`docs/testing.md`): VERIFIED UNDER FAILURE against the
  payment-stub only, on one laptop, one run per scenario (scenarios 3 to 5
  run twice), no load, no webhook.** Real inventory,
  order and payment-stub processes. Measured: stub ERROR_5XX gives `503
  PAYMENT_UNAVAILABLE` in 0.55 to 0.59 s once warm (1.6 s for the first request
  after start-up; two fast `500`s plus the 500 ms wait, not the timeout ceiling), the order `PAYMENT_UNAVAILABLE` with one
  `OrderCancelled`; once the breaker opened, requests failed in 37 to 68 ms with
  no call reaching the stub; OPEN -> HALF_OPEN -> CLOSED was observed in
  Order's log (half-open entered only at the first call after the 10 s); the
  breaker reopened at 3 of 5 failed calls (60%) as configured. SLOW_AFTER 3000
  ms: both attempts time out, `503`, but **the stub holds the payment created by
  attempt 1, and nothing refunds it** (the lost-reply gap; Day 6). Since Day 6 a
  SUCCESS webhook for such a payment records a `refunds` row (tested in the
  suite on a `PAYMENT_UNAVAILABLE` order; this exact scenario was not re-run),
  and nothing pays it out (refund worker not built). Switching
  the stub to HEALTHY inside the 500 ms retry wait gave `201` with the payment
  attempt 1 created (2 of 2 runs, timing dependent).
- **Day 5, still designed only:** refund worker (stretch). (The sweep was built on Day 7, below.)
- **Day 6 webhook handler (`POST /webhooks/payment`, order-service,
  `feat/day6-webhook`): IMPLEMENTED and TESTED on real Postgres; not
  load-tested; not run against a real gateway.** (When merged the payment-stub
  sent no webhooks and the relay was not built; both since built, see the Day 6
  items below.) Decisions: `decisions.md`, "Day 6: webhook, one-payment edge
  cases, PaymentConfirmed, outbox relay", points 1 to 5.
  - HMAC-SHA256 hex in `X-Signature` over the raw body bytes, checked before
    parsing, constant-time on the decoded bytes; secret from
    `PAYMENT_WEBHOOK_SECRET`, blank fails startup. `401` bad/missing/tampered
    signature, `400` malformed, `404` unknown order (WARN), `409` still
    `CREATED` (B4, nothing written), `200` after commit.
  - `markPaid` CAS now also matches `payment_ref` and `amount` (numeric);
    `markPaymentFailed` now matches `payment_ref`. SUCCESS winner writes
    `PaymentConfirmed{order_id, buyer_id, items[], amount}` (money as strings,
    no `brand_id`) in the same transaction; a loser re-reads, treats `PAID` with
    the same ref as a redelivery, and otherwise inserts a `refunds` row with the
    webhook's ref and amount, `ON CONFLICT (payment_ref) DO NOTHING` (B1); WARN
    on every refund, naming an amount mismatch. FAILED winner writes
    `OrderCancelled{PAYMENT_FAILED}`; a loser writes nothing.
  - Tested: happy path (one `PaymentConfirmed`, payload checked); 401 for
    missing, wrong, non-hex and tampered signatures with nothing written; 14
    malformed bodies are 400; unknown order 404; early SUCCESS and FAILED 409
    with nothing changed; redelivered SUCCESS is a no-op; a second ref on a
    `PAID` order gives one refund row however often it arrives; wrong amount
    leaves `PENDING_PAYMENT` and refunds the webhook amount; `499` pays a
    `499.00` order; FAILED for another ref is ignored, for the held ref gives
    `PAYMENT_FAILED` + `OrderCancelled`; SUCCESS on `ORDER_EXPIRED` and on
    `PAYMENT_UNAVAILABLE` (no ref held) records a refund.
  - **Raced** (real threads, real Postgres): webhook SUCCESS vs `expire` on one
    `PENDING_PAYMENT` order. Both orderings forced (the loser seen blocked on
    the row lock in `pg_stat_activity`, then 0 rows), plus 100 free-running
    rounds (two runs: webhook won 27 and 22, sweep won 73 and 78), always exactly one winner
    with only its side effects. The sweep is **simulated** (the `expire` CAS +
    an `OrderCancelled` row); the real sweep is Day 7.
  - Break-and-restore: `payment_ref` and `amount` in `markPaid`, `payment_ref`
    in `markPaymentFailed`, and `ON CONFLICT` on the refund insert each failed
    their tests when removed.
  - **Not exercised:** a real or stub gateway sending webhooks or retrying a
    `409`; the relay (outbox rows are written, never published; since built,
    see the next item); the refund worker (rows are `PENDING`, nobody pays them
    out); the reconciliation sweep.
- **Day 6 outbox relay + Kafka producer (order-service, `feat/day6-relay`):
  IMPLEMENTED; TESTED AGAINST AN EMBEDDED BROKER (`@EmbeddedKafka`, in-JVM
  KRaft) and real Postgres; docker-compose Kafka NOT run; not load-tested.**
  Decisions: `decisions.md`, "Day 6", point 6. Delivery is **at-least-once**.
  - Plain `spring-kafka`, wired by hand (`OutboxKafkaConfig`): `acks=all`,
    idempotent producer, String key/value; request, delivery and max-block
    timeouts all equal the ack timeout. Topics `payment-confirmed` and
    `order-cancelled` (1 partition) created by `NewTopic` beans. Key = order
    id; value = `payload_json` as stored (jsonb text: flat, bare, money as
    strings; jsonb's own key order and spacing).
  - `OutboxRelay.runOnce()`: one transaction; claim up to 50 unpublished rows
    by `created_at, id` with `FOR UPDATE SKIP LOCKED`; per row send, wait for
    the ack (send + ack capped at 5 s), then `published_at = clock_timestamp()`.
    First failure: `attempts + 1`, `last_error` (with root cause), stop the
    batch. Scheduled every 1 s (fixed delay); `outbox.relay.enabled=false`
    removes the schedule (off in the test config). Poll 1 s, batch 50, ack 5 s
    are **provisional, unmeasured**; recheck Day 9.
  - Tested: right topic, key and exact stored payload, then marked; a second
    pass sends nothing; topics exist with 1 partition. Broker unreachable
    (unused port, 1 s ack timeout): the pass fails in about one ack timeout,
    nothing marked, first row `attempts` 1 + `last_error`, later rows untouched;
    a working relay then sends all three in order. A `published_at` UPDATE
    failing after the ack (test trigger) rolls the pass back and the next pass
    re-sends: **2 messages, 0 lost**. Two relay instances (real threads): A holds
    rows 1-3 inside its first send, B skips them, sends 4-6 and finishes while
    A still holds; each row reaches Kafka once. End to end: webhook SUCCESS ->
    outbox -> relay -> a real consumer on `payment-confirmed` (amount `"499.00"`,
    no `brand_id`, no envelope).
  - Break-and-restore: marking before the ack made the unreachable-broker test
    fail (a row marked published with no ack, i.e. a lost event); removing
    `SKIP LOCKED` made the two-instance test fail (B waited on A's locks).
  - **Not exercised:** a separate broker process (the docker-compose Kafka was
    not started), a real process crash (the crash window is simulated by a
    failing UPDATE), any consumer service (Inventory's consumer is Day 7), a
    multi-partition topic, broker failover, any load or relay lag measurement.
    (A separate broker process has since been used; see the next item.)
- **Day 6 webhook + relay ALSO VERIFIED against real processes and the
  docker-compose Kafka, on one laptop (`docs/testing.md`, "Day 6 end to end").**
  Not load-tested; the gateway is the payment-stub, not a real gateway and not
  the Razorpay Clone; no consumer service. Real inventory, order and payment-stub
  processes, local Postgres, `apache/kafka:4.3.1` in Docker. Script run 3 times
  (run 1's Kafka read-back broken by a script bug, runs 2 and 3 clean). Observed:
  happy path to `PAID` and one `payment-confirmed` message (money as strings, no
  `brand_id`); SUCCESS x3 = one message, 0 refunds; a second ref on a `PAID`
  order = one refund row however often sent; wrong amount = `PENDING_PAYMENT` +
  refund of the webhook amount; early webhook = stub sees `409` then `200` about
  2 s later; bad signature `401`; a row written with the relay off is published
  after Order restarts (a stop, not a crash at the ack instant); with Kafka
  paused 31.5 s the relay failed a pass about every 6 s (`attempts` rose 0 to 5)
  and published as the broker resumed. **That paused run produced a DUPLICATE
  message (2 of 2 clean runs)**: copy 1 carries the time of the relay's first
  send (0.4 s after the pause), which the client timed out and cancelled; copy 2
  is the re-send after the unpause. Likely cause, consistent with Order's log
  but **not proven**: the paused broker still appended the cancelled request on
  resume, and the re-send went out under a new producer epoch, which the
  idempotent producer does not deduplicate. At-least-once as designed;
  consumers must be idempotent.
- **Payment-stub webhook sender (`feat/day6-stub-webhook`): BUILT and TESTED
  (its own suite, against a fake Order) and used in the run above.** Signs the
  exact bytes it sends (HMAC-SHA256, `PAYMENT_WEBHOOK_SECRET`, blank fails
  startup); retries every 2 s on any non-2xx or I/O error, gives up after 120 s
  (the stub's ASSUMPTION about a gateway, WARN at startup if not above Order's
  62.5 s longest CREATED deadline); `/admin/webhook` (stored-payment defaults,
  any field overridable, `times` for redelivery); `/admin/auto-webhook` (OFF,
  SUCCESS, FAILED; `BEFORE_REPLY` sends attempt 1 before the initiate `202`).
- **Payment stub (`payment-stub/`, merged from `feat/day5-payment-stub`): BUILT
  and TESTED ALONE (its own suite); run against Order only in the failure demo
  above; NOT verified against a real gateway.** Listens on 127.0.0.1 only.
  A separate small Spring Boot app on port 8085, its own process (so Order's real
  timeouts, retries and breaker are exercised over real HTTP), no DB,
  in-memory `ConcurrentHashMap` keyed by `Idempotency-Key`.
  `POST /api/payments/initiate`: same key and body replays the same
  `paymentRef` (`202`); same key with a different body is `400
  IDEMPOTENCY_KEY_REUSED`; the lookup and create are one atomic step per key;
  bad amount or currency is `400 INVALID_REQUEST`. `POST /admin/mode` switches
  HEALTHY, SLOW_BEFORE, SLOW_AFTER, ERROR_5XX, REJECT_4XX (local use only).
  Tested over real HTTP with real threads: replay, different-body, 8 threads x 50
  rounds on one key giving one `paymentRef`, invalid inputs, a SLOW_AFTER
  client timeout followed by a same-key retry returning the same `paymentRef`,
  and 5xx/4xx modes creating nothing. Break-and-restore done on the same-key
  lookup (4 tests failed) and on the per-key atomicity (the race test failed in
  3 of 3 runs). **Money is a decimal string here; real gateways use integer
  minor units**, so this is not a model of one. It is not the Razorpay Clone and
  proves none of RZP-1 to RZP-9. Not built: refund, status lookup (webhooks since built, see the Day 6 items above).
- **Day 7 Inventory consumers (`feat/day7-inventory-consumers`): IMPLEMENTED and
  TESTED on real Postgres and an embedded broker (`@EmbeddedKafka`); also VERIFIED
  UNDER FAILURE on real processes (the end-to-end item below); not load-tested.**
  Derived from `decisions.md`, "Day 7", points 1 to 4. Delivery is
  **at-least-once**; nothing here is exactly-once.
  - Plain `spring-kafka`, wired by hand (`InventoryKafkaConfig`, as Order's
    producer). Group `inventory-service`, String deserializers, **auto-commit off
    and ack mode RECORD, so the offset moves only after the listener returns, i.e.
    after the database transaction commits**. `auto.offset.reset=earliest` (chosen
    while building, not in the decision entry: a new group reads whatever is still
    on the topic). `kafka.consumers-enabled=false` in the test config so no test
    context connects to a broker unless it asks to.
  - `ReservationEventService`, plain `JdbcTemplate`. **Release** (`OrderCancelled`):
    `UPDATE stock_reservations SET status = 'RELEASED' WHERE order_id = ? AND
    status = 'RESERVED' RETURNING ...`, then add each returned quantity back to
    `available_stock` with `version = version + 1`, in ascending item id, in ONE
    transaction. The status guard decides the winner under READ COMMITTED: a
    duplicate matches 0 rows and adds 0 stock; a `CONFIRMED` row is never matched.
    The `available_stock <= total_stock` CHECK stays as the backstop. **Confirm**
    (`PaymentConfirmed`): same guard to `CONFIRMED`; on 0 rows it reads the state:
    all `CONFIRMED` is a quiet duplicate; any `RELEASED`, or no rows, logs ERROR
    with the order id and changes nothing (never flipped back).
  - Listeners need only `order_id`; all four `OrderCancelled` reasons release.
    A malformed message (broken JSON, missing or non-integer `order_id`) is not
    retried: skipped with an ERROR holding the raw message. Any other exception
    is retried with exponential backoff (1 s, x2, cap 30 s, no limit on attempts),
    one WARN per failure. **No dead-letter topic.** The three backoff numbers are
    **provisional, unmeasured**.
  - Tested (Inventory suite 81 -> 102): release gives the stock back, a
    multi-item order releases every item, only this order's rows move, the same
    release twice adds stock once (version unmoved the second time), two threads
    releasing at once add stock once (50 rounds), a `CONFIRMED` row is never
    released, confirm duplicate is quiet, confirm on `RELEASED` / mixed / missing
    logs ERROR and changes nothing. On the embedded broker: all four reasons
    release, `OrderCancelled` after `PaymentConfirmed` leaves `CONFIRMED`, a
    duplicate message adds stock once, four malformed payloads are skipped (one
    ERROR each, no retry WARN) and the valid message after them still runs, a
    database error (trigger on the item row) is retried with WARNs while the
    transaction rolls back, the offset stays put, and when the database recovers
    the same message succeeds with stock up once, and a message that fails once is
    redelivered with one add-back.
  - Break-and-restore: removing `AND status = 'RESERVED'` from `release` failed 5
    of the 21 new tests (the duplicate and concurrent ones among them; two of
    those fired the `available_stock <= total_stock` CHECK instead of an
    assertion). Restored.
  - **Not exercised by these tests:** a real broker process, a rebalance, more
    than one partition or instance, a JVM crash between the commit and the offset
    commit (a database error that rolls back stands in for it), Order's real
    producer, load.
- **Day 7 Order: expiry sweep, drain loop, V9 index, late-reservation republish
  (`feat/day7-order-sweep`, plus the drain loop on `test/day7-e2e`): IMPLEMENTED
  and TESTED on real Postgres; EXPLAIN looked at; also VERIFIED UNDER FAILURE on
  real processes (next item); not load-tested.** Derived from `decisions.md`,
  "Day 7", points 5 to 7, and the Day 5 sweep interval.
  - **V9** (never an edit of V2): `idx_orders_expiry_sweep_live ON orders
    (expires_at) WHERE status IN ('CREATED', 'PENDING_PAYMENT')`; V2's
    `idx_orders_expiry_sweep` is dropped; `idx_orders_reconciliation_sweep` is
    untouched. Tested by reading `pg_indexes` and `pg_index`'s predicate. **EXPLAIN
    (about 5,000 terminal rows plus 5 live, one laptop):** the planner's own
    choice in the full suite was a bitmap scan on `idx_orders_reconciliation_sweep`
    (its leading `status` column also matches); with seq scans off and that index
    dropped inside a rolled-back transaction it used the partial index, so the
    partial index CAN serve the query (the literal statuses match its predicate).
    **No timing was taken; no speedup is claimed.**
  - **Sweep** (`ExpirySweep`, `ExpiryTransaction`, `ExpirySweepScheduling`): every
    30 s (`order.sweep.interval-ms`, fixed delay). One pass selects up to
    `batch-size` (100) due ids with their status, statuses written as LITERALS,
    `ORDER BY expires_at`, `clock_timestamp()`; then each order gets its own
    short transaction through a proxy bean: the existing `expire` CAS, and
    `OrderCancelled{ORDER_EXPIRED, buyer_id}` in the same transaction only if the
    CAS returned 1. No `SKIP LOCKED`. One failing order is logged and the rest of
    the batch still runs. **Drain loop:** a tick runs another pass at once while
    the last one picked a full batch; it stops when a pass picks fewer than
    `batch-size`, when a pass expired nothing, or after `max-passes-per-tick`
    (10). `batch-size` and `max-passes-per-tick` are **provisional, unmeasured**.
    The sweep is switched off in the test config.
  - **Late-reservation republish, Version A only:** when the reserve loop gets
    `RESERVED` but the order already left `CREATED`, one `INSERT ... SELECT`
    writes a new `OrderCancelled` (reason = the status) only while the order is
    `RESERVATION_FAILED`, `PAYMENT_UNAVAILABLE`, `ORDER_EXPIRED` or
    `PAYMENT_FAILED`; never for `PAID` or `PENDING_PAYMENT`. Not on the
    `RESERVATION_RELEASED` path or the Inventory-timeout path (Version B is a
    documented gap; see §9).
  - Tested (Order suite 268 -> 290): due `CREATED` and `PENDING_PAYMENT` expire with
    exactly one `OrderCancelled` each; not-yet-due and terminal orders untouched;
    batch limit, oldest first, leftovers next pass; one failing order does not
    block the others and is retried; drain loop (backlog bigger than the batch
    drains in one tick, a short batch stops it, the cap stops it, a pass that
    expired nothing stops it); **raced on real Postgres** (real threads, the loser
    seen blocked on the row lock, then 0 rows): the real sweep pass vs the webhook
    (replaces the earlier simulation; both orderings forced, 100 free rounds),
    sweep vs the payment start (both orderings forced, 100 free rounds; payment
    won some rounds and the sweep others, always exactly one winner), and two
    passes at once (20 rounds x 10 orders: one `OrderCancelled` per order); the
    republish for each of the four states and none for `PAID` / `PENDING_PAYMENT`.
  - Break-and-restore: writing the sweep's outbox row without checking `expire`'s
    rows-affected failed 3 of the 4 sweep race tests (one order got 2
    `OrderCancelled` rows); adding `PAID` to the republish list failed its test.
    Both restored.
  - **Not exercised by these tests:** the `@Scheduled` timer, two JVMs, Version B,
    the `409 ORDER_EXPIRED` answer from inside a race, load.
- **Day 7 VERIFIED UNDER FAILURE on real processes (`docs/testing.md`, "Day 7 end
  to end"). One laptop, payment-stub gateway, one broker, one partition, one run
  per scenario, no load.** Real inventory, order and payment-stub processes, local
  Postgres, the docker-compose Kafka. Observed: a failed payment released the
  stock (49 -> 50, 1.9 s from the webhook); a paid order's reservation was
  `CONFIRMED` and stock stayed down; an unpaid order (20 s window) expired 29.4 s
  after creation and the stock came back; 3 re-produced copies of one
  `OrderCancelled` on the real broker changed neither stock nor version; with
  Kafka paused the event stayed unpublished and the stock stayed down until the
  unpause, then it was published and released within 0.6 s (the topic held 2
  copies of the event, the Day 6 pause-duplicate again, and the stock rose by
  exactly one); with Inventory stopped, 2 failed + 1 expired orders were
  published and, after Inventory restarted, the stock rose by exactly 3 with one
  release per order; a two-item order released both items. Also seen: on first
  start Inventory consumed 15 old Day 6 `PaymentConfirmed` messages and logged 15
  ERRORs (their orders did not exist in the freshly seeded database).
  **Not covered:** a crash between the commit and the offset commit, several
  instances or a rebalance, more than one partition, the drain loop on a real
  backlog, a malformed message on a real broker, load, a real gateway or the
  Razorpay Clone.
- **Not built anywhere:** repositories beyond the Day-1 ones above (apart from
  the ones `reserve` added), service implementations other than Inventory's
  `reserve`, Order's create path, payment step, webhook handler, outbox relay and
  expiry sweep, and Inventory's two consumers; any controller other than
  Inventory's reserve endpoint, Order's `POST /api/orders` and `POST
  /webhooks/payment` (and the payment-stub's own); the API Gateway, the
  reconciliation sweep, the refund worker, Inventory's stale-reservation
  reconciler, Order's internal status endpoint, Identity's relay, Notification's
  consumers (nothing consumes `BrandUpserted` / `UserUpserted`), Redis, the
  signed-header check and any security in Order or Inventory. (Identity's four
  `/api/auth/**` controllers and V8 exist since Day 8; the Gateway does not.)
  All four services share one local Postgres credential pair but each owns a
  separate database.

**Tested after Day 7 (superseded by 637 on 2026-11-08, see Day 8 above): 490 tests, all passing, 0 failures, 0 errors,
0 skipped** - all five suites run on 2026-11-06 (branch `docs/day7-status`, no code
change against `main` `b3dc125`), no Docker needed (the relay and consumer tests
use an embedded broker): Order 290, Inventory 102, Identity 47, Notification 29,
payment-stub 22. Since Day 6: Order +22 (`ExpirySweepTest` 10, `ExpirySweepRaceTest`
4, `LateReservationRepublishTest` 7, one V9 index case in
`OrderSchemaHardeningTest`) and Inventory +21 (`ReservationEventServiceTest` 12,
`InventoryConsumerKafkaTest` 9).

**Day 6 figure (kept for history): 447 tests, all passing, 0 failures, 0 errors,
0 skipped** — all five suites run on 2026-11-06 (branch `docs/audit-day6`, no
code change against `main` `44c751b`), Order DB reset with `seed.sh` first, no
Docker needed (the relay tests use an embedded broker): Order 268, Inventory
81, Identity 47, Notification 29, payment-stub 22. Order's 51 since Day 5:
`PaymentWebhookControllerTest` 38, `PaymentWebhookRaceTest` 4,
`OutboxRelayTest` 6, and 3 new amount/ref cases in `OrderRepositoryCasTest`
(26 now); payment-stub's 10 are `WebhookSenderTest`.

**Day 5 figure (kept for history): 386 tests, all passing, 0 failures, 0 errors,
0 skipped** — all five suites run on `main` on 2026-11-05 after the Day 5
merges, Order DB reset with `seed.sh` first: Order 217, Inventory 81,
Identity 47, Notification 29, payment-stub 12. Order's 38 since Day 4: the fee
and deadline (11), the payment step (24), the breaker transition log (3).

**Day 4 figure (kept for history): 336 tests, all passing** — all four
services run on branch `feat/day4-order-create` on 2026-11-05: Order 179,
Inventory 81, Identity 47, Notification 29 (the previous four-service figure
was 284: Order 127 plus 81, 47, 29). Order's 52 new tests are V8 (11), the
Feign stub test (5), the insert (3), the endpoint (8), conflict handling (10),
the reserve loop (9), the races (i)/(ii) and (iii) (2 each) and the two
transaction-boundary tests (2). Order's earlier 127 are the 99 from V7 (the earlier 69
plus 30 new for V7 and the review's gaps) plus 28 added since for the
repositories and the entity-updatability tests (the V7 constraint
that fires is now asserted by name; native-SQL `NOT NULL` tests; index
predicates read from `pg_indexes`). Each count includes a context test
and the zone-less-timestamp guard every service carries. The persistence tests
save into real Postgres inside a rolled-back transaction; every CHECK, unique
key and FK is verified to reject bad rows, not merely to exist.

**Inventory after `reserve`: 73 tests, all passing, 0 failures, 0 errors, 0
skipped** (inventory suite only, run on branch `feat/reserve-service` on
2026-11-04; the other three services were not re-run for this count). That was
the 63 Inventory had then plus 10 in `ReservationServiceTest`. It covers, single-threaded:
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
`8084`; `8080` is reserved for the API Gateway and `8761` for the Eureka server
(both built on Day 8). `8085` is the payment
stub, on 127.0.0.1 only (built and tested; run against Order in the Day 5
failure demo and the Day 6 end-to-end run; see §8). Kafka (docker-compose) on
`localhost:9092`.

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
- **Day 5 decisions: written; the deadline, fee, payment step and (Day 7) the sweep
  since implemented and tested (below); the refund worker not built** (all
  **provisional**, unmeasured, recheck Day 9; see `decisions.md`, "Day 5: payment
  step, resilience and derived deadlines"): the `CREATED` deadline is re-derived
  (3 s per reserve call = connect 1 s + read 2 s; retry ceiling 6.5 s;
  `maxLines` = floor((60 - 6.5) / 3) = 17; deadline = 3 s x lines + 6.5 s + 5 s
  margin); payment window 6 min; sweep interval 30 s; refund claim lease 2 h.
  **Implemented and tested for the fee and the deadline** (`maxLines` 17, 3 s per
  call; §8); the payment client and breaker are implemented and tested against a
  stub only (§8); the 30 s sweep interval is implemented and tested since Day 7
  (§8); the refund claim lease is still unused (refund worker not built). Pool size
  and DB timeout values stay open.
- **Test isolation (noted, not fixed):** 4 Order entity tests
  (`OrderPersistenceTest`, `OrderSchemaHardeningTest`, two in
  `OutboxEventPersistenceTest`) count absolute rows in the shared dev database,
  so they failed after the Day 5 demo left orders and outbox rows behind, and
  passed after `seed.sh` reset them. Reset before running the suite until they
  count only their own rows.
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
- **DECIDED 2026-11-05 (see "Who stamps a time column depends on who compares
  it" in `decisions.md`): deadlines use `clock_timestamp()`, audit and ordering
  stamps may use `now()`, `reserved_at` stays `now()`. Original text kept
  below.** **Two database clock functions are in use for the same job.** `stock_reservations.reserved_at` defaults to `now()`
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
- **Closed by decision; fee implemented and tested: zero-amount order.**
  `sale_price` may still be `0.00`, but a flat 49.00 fee on every order makes
  the payable amount > 0 (`decisions.md`, "Day 5: payment step, resilience and
  derived deadlines"). An `amount > 0` assertion before `initiate` is a bug
  guard only. The fee and the guard are tested (§8); since Day 5 the guard
  (`requirePayable`) runs right before the `initiate` call.
- **FR-B1 (buyer browse): designed on Day 8, not built; no endpoint exists.**
  Moved out of the open list: the design (uncached, paged, an `inStock` flag,
  never an exact count) is in §3, "Edge: Gateway" and `decisions.md`, "Day 8".
  It no longer waits for caching.
- **Decided 2026-11-05; implemented and tested (Day 4):** on
  `RESERVATION_RELEASED` mid-loop Order stops the loop, writes no CAS and no
  event, re-reads the real status and reports it; if still `CREATED`, it logs
  ERROR and answers `500 INCONSISTENT_STATE`, and does not guess. Repairing that
  state is the stale-reservation reconciler's case (designed, not built; Day 7 did not build it), not fixed here.
- **Decided; implemented and tested (Day 4):** all three reserve "no" outcomes
  (`OUT_OF_STOCK`, `SALE_NOT_LIVE`, `ITEM_NOT_IN_SALE`) end the order as
  `RESERVATION_FAILED`.
  No new state, no migration (§6).
- **Decided 2026-11-05; implemented and tested (Day 4):** a retried create on a
  `RESERVATION_FAILED` order replays the failure with no reason and never
  calls Inventory; a new attempt needs a new key. `failure_detail` stays
  debug only.
- **Bean Validation exists in Inventory and Order** (Order added it on Day 4).
  Identity and Notification add `spring-boot-starter-validation` in their own
  API phase.
- **Done (Day 4), tested:** Order's create request rejects `quantity > 5` (per
  line and on a merged duplicate) with a `400` before any `reserve` call;
  the test checks Inventory is never called.
- **Decided 2026-11-05; implemented, tested and raced (Day 4, see §8):** any
  unique violation on order insert is
  caught and re-queried in a new transaction (never parsing constraint names):
  same `(buyer_id, idempotency_key)` is a replay; else `409 ORDER_ALREADY_OPEN`
  with the existing order; else `409 CONFLICT_RETRY`. Never a `500`.
- **A buyer cannot start a new order while an unpaid one is open**, until it
  pays or expires. The wait is bounded by the payment window (6 min,
  provisional, Day 5; plus up to one sweep interval, now that the sweep exists).
  Accepted cost of the one-live-order rule.
- **One person with two accounts is not covered** by the one-live-order rule:
  the rule is per `buyer_id`, and Identity does not link accounts. The cap of
  5 is also still per item per order.
- **Decided 2026-11-05; implemented (Day 4):** a retried create while `CREATED`
  re-runs the reserve loop (abort by expiry **or** resume on retry), safe only if
  every write is CAS/write-once and the outbox row is written only when the CAS
  wins. Backed by two-thread races on real Postgres (§8, races (i) and (iii)),
  with Inventory mocked; not exercised against a real Inventory or the sweep. Also: same key with a
  different request is `409 IDEMPOTENCY_KEY_REUSED`, checked before replay.
  See the conditional rule on `order_items` (§4).
- **Identity, API phase: designed on Day 8, not built.** The lost signup race
  (`409`, never a `500`) and the JWT subject type (`typ`, because brand 7 and
  user 7 share an id) are decided in `decisions.md`, "Day 8", and are no longer
  open decisions. Still open: published `outbox` rows need a cleanup job, and
  so do expired `refresh_tokens` rows (designed only). Since Day 6 Order's relay
  marks its own `outbox` rows published, so Order has the same unbuilt cleanup
  need (noted, not decided).
- **New with Day 8 (see `decisions.md`, "Day 8"; DESIGNED, NOT BUILT; every
  value provisional and unmeasured):**
  - **Limits the design accepts:** `:8082` and `:8083` stay reachable directly
    (Inventory has no auth); a signed header can be replayed for 60 s; HS256
    means the Gateway can mint tokens; the per-IP key on `/api/auth/**` is
    unfair on shared networks; the two-tab refresh race logs a buyer out
    everywhere; access tokens are not revocable; browse offset paging can skip
    a sale that ends between pages.
  - **Open, not decided:** the refresh-token and outbox cleanup jobs; how the
    Day 9 load test gets many logged-in buyers (seeded users have unusable
    password hashes); whether the limiter really fails open in the chosen
    library (to be checked when built, not assumed); brand login (only `user`
    tokens are designed).
  - **Day 9 trap:** the oversell load test needs many buyers or a raised
    limit, or the per-buyer limit answers `429` before Inventory is reached.
- Build Razorpay Clone with the behaviours this design leans on
  (`docs/requirements.md` §9, `RZP-1`–`RZP-9`), above all: idempotency on
  `initiate` and `refund`, a payment-status lookup queryable by our idempotency
  key or order reference, and payments and webhooks that carry our order id.
  Not built yet; designed-not-verified until it is.
- **Found by independent review, Oct 29, not decided.** An independent static
  review of Order V1-V6 happened on 2026-10-29; the reviewer could not run the
  tests. What it found and V7 fixed is in §4 (V7). The items below were left
  alone on purpose, each waiting for a decision; each line says where it
  stands now (A2 is still undecided, and so is A3's refund-index part):
  - **A1:** entity updatability and `@DynamicUpdate`. **Decided** (see
    `decisions.md`, "CAS-owned columns are insert-only in the entities"):
    implemented, **run on 2026-11-03**, break-and-restore done; later amended
    (`@DynamicUpdate` removed, six set-once columns locked, see the amendment
    in `decisions.md`).
  - **A2:** `refunds.next_retry_at` NULL handling and the refund claim query.
    Not decided. **Now live:** since Day 6 the webhook handler writes `PENDING`
    refund rows with `next_retry_at` NULL, so whoever builds the refund worker
    must decide whether NULL means "due now" before writing the claim query (a
    claim on `next_retry_at <= now()` alone would never pick these rows).
  - **A3:** redesign of the sweep and refund indexes. **Expiry-sweep part DECIDED
    and implemented, tested (Day 7, V9; §8)**; the refund-index part is still not
    decided.
  - **B1:** `ON CONFLICT DO NOTHING` for the refund insert. **Decided 2026-11-05
    (derived); implemented and tested (Day 6, §8; break-and-restore done)** ("Day 6: webhook, one-payment edge cases, PaymentConfirmed, outbox relay").
  - **B2, B3:** the retried create (and the `409` it returns) and a request
    fingerprint. **Decided 2026-11-05; implemented and tested (Day 4)** (no
    fingerprint column; compare against stored `flash_sale_id` and `order_items`).
  - **B4:** a webhook arriving before the order is `PENDING_PAYMENT`.
    **Decided 2026-11-05: answer `409`, change nothing; implemented and tested
    (Day 6, §8), and seen end to end: the stub got `409` then `200` on its retry
    about 2 s later** ("Day 6: webhook, one-payment edge cases, PaymentConfirmed, outbox relay").
  - **B5:** which clock function stamps `expires_at`. **Decided 2026-11-05:**
    `clock_timestamp()`, stamped by the database (native `INSERT ... RETURNING
    id`). Implemented and tested (Day 4).
  - A `CHECK` on `payment_reconciled_at`, and a `CHECK` that `PROCESSING`
    implies `claimed_at`. **Decided 2026-11-05; V8 implemented and tested.**
    `payment_reconciled_at` is allowed only on `PAYMENT_FAILED`,
    `PAYMENT_UNAVAILABLE`, `ORDER_EXPIRED` and `RESERVATION_FAILED` (any failure
    state; no payment can exist for the last).
- **New, 2026-11-05 (Order creation: retries, conflicts, deadlines and Inventory failures), implemented
  and tested on Day 4 (see §8):** an Inventory timeout or refusal mid-loop ends
  the order as `RESERVATION_FAILED` + `OrderCancelled`, `503
  INVENTORY_UNAVAILABLE`. **Known gap (Version B, documented, not fixed):** a timed-out call can commit after the
  release and orphan a reservation. Day 7's republish (Version A) is implemented
  and tested but cannot see this case; only the reconciler would, and it is
  designed, not built. The HTTP client is Spring Cloud OpenFeign 2025.1.2 on Boot 4.1.1:
  it resolves and the context starts; its behaviour is tested against a stub,
  not against Inventory. Values: connect timeout 1 s, reserve (read) timeout 2 s, max-blocking policy 60 s, deadline margin 5 s, all **provisional**, taken from Inventory load runs on one laptop that were not repeated; recheck on Day 9. The deadline
  was re-derived on Day 5 with retry attempts: **implemented and tested** (3 s per
  call, `maxLines` 17; see the Day 5 items above).
- **New with Day 6 (see `decisions.md`, "Day 6"; recorded, none decided here):**
  - **OPEN:** `brand_contacts` and `BrandUpserted` have no consumer now that
    `PaymentConfirmed` carries no `brand_id`. Drop, keep for the Notification
    stretch, or add `brand_id` back: not decided.
  - **Relay lock time is unmeasured.** Stopping at the first failure bounds a
    *failing* pass to about one ack timeout. A slow broker that still acks can
    hold the claim transaction (row locks and a pooled connection) for up to
    batch x ack timeout (50 x 5 s). Not measured; recheck Day 9.
  - **Duplicate observed:** with Kafka paused, one order's `PaymentConfirmed`
    was on the topic twice in 2 of 2 clean end-to-end runs (§8,
    `docs/testing.md`). Cause likely, not proven. Consumers must be
    idempotent; this is the expected at-least-once case, not a bug. Inventory's
    consumers (Day 7) are, by the status guard; the pause-duplicate was seen again
    on `OrderCancelled` and added stock once (§8, `docs/testing.md`, "Day 7 end to end").
  - **Known edge, wrong amount then right amount:** a wrong-amount SUCCESS
    leaves the order `PENDING_PAYMENT` with a refund row for the held ref; if the
    same ref later arrived with the right amount, the order would be `PAID`
    while that refund row exists. Needs a gateway that reports two amounts for
    one payment. The future refund worker should check that the order is not
    `PAID` with that ref before paying out. Not built, not tested.
- **New with Day 7 (see `decisions.md`, "Day 7"; implemented and tested, §8):**
  - **Known gap, Version B:** an Inventory `reserve` that times out at Order but
    still commits leaves a `RESERVED` row for an order Order has cancelled, and
    Order never sees it. Documented, not fixed. **Frequency UNMEASURED** (an
    earlier "rare" was a guess); count it after the Day 9 load test by comparing
    the two databases' order ids.
    **Update, Day 9:** counted (`count-gap.sh`; 285 late commits in R2F, all released by timing) and now
    released by Inventory's stale-reservation reconciler (§8).
  - **No dead-letter topic.** A skipped malformed message leaves that order's
    stock `RESERVED` until fixed by hand; the stale-reservation reconciler that
    would catch it is designed only.
  - **`expires_at < clock_timestamp()` is a filter, not an index condition**
    (`clock_timestamp()` changes per call, so the planner cannot use it as a range
    bound). The partial index gives the live-only set and the `expires_at`
    order. Switching to `now()` is a decision, deferred.
  - **One-partition topics.** Key = order id already keeps one order's events in
    order; more partitions would only help scaling (not needed, not tested).
  - **Inventory logs ERROR for a `PaymentConfirmed` whose reservations are
    missing, so stale or reset dev data triggers it:** 15 such ERRORs on the first
    start of the Day 7 run, from old Day 6 messages. They look the same as a real
    lost reservation. Not changed.
  - **PROVISIONAL, unmeasured (recheck Day 9):** sweep `batch-size` 100,
    `max-passes-per-tick` 10, the consumer retry backoff (1 s, x2, cap 30 s). The
    drain loop is built and unit-tested but was **not exercised on a real
    backlog**.
  - **Not tested:** a crash between the ack and the offset commit, several
    instances or a rebalance, a malformed message on a real broker, load, a real
    gateway.

The PRD is `docs/Flashmart.prd` (Revision 4, markdown). The earlier
`FlashMart_Project_Plan_v3.pdf` reference was stale — no such file exists or
ever existed in this repository.
