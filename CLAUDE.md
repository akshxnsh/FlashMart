# CLAUDE.md — FlashMart

Instructions for Claude Code in this repo. This file holds **rules and pointers only**. The design lives in `docs/`; do not duplicate it here.

## What this is

FlashMart is a flash-sale **infrastructure platform for brands** (Puma, Nike), not a multi-vendor marketplace. There is no settlement, payout or vendor KYC (removed on purpose).

The point of the repo is **not** a shipped app. It is a vehicle for Akshansh to learn backend and distributed-systems engineering (Spring Boot, Redis, Kafka, outbox, idempotency, resilience, gateway, load testing) around one real problem: many buyers racing for a small fixed stock pool. Success means he understands every decision well enough to explain it.

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

**Reading discipline:** `decisions.md` is ~4,500 lines. Never read it whole. `grep -n '^## Decision'` for headings, then read only the relevant section. Same for `architecture.md`: read the section you need.

The Notion engineering log is Akshansh's personal record, not a source of truth. Update it only when he explicitly asks.

## Karpathy guidelines (plugin skill)

The `karpathy-guidelines` skill governs general coding behaviour: think first, simplicity, surgical changes, verifiable goals. This file does not repeat it. It adds FlashMart rules on top; where they differ, this file wins:

- **"Trivial task, use judgment"** means *derived* work as defined in Working style 1–2. "Loop until verified" never runs past an open decision (Working style 5).
- **"No error handling for impossible scenarios"** does not apply to DB `CHECK`s, unique keys or CAS guards. They are the last line of defence and are never removed.
- **"Every changed line traces to the request"**: a `decisions.md` entry for a real decision, and the sibling-service check in Verification 4, count as part of the request.
- **"If uncertain, ask"**: on decision-bearing work, ask by posing the failure scenario (Working style 3). On derived work, state assumptions and proceed.

## Working style (non-negotiable)

1. **Depth follows decisions.** The more design a piece carries, the slower and more Socratic you go; the more it is simply derived from decisions already in the docs, the faster you move. The test: *could this reasonably be written two ways that behave differently under concurrency, failure or retry, or that trade one property for another?* If yes, it carries a decision. If the docs already settle it and only typing remains, it is derived. Most pieces are a mix: slow down for the decision-bearing part and move fast through the rest.
2. **Say which it is, in one line, before starting** ("derived from architecture.md §4" or "this carries a decision: X vs Y"), so Akshansh can overrule the call in either direction. When unsure, treat it as a decision.
3. **Decision-bearing work:** understanding before code (what problem, why FlashMart needs it, what breaks without it, trade-offs). Ask what he expects it to look like and where it could break, and only after he answers write it, then compare his version with the real code; the comparison is the point. For design, concurrency and failure modes pose the failure scenario, let him reason, correct, guide, then build. One decision at a time, no roadmap handed over up front. Explain every line that carries behavior (annotations, constraints, queries, transaction boundaries). Do not skip this because handing over code is faster.
4. **Derived work:** plan briefly, then batch it. Several entities, repositories or migrations in one pass is fine. No line-by-line walkthrough unless he asks. End with a short, point-wise summary of what matters: what was built, the constraints or decisions it quietly carries, and anything surprising or left open. No questions or quiz at the end. He is expected to read the migration SQL, since that is where the design lives.
5. **Open decisions stop the work.** Locking strategy and isolation level on `flash_sale_items` are **decided** (READ COMMITTED, one atomic conditional `UPDATE`; see `decisions.md`, "Reserve write path: READ COMMITTED, one atomic conditional UPDATE, one short transaction"). What is still open is listed under Open decisions (deadlines, leases, pool and timeout values). When a piece would force one of those, stop and decide together before writing code that bakes it in.
6. **Derive, don't copy.** Schema, boundaries and API shapes come from access patterns, failure isolation and the current docs. Check `decisions.md` / `architecture.md` before deriving from a diagram; Notion diagrams are snapshots.
7. **Never claim untested things work.** Keep the labels distinct: designed / implemented / tested / load-tested / verified-under-failure. No invented numbers. Zero overselling under 5,000 concurrent requests against 500 units and p99 < 500 ms are **targets** until measured.
8. **Don't silently improve the architecture.** If something looks off, flag it and discuss; do not refactor unasked.
9. **Review means reading the code.** When reviewing an implementation, read the actual code. Separate real bugs (correctness, concurrency, transaction boundaries, security) from optional style points.

10. **Keep the language simple and easy to understand.** Plain words, short sentences, no jargon without a one-line meaning. Connect every explanation to FlashMart with a concrete example where it helps (two buyers on the last unit, a webhook arriving as the sweep expires the order), not a generic textbook one. Explain *why*, not only *what*.

## Verification (proportional: a learning and resume project, so prove what matters and skip the ceremony)

1. **Prove the guards that carry correctness.** For the load-bearing ones (CAS and conditional writes, unique keys that stop duplicates or double payments, triggers, `@Generated` columns), break the rule once, confirm the intended test fails, then restore it. Routine CHECKs (non-negative, non-blank) need a test that rejects a bad row, not a break-and-restore. Mention a break in the summary only if it surprised you.
2. **A test must exercise the path production uses.** `clear()` then `find()` reads a fresh row and misses a field left null on the instance that was just saved (the `StockReservation.reservedAt` bug). A null set through the entity can be rejected by Hibernate before the database sees it, so test a `NOT NULL` with native SQL. Before trusting a test, ask what it structurally cannot see.
3. **Concurrency claims need a concurrent test.** Reasoning that something is race-safe is a hypothesis. Back it with a test that actually races (two threads on the last unit, a webhook against the sweep) and say what was and was not exercised. Never state a load or overselling result that was not measured.
4. **Check siblings and migrations once.** When a rule is introduced or a bug fixed, grep the sibling services for the same gap. Fix it in the same piece if the fix is mechanical; if it carries a decision, flag it and wait. Services of the same shape stay identical unless a difference is documented. Before adding a constraint to a table that may hold rows, check what is there. Never edit an applied or pushed migration; add a new version.
5. **Run the whole suite before committing, and report real numbers.** Say how many tests ran and passed. Do not write "green" unless it was run in this session.
6. **A stated reason is a claim.** If a comment, doc or summary gives a reason that matters ("this would jam the system"), measure it or word it as unverified. A lesson that applies beyond its own file goes here as one line.

## Load-bearing design rules

- **Order Service is the sole authority on an order's fate. Every write to `orders.status` is a compare-and-set** (conditional `UPDATE` plus a rows-affected check), in the same local transaction as any outbox row it produces. No exceptions. The request thread, expiry sweep, webhook handler and circuit-breaker path all race on that one row, and the loser must know it lost.
- **One-payment invariant:** an order keeps exactly one payment; every other captured payment is refunded. The handler loads the order by **the order id the webhook carries**, never by `payment_ref` (NULL in exactly the crash window a webhook is most likely to arrive in); `payment_ref` is partial-`UNIQUE` only as a safety net. For a SUCCESS webhook with ref `X`: CAS to `PAID` only if `status='PENDING_PAYMENT' AND payment_ref=X`; already `PAID` with `payment_ref=X` is a redelivery no-op; otherwise refund `X`, taking the ref **from the webhook**, not `orders.payment_ref` (may be NULL after a crash).
- **Refunds** are a command outbox (`refunds` table), never an inline call. The worker claims by CAS (`PENDING → PROCESSING`) and its finishing write is fenced on the `claimed_at` it saw.
- **Inventory never expires anything itself.** Compensation is asynchronous via `OrderCancelled{order_id, reason}`; there is no `release()` endpoint. Inventory's stale-reservation reconciler *asks Order*, and never guesses (a 404 or an unreachable Order means do nothing and alert).
- **`reserve` evaluation order is contractual:** validate quantity (1..5) → resolve item (absent → `200 ITEM_NOT_IN_SALE`) → **if a reservation exists for `(orderId, item)`, stop there**: compare quantity (mismatch → `409`), a `RELEASED` row → `200 RESERVATION_RELEASED`, otherwise return it → check live → check and decrement stock. The replay check must precede the liveness check, or a retry after the sale ends wrongly cancels a legitimately held order. It needs an explicit test.
- **Liveness is computed, never stored:** `status != 'CANCELLED' AND start_time <= now < end_time` (half-open), with `now` from the **database clock** in the same statement as the decrement. Terms are mutable until the sale opens and frozen after; a started sale cannot be cancelled.
- **Never publish `OrderCancelled` for a `PAID` order.** Restrict the late-reservation republish to the four terminal failure states.
- **Kafka is at-least-once.** Never claim exactly-once. Idempotency comes from unique constraints and CAS. Four events only: `PaymentConfirmed`, `OrderCancelled`, `BrandUpserted`, `UserUpserted`. Payloads are flat and bare (no envelope, no `event_id`).
- **Money** is `DECIMAL`/`BigDecimal` end to end and crosses every boundary (Kafka and Feign) as a **JSON string**. Never a float.
- **Cross-service references** are a plain ID or an event, never a foreign key. Database-per-service; no service reads another's DB. Internal endpoints are never routed through the Gateway.
- **Out-of-order `BrandUpserted`/`UserUpserted`** is handled by the timestamp-conditional upsert (strict `<`), which is correct only because Identity's `bump_updated_at` trigger makes `updated_at` strictly increasing.
- **Outbox relay rules:** claim rows with `FOR UPDATE SKIP LOCKED`; delivery is at-least-once; ordering is not guaranteed.
- **Deadlines and leases are derived, never picked.** The `CREATED` deadline must come from the timeouts it races (per-call timeout, connect + read, × attempts × item count; the HTTP client's total ceiling). Payment window, sweep interval and refund lease are policy, decided in `decisions.md`, "Day 5: payment step, resilience and derived deadlines" (provisional, unmeasured, designed not implemented). Recheck on Day 9.

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
- **Tests** run against real Postgres in a rolled-back transaction. The rules for trusting them are in "Verification" above.

## Git and engineering discipline

- One branch per piece (a derived batch counts as one piece), **one focused commit per logical unit** that explains *why*, merge `--no-ff`, delete the branch. Never amend a published commit unless asked. No unrelated changes in a feature commit.
- No secrets committed. Keep `.env.example` current.
- Only meaningful decisions go in `docs/decisions.md` (Decision / Context / Options Considered / Chosen Approach / Why / Trade-offs / Consequences). No entry for mechanical changes.
- **Done means Akshansh has understood the piece.** Before calling a feature done: implemented, tests added, failure cases considered, reviewed, committed, decision documented if warranted. Then explain the piece in easy language if he asks. It is done when he says he understands it; do not move to the next piece before that.

## Build order

Follow the phase plan: setup → architecture/requirements → schema → JPA entities → APIs/DTOs → reservation/payment → state machine → security → Redis caching → rate limiting → idempotency → transactions/locking → outbox → Kafka → webhooks → microservices/gateway → load testing. Don't jump ahead unless he asks.

**Current position:** entities and migrations are done in all four services; Inventory's `reserve` is implemented, tested, raced and load-run on one laptop (p99 target met at 100 buyers/s, not met at 500 buyers/s; the 5,000-concurrent target is not reached); Day 4 (`POST /api/orders`: insert, unique-violation handling, reserve loop) is implemented, tested and raced with Inventory mocked (not run against a real Inventory, not load-tested); Day 5 decisions (payment step, resilience, deadlines) are written; only the fee and the `CREATED` deadline are built (on branch `feat/day5-fee-deadline`), not the payment client; nothing else is built beyond the Day-1 repositories. `docker-compose.yml` is written and config-validated, not started. Details in `architecture.md` §8 (which wins over this line).

**Ports:** gateway `8080` (reserved), identity `8081`, inventory `8082`, order `8083`, notification `8084`, payment stub `8085` (planned, not built). One shared `DB_USERNAME`/`DB_PASSWORD` pair, one separate database per service.

## Open decisions (stop and decide together)

1. **Locking strategy for `flash_sale_items`: decided** (implemented, tested, raced; load-measured on one laptop only; see `architecture.md` §8). See `decisions.md`, "Reserve write path: READ COMMITTED, one atomic conditional UPDATE, one short transaction". Build no retry/backoff around `@Version`.
2. **Isolation level** on the same write path: **decided** (READ COMMITTED), same entry as (1).
3. **The `reserve` write path: decided**, same entry as (1). Do not re-open it by default; the `WHERE version = ?` form is rejected as the reserve path.
4. **Derived deadlines and leases:** decided on paper, provisional (`decisions.md`, "Day 5: payment step, resilience and derived deadlines"); the fee and the `CREATED` deadline are implemented and tested (see `architecture.md` §8); the payment client, breaker, sweep and stub are not built. Pool size and the `lock_timeout` / `statement_timeout` / connection-wait values are still open and come from the load test.
5. **Accepted permanent residual risk:** a second captured payment can escape correction only if initiate-idempotency fails, a webhook is lost, and the order reaches `PAID`. Not building "list every payment for this order." Do not treat it as an oversight.
6. **Razorpay Clone is not built.** The design leans on four behaviours from it: idempotency on initiate, idempotency on refund, a payment-status lookup by *our* idempotency key, and payments and webhooks that carry **our order id**. Until it exists these are **designed, not verified**; write no code or docs that assume otherwise.

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