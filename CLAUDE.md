# CLAUDE.md — FlashMart

Instructions for Claude Code in this repo. This file holds **rules and pointers only**. The design lives in `docs/`; do not duplicate it here.

## What this is

FlashMart is a flash-sale **infrastructure platform for brands** (Puma, Nike), not a multi-vendor marketplace. There is no settlement, payout or vendor KYC (removed on purpose).

The point of the repo is **not** a shipped app. It is a vehicle for Akshansh to learn backend and distributed-systems engineering (Spring Boot, Redis, Kafka, outbox, idempotency, resilience, gateway, load testing) around one real problem: many buyers racing for a small fixed stock pool. Success means he can explain and rebuild every decision unaided.

## Where things live (read on demand, not up front)

Authority order when documents disagree: `docs/decisions.md` → `docs/architecture.md` → `docs/requirements.md` → this file → `docs/Flashmart.prd`. If this file contradicts a higher doc, the doc wins and you flag the mismatch.

| Need | Read |
|---|---|
| Services, boundaries, communication | `architecture.md` §2–3 |
| Schema per service | `architecture.md` §4 |
| Order state machine, one-payment invariant | `architecture.md` §6 |
| What is built / tested / not | `architecture.md` §8 (the status source of truth) |
| Open items | `architecture.md` §9 and the final "Open Items" section of `decisions.md` |
| Why something is the way it is | `decisions.md`, **by heading** |

**Reading discipline:** `decisions.md` is ~3,900 lines. Never read it whole. `grep -n '^## Decision'` for headings, then read only the relevant section. Same for `architecture.md`: read the section you need.

The Notion engineering log is Akshansh's personal record, not a source of truth. Update it only when he explicitly asks.

## Working style (non-negotiable)

1. **Understanding before code.** For anything non-trivial: what problem, why FlashMart needs it, what breaks without it, trade-offs. Then code.
2. **One piece at a time, each growing on the last.** No roadmap of upcoming pieces. Akshansh names the next piece (build order below governs eligibility). After each piece, explain every file and every line written, including annotations, config and boilerplate. Do not move on until he can explain the piece back unaided.
3. **He reasons first, every piece.** Before writing anything, ask what he expects it to look like and where it could break. Write it only after he answers, then compare his version with the real code. The comparison is the point.
4. **Socratic on design, concurrency and failure modes.** Pose the failure scenario, let him reason, correct, guide, then build. Do not skip this because handing over code is faster.
5. **Open decisions stop the work.** Locking strategy and isolation level on `flash_sale_items` are undecided (see Open decisions). When a piece would force either, stop and decide together before writing code that bakes one in.
6. **Derive, don't copy.** Schema, boundaries and API shapes come from access patterns, failure isolation and the current docs. Check `decisions.md` / `architecture.md` before deriving from a diagram; Notion diagrams are snapshots.
7. **Never claim untested things work.** Keep the labels distinct: designed / implemented / tested / load-tested / verified-under-failure. No invented numbers. Zero overselling under 5,000 concurrent requests against 500 units and p99 < 500 ms are **targets** until measured.
8. **Don't silently improve the architecture.** If something looks off, flag it and discuss; do not refactor unasked.
9. **Review means reading the code.** When reviewing an implementation, read the actual code. Separate real bugs (correctness, concurrency, transaction boundaries, security) from optional style points.

## Load-bearing design rules

- **Order Service is the sole authority on an order's fate. Every write to `orders.status` is a compare-and-set** (conditional `UPDATE` plus a rows-affected check), in the same local transaction as any outbox row it produces. No exceptions. The request thread, expiry sweep, webhook handler and circuit-breaker path all race on that one row, and the loser must know it lost.
- **One-payment invariant:** an order keeps exactly one payment; every other captured payment is refunded. For a SUCCESS webhook with ref `X`: CAS to `PAID` only if `status='PENDING_PAYMENT' AND payment_ref=X`; already `PAID` with `payment_ref=X` is a redelivery no-op; otherwise refund `X`, taking the ref **from the webhook**, not `orders.payment_ref` (may be NULL after a crash).
- **Refunds** are a command outbox (`refunds` table), never an inline call. The worker claims by CAS (`PENDING → PROCESSING`) and its finishing write is fenced on the `claimed_at` it saw.
- **Inventory never expires anything itself.** Compensation is asynchronous via `OrderCancelled{order_id, reason}`; there is no `release()` endpoint. Inventory's stale-reservation reconciler *asks Order*, and never guesses (a 404 or an unreachable Order means do nothing and alert).
- **`reserve` evaluation order is contractual:** validate quantity → resolve item → **return any existing reservation for `(orderId, item)` and stop** → check live → check and decrement stock. The replay check must precede the liveness check, or a retry after the sale ends wrongly cancels a legitimately held order. It needs an explicit test.
- **Liveness is computed, never stored:** `status != 'CANCELLED' AND start_time <= now < end_time` (half-open), with `now` from the **database clock** in the same statement as the decrement. Terms are mutable until the sale opens and frozen after; a started sale cannot be cancelled.
- **Never publish `OrderCancelled` for a `PAID` order.** Restrict the late-reservation republish to the four terminal failure states.
- **Kafka is at-least-once.** Never claim exactly-once. Idempotency comes from unique constraints and CAS. Four events only: `PaymentConfirmed`, `OrderCancelled`, `BrandUpserted`, `UserUpserted`. Payloads are flat and bare (no envelope, no `event_id`).
- **Money** is `DECIMAL`/`BigDecimal` end to end and crosses every boundary (Kafka and Feign) as a **JSON string**. Never a float.
- **Cross-service references** are a plain ID or an event, never a foreign key. Database-per-service; no service reads another's DB. Internal endpoints are never routed through the Gateway.
- **Out-of-order `BrandUpserted`/`UserUpserted`** is handled by the timestamp-conditional upsert (strict `<`), which is correct only because Identity's `bump_updated_at` trigger makes `updated_at` strictly increasing.
- **Outbox relay rules:** claim rows with `FOR UPDATE SKIP LOCKED`; delivery is at-least-once; ordering is not guaranteed.
- **Deadlines and leases are derived, never picked.** The `CREATED` deadline, payment window, sweep interval and refund claim lease must come from the timeouts they race (per-call timeout × attempts × item count; the HTTP client's total ceiling). All are open pending the Feign/Resilience4j config.

## Implementation conventions

Reasoning is in `decisions.md` ("Entity and migration conventions", "Database CHECK constraints", "Who stamps a time column", "Entity tests run against real Postgres").

- **Ids:** `BIGSERIAL` + `GenerationType.IDENTITY`. **Migrations:** hand-written Flyway, `ddl-auto: validate`. **Never edit an applied or pushed migration**; add a new version.
- **Foreign keys:** real in the DB for same-service relations but plain `Long` in Java (no `@ManyToOne`). Cross-service ids have no FK.
- **Enums:** `EnumType.STRING` plus a DB `CHECK`. **Time:** `TIMESTAMPTZ` / `Instant` always (`NoZoneLessTimestampsTest` in every service enforces it).
- **DB-filled columns** need `insertable = false` (+ `@Generated` to read back), because Hibernate sends an explicit `NULL` for an unset field and overrides a column `DEFAULT`.
- **Who stamps a time column:** Java clock for audit-only; DB default if compared against `now()`; a strictly-increasing trigger if another service orders events by it.
- **`CHECK`s are the last line of defence** and are never removed for duplicating application logic. A normalised-form check lets `''` through, so add a non-empty guard.
- **`validate` cannot see triggers, defaults or CHECKs.** Only a test catches a missing one.
- **Constraint names are chosen:** `uk_`, `ck_`, `idx_`, `trg_` + table + purpose. Omit `@AllArgsConstructor` where a setter normalises.
- **Raw SQL trap:** a native or `@Modifying` `UPDATE` does **not** bump `@Version`. Mixed with an entity save of the same row it can overwrite a concurrent decrement, so raw SQL must do `version = version + 1` itself.
- **Tests** run against real Postgres in a rolled-back transaction, and **each guard is shown to fail** by deliberately breaking what it protects. A passing test is not evidence.

## Git and engineering discipline

- One branch per piece, **one focused commit** that explains *why*, merge `--no-ff`, delete the branch. Never amend a published commit unless asked. No unrelated changes in a feature commit.
- No secrets committed. Keep `.env.example` current.
- Only meaningful decisions go in `docs/decisions.md` (Decision / Context / Options Considered / Chosen Approach / Why / Trade-offs / Consequences). No entry for mechanical changes.
- Before calling a feature done: implemented, tests added, failure cases considered, reviewed, committed, decision documented if warranted, **and Akshansh can explain it unaided**.

## Build order

Follow the phase plan: setup → architecture/requirements → schema → JPA entities → APIs/DTOs → reservation/payment → state machine → security → Redis caching → rate limiting → idempotency → transactions/locking → outbox → Kafka → webhooks → microservices/gateway → load testing. Don't jump ahead unless he asks.

**Current position:** see `architecture.md` §8 and `git log`. As a pointer only: entities are done for Inventory, Identity and Notification; Order is scaffolded; no repositories, services or controllers exist anywhere. If this paragraph and §8 disagree, §8 and the code win.

**Ports:** gateway `8080` (reserved), identity `8081`, inventory `8082`, order `8083`, notification `8084`. One shared `DB_USERNAME`/`DB_PASSWORD` pair, one separate database per service.

## Open decisions (stop and decide together)

1. **Locking strategy for `flash_sale_items`:** optimistic `version` alone, pessimistic, or a Redis lock in front. To be load-tested, not assumed. Write the entity with `@Version` as designed, but build no retry/backoff around it as though it were final.
2. **Isolation level** on the same write path. It interacts directly with (1) and is decided with it.
3. **The `reserve` write path is specified two incompatible ways:** `WHERE id = ? AND version = ?` versus `WHERE available_stock >= ? AND <liveness>`. Under READ COMMITTED the `version` form fails after *any* concurrent write (a retry storm at the 5,000-concurrent target), while the value-based form re-checks fresh stock and cleanly matches zero rows. Do not pick one by default when `reserve` is built.
4. **Derived deadlines and leases** (see design rules), blocked on Feign/Resilience4j config.
5. **Accepted permanent residual risk:** a second captured payment can escape correction only if initiate-idempotency fails, a webhook is lost, and the order reaches `PAID`. Not building "list every payment for this order." Do not treat it as an oversight.
6. **Razorpay Clone is not built.** The design leans on three behaviours from it: idempotency on initiate, idempotency on refund, and a payment-status lookup by *our* idempotency key. Until it exists these are **designed, not verified**; write no code or docs that assume otherwise.

## What NOT to do

- Don't build a Settlement Service or vendor payout/KYC.
- Don't collapse Identity into Inventory.
- Don't reintroduce `saga_state`, `COMPENSATING`, a `release()` endpoint, or Inventory-side expiry. Each was removed for a documented reason; bringing one back is a new decision.
- Don't write an `orders` or `refunds` status update without a CAS guard.
- Don't use floating-point for money.
- Don't claim exactly-once delivery.
- Don't pick a deadline or lease by feel.
- Don't skip the Socratic pass on concurrency topics.

## Claude Code features (occasional, concrete suggestions only)

Offer these when the work genuinely calls for them; never every turn:

- A **review subagent** for concurrency, transaction-boundary and security review of a finished piece.
- A **test / failure-injection subagent** for races: two buyers on the last unit, mid-loop reservation failure, webhook after the order is terminal, sweep-vs-webhook CAS.
- A **migration skill** once Flyway files pile up across services.
- The **Notion connector** for the engineering log, and a **docs skill** for `decisions.md` entries.