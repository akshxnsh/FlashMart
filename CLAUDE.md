# CLAUDE.md — FlashMart

This file is instructions for Claude Code when working in this repo. Read it before touching any code.

## What FlashMart actually is

FlashMart is a flash-sale **infrastructure platform for brands** (e.g. Puma, Nike) — not a multi-vendor marketplace. A brand runs its own time-boxed, limited-stock sale; FlashMart does not aggregate independent sellers and does not handle vendor payouts or settlement (that scope was deliberately removed — see Revision 2 of the PRD).

The real point of this repo is **not** "ship a working app." It's a structured vehicle for learning backend engineering and distributed systems — Spring Boot, Redis, Kafka, sagas, outbox, idempotency, resilience patterns, API gateway, and load testing — by building something with a genuine concurrency/consistency problem at its core (many buyers racing for a small, fixed pool of stock).

**Every architectural fact below is derived, not aspirational — treat it as the source of truth over the original PRD's August draft, which is superseded.**

## Non-negotiable working style

Claude Code must follow the same philosophy Claude (chat) has been using across this project:

1. **Understanding before code.** For any non-trivial feature: what problem does this solve, why does FlashMart need it, what breaks without it, what are the trade-offs — before implementation.
2. **Socratic where it matters.** For design/concurrency/failure-mode questions, don't just hand over the answer or the finished class. Pose the failure scenario, let Akshansh reason first, correct/guide, then build.
3. **Derive, don't copy.** Schema, service boundaries, and API shapes are derived from access patterns, failure isolation, and sequence/state diagrams already produced — not lifted wholesale from the original PRD sketch, which contains corrections (see "Known corrections" below).
4. **Small, explainable increments.** One meaningful piece at a time, not a fully-scaffolded microservice dropped in one shot. Akshansh should be able to explain any code Claude Code writes without AI help.
5. **Never claim untested things work.** Distinguish designed / implemented / tested / load-tested / verified-under-failure. No invented performance numbers, ever — Section 7 metrics (zero overselling under 5,000 concurrent requests / 500 units, p99 < 500ms, etc.) are *targets* until actually measured.
6. **Don't silently "improve" the architecture.** If something looks off (e.g. a service boundary, a missing index), flag it and discuss — don't just refactor unasked.

## Current architecture (as derived — Revision 2)

- **Services:** API Gateway (no DB), Order Service, Inventory Service, Notification Service, Identity Service (formerly Brand/Vendor Service — now also owns buyers/users). No Settlement Service.
- **Order model:** multi-item orders (`orders` + `order_items`), all-or-nothing reservation — Order Service loops over unique items calling Inventory's `reserve(productId, qty)` once per item, with compensating `release()` calls on any mid-loop failure.
- **Order Service schema:** finalized — `orders`, `order_items`, `saga_state` (includes `COMPENSATING`), `outbox`.
- **Inventory Service schema:** finalized — `flash_sales` (brand-event-level, one row per sale), `flash_sale_items` (per-product terms + `version` optimistic-lock column — this is where concurrent decrement contention actually lives), `stock_reservations` (references `flash_sale_item_id`, no version column — insert-only per buyer, no contention), `products`.
- **Known correction from the original PRD:** `flash_sales` was originally one-row-per-product — wrong, since a multi-item order must reference exactly one sale entity. Also, optimistic-lock `version` belongs on `flash_sale_items`, not `stock_reservations`.
- **State machine (Order):** `CREATED -> PENDING_PAYMENT -> {PAID | PAYMENT_FAILED | ORDER_EXPIRED | PAYMENT_UNAVAILABLE}`. All four non-transient outcomes are terminal by design (no waiting on downstream consumer ack — that would recouple Order Service to Notification's uptime).
- **Payment:** external Razorpay Clone via OpenFeign, one payment per order regardless of item count, wrapped in Resilience4j circuit breaker + selective retry. Confirmation is async via signed webhook, never inline.
- **Cross-cutting:** Spring Cloud Gateway (JWT validation once at the edge, coarse rate limiting), Redis (distributed locks, caching, token-bucket rate limiting), Kafka + outbox pattern for all state-change events.

Full detail lives in `docs/requirements.md`, `docs/decisions.md`, and the PRD (`FlashMart_Project_Plan_v2.pdf`). If this file and those disagree, treat the docs as current and flag the mismatch rather than picking one silently.

## Build order

Follow the phase-by-phase plan (setup → architecture/requirements → schema → JPA entities → APIs/DTOs → reservation/payment → state machine → security → Redis caching → rate limiting → idempotency → transactions/locking → outbox → Kafka → webhooks → microservices/gateway → load testing). Don't jump ahead to a later phase's concern (e.g. don't wire Kafka before the outbox table exists) unless Akshansh explicitly asks to.

## Engineering discipline expected in this repo

- Feature branches for anything non-trivial; small, focused commits with meaningful messages.
- No secrets committed; keep `.env.example` current.
- Don't bundle unrelated changes into one feature's commit/PR.
- For meaningful architectural decisions, propose an entry for `docs/decisions.md` in the format: Decision / Context / Options Considered / Chosen Approach / Why / Trade-offs / Consequences. Don't create docs for trivial changes.
- Before calling a major feature done, check: implemented, tests added, failure cases considered, reviewed, committed, decision documented if warranted, and Akshansh can explain it unaided.

## Suggest Claude Code's own features as we go (light touch)

Part of the point of this project is also getting hands-on with Claude Code itself, not just backend engineering. So: as natural opportunities come up during the build — don't force it, don't do it every turn — point out where a **subagent**, a **skill**, or a **connector/MCP integration** could genuinely help, and offer to set it up. A few concrete, realistic examples worth surfacing when relevant (not all of these at once):

- A **code-review subagent** for reviewing Codex-generated implementations before Akshansh treats them as done (concurrency, transaction boundary, security issues) — fits directly into the existing "review the actual code" workflow.
- A **test-writing / failure-injection subagent** for the race-condition and saga-compensation test cases (two buyers hitting the last unit, mid-loop reservation failure, webhook arriving after order is already terminal).
- A small **schema/migration skill** once Flyway/Liquibase migrations start piling up across four services, to keep migration-writing consistent.
- The **Notion connector**, since Akshansh already keeps a FlashMart engineering log there — useful once "update my Notion" requests happen from inside Claude Code too, not just chat.
- A **docs skill** for keeping `docs/decisions.md` entries in a consistent format without it becoming a chore.

Keep these suggestions occasional and concrete — tied to what's actually being worked on — not a checklist recited every session. The goal is a few genuine touches of experience with subagents/skills/connectors, not maximal adoption.

## What NOT to do

- Don't build the Settlement Service or vendor payout/KYC logic — explicitly out of scope under the brand-platform pivot.
- Don't collapse Identity Service into Inventory Service without Akshansh revisiting that open decision.
- Don't claim "exactly-once" Kafka delivery — describe actual delivery semantics and how idempotent consumers/unique constraints achieve the practical guarantee instead.
- Don't skip the Socratic pass on concurrency/distributed-systems topics just because it's faster to hand over finished code.