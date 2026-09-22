# FlashMart — Architectural Decisions

## Decision: Saga (orchestration) instead of 2PC or a single distributed transaction for Order/Inventory/Payment consistency

**Context / Problem:**
Order, Inventory, and Payment (via Razorpay Clone) are independent services/databases.
A single purchase needs all three to reach a consistent terminal state even if one
fails mid-flow — but they can't share one local DB transaction, and Payment is an
external HTTP call that isn't transactional.

**Options Considered:**
- Single distributed `@Transactional` block spanning all three.
- Two-Phase Commit (2PC) across participants.
- Saga pattern (orchestrated, with compensating actions).

**Chosen Approach:**
Saga pattern, orchestrated by Order Service (`saga_state`), with compensating
actions on failure.

**Why:**
A single transaction or 2PC would require holding an Inventory row lock (and a
DB connection + server thread) open for the full duration of the Razorpay Clone
HTTP call. Under concurrent load this exhausts the connection/thread pool and
turns one slow external dependency into a full service outage. 2PC additionally
requires Razorpay Clone to support a "prepare but don't commit" protocol, which
external payment gateways don't offer.

**Trade-offs:**
Gives up atomicity for eventual consistency — there's a real, observable
intermediate window (stock reserved, payment unresolved). No cross-service
rollback; failures are undone via separate compensating transactions instead.

**Consequences:**
- Order lifecycle needs explicit intermediate states (`RESERVED` →
  `PENDING_PAYMENT` → `PAID`), not one atomic flip — already reflected in NFR-C3
  and the state-machine section of the PRD.
- Requires a safety-net mechanism for the case where the expected event
  (payment webhook) never arrives at all (see next decision).

---

## Decision: TTL-based polling sweep, not a Kafka delayed-message pattern, for reservation expiry / webhook-never-arrives safety net

**Context / Problem:**
If Razorpay Clone's payment webhook never arrives, Order Service has no event to
react to and can't wait indefinitely. Need a mechanism that fires a compensating
action after a bounded wait.

**Options Considered:**
- Kafka-based delayed message (publish now, consumer re-checks/re-queues until a
  `due_at` timestamp passes).
- Purpose-built delay layer (e.g. Redis sorted set by due-time, RabbitMQ delay
  plugin).
- Scheduled polling job querying `stock_reservations` for expired rows.

**Chosen Approach:**
Scheduled polling job sweeping `stock_reservations` where `expires_at < now()`.

**Why:**
Kafka has no native delayed-delivery feature — building one is effectively
polling dressed up as messaging (repeated re-read/re-queue against a timestamp),
which is more moving parts for the same result. Given `stock_reservations.expires_at`
already exists in the schema and this is a narrow, single-purpose need (not a
general delay system), the simple scheduled sweep is the more direct correct
mechanism.

**Trade-offs:**
Reaction time after the deadline passes depends on the sweep interval, not
instant — a true push-based delay mechanism would react immediately at the
deadline. This is an accepted trade-off given the TTL is a safety net, not the
primary resolution path (the webhook is).

**Consequences:**
Sweep interval becomes a tunable parameter to decide later (affects how long
stock can sit wrongly-reserved past its TTL before release).

---

## Decision: `docs/requirements.md` stays scoped to functional/non-functional requirements only — no API contracts, schema, or code

**Context / Problem:**
Needed to decide how much implementation detail belongs in the requirements doc
itself versus downstream artifacts.

**Options Considered:**
- Include rough API endpoint sketches / DB schema alongside requirements for
  convenience.
- Keep requirements strictly to functional behavior and NFRs, with schema/API
  derived later from architecture, sequence, and state diagrams.

**Chosen Approach:**
Requirements doc excludes API contracts, schema, and code entirely.

**Why:**
Consistent with the project's own established principle that schema and
implementation decisions must be derived downstream from system design and flow
artifacts, not invented independently or baked in early.

**Trade-offs:**
Slower to get to something that "looks like a spec you could hand to a
developer" — requires sequence/state diagrams and schema derivation as separate
steps before implementation-ready detail exists.

**Consequences:**
Sequence diagrams (next phase) and state diagrams must exist before schema
work begins; schema fields (e.g. `version` column, `expires_at`) should be
traceable back to a specific NFR or state-machine transition, not assumed.

---

## Decision: Performance targets (NFR-P1–P4) documented as unverified targets, not requirements assumed to be satisfied by design

**Context / Problem:**
The PRD contains specific performance numbers (zero overselling under 5,000
concurrent buyers vs 500 units, p99 < 500ms, etc.). Needed to decide how to
represent these in requirements.md without overstating what's actually known.

**Options Considered:**
- List them as hard requirements alongside correctness/resilience NFRs.
- List them separately, explicitly labeled as targets pending load-test
  verification.

**Chosen Approach:**
Separate "NFRs — Performance (TARGETS, unverified until load-tested)" section.

**Why:**
Matches the project's own rule to never claim something works because the code/
design exists — designed, implemented, tested, and load-tested are distinct
claims, and these numbers are only meaningful once measured.

**Trade-offs:**
None functionally — purely a documentation-discipline decision.

**Consequences:**
These targets can only be marked "met" after the load-testing phase (Phase 11)
actually runs and produces numbers; nothing before that should claim them as
achieved.

---

## Decision: Service boundaries drawn by traffic shape + failure blast radius

**Context / Problem:**
FlashMart's data (vendors, products, flash sales, stock reservations, orders, notifications, settlements) could live in one monolith service/DB, or be split across multiple services. Needed a non-arbitrary rule for where to cut boundaries — "split by noun" or "microservices are good practice" aren't real justifications.

**Options Considered:**
1. Single monolith service/DB for all entities.
2. Split by domain noun (Vendor, Product, Order, etc.) with no traffic-based justification.
3. Split by traffic shape (hot/bursty vs. cold/batch) and failure blast radius — group entities so an incident in one group can't degrade an unrelated group.

**Chosen Approach:**
Option 3. Resulted in 5 owning services: Order Service (orders, order_items, saga_state, outbox), Inventory Service (products, flash_sales, stock_reservations), Vendor Service (vendors, bank_details, api_keys, KYC), Notification Service (notification_log), Settlement Service (settlements, payout_batches). API Gateway owns no data — stateless routing/auth-filter layer only.

**Why:**
A flash sale can send 5,000 concurrent buy-attempts against Inventory/Order in seconds. In a shared DB, that traffic exhausts the connection pool and contends for row locks against completely unrelated, low-urgency work (vendor profile reads, settlement batch jobs) — one hot feature degrades features that have nothing to do with it. Splitting by traffic shape isolates that blast radius: Inventory/Order can be scaled and hardened independently of Notification/Settlement, which have different (cold, batch-tolerant) access patterns.

**Trade-offs:**
- Gains: independent scaling/deploys per traffic profile; a bug or incident in one service can't directly starve resources in another; database-per-service removes cross-service lock contention entirely.
- Costs: no more enforced FK constraints across service boundaries (e.g. Inventory's `products.vendor_id` is a plain value reference, not a DB-enforced FK to Vendor Service) — referential integrity becomes an application-level concern; more services to deploy/monitor/version than a monolith.

**Consequences:**
Every future service-boundary question (e.g. "should X be its own service") must be justified against this same rule — a concrete traffic-shape or blast-radius story — not against convention or how "production-grade" it looks. See the Vendor Service decision below for the first application of this test.

---

## Decision: Vendor Service kept as one combined service (not split into Identity/Auth vs. Vendor Profile/Payout)

**Context / Problem:**
Vendor-related data spans three concerns: identity/auth (login, JWT, API keys), business profile (name, KYC), and payout details (bank account, commission terms). Real-world systems often split identity/auth into its own service. Needed to decide whether FlashMart should do the same.

**Options Considered:**
1. **Single Vendor Service** — owns identity/auth, business profile, and payout details together.
2. **Split into two services** — a generic Identity/Auth service (shared by buyer and vendor login) separate from a Vendor Profile service (KYC, bank details, commission terms).

**Chosen Approach:**
Option 1 — single combined Vendor Service.

**Why:**
Applied the traffic-shape/blast-radius rule above and found no concrete story to justify the split at FlashMart's scale: vendor traffic is inherently low-volume and non-bursty (no 5,000-concurrent-vendor moment anywhere in the system), so there's no load-driven reason to separate auth from profile data. The instinct toward Option 2 was traced back to "this is what production systems do" (a pattern-matching reflex) rather than a derived necessity — real Identity/Auth splits are typically justified by being a genuinely cross-cutting concern shared across many products/services org-wide, which doesn't apply here (FlashMart is the only consumer of this auth).

**Trade-offs:**
- Gains: one boundary to reason about and operate; avoids adding a service with no load or failure scenario to justify or stress-test it.
- Costs: sensitive data (bank details, KYC) and higher-frequency auth-check traffic share one service/DB — acceptable at this project's scale, but would need revisiting if FlashMart ever had multiple products sharing one login system, or a real security/compliance driver for isolating credential storage.

**Consequences:**
If a future requirement introduces a concrete reason (e.g., a second product needing shared login, or a compliance requirement to isolate credential storage), this decision should be revisited using the same rule — not reversed on "best practice" grounds alone.

---

## Decision: Communication pattern per service interaction (sync Feign / async Kafka / inbound webhook)

**Context / Problem:**
With 5 services, each inter-service interaction needs a communication mode. Needed a rule for choosing sync vs. async rather than defaulting to REST everywhere or Kafka everywhere.

**Options Considered:**
1. Default to REST/Feign (synchronous) for all inter-service calls.
2. Default to Kafka (asynchronous) for all inter-service communication.
3. Choose per-interaction based on whether the caller needs the result before it can proceed in the current request.

**Chosen Approach:**
Option 3, resulting in:
- Order Service → Inventory Service (reserve stock): **sync, Feign** — Order cannot proceed without the reservation outcome.
- Order Service → Razorpay Clone (initiate payment): **sync, Feign** — but the response (202 Accepted) confirms only that the request was received, not the payment outcome; Order Service sets status to `PENDING_PAYMENT`, not `PAID`, off this call.
- Razorpay Clone → Order Service (payment result): **async, inbound webhook** — outcome timing is outside Order Service's control (depends on buyer/bank), so it cannot be blocked on synchronously.
- Order Service → Inventory/Notification/Settlement (post-payment-confirmed): **async, Kafka events** (`PaymentConfirmed`, etc.) — none of these consumers need to block Order Service or respond back.

**Why:**
Forcing the payment confirmation wait into a synchronous call would hold a request thread open for however long the buyer takes to complete payment (seconds to minutes) — exactly the thread-starvation failure mode the architecture is trying to avoid during flash-sale load. Kafka is used only where the producer genuinely doesn't need a response.

**Trade-offs:**
- Gains: no thread/connection held open across slow, externally-timed operations; downstream consumers (Notification, Settlement) can lag without impacting the checkout path.
- Costs: three distinct communication primitives (not one) to build, test, and reason about failure modes for — sync timeouts/circuit breakers, webhook signature verification + non-delivery handling, and Kafka consumer idempotency are all separate concerns.

**Consequences:**
- Kafka's at-least-once delivery means every consumer (starting with Inventory Service's `PaymentConfirmed` handler) must be built as an **idempotent consumer** — checking current state before applying a transition — rather than assuming each event arrives exactly once.
- Inbound webhook delivery is not guaranteed to happen at all (e.g., if Razorpay Clone crashes before sending it) — noted as a gap not yet addressed by the PRD; a reconciliation/polling fallback may be needed later (open item, not yet designed).

---

## Decision: Go multi-service (Gateway + Eureka discovery) from day one, skipping the PRD's phased monolith-first build

**Context / Problem:**
The PRD's original 22-session build plan introduces the API Gateway and the
Order/Inventory/Notification/Settlement service split late (Session 22,
"Distributed microservices setup"), implying earlier sessions build against a
single deployable that gets split apart later.

**Options Considered:**
1. Follow the PRD's phased approach — build against a single service/monolith
   first, introduce the Gateway and service split later.
2. Stand up the full multi-service shape (API Gateway, Eureka discovery, and
   the separate Order/Inventory/Notification/Settlement services) from the
   start.

**Chosen Approach:**
Option 2 — multi-service with Gateway and discovery from day one.

**Why:**
Akshansh has prior experience building a similar multi-service project
structure, so the incremental "prove it in one service, split later" path
wasn't needed to de-risk the unfamiliar parts of the setup.

**Trade-offs:**
- Gains: no later migration step where already-written monolith code has to be
  torn apart into services and rewired through a gateway; the service
  boundaries decided above are load-bearing from the first line of code.
- Costs: more infrastructure (Gateway, Eureka, multiple DBs) has to be stood up
  before any business logic runs; less opportunity to validate a feature
  end-to-end quickly before the distributed-systems concerns (routing,
  discovery, cross-service calls) are in the picture.

**Consequences:**
Environment/repo setup (Docker Compose with multiple Postgres instances, Redis,
Kafka, Gateway, discovery service) is a prerequisite before the first working
endpoint, rather than something introduced partway through the build plan.

---

## Decision: Config server lives in its own repo, separate from the business-services monorepo

**Context / Problem:**
FlashMart's codebase spans an API Gateway, a discovery service, four business
services, and a Spring Cloud Config Server. Needed to decide repo layout —
one repo for everything, or split.

**Options Considered:**
1. Single repo for all components, including the config server.
2. Monorepo for the business/routing services (api-gateway, discovery-service,
   order-service, inventory-service, notification-service, settlement-service)
   with the config server pulled into a separate repo.

**Chosen Approach:**
Option 2 — monorepo for the business services, config server in its own repo.

**Why:**
Infrastructure (the config server) and business logic have different release
lifecycles — the config server changes on its own schedule (config/env
concerns) independent of when business services ship features, so coupling
their versioning in one repo doesn't reflect how they actually change.

**Trade-offs:**
Two repos to clone/manage instead of one; cross-repo changes (e.g. a config key
rename affecting a business service) require coordinating commits across repo
boundaries.

**Consequences:**
None yet identified beyond standard multi-repo coordination overhead.

---

## Decision: Vendor email delivered to Notification Service via event enrichment, not a synchronous Vendor Service call

**Context / Problem:**
Notification Service needs the vendor's email to notify the vendor when an
order is confirmed. Vendor Service is not called synchronously anywhere in the
purchase flow — its data (bank details, KYC, contact info) is otherwise only
touched during the separate settlement/payout batch flow.

**Options Considered:**
1. Notification Service calls Vendor Service synchronously (Feign) to fetch the
   vendor's email when it receives the `OrderConfirmed` event.
2. Order Service enriches the `OrderConfirmed` Kafka event with the vendor
   email itself, so Notification Service reads it directly off the event.

**Chosen Approach:**
Option 2 — event enrichment.

**Why:**
Option 1 would make Notification Service's ability to send a notification
depend on Vendor Service being up at that moment — a new runtime coupling
between two services whose entire justification for being separate (see the
service-boundary decision above) was failure isolation. Enriching the event
upstream, once, means Notification Service can process the event using only
what's already inside it, independent of any other service's availability.

**Trade-offs:**
The vendor email embedded in the event is a snapshot at enrichment time, not a
live lookup — if the vendor's email changes between enrichment and
notification delivery, the notification could go to a stale address (accepted,
since this data already tolerates some staleness for this use case).

**Consequences:**
Order Service itself now needs a way to obtain the vendor email without adding
a new synchronous dependency on Vendor Service — see next decision.

---

## Decision: Vendor email sourced via the existing Inventory reservation call, denormalized into Inventory's `products` table — not a new Vendor Service call or a Redis cache

**Context / Problem:**
Order Service needs the vendor's email to enrich the `OrderConfirmed` event
(previous decision), but adding a new synchronous call to Vendor Service just
for this would reintroduce the coupling that decision was meant to avoid.

**Options Considered:**
1. New Feign call from Order Service to Vendor Service to fetch vendor email.
2. Redis cache of vendor email, populated from Vendor Service, read by Order
   Service.
3. Denormalize vendor email into Inventory Service's `products` table (kept
   fresh via a Kafka event from Vendor Service on email change), returned as
   part of the response Order Service already gets from its existing
   `reserve(productId, qty)` Feign call to Inventory Service.

**Chosen Approach:**
Option 3.

**Why:**
Option 1 was rejected for the same reason as the previous decision — it's a new
runtime dependency on Vendor Service inside the purchase path. Option 2 (Redis
cache) was explicitly considered and rejected: the cache-population,
invalidation, and cache-miss-fallback complexity wasn't judged worth it for
data that's already tolerant of some staleness — a cache is solving a problem
(read latency/load on Vendor Service) that piggybacking on an existing call
already avoids for free. Option 3 reuses a call Order Service already makes for
an unrelated reason (stock reservation), so no new dependency, timeout budget,
or failure mode is introduced.

**Trade-offs:**
Inventory Service now owns a denormalized copy of data it doesn't otherwise
care about (vendor email), and must consume a Kafka event from Vendor Service
to keep it fresh — a new consumer just to serve this one field. There's a
staleness window between a vendor changing their email in Vendor Service and
that Kafka event being consumed and applied in Inventory.

**Consequences:**
Inventory Service's `products` schema needs the denormalized email column, and
its Kafka consumer set grows by one (vendor-email-change events from Vendor
Service) — to be reflected when Inventory's schema is finalized.

---

## Decision: Order state machine — final state set derived from sequence diagrams; `CONFIRMED` and `RESERVED` dropped from the PRD's original list

**Context / Problem:**
The PRD's draft purchase flow implied an order lifecycle of `RESERVED` →
`PENDING_PAYMENT` → `PAID` → `CONFIRMED` → `SHIPPED` / `CANCELLED` / `EXPIRED`.
Needed to derive the actual state set Order Service's state machine should
implement, strictly from what the sequence diagrams show Order Service doing —
not carry the PRD's draft list forward unexamined.

**Options Considered:**
1. Keep the PRD's original state list as-is.
2. Derive the state list from the sequence diagrams: a state only belongs to
   Order Service's machine if some diagram actually shows Order Service setting
   it.

**Chosen Approach:**
Option 2. Final Order Service states: `CREATED`, `PENDING_PAYMENT` (transient)
→ `PAID`, `PAYMENT_FAILED`, `ORDER_EXPIRED`, `PAYMENT_UNAVAILABLE` — the latter
four all terminal.

**Why:**
`CONFIRMED` was dropped because nothing in any sequence diagram sets it — Order
Service never blocks waiting for Inventory, Notification, or Settlement to
finish their downstream work (doing so would recouple Order Service to their
uptime, undoing the async decoupling already decided above). `PAID` is treated
as terminal by design: it represents the one fact that matters to Order
Service's own responsibility (payment was captured) — everything after that is
eventual and owned by other services. `RESERVED` was dropped from Order
Service's machine entirely because it's not Order's state to hold — it belongs
to Inventory Service's `stock_reservations` table, a different entity with its
own lifecycle.

**Trade-offs:**
Order Service's state no longer reflects "has the vendor shipped / has the
buyer been notified" — by design, since tracking that would require Order
Service to wait on or poll other services. Anyone reading Order Service's
status alone gets "was this order paid or not," not full fulfillment status.

**Consequences:**
Every terminal state needs an idempotent guard against late-arriving events —
see next decision.

---

## Decision: Idempotent terminal-state guard for late-arriving payment webhooks/events

**Context / Problem:**
A payment webhook (or, more generally, any event Order Service reacts to) can
arrive after Order Service has already moved an order to a terminal state —
e.g., the reservation TTL sweep already expired the order before a late
`SUCCESS` webhook shows up from Razorpay Clone.

**Options Considered:**
1. Apply whatever status the incoming webhook/event carries, regardless of the
   order's current state.
2. Guard every terminal state against re-entry, with handling that depends on
   which terminal state the order is already in and which late event arrived.

**Chosen Approach:**
Option 2.

**Why / behavior derived:**
A late `FAILED` or `PAYMENT_UNAVAILABLE` webhook arriving after the order is
already in any terminal state is a pure no-op (log only) — nothing real
happened financially, so there's nothing to undo. A late `SUCCESS` webhook
arriving after the order is already `ORDER_EXPIRED` is different: real money
was captured on the gateway side, so it must trigger a compensating refund call
to Razorpay Clone, then be logged — but the order's own status stays
`ORDER_EXPIRED` (the buyer-facing outcome doesn't change just because a refund
happened after the fact; the refund is a side-effect cleanup, not a new state
transition).

**Trade-offs:**
Every state-transition handler needs an explicit "am I already terminal?" check
before acting, and refund/compensation logic has to live alongside — but
outside — the state machine's transition table, since it doesn't itself change
the order's state.

**Consequences:**
This guard needs to be built and explicitly tested (a late webhook fired after
expiry) once the state machine and webhook handler are implemented — a
candidate for one of the "break it" scenarios in the Learn → Build → Revise
cycle for this feature.