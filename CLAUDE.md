# CLAUDE.md — FlashMart

This file is instructions for Claude Code when working in this repo. Read it before touching any code.

## What FlashMart actually is

FlashMart is a flash-sale **infrastructure platform for brands** (e.g. Puma, Nike) — not a multi-vendor marketplace. A brand runs its own time-boxed, limited-stock sale; FlashMart does not aggregate independent sellers and does not handle vendor payouts or settlement (that scope was deliberately removed).

The real point of this repo is **not** "ship a working app." It's a structured vehicle for learning backend engineering and distributed systems — Spring Boot, Redis, Kafka, sagas, outbox, idempotency, resilience patterns, API gateway, and load testing — by building something with a genuine concurrency/consistency problem at its core (many buyers racing for a small, fixed pool of stock).

**Every architectural fact below is derived, not aspirational.**

## Document hierarchy

When documents disagree, this is the order of authority:

1. `docs/decisions.md` — the record of *why*. Highest authority.
2. `docs/architecture.md` — the current derived design.
3. `docs/requirements.md` — FRs/NFRs.
4. This file — a summary for Claude Code. If it contradicts the above, **the docs win and the mismatch should be flagged**.
5. `docs/Flashmart.prd` — the PRD (narrative overview: concepts, flows, build plan, status). Superseded by the above wherever they conflict. Now in the repo, Revision 4, regenerated to match `docs/architecture.md` through Revision 3 — the earlier `FlashMart_Project_Plan_v3.pdf` reference is stale and no such file exists; this is the current PRD.

The Notion engineering log is a personal progress record, not a source of truth. Its design sections (§1–5) are current as of this update — synced to Revision 3, diagrams included, as Mermaid blocks Notion renders natively. §7 (JPA Entities) records every entity written so far under a heading per service and per entity, and §9 (Infra Wiring) records the project setup; §6, §8, §10 and §11 remain empty because none of that exists yet. Treat it as a snapshot, not a live source: it will drift again the next time the design changes unless someone remembers to re-sync it.

## Non-negotiable working style

Claude Code must follow the same philosophy Claude (chat) has been using across this project:

1. **Understanding before code.** For any non-trivial feature: what problem does this solve, why does FlashMart need it, what breaks without it, what are the trade-offs — before implementation.
2. **One piece at a time, each one growing on the last.** No roadmap of upcoming pieces handed over up front — Akshansh names the next piece (build order below still governs what's eligible). After each piece, Claude Code explains every file and every line it just wrote, including annotations, config, and boilerplate — nothing waved past as "standard." Don't move on to the next piece until Akshansh can explain the current one back unaided.
3. **Akshansh reasons first, every piece.** Before Claude Code writes anything, ask what he expects the piece to look like and where it could break. Only after he's answered does Claude Code write it — and then explicitly compare his version against the real code. That comparison, not the code itself, is the point of the exercise.
4. **Socratic where it matters.** For design/concurrency/failure-mode questions, don't just hand over the answer or the finished class. Pose the failure scenario, let Akshansh reason first, correct/guide, then build.
5. **Open decisions stop the work — no default slips in quietly.** The locking strategy for `flash_sale_items` and the transaction isolation level on the same write path are still undecided (see "Known open items"). The moment a piece would force either one, stop and decide it together before writing code that bakes one in.
6. **Derive, don't copy.** Schema, service boundaries, and API shapes are derived from access patterns, failure isolation, and sequence/state diagrams already produced — **but check the diagram is still current first.** The Notion diagrams are synced to Revision 3 as of this update, but Notion is a snapshot, not a live source — re-verify against `docs/decisions.md`/`docs/architecture.md` before deriving from it if the design has moved since. Deriving from a stale diagram is worse than deriving from nothing.
7. **Never claim untested things work.** Distinguish designed / implemented / tested / load-tested / verified-under-failure. No invented performance numbers, ever — the metrics (zero overselling under 5,000 concurrent requests / 500 units, p99 < 500ms, etc.) are *targets* until actually measured.
8. **Don't silently "improve" the architecture.** If something looks off (e.g. a service boundary, a missing index), flag it and discuss — don't just refactor unasked.

## Current architecture (Revision 3)

### Services

API Gateway (no DB), Order Service, Inventory Service, Notification Service, Identity Service (owns brands, API keys, **and** buyers/users). No Settlement Service. Razorpay Clone is a separate project, called via OpenFeign.

### The load-bearing rule

**Order Service is the sole authority on an order's fate, and every write to `orders.status` is a compare-and-set.**

```sql
UPDATE orders SET status='PAID' WHERE id=? AND status='PENDING_PAYMENT' AND payment_ref=?
```

The request thread, the expiry sweep, the webhook handler and the circuit-breaker path all issue conditional updates and check rows-affected. The status change and any outbox row it produces share one local transaction. Races are settled by one atomic update on one row; the loser knows it lost. **Inventory never expires anything on its own.**

### Order Service schema

`orders`, `order_items`, `refunds`, `outbox`. **No `saga_state`** — it was dropped (nothing read `step`, and a second copy of state can go stale). `orders.failure_detail` carries what `last_error` did, for debugging only; it never drives state.

- `orders` — adds `payment_ref`, `expires_at` (means two things by status: bounds the reserve loop while `CREATED`, then reset to the payment window on `PENDING_PAYMENT`), `failure_detail`, `payment_reconciled_at` (nullable; bounds the reconciliation sweep's scan set). `UNIQUE(buyer_id, idempotency_key)`. `CHECK (status NOT IN ('PENDING_PAYMENT','PAID') OR amount IS NOT NULL)`.
- `order_items` — `UNIQUE(order_id, product_id)`. All rows inserted up front; `product_name`/`unit_price`/`subtotal` stay NULL until that item's `reserve()` returns them. `product_name` is a snapshot, never resynced if the brand later renames the product.
- `refunds` — a **command outbox**, not an event outbox: the only consumer is Order itself, so no Kafka hop. `UNIQUE(payment_ref)` — refunds are per *payment*, not per order. Status `PENDING | PROCESSING | SUCCEEDED | NEEDS_MANUAL_REVIEW`; `claimed_at` doubles as claim lease and fence token for the worker's finishing write.

### Order state machine

`CREATED` and `PENDING_PAYMENT` (transient) → five terminal states: `PAID`, `PAYMENT_FAILED`, `ORDER_EXPIRED`, `PAYMENT_UNAVAILABLE`, `RESERVATION_FAILED`.

No `CONFIRMED`, no `RESERVED`, no `COMPENSATING` — each was dropped because nothing would move an order *out* of it.

**One-payment invariant** (replaces the old terminal-state guard, which had a hole): *an order keeps exactly one payment; every other captured payment for that order is refunded.* For a SUCCESS webhook carrying payment ref `X`: CAS to `PAID` only if `status='PENDING_PAYMENT' AND payment_ref=X`; if that fails and the order is already `PAID` with `payment_ref=X`, it's a redelivery (no-op); otherwise refund `X`, using the reference **from the webhook** (`orders.payment_ref` may be NULL if Order crashed before saving it).

### Inventory Service schema

`flash_sales`, `flash_sale_items`, `stock_reservations`, `products`. **No `outbox`** — Inventory publishes nothing; it is a pure consumer plus a Feign responder.

- `flash_sales` — `status` is **`ACTIVE | CANCELLED` only**. `SCHEDULED`/`LIVE`/`ENDED` are *not* stored; they're computed, because no timestamp implies cancellation but every other state is derivable. **Liveness = `status != 'CANCELLED' AND start_time <= now < end_time`** — half-open on purpose (inclusive start so the sale opens exactly on its timestamp, exclusive end so there's no instant where it's both open and closed; SQL `BETWEEN` is inclusive at both ends and is wrong here). `now` is the **database's** clock, evaluated in the same statement as the stock decrement — several Inventory pods reading their own wall clocks would disagree at the boundary, which is the Revision 3 two-clocks problem one level down. **Everything about a sale is mutable until it opens and frozen after** — `sale_price`, `start_time`, `end_time` and cancellation all share that boundary. A sale cannot be cancelled once started, which is what makes "cancelled sale with live reservations or paid orders" an impossible state rather than an unhandled one. The consequence is deliberate and harsh: a live sale always runs to its `end_time`, so a mispriced sale has **no remedy** — don't design one, and don't assume "cancel and recreate" is available mid-sale.
- `flash_sale_items` — `UNIQUE(flash_sale_id, product_id)`; `total_stock` fixed (reporting), `available_stock` is the decremented counter, `version` is the optimistic lock on `available_stock`. `sale_price` is immutable once the sale has opened (same rule and same enforcement point as `start_time`/`end_time`).
- `stock_reservations` — `UNIQUE(order_id, flash_sale_item_id)` makes a retried reserve idempotent. **Carries `quantity`** — `OrderCancelled` names only the order, so the release path reads the held units from these rows and nowhere else; it is also what the replay check compares to return `409 QUANTITY_MISMATCH`. **No `expires_at`** — Inventory holds no deadline of its own; `reserved_at` exists for the stale-reservation reconciler, which *asks Order* rather than deciding for itself. Indexed on `(status, reserved_at)` for that reconciler; **no separate `(order_id)` index** — `UNIQUE(order_id, flash_sale_item_id)` already leads with `order_id`, so a second one would only slow the insert on every reserve.

**The reconciler contract:** `GET /internal/orders/{orderId}/status` → `{ orderId, status }`. `PAID` → confirm; any terminal failure → release; `CREATED`/`PENDING_PAYMENT` → do nothing **and alert** (an hours-old reservation on a still-live order means Order's expiry sweep has stopped); `404` or unreachable → do nothing and alert, never guess — a missing order is an anomaly, not evidence the stock is free. Safe without locking because every answer acted on is terminal and cannot change after it's read. **Internal only — never route it through the Gateway** (no ownership check; it would be an IDOR). Scan a bounded number of rows per pass: under a systemic failure every reservation goes stale at once, and an unbounded scan would flood Order mid-incident.

### Reservation

Order loops over unique items, calling `POST /internal/flash-sale-items/reserve` with `{ orderId, flashSaleId, productId, quantity }` once per item — keyed by `(flash_sale_id, product_id)`. Success returns `{ success: true, reservationId, salePrice, brandId, productName }` (`salePrice` as a **string**, per the money-serialization rule below). `productName` is snapshotted into `order_items.product_name` for Notification's receipt. Business outcomes are `200` with `success: false` (`OUT_OF_STOCK`, `SALE_NOT_LIVE`); non-`200` means an upstream bug and should alert (`404 ITEM_NOT_FOUND`, `409 QUANTITY_MISMATCH`, `400 INVALID_QUANTITY`).

**Evaluation order is part of the contract, not an implementation detail:** validate quantity → resolve the item → **return any existing reservation for `(orderId, item)` and stop** → only then check `LIVE` → only then check and decrement stock. The replay check must come first: if a reservation succeeds, the Feign call times out, the sale ends, and Order retries, a `LIVE`-first implementation would fail the retry and cancel an order whose stock was legitimately held. Needs an explicit test. Price comes from `flash_sale_items.sale_price` read in the same transaction as the hold, so price and hold can't disagree. `brand_id` is used once per order (every order belongs to exactly one sale, therefore one brand) and lives only in event payloads — never a column on `orders`.

**There is no `release()` endpoint.** Compensation is asynchronous: whatever kills an order writes one `OrderCancelled{order_id, reason}` event, and Inventory releases every reservation currently held for that `order_id`, regardless of reason. Nothing waits on the release completing.

### Identity Service schema

`brands`, `users`, `api_keys`, `outbox`. Only `brands` is written so far.

- `brands`/`users` — `UNIQUE(email)` plus `CHECK (email = lower(btrim(email)))`: emails are stored trimmed and lowercased, because a plain `UNIQUE` is case-sensitive in Postgres and would let one mailbox register twice. The entity's setter normalises; the CHECK is the backstop. **That CHECK alone is not enough** — `''` satisfies it, so three more guards exist on both tables: `length(email) > 0`, an `@` with something on each side, and `length(btrim(name)) > 0`. The `@` check is a minimal sanity gate, **not** email validation; real checking is Bean Validation at the API plus a verification flow. `UNIQUE(email)` is per table, so one address may be both a brand and a buyer — deliberate, and it means **there can be no single login endpoint**. **`updated_at` is database-generated and strictly increasing** — a `BEFORE UPDATE` trigger (`bump_updated_at`) stamps `GREATEST(clock_timestamp(), old + 1 µs)`, because Notification discards any `BrandUpserted`/`UserUpserted` that looks older than what it holds, so a lagging pod clock must never stamp a newer change with an older time. Java never writes the column (`@Generated`, `insertable = false`). `TIMESTAMPTZ`/`Instant` here, unlike Inventory's `TIMESTAMP`, because the event contract is UTC and this is the one comparison a zone-less timestamp could skew.
- `api_keys` — `key_hash` is a **fast, deterministic hash (SHA-256)**, never bcrypt: the key is found with `WHERE key_hash = ?`, so the same key must always hash the same way. `CHECK (status IN ('ACTIVE','REVOKED'))`; revoked rows are kept, not deleted. `brand_id` is a real FK (same service).
- `outbox` — `payload_json` is `JSONB`; the index is **partial**, `(created_at) WHERE published = false`, not `(published, created_at)` over every row. `aggregate_id` has no FK (it names a brand or a user). Order's outbox takes the same index shape.

### Events

| Publisher | Event | Consumers |
|---|---|---|
| Order | `PaymentConfirmed { order_id, buyer_id, brand_id, items[{product_id, product_name, quantity, unit_price, subtotal}], amount }` | Inventory (confirm — needs only `order_id`), Notification (receipt — needs the rest) |
| Order | `OrderCancelled{order_id, reason, buyer_id}` | Inventory (release all, any reason); Notification (buyer only, and **only** for `PAYMENT_FAILED` / `ORDER_EXPIRED`) |
| Identity | `BrandUpserted` / `UserUpserted` | Notification (refresh contact caches) |

`reason` ∈ `RESERVATION_FAILED | PAYMENT_FAILED | PAYMENT_UNAVAILABLE | ORDER_EXPIRED`.

**Notification filters on `reason`; Order publishes unconditionally** (Inventory needs every one to release stock). Notify only where the buyer's request has already closed — `PAYMENT_FAILED` and `ORDER_EXPIRED`. The other two are answered in the buyer's own synchronous response, and emailing them would fire thousands of messages in the peak seconds of a sale, giving a deliberately cold service a hot-path load profile. Buyer only, never the brand. This satisfies FR-B5: "notify" includes the synchronous response, not just email.

**Dropped — for two different reasons, which matters if one is ever proposed again:**

- *No consumer:* `StockReserved` (reserve is synchronous; Order uses the response), `OrderCreated` (nothing can act on "an order was requested" before it's known to survive), `PaymentInitiated` (initiate is synchronous; Order records `payment_ref` itself), `OrderConfirmed` (the `CONFIRMED` state it announced was itself dropped). Any of these could be added back cleanly if a real consumer appeared.
- *Absorbed into `OrderCancelled`:* `ReservationExpired` (expiry moved to Order; the event now flows the other way) and `PaymentFailed` (Inventory's reaction to a failed payment is identical to every other way an order dies). Re-adding either would mean re-splitting the death path that was deliberately unified — that's a new decision, not a restoration.

Only Order and Identity own an outbox. Kafka is at-least-once; idempotency comes from unique constraints and CAS, never from claiming exactly-once. Inventory's confirm handler gets this for free: `UPDATE stock_reservations SET status='CONFIRMED' WHERE order_id=? AND status='RESERVED'` matches zero rows on redelivery.

**`notification_log` retries `FAILED` sends** (`attempts`, `next_retry_at`), capped at 5, then stays `FAILED` — no escalation, unlike `refunds`. A missed courtesy email needs nobody to act; a stuck refund does. Claim before sending with a bare CAS (`status='FAILED' → 'RETRYING'`), **no fencing, no `claimed_at`, no stale-claim sweep** — that machinery defends a rare crash corner case not worth building here. The claim itself is *not* optional, though: running more than one Notification instance on the same retry schedule is ordinary horizontal scaling, not an edge case, and without a claim two instances will routinely double-send.

**Every event is a flat, bare payload — no envelope, no `event_version`, no `event_id`.** Decided, not deferred: adding a field is safe by default (old consumers ignore it); the discipline is to only ever add optional fields and rename rather than repurpose one. **Every monetary value crosses as a JSON string** (`"3999.00"`, never a bare number) — this applies to every Feign response too, not just Kafka, including `reserve()`'s `salePrice`. `BrandUpserted`/`UserUpserted`'s `updated_at` must be the **source-side** timestamp (when Identity made the change), not receipt time — the write it guards is decided (below), not deferred.

**Out-of-order `BrandUpserted`/`UserUpserted` delivery is handled, not deferred.** Every upsert into `brand_contacts`/`buyer_contacts` is conditional: `ON CONFLICT (brand_id) DO UPDATE ... WHERE brand_contacts.updated_at < EXCLUDED.updated_at` (same shape for `buyer_contacts`, keyed on `user_id`). A stale event fails the `WHERE` and is silently skipped. This does **not** depend on Kafka delivery order — that's the point of a conditional write over an unconditional one — so there is no Kafka partition-key/ordering item to settle for this reason.

### Payment

One payment per order regardless of item count, wrapped in Resilience4j circuit breaker + selective retry. The initiate call carries an idempotency key **derived deterministically from the order ID** (not regenerated per attempt), *intended* to make a retried initiate return the same payment rather than create a second one — see the caveat below. Response is 202 Accepted only — Order sets `PENDING_PAYMENT`, never `PAID`, off that call. Confirmation arrives via signed webhook; the handler returns 200 only after its transaction commits, relying on the gateway's retry if it crashes first.

**Cross-project dependency:** FlashMart's duplicate-payment prevention, refund-retry safety, and webhook-never-arrives reconciliation depend on Razorpay Clone honouring idempotency keys on both initiate and refund, and on it exposing a payment-status lookup **queryable by our idempotency key or merchant order reference** — not only by the gateway's own payment ID, since the reconciliation sweep has no payment ID to offer. Razorpay Clone is not built yet; these are specified in its own requirements. Until then, this is **designed, not verified** — don't write code or docs that assume it's proven.

### Cross-cutting

Spring Cloud Gateway (JWT validated once at the edge, coarse rate limiting), Redis (caching, token-bucket rate limiting; distributed locks still an open decision), Kafka + outbox for state-change events.

Cross-service relationships are **always either a plain ID or an event, never a foreign key**. Referential integrity across services is an application-level concern by design.

## Build order

Follow the phase plan (setup → architecture/requirements → schema → JPA entities → APIs/DTOs → reservation/payment → state machine → security → Redis caching → rate limiting → idempotency → transactions/locking → outbox → Kafka → webhooks → microservices/gateway → load testing). Don't jump ahead to a later phase's concern unless Akshansh explicitly asks.

**Current position:** Inventory Service's four JPA entities are written and merged — `Product`, `FlashSale`, `FlashSaleItem`, `StockReservation` — on Flyway migrations V1–V5, booting and schema-validating against Postgres, with 15 persistence tests. Identity has `Brand` and `User` (Flyway V1–V4, 22 tests), including the project's first trigger, `bump_updated_at`, attached to both tables; `api_keys` and `outbox` are next. **Known and queued:** Inventory's timestamp columns are zone-less `TIMESTAMP`, so comparing `start_time`/`reserved_at` against `now()` resolves using the *connection's* time zone — two pods with different settings give different liveness answers, demonstrated on the live schema. Converting Inventory to `TIMESTAMPTZ`/`Instant` (as Identity already is) is the next piece, before any `reserve` code is written on top of it. Order and Notification are **scaffolded only**: Spring Boot 4.1.1 projects with the same dependency set, their own Postgres database, an empty `db/migration/`, and a fixed port; they boot but have no entities. No service has repositories, services or controllers yet.

**Ports** (8080 reserved for the API Gateway, not built): identity `8081`, inventory `8082`, order `8083`, notification `8084`. All four share one `DB_USERNAME`/`DB_PASSWORD` pair against one local Postgres instance, but each owns a **separate database** — the "services never share a schema" rule still holds.

## Engineering discipline expected in this repo

- Feature branches for anything non-trivial; **one focused commit per piece**, meaningful message — not one giant commit spanning several pieces, and not several commits fragmenting one piece.
- No secrets committed; keep `.env.example` current.
- Don't bundle unrelated changes into one feature's commit/PR.
- **Only meaningful decisions go into `docs/decisions.md`** — propose an entry (Decision / Context / Options Considered / Chosen Approach / Why / Trade-offs / Consequences) when a piece actually settles an open design question. Don't create an entry for a trivial or purely mechanical change.
- **Notion is updated only when Akshansh asks** — never proactively, even after a decisions.md entry lands.
- Before calling a major feature done, check: implemented, tests added, failure cases considered, reviewed, committed, decision documented if warranted, and Akshansh can explain it unaided.

## Suggest Claude Code's own features as we go (light touch)

Part of the point of this project is also getting hands-on with Claude Code itself. As natural opportunities come up during the build — don't force it, don't do it every turn — point out where a **subagent**, a **skill**, or a **connector/MCP integration** could genuinely help, and offer to set it up:

- A **code-review subagent** for reviewing Codex-generated implementations (concurrency, transaction boundaries, security) — fits the existing "review the actual code" workflow.
- A **test-writing / failure-injection subagent** for race-condition and saga-compensation cases (two buyers on the last unit, mid-loop reservation failure, webhook arriving after the order is terminal, sweep-vs-webhook CAS races).
- A small **schema/migration skill** once Flyway/Liquibase migrations pile up across four services.
- The **Notion connector**, since Akshansh keeps a FlashMart engineering log there.
- A **docs skill** for keeping `docs/decisions.md` entries consistent without it becoming a chore.

Keep these occasional and concrete, tied to what's actually being worked on.

## What NOT to do

- Don't build the Settlement Service or vendor payout/KYC logic — explicitly out of scope.
- Don't collapse Identity Service into Inventory Service; that boundary was decided deliberately.
- Don't reintroduce `saga_state`, `COMPENSATING`, a `release()` endpoint, or Inventory-side expiry — each was removed for a documented reason. If resume-style saga recovery is ever needed, `saga_state` comes back with a real reader, as a new decision.
- Don't claim "exactly-once" Kafka delivery — describe actual delivery semantics and how idempotent consumers/unique constraints achieve the practical guarantee.
- Don't write a status update to `orders` without a CAS guard. No exceptions. The same applies to `refunds`: the worker claims a row by CAS (`PENDING → PROCESSING`) and its finishing write is fenced on the `claimed_at` it saw, so a slow worker can't overwrite a newer claim.
- Don't publish `OrderCancelled` for an order whose current status is `PAID`. The late-reservation republish reads the order's current status to fill `reason` — restrict that to the four terminal *failure* states. Publishing it for a paid order would release stock that was legitimately sold.
- Don't use floating-point types for money anywhere — `DECIMAL` end to end.
- Don't pick a deadline or lease by feel. Every one of them (the `CREATED` deadline, the payment window, the sweep interval, the refund claim lease) must be **derived** from the timeouts it races — per-call timeout × attempts × item count for the reserve loop, the HTTP client's total ceiling for the refund lease. A deadline shorter than the operation it bounds turns a healthy slow request into a spurious expiry. All four are still open, pending the Feign/Resilience4j config.
- Don't skip the Socratic pass on concurrency/distributed-systems topics just because it's faster to hand over finished code.

## Known open items

The full list lives in `docs/decisions.md`. These are the ones worth knowing before writing code.

**The webhook-never-arrives hole is closed** (designed, not built). A reconciliation sweep scans orders in a terminal *failure* state that aren't yet reconciled, and asks the gateway what exists for that order's **deterministic idempotency key** — deliberately not for `orders.payment_ref`, which is NULL in exactly the crash window most likely to lack a webhook. A captured payment opens a `refunds` row exactly as a losing webhook would; `payment_reconciled_at` is then set so the scan set stays bounded. `PAID` orders are never scanned, on the premise that a `PAID` order's one payment is applied by definition.

**Accepted permanent residual risk:** that premise fails if idempotency ever loses on `initiate` *and* a webhook is separately lost — a second captured payment can then escape correction entirely, in two shapes. If both webhooks are lost, the order expires and the sweep runs, but it only asks about *this order's* key, so a second payment would need a "list every payment for this order" query that doesn't exist. If one webhook wins and the order reaches `PAID`, the orphaned second payment is **never found at all**, since `PAID` orders aren't scanned — the worse case, because nothing ever looks again. Requires three independent rare failures to stack; accepted rather than built, and this is the one path in the design that leans on prevention instead of independent correction.

**The locking strategy for `flash_sale_items` is still undecided** — optimistic `version` alone, pessimistic locking, or a Redis lock in front. This bears directly on the next phase: write the entity with `@Version` as designed, but don't treat optimistic locking as settled, and don't build retry/backoff behaviour around it as though the strategy were final. It is to be load-tested, not assumed.

**The reserve write path is specified two incompatible ways** — recorded as an Open Item in `docs/decisions.md`, not decided. "Optimistic-lock `version`" says `WHERE id = ? AND version = ?`; "Inventory's `reserve` API contract" says liveness and the stock check/decrement in one statement against the database clock, i.e. `WHERE available_stock >= ? AND <liveness>`. Under READ COMMITTED an `UPDATE` that unblocks re-evaluates its `WHERE` against the *newly committed* row, so the value-based clause re-checks fresh stock and cleanly matches zero rows when stock ran out (correct, no retry), while a `version = ?` clause fails after *any* concurrent write — a retry storm at the 5,000-concurrent target. Don't pick one by default when `reserve` is built. A related trap: a native or `@Modifying` `UPDATE` does **not** bump `version`; mixed with an entity save of the same row it can silently overwrite a concurrent decrement, so any raw SQL must do `version = version + 1` itself.

**The sequence diagrams and ERDs are no longer stale — verified 2026-09-29.** The old "Reservation TTL Expiry" (showed Inventory's own scheduler) and "Payment Failure → Saga Compensation" (showed a per-item `release()` call) diagrams have been removed from the Notion engineering log and replaced with six diagrams matching Revision 3: Purchase Happy Path, Reservation Failure Mid-Loop, Expiry — the Two Windows, Circuit Breaker Open, Reconciliation Sweep, and Inventory's Stale-Reservation Reconciler. All four ERDs (Order, Inventory, Identity, Notification) were checked against the schema facts in this file and in `docs/architecture.md` — terminal state set, no `saga_state`, no `release()`, no `expires_at` on `stock_reservations`, Inventory's missing outbox, the `refunds` command-outbox shape — and match. **Re-synced 2026-09-30** when the Inventory entities were written: `stock_reservations.quantity` was added to the Inventory ERD (always required by the release path and the `QUANTITY_MISMATCH` check, but the schema stub's `...` hid it), `products` gained its audit timestamps, the redundant `(order_id)` index was dropped, and the "no code behind the schema yet" claims in §5 and §9 were corrected. **Re-synced 2026-10-02** when Identity's `Brand` was written: the Identity ERD now shows `TIMESTAMPTZ` and the rules on `brands`/`users` (case-insensitive email, DB-generated strictly increasing `updated_at`), `flash_sale_items.version` is `bigint`, the purchase sequence no longer presents a version-checked decrement as settled (it is an open item), and §5's "design-only" claim was corrected. Safe to derive from now, with the usual caveat that Notion is a snapshot: re-verify if the design moves again after this date.

