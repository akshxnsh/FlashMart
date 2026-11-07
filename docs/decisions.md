# FlashMart — Architectural Decisions

> **Naming note:** the service that owns brand/seller identity has been renamed
> twice: **Vendor Service → Brand Service** (product-vision pivot) → **Identity
> Service** (now also owns buyers). Entries written before those changes keep
> their original names to preserve history; notes on each entry point to what
> superseded it.
>
> **Status note:** everything here is *designed*, not implemented or tested,
> unless an entry says otherwise. As of 2026-10-22 the exceptions are: every
> schema decision for all four services (Inventory, Identity, Order,
> Notification), implemented as entities on Flyway migrations and
> persistence-tested against real Postgres; and Inventory's reserve HTTP
> contract (DTOs, validation, error mapping), implemented and tested at the
> HTTP layer with the service mocked. `architecture.md` §8 is the status source
> of truth. As of 2026-10-22, no `reserve` logic, repositories, other
> controllers, Kafka, Redis or security code existed and nothing was
> load-tested; `reserve` and the Day-1 repositories have since been built (see
> `architecture.md` §8). Kafka, Redis and security remain *designed*.
>
> **Correction (Day 8 docs pass):** the last sentence is out of date for Kafka.
> The outbox relay and producer (Day 6) and Inventory's two consumers (Day 7)
> are implemented and tested; see `architecture.md` §8. **Redis and security are
> still designed only**; "Day 8: auth, gateway identity, rate limiting, browse"
> (last entry) designs them and is itself **not built**.

---

## Decision: Saga (orchestration) instead of 2PC or a single distributed transaction for Order/Inventory/Payment consistency

> **Note:** the *pattern* stands, but the `saga_state` table named below no
> longer exists. Orchestration state now lives directly on `orders` — see
> "`saga_state` dropped" in Revision 3 below. Order Service is still the sole
> orchestrator; it just tracks its own progress on the order row rather than
> in a parallel table that could drift out of sync with it.

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
- Order lifecycle needs explicit intermediate states, not one atomic flip.
  *(Originally written as `RESERVED` → `PENDING_PAYMENT` → `PAID`; `RESERVED`
  was later dropped from Order's state machine — see "Order state machine"
  decision below.)*
- Requires a safety-net mechanism for the case where the expected event
  (payment webhook) never arrives at all (see next decision).
- *(See "Multi-item orders" decision below — the saga's inventory-reservation
  step now spans N items per order, not one, which is a direct consequence of
  this orchestration design being reused rather than rebuilt.)*

---

## Decision: TTL-based polling sweep, not a Kafka delayed-message pattern, for reservation expiry / webhook-never-arrives safety net

> **Note:** **superseded twice.** (1) The original text ignored that
> `stock_reservations` is Inventory's table while `ORDER_EXPIRED` is Order's
> state, so an event was added to bridge them ("Order expiry via a
> `ReservationExpired` event", below). (2) That bridge has now been removed
> entirely: the sweep runs **inside Order Service**, over a new
> `orders.expires_at` column, and Inventory no longer expires anything on its
> own — see "Order Service is the sole authority on an order's fate" in
> Revision 3 below. What survives from this entry is the original choice of
> *mechanism*: a scheduled polling sweep rather than a Kafka delayed-message
> pattern. That reasoning is unchanged; only the table and the owning service
> moved.

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
stock can sit wrongly-reserved past its TTL before release). With multi-item
orders, a single sweep now needs to release a *set* of reservations tied to one
order, not a single row.

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

> **Note:** the resulting service list below (Vendor Service and Settlement
> Service) is superseded by the "Product vision pivot" and "Identity Service"
> decisions further down. The *rule* used to derive boundaries — traffic shape
> + blast radius — still stands and is unaffected; only the concrete service
    > list changed. Current services: Order, Inventory, Notification, Identity
    > (+ stateless API Gateway).

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
Every future service-boundary question (e.g. "should X be its own service") must be justified against this same rule — a concrete traffic-shape or blast-radius story — not against convention or how "production-grade" it looks. See the Vendor Service decision below for the first application of this test, and the Identity Service decision for the most recent.

---

## Decision: Vendor Service kept as one combined service (not split into Identity/Auth vs. Vendor Profile/Payout)

> **Note:** superseded in two steps. (1) "Product vision pivot" removed the
> payout/KYC scope. (2) "Identity Service" (below) merged buyer accounts into
> the same service. The *reasoning rule* here (don't split without a concrete
> traffic/blast-radius story) is still live and is what the Identity Service
> decision applies.

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
- Kafka's at-least-once delivery means every consumer (starting with Inventory Service's `PaymentConfirmed` handler) must be built as an **idempotent consumer** — checking current state before applying a transition — rather than assuming each event arrives exactly once. *(Notification Service's version of this is now designed — see "Notification idempotency" below.)*
- Inbound webhook delivery is not guaranteed to happen at all (e.g., if Razorpay Clone crashes before sending it) — noted as a gap not yet addressed by the PRD; a reconciliation/polling fallback may be needed later (open item, not yet designed).
- The Order→Inventory reservation call in this table refers to a single-item call; the "Reservation strategy" decision below spells out how this is invoked for multi-item orders (a loop of these same calls, not a new communication mode). *(The call must now identify the item by `(flash_sale_id, product_id)`, not `productId` alone — see "UNIQUE(flash_sale_id, product_id)" below. It also now returns `brand_id` — see "Brand email delivery" below.)*
- Settlement Service is no longer a consumer of `PaymentConfirmed` — see "Product vision pivot" below.
- New async interaction: Inventory Service → Order Service, `ReservationExpired` (Kafka, via Inventory's outbox) — see "Order expiry via a `ReservationExpired` event" below.
- `StockReserved` is dropped from the event list — reservation is a synchronous Feign call, so no consumer needs it. See "Drop `StockReserved`" below.

---

## Decision: Go multi-service (Gateway + Eureka discovery) from day one, skipping the PRD's phased monolith-first build

> **Note:** the service list below predates the pivot. Settlement Service no
> longer exists; the business services are now Order, Inventory, Notification,
> Identity.

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

> **Note:** the monorepo list below predates the pivot — `settlement-service`
> is removed and an `identity-service` module replaces the old vendor/brand
> service.

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

> **Note:** **Superseded.** Written pre-pivot, when FlashMart was a
> multi-vendor marketplace and one order could contain products from several
> vendors, so each product needed to carry its own vendor's contact info. That
> premise no longer holds — every order belongs to exactly one brand (see "One
> flash sale per order"). The *goal* here (get the email to Notification
> without a synchronous call to Identity) is preserved, but the *mechanism* is
> replaced — see "Brand email delivery to Notification Service" below.

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

> **Note:** **Superseded** — see "Brand email delivery to Notification
> Service" below. This decision put the denormalized email on `products`,
> which meant it was fetched once *per item*, and it put email-handling logic
> inside Inventory Service, which never actually uses an email. Both premises
> broke once the brand-platform model made "one order → one brand" true: the
> email only needs to be resolved once per order, and by the one service that
> actually sends it.

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
Service) — to be reflected when Inventory's schema is finalized. With multi-item
orders, this reservation call now happens once per item in the order (see
"Reservation strategy" below) — the enrichment pattern itself is unaffected,
since each call already returns its own product's vendor email independently.

---

## Decision: Order state machine — final state set derived from sequence diagrams; `CONFIRMED` and `RESERVED` dropped from the PRD's original list

> **Note:** a fifth terminal state, `RESERVATION_FAILED`, has since been added
> for the explicit out-of-stock case — see Revision 3 below. The derivation
> rule used here (a state is only real if some sequence diagram actually shows
> Order Service setting it) is what justified adding it, and is also what later
> removed `saga_state.COMPENSATING` on the same test.

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
see next decision. This state machine is unaffected by the multi-item order
model — it remains order-level regardless of item count (see "Multi-item
orders" decision below).

---

## Decision: Idempotent terminal-state guard for late-arriving payment webhooks/events

> **Note:** **superseded** by "An order keeps exactly one payment" in Revision 3
> below. The guard as written here enumerated cases by *which terminal state the
> order was already in*, and had a hole: it only refunded a late `SUCCESS` after
> `ORDER_EXPIRED`, so a second captured payment arriving on an already-`PAID`
> order would have been silently kept. The replacement states one invariant
> instead of a case table, and is enforced by the compare-and-set itself rather
> than by a pre-check.

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

The same guard makes Order Service's `ReservationExpired` consumer idempotent
without any extra table: a duplicate delivery for an order already in
`ORDER_EXPIRED` (or any other terminal state) is a no-op. See "Order expiry via
a `ReservationExpired` event" below.

---

## Decision: Multi-item orders — Order supports multiple OrderItems, not one-product-per-order

**Context / Problem:**
The PRD's schema sketch (`orders.flash_sale_id`, singular) and the purchase
sequence diagram (`reserve(productId, qty)`, one call) implicitly modeled one
order = one product = one flash sale. This does not reflect realistic
e-commerce usage, where a customer buys several different products from the
same brand's sale in one checkout (e.g. Shoes ×2 + T-Shirt ×1).

**Options Considered:**
1. Keep one-product-per-order, matching the PRD's original schema sketch.
2. Support multiple OrderItems per Order, each with its own product, quantity,
   unit price, and subtotal.

**Chosen Approach:**
Option 2. `Order` → many `OrderItem`s; each `OrderItem` holds `productId`,
`quantity`, `unitPrice`, `subtotal`.

**Why:**
One-product-per-order was simpler but not realistic, and it was quietly
avoiding — not solving — the partial-multi-item-reservation-failure problem
that any real flash-sale checkout with more than one item eventually has to
face. Making the model explicit forces that problem to be designed for
directly (see "Reservation strategy" decision below) rather than deferred
indefinitely.

**Trade-offs:**
Reintroduces genuine saga complexity that single-item orders had sidestepped:
partial reservation failure across N items, saga step semantics that now mean
"all items," and event payloads that must carry a full item list instead of a
single product/quantity pair.

**Consequences:**
- `orders.quantity` removed (quantity now lives per item).
- ~~`flash_sale_id` moves to `order_items` (each line item can belong to a
  different flash sale).~~ **Corrected:** `flash_sale_id` **stays on
  `orders`** — an order is placed against exactly one brand's sale. See
  "One flash sale per order" below.
- `orders.amount` becomes derived as `SUM(order_items.subtotal)` at
  order-creation time, not a single flash-sale-price × qty calculation.
- The `reserve(productId, qty)` Feign call is no longer sufficient as a single
  call per order — see "Reservation strategy" decision below.
- ~~`outbox` event payloads (`OrderCreated`, `PaymentConfirmed`, etc.) must carry
  the full item list, not a single product/quantity pair.~~ **Corrected:**
  `OrderCreated` no longer exists (see "`OrderCreated` dropped" in Revision 3).
  `PaymentConfirmed` still carries the full item list — not for Inventory, which
  looks its reservations up by `order_id`, but for Notification, which needs the
  items to compose the buyer's confirmation.
- A new sequence diagram is needed for partial-item-reservation-failure, which
  did not exist as a scenario under the single-item model.

---

## Decision: Reservation strategy for multi-item orders — all-or-nothing, via a sequential loop of per-item Feign calls

> **Note:** the all-or-nothing policy and the per-item loop both stand. Two
> consequences listed below do not: the synchronous `release()` counterpart and
> `saga_state.status = COMPENSATING` were **both dropped** — compensation now
> happens by publishing one `OrderCancelled` event that Inventory reacts to,
> not by Order actively calling `release()` per item. See "One order-death
> event" and "`saga_state` dropped" in Revision 3 below.

**Context / Problem:**
Once an order can contain multiple items, a real scenario emerges that didn't
exist before: some items in an order can be reserved successfully while others
cannot (out of stock). Needed to decide both the *outcome policy* (what happens
to the order) and the *call shape* (how Order Service actually invokes
Inventory Service for N items).

**Options Considered:**
- Outcome policy:
    1. All-or-nothing — if any item's reservation fails, release any items
       already reserved for that order and do not proceed to payment.
    2. Partial fulfillment — let the order proceed with only the items that
       succeeded.
- Call shape:
    1. Order Service loops over unique order items, calling Inventory's existing
       single-item `reserve(productId, quantity)` once per iteration.
    2. A new batch endpoint on Inventory Service accepting a list of
       `{productId, quantity}` pairs, reserved in one call.

**Chosen Approach:**
All-or-nothing outcome policy, implemented as a sequential loop of per-item
`reserve(productId, quantity)` Feign calls (not a new batch endpoint).

**Why:**
Partial fulfillment was rejected because it would change what "PAID" means
mid-flight — the charged amount, the item list, and the buyer's expectation
would all have to be recalculated after the fact, which conflicts with the
existing decision to snapshot `amount` at order-creation time. All-or-nothing
keeps the existing Order state machine's semantics intact: `PENDING_PAYMENT`
still means "every item is reserved," unchanged from the single-item model.
The per-item-loop call shape (over a new batch endpoint) was chosen to keep
Inventory Service's API surface unchanged — it never needs to know or reason
about multi-item orders at all; that concept, and the saga complexity that
comes with it, stays entirely inside Order Service, which already owns saga
orchestration.

**Trade-offs:**
- Sequential per-item Feign calls mean per-order reservation latency scales
  with item count (N round trips instead of 1) — a real cost against the
  buy-endpoint p99 latency target (NFR-P2), to be measured once load-tested,
  not assumed acceptable.
- Requires explicit compensating logic: on any item's failure mid-loop,
  already-reserved items from earlier in the same loop must be released via a
  matching release/cancel call — Inventory Service's `reserve` API needs a
  counterpart for this to be possible.
- Introduces a time-of-check gap within a single order: real wall-clock time
  passes between the first item's reservation and a later item's failure,
  during which the earlier reservation's own TTL clock is already running.

**Consequences:**
- Inventory Service needs a `release`/`cancel` reservation endpoint,
  independent of and in addition to `reserve` — not yet designed.
- `saga_state.step = INVENTORY_RESERVATION` now represents "all N items
  reserved," not a single item. A failure partway through the loop is
  represented by `saga_state.status = COMPENSATING` (added to the status set:
  `IN_PROGRESS | SUCCESS | FAILED | COMPENSATING`) while already-reserved items
  are released, then `FAILED`.
- The reserve call must identify the item by `(flash_sale_id, product_id)`,
  not `productId` alone — see "UNIQUE(flash_sale_id, product_id)" below.
- A new sequence diagram (partial-item-reservation-failure → compensation) is
  needed before Inventory Service's reservation API can be finalized.

---

## Decision: Product vision pivot — FlashMart is a flash-sale platform for brands, not a multi-vendor marketplace; Settlement Service removed

**Context / Problem:**
The original PRD modeled FlashMart as a multi-vendor marketplace: independent
sellers list products, and the platform itself computes commission and batches
payouts to them (Settlement Service, `settlements`/`payout_batches` tables).
This was revisited: FlashMart's actual intended use case is brands (e.g. Puma,
Nike) running their own flash sales on the platform, not the platform
intermediating money between independent third-party sellers.

**Options Considered:**
1. Keep the marketplace framing — independent vendors, platform-computed
   commission, payout batching (original PRD scope).
2. Narrow to a brand-platform framing — brands manage their own products,
   pricing, and inventory directly; the platform does not compute commission
   or handle payouts on their behalf.

**Chosen Approach:**
Option 2. Settlement Service and its two tables (`settlements`,
`payout_batches`) are removed entirely. Vendor Service's scope narrows to
brand identity, API keys, and product ownership — bank details and KYC (which
existed for payout/compliance purposes) are dropped.

**Why:**
Under the brand-platform model there is no platform-computed money movement to
third-party sellers to justify a settlement engine — each brand owns its own
payment relationship independent of FlashMart. This is a deliberate scope cut,
not a re-solved problem: the original PRD explicitly called vendor
settlement/commission a "first-class real-world backend problem" worth
building, and this decision removes it rather than replacing it with something
equivalent.

**Trade-offs:**
- Gains: removes an entire service, its data model, and its Kafka consumer
  relationship, simplifying the system with no corresponding new complexity
  introduced elsewhere.
- Costs: gives up the settlement/payout engine as a learning opportunity and
  as a resume bullet (PRD Section 8) — this trade-off is accepted knowingly,
  not incidentally.

**Consequences:**
- Settlement Service removed from the architecture and API Gateway routing.
- The `PaymentConfirmed → Settlement Service` Kafka consumer relationship no
  longer exists.
- Vendor Service's original single-vs-split decision (see above) gets
  re-opened at smaller scope. *(Resolved — see "Identity Service" below.)*
- Order Service, its schema, saga design, and state machine are entirely
  unaffected by this decision — none of them depended on settlement.

---

## Decision: One flash sale per order — `flash_sale_id` stays on `orders`, not on `order_items`

**Context / Problem:**
The multi-item decision originally moved `flash_sale_id` down to `order_items`,
allowing one order to span several sales. Under the brand-platform model this
needed re-examining: can a single checkout mix items from different sales?

**Options Considered:**
1. `flash_sale_id` on `order_items` — each line can belong to a different sale.
2. `flash_sale_id` on `orders` — every item in an order belongs to the same sale.

**Chosen Approach:**
Option 2.

**Why:**
A flash sale is one brand's time-boxed event. Buyers check out within one
brand's sale, and cross-sale orders would mean one payment spanning several
brands' events, each with its own start/end window — complexity with no
requirement behind it. Keeping it on `orders` states the rule in the schema
itself.

**Trade-offs:**
A buyer who wants items from two different sales needs two orders (two
payments). Accepted.

**Consequences:**
- Order Service must validate at creation that every requested item belongs to
  the order's `flash_sale_id`.
  > **Correction (from "`ITEM_NOT_IN_SALE` is a `200` outcome"):** no such
  > check exists or can exist as designed. Order cannot read Inventory's
  > database and no endpoint answers the question, so an item outside the sale
  > is only discovered by `reserve`, which answers `200 ITEM_NOT_IN_SALE`.
- Since a sale belongs to exactly one brand, every order belongs to exactly one
  brand — this is the fact that made the "Brand email delivery" redesign below
  possible.

---

## Decision: `flash_sales` is a brand-level sale event; per-product terms live in `flash_sale_items`

**Context / Problem:**
The PRD sketch `flash_sales(id, product_id, sale_price, total_stock, ...)` had
one row per product. That can't represent "Puma's Midnight Sale" as one thing
that `orders.flash_sale_id` points to — a two-product order would be ambiguous
about which row it belongs to.

**Options Considered:**
1. Keep one `flash_sales` row per product.
2. Split into `flash_sales` (the event) and `flash_sale_items` (each product's
   price and stock inside that event).

**Chosen Approach:**
Option 2:
- `flash_sales(id, brand_id, name, start_time, end_time, status)`
- `flash_sale_items(id, flash_sale_id FK, product_id FK, sale_price, total_stock, version)`
- `stock_reservations.flash_sale_item_id` (was `flash_sale_id`) points at the
  specific product-in-sale being held.

**Why:**
The sale window and status belong to the event; price and stock belong to the
product within the event. Each product still needs its own independent stock
pool inside one sale.

**Trade-offs:**
One more table and one more join when reading a sale with its products.

**Consequences:**
Inventory's contended row is now `flash_sale_items`, which drives where the
optimistic-lock column goes (next decision). `flash_sales.brand_id` is also
what makes it possible for a `reserve()` call to return the order's `brand_id`
— see "Brand email delivery" below.

---

## Decision: Optimistic-lock `version` column on `flash_sale_items`, not on `stock_reservations`

> **Note:** the counter this protects is now `available_stock`, not
> `total_stock` — see "`total_stock` stays fixed; `available_stock` is the
> decremented counter" below. The reasoning is unchanged.
>
> **Second note (2026-10-02) — the Consequences below are not settled.** The
> `version` column and `@Version` exist on the entity as designed. But the
> claim that reserve "becomes a conditional update ... `WHERE id = ? AND
> version = ?`" contradicts "Inventory's `reserve` API contract", which has
> liveness and the stock check/decrement in one statement against the
> database clock. Under READ COMMITTED those differ sharply (see Open Items:
> "The reserve write path is specified two different ways"). Read the
> Consequences here as one candidate, not the decision.
>
> **Third note (2026-11-04) - decided elsewhere.** The reserve write path is
> now decided in "Reserve write path: READ COMMITTED, one atomic conditional UPDATE, one short transaction": the `WHERE version = ?` form is rejected as the reserve
> path. `version` stays for entity saves.

**Context / Problem:**
The PRD carried `version` on `stock_reservations` as a placeholder without
deciding what it protects.

**Options Considered:**
1. `version` on `stock_reservations`.
2. `version` on `flash_sale_items`.

**Chosen Approach:**
Option 2.

**Why:**
Concurrent buyers never update the same reservation row — each reservation is
a newly inserted row owned by one order. The row thousands of buyers actually
fight over is the shared stock counter, `flash_sale_items.total_stock`. The
version check belongs where the contention is.

**Trade-offs:**
Under heavy contention on one hot item, many version checks fail and must be
retried or rejected — optimistic locking is cheap when conflicts are rare, and
a flash sale is exactly when they aren't. Whether optimistic locking alone is
enough here, versus pessimistic locking or a Redis lock in front of it, is
**not yet decided** (Session 17 topic; to be tested, not assumed).

**Consequences:**
Reserve becomes a conditional update on `flash_sale_items`
(`... WHERE id = ? AND version = ?`) followed by a `stock_reservations` insert.
Whether those two writes share one local transaction is still to be designed.

---

## Decision: `UNIQUE(flash_sale_id, product_id)` on `flash_sale_items`

**Context / Problem:**
Nothing stopped the same product from being listed twice in one sale, with two
prices and two stock pools.

**Options Considered:**
1. Rely on application code to avoid duplicates.
2. Enforce with a database UNIQUE constraint.

**Chosen Approach:**
Option 2.

**Why:**
With duplicates, a reserve request for a product in a sale has no single row
to decrement, and stock could oversell across the two pools while each looks
fine on its own. A DB constraint makes the bad state impossible instead of
relying on every code path to remember a rule.

**Trade-offs:**
Changing a product's terms within a sale means updating its existing row, not
inserting a new one.

**Consequences:**
Order Service's reserve call must carry `(flash_sale_id, product_id)` — both
are already known to Order Service (`orders.flash_sale_id`,
`order_items.product_id`), so Inventory can resolve exactly one
`flash_sale_items` row. (Unrelated to brand-email delivery — that is resolved
separately below.)

---

## Decision: Brand Service becomes Identity Service, owning `brands`, `api_keys`, and `users` (buyers)

**Context / Problem:**
Two open items converged. (1) After the pivot, it was undecided whether Brand
Service stays standalone or folds into Inventory. (2) No service owned buyer
accounts — `orders.buyer_id` pointed at nothing that had been deliberately
placed.

**Options Considered:**
1. Fold brand data into Inventory Service; create a separate User Service for
   buyers.
2. Keep Brand Service standalone; create a separate User Service for buyers.
3. One Identity Service owning brands, their API keys, and buyer accounts.

**Chosen Approach:**
Option 3.
- `brands(id, name, email UNIQUE, password_hash, created_at, updated_at)`
- `api_keys(id, brand_id FK, key_hash, status ACTIVE|REVOKED, created_at)`
- `users(id, name, email UNIQUE, password_hash, created_at)`

**Why:**
Applying the traffic-shape rule: brands and buyers share the same access
pattern — infrequent signup/login, never on the hot checkout path. Folding
brands into Inventory would put cold identity data inside the hottest service
with no benefit. A separate User Service would add a service with no distinct
traffic or failure story. They are grouped by access pattern, not because
brands and buyers are the same kind of entity (`users` has no relationship to
`brands` or `api_keys`).

This is not a reversal-by-reflex of the earlier "Vendor Service kept combined"
decision, which rejected a separate auth service. Here, buyer and brand login
genuinely share one cold, cross-cutting concern, which is the specific
condition that earlier decision named as a valid reason to revisit.

**Trade-offs:**
Buyer and brand credentials share one database, so a breach there exposes
both. API keys are stored hashed and passwords as hashes, which limits the
damage but doesn't remove the shared blast radius.

**Consequences:**
- `products.brand_id`, `flash_sales.brand_id` → `brands.id`, and
  `orders.buyer_id` → `users.id` are cross-service references (no DB FK).
- Identity publishes `BrandUpserted` and `UserUpserted` events, through its
  own outbox — see "Contact caches fed by `BrandUpserted` / `UserUpserted`" and
  "Every service that publishes events owns an outbox" below.

---

## Decision: Notification consumer idempotency via `UNIQUE(order_id, event_type, recipient_type, channel)` on `notification_log`

**Context / Problem:**
Kafka delivers at least once. A redelivered `PaymentConfirmed` must not send a
second email.

**Options Considered:**
1. Check-then-insert in application code ("have I already sent this?").
2. A UNIQUE constraint so the database rejects the duplicate insert.

**Chosen Approach:**
Option 2, with `channel` in the key.

**Why:**
Check-then-insert races when two deliveries of the same event are processed at
once; both checks can pass. The constraint closes that race at the database.
Including `channel` allows one event to go out by email and SMS without the
second being treated as a duplicate.

**Trade-offs:**
A duplicate delivery surfaces as a constraint-violation exception the consumer
must catch and treat as "already handled," not as an error.

**Consequences:**
- `order_id` is a cross-service reference to Order Service (no DB FK).
- `status` is `SENT | FAILED` and `sent_at` is set only on success. Because of
  the unique key, retrying a `FAILED` send must update the existing row rather
  than insert a new one.

---

## Decision: Brand email delivery to Notification Service — local `brand_contacts` cache, fed by an Identity Service event; `brand_id` carried in the outbox payload but not persisted on `orders`

> **Note:** the event is now `BrandUpserted`, fired on brand **creation** as
> well as email change — a "changed"-only event left new brands with no row in
> `brand_contacts`. See "Contact caches fed by `BrandUpserted` / `UserUpserted`"
> below. Also: with `OrderCreated` dropped (Revision 3), `PaymentConfirmed` is
> now the **sole** Order outbox payload carrying `brand_id`/`buyer_id`. If
> Notification is ever made a consumer of `OrderCancelled` too, that payload
> needs the same enrichment — currently only Inventory consumes it, and
> Inventory needs neither field.

**Context / Problem:**
The two pre-pivot decisions above ("Vendor email via event enrichment" and
"denormalized into Inventory's `products`") no longer fit. They were built for
a world where one order could span many vendors, so each `products` row had to
carry its own vendor's email, fetched once per item. Under the brand-platform
model that premise is gone: every order belongs to exactly one brand (see "One
flash sale per order"). The email was also being carried through two services
— Inventory and Order — that never use it; only Notification does.

**Options Considered:**
1. Leave the old design as-is (email denormalized onto `products`, fetched
   once per reserved item).
2. Move the denormalized email onto `flash_sales` instead of `products`, so
   it's fetched once per sale rather than once per product.
3. Stop carrying the email through Inventory and Order entirely. Give
   Notification Service its own local `brand_contacts(brand_id, email,
   updated_at)` table, kept fresh by consuming an event Identity Service
   publishes on brand-email change. Have Inventory's `reserve()` return
   `brand_id` (not the email) via `flash_sales.brand_id`, which Order Service
   reads once per order and places into the `OrderCreated`/`PaymentConfirmed`
   outbox payload — never persisted as a column on `orders`.

**Chosen Approach:**
Option 3.

**Why:**
Options 1 and 2 both still route an email through two services that don't use
it, just at different granularity. Option 3 follows the same rule already
applied to service boundaries and Kafka consumption: data should live with, or
flow directly to, the service that uses it. Notification is the only consumer
of the brand email, so Notification should own the read path for it — the
same pattern the ERDs already show for `brands.updated_at` triggering a
Kafka-consumed denormalized copy, just pointed at the correct service.
`brand_id`, unlike the email, is not pass-through data for Order Service — an
order fundamentally belongs to one brand, the same way it belongs to one
`flash_sale_id` — so carrying it is not the same mistake as carrying the
email was. It is fetched once per order (from the first `reserve()` response
in the item loop, not once per item) because, like `flash_sale_id`, it cannot
differ across items in the same order.

`orders` gains no new stored column: nothing in FlashMart today needs to query
"this buyer's orders from this brand" after the fact, so persisting `brand_id`
would be exactly the same anti-pattern being removed elsewhere — data stored
but never read by its owner. If that query need appears later, adding the
column then is cheap; the decision is revisited using the same rule, not
reversed on convenience.

**Trade-offs:**
- Notification Service now owns and must keep in sync a piece of state
  (`brand_contacts`) it previously never held — a new Kafka consumer and a new
  table, purely to serve this one field.
- The email in `brand_contacts` can be stale between a brand changing it in
  Identity and that event being consumed — same accepted staleness as the
  superseded design had.
- `brand_id` is still visible only inside event payloads for `orders`, never
  in the table itself — if a stored-`brand_id` need appears later, that's a
  schema migration, not a payload change.

**Consequences:**
- **Identity Service** must publish a brand-email-changed event (name TBD,
  e.g. `BrandEmailUpdated`) — not yet designed; does not currently exist.
- **Notification Service ERD** gains a new table:
  `brand_contacts(brand_id PK, email, updated_at)` — `brand_id` is a
  cross-service reference to Identity's `brands.id`, no DB FK, and not joined
  to `notification_log` by any relationship line (`notification_log` has no
  `brand_id` column; the two are joined in application code, keyed by the
  `brand_id` carried in the event being processed).
- **Inventory Service ERD is unchanged** — no denormalized email column on
  `products`; `reserve()`'s response contract grows to include `brand_id`
  (already present as `flash_sales.brand_id`), which is not a schema change.
- **Order Service ERD is unchanged** — `orders` gains no new column;
  `brand_id` exists only inside outbox event payloads.
- This fully resolves the contradiction between the Inventory and Identity
  ERDs that the two superseded decisions above left open.

---

## Decision: Order expiry via a `ReservationExpired` event from Inventory Service

> **Note:** **Superseded entirely.** `ReservationExpired` no longer exists. The
> premise here — that Inventory owns the expiry decision and must tell Order
> about it — is exactly what created a race in which Inventory could release
> stock while a payment was in flight, selling the same unit twice. Expiry is
> now Order's decision, made by a compare-and-set on its own row, and the event
> flows the other way (Order → Inventory) under the name `OrderCancelled`. See
> "Order Service is the sole authority on an order's fate" and "One order-death
> event" in Revision 3 below. What survives is the *shape* chosen here: one
> event per order, not one per reserved item.

**Context / Problem:**
The TTL sweep runs over `stock_reservations`, which Inventory owns. But
`ORDER_EXPIRED` is Order Service's state, and Order cannot read Inventory's
database. As designed, Inventory would release the stock while the order sat in
`PENDING_PAYMENT` forever.

**Options Considered:**
1. Inventory publishes a `ReservationExpired` event; Order consumes it and
   moves the order to `ORDER_EXPIRED`.
2. Each service runs its own sweep on its own table, using the same TTL.

**Chosen Approach:**
Option 1. The sweep emits **one event per order**, not one per reserved item.

**Why:**
Two independent sweeps would each need to agree on the same TTL and clock, and
could disagree at the edges (Inventory releases stock, Order hasn't expired
the order yet, or the reverse). One source of truth for "this hold has
expired" — the service that owns the hold — avoids that. One event per order,
because Order only needs to know "order 42 expired", not how many items it
had.

**Trade-offs:**
- Order learns about expiry with Kafka lag, not instantly.
- Inventory now publishes events, which brings the dual-write problem into
  Inventory (see the outbox decision below).

**Consequences:**
- Inventory writes the reservation status change, the `available_stock`
  increments, and the `ReservationExpired` outbox row in **one local
  transaction**.
- Order's consumer is idempotent via the existing terminal-state guard — no
  extra table.
- A late payment `SUCCESS` webhook arriving after `ORDER_EXPIRED` still
  triggers the refund path (uses `orders.payment_ref`).

---

## Decision: Drop `StockReserved` from the event list

**Context / Problem:**
The PRD lists `StockReserved` as a Kafka event. Reservation is now a
synchronous Feign call whose response Order Service already uses.

**Options Considered:**
1. Keep publishing `StockReserved`.
2. Drop it.

**Chosen Approach:**
Option 2.

**Why:**
No service consumes it. Keeping it would force Inventory to write an outbox
row on the hottest path (every reserve) for an event nobody reads.

**Trade-offs:**
If a future consumer needs reservation activity, the event has to be added
back.

**Consequences:**
PRD Section 3.2's async event list and Section 6's session 18 entry need
updating to remove `StockReserved`.

---

## Decision: Every service that publishes events owns an outbox

> **Note:** the *rule* stands and is unchanged — any service that publishes
> events owns an outbox written in the same local transaction. The *list* has
> changed: Inventory no longer publishes anything (its only event,
> `ReservationExpired`, was removed), so it no longer has an outbox. Current
> publishers: **Order and Identity only**. Order's outbox now also carries
> `OrderCancelled`. See "Inventory publishes nothing" in Revision 3 below.
>
> **Second note (2026-10-02):** the `(published, created_at)` index this entry
> and the index-list entry call for is replaced by a *partial* index, and
> `payload_json` is `JSONB` — see "Identity's remaining tables" below. The
> `published` boolean named in the schema line below is also gone: it is now
> `published_at TIMESTAMPTZ` (NULL = unsent), plus `attempts`/`last_error`, so
> the partial index is `WHERE published_at IS NULL`.

**Context / Problem:**
Originally only Order Service had an `outbox`. Two more services now publish
events: Inventory (`ReservationExpired`) and Identity (`BrandUpserted`,
`UserUpserted`). Each does a database write and a Kafka publish together — the
same dual-write problem the outbox was introduced to solve.

**Options Considered:**
1. Publish directly to Kafka after the DB commit.
2. Give each publishing service its own `outbox` table, written in the same
   local transaction as the business change.

**Chosen Approach:**
Option 2 — Order, Inventory, and Identity each own an `outbox`
(`id, aggregate_id, event_type, payload_json, published, created_at`).
Notification publishes nothing and has none.

**Why:**
With direct publishing, a crash between commit and publish loses the event for
good. For Identity, that means Notification emails an old address forever; for
Inventory, an order stays `PENDING_PAYMENT` forever.

**Trade-offs:**
Three outbox tables and three relays to run and monitor instead of one.

**Consequences:**
- Rule going forward: a service that starts publishing events gets an outbox
  in the same change.
- Each outbox needs an index on `(published, created_at)` for its relay.
- For Inventory's `ReservationExpired`, `aggregate_id` is the `order_id`.

---

## Decision: Idempotent reserve via `UNIQUE(order_id, flash_sale_item_id)` on `stock_reservations`

**Context / Problem:**
Order calls `reserve`; Inventory commits; the Feign response times out; Order
retries. Without a guard, Inventory reserves the same item a second time for
the same order.

**Options Considered:**
1. Check for an existing reservation in application code before inserting.
2. A UNIQUE constraint on `(order_id, flash_sale_item_id)`.

**Chosen Approach:**
Option 2. On a constraint violation, Inventory returns the existing
reservation as success.

**Why:**
Check-then-insert races under concurrent retries; the constraint doesn't. Same
pattern as `orders.idempotency_key` and `notification_log`'s unique key.

**Trade-offs:**
Inventory must translate the constraint violation into "already reserved" —
and must not decrement `available_stock` twice. The decrement and the insert
must therefore be in the same local transaction, so a rejected insert rolls
back the decrement too.

**Consequences:**
Resolves the open question "whether the stock decrement and the reservation
insert share one local transaction" — they must.

**Addendum (added when the entity was written):** the row also carries
`quantity`, which no earlier entry stated — the schema stub ended in `...` and
hid it. It is not optional. `OrderCancelled { order_id, reason, buyer_id }`
carries no item list, and there is no `release()` endpoint to pass one, so the
held units exist **only** on these rows: release reads
`WHERE order_id = ? AND status = 'RESERVED'` and adds each row's `quantity`
back. The same column is what the replay check compares to distinguish a
genuine retry from `409 QUANTITY_MISMATCH` (see "Inventory's `reserve` API
contract"), so the contract already depended on it. Constraints:
`quantity > 0`, matching `400 INVALID_QUANTITY`.

The release path must flip `RESERVED → RELEASED` by CAS **first** and add back
only the units whose rows actually flipped, in one transaction. At-least-once
Kafka means a redelivered `OrderCancelled` would otherwise add the same units
twice; the flip matching zero rows is what makes the redelivery a no-op. The
`available_stock <= total_stock` CHECK is the last-resort net if that ordering
is ever got wrong. Filtering on `status = 'RESERVED'` is also what enforces
"CONFIRMED is never released".

---

## Decision: `total_stock` stays fixed; `available_stock` is the decremented counter

**Context / Problem:**
Reserving decremented `total_stock`, so the column stopped meaning "total"
the moment the sale started, and the original quantity on sale was lost.

**Options Considered:**
1. Rename `total_stock` to `available_stock`.
2. Keep `total_stock` fixed and add `available_stock` as the working counter.

**Chosen Approach:**
Option 2.

**Why:**
Keeps "how many units were on sale" for reporting (e.g. sold-through
percentage) at the cost of one integer column.

**Trade-offs:**
Two columns that must stay consistent; `available_stock` must never exceed
`total_stock` or go below zero.

**Consequences:**
Reserve decrements `available_stock`; release and expiry increment it. The
`version` column protects `available_stock`.

---

## Decision: `flash_sales.status` is stored: `SCHEDULED | LIVE | ENDED | CANCELLED`

> **Note:** **superseded.** The instinct to store rather than derive was right
> for exactly one value — `CANCELLED`, which no timestamp implies — and wrong
> for the other three. `status` is now `ACTIVE | CANCELLED`, and
> `SCHEDULED`/`LIVE`/`ENDED` are computed from `start_time`/`end_time` at every
> call. The "needs a mechanism to move sales forward" consequence below is
> resolved by removing the need rather than building the mechanism — see "No
> status-transition job" in Revision 3.

**Context / Problem:**
`flash_sales.status` had no defined values, and it wasn't decided whether it
should be stored or derived from `start_time` / `end_time`.

**Options Considered:**
1. Derive status from the time window.
2. Store it explicitly.

**Chosen Approach:**
Option 2.

**Why:**
A brand cancelling a sale can't be worked out from timestamps alone.

**Trade-offs:**
Stored status can drift from the time window (e.g. still `SCHEDULED` after
`start_time`) unless something moves it forward.

**Consequences:**
Needs a mechanism (scheduled job, or check-on-read) to move sales from
`SCHEDULED` → `LIVE` → `ENDED` — not yet designed. Reserve must reject
anything not `LIVE`.

---

## Decision: `orders.payment_ref` stores Razorpay Clone's payment reference

> **Note:** still correct, with one addition. `payment_ref` is now also the
> field the "exactly one payment" compare-and-set matches on, and a refund must
> **not** depend on it being populated: if Order crashed (or lost the expiry
> race) between initiating and saving it, `payment_ref` is `NULL` while a real
> payment exists upstream. The refund therefore uses the payment reference
> carried on the **inbound webhook**, not this column. See "An order keeps
> exactly one payment" in Revision 3 below.

**Context / Problem:**
The initiate call returns a transaction reference, but no column stored it.
The terminal-state guard's refund path (late `SUCCESS` after `ORDER_EXPIRED`)
needs it, and so does reconciliation.

**Options Considered:**
1. Don't store it; look payments up by order ID at Razorpay Clone when needed.
2. Store it on `orders`.

**Chosen Approach:**
Option 2 — nullable `payment_ref`, set after the initiate call returns.

**Why:**
Refund and reconciliation calls are keyed by the gateway's payment ID. Storing
it avoids depending on the gateway supporting lookup by our order ID.

**Trade-offs:**
Nullable column: `NULL` between order creation and a successful initiate, and
permanently `NULL` for orders that never reached payment.

**Consequences:**
The refund path in the terminal-state guard can now be implemented.

---

## Decision: `UNIQUE(order_id, product_id)` on `order_items`

**Context / Problem:**
Nothing stopped one order from having two lines for the same product. The
reserve loop would then call reserve twice for one item; with the new
reservation UNIQUE key, the second call would look like a conflict and the
order would compensate for no real reason.

**Options Considered:**
1. Merge duplicates in code only.
2. Merge duplicates in code and enforce with a UNIQUE constraint.

**Chosen Approach:**
Option 2.

**Why:**
The constraint guarantees the rule the reserve loop depends on ("one line per
product per order") even if a code path forgets to merge.

**Trade-offs:**
Request handling must merge duplicate lines (summing quantities) before
insert, or the insert fails.

**Consequences:**
The reserve loop can iterate `order_items` directly — each row is already a
unique item.

---

## Decision: Idempotency key scoped per buyer — `UNIQUE(buyer_id, idempotency_key)`

**Context / Problem:**
`orders.idempotency_key` was unique across all buyers.

**Options Considered:**
1. Globally unique key.
2. Unique per buyer.

**Chosen Approach:**
Option 2.

**Why:**
A retry lookup should only ever find the same buyer's own order; one client's
key can never collide with, or return, another buyer's order.

**Trade-offs:**
None significant.

**Consequences:**
The `(buyer_id, idempotency_key)` index also serves "list my orders" queries,
since it starts with `buyer_id`.

---

## Decision: Contact caches fed by `BrandUpserted` / `UserUpserted`; buyer emails handled the same way as brand emails

**Context / Problem:**
Two gaps. (1) `brand_contacts` was fed only by a brand-email-*changed* event,
so a newly created brand had no row and its first notification would fail.
(2) Notification had no way to get a buyer's email at all.

**Options Considered:**
1. Notification calls Identity synchronously for missing contacts.
2. Identity publishes an upsert event on both creation and email change, for
   both brands and buyers; Notification keeps a local cache of each.

**Chosen Approach:**
Option 2:
- Identity publishes `BrandUpserted` (brand creation, email change) and
  `UserUpserted` (signup, email change) via its outbox.
- Notification keeps `brand_contacts(brand_id PK, email, updated_at)` and a
  new `buyer_contacts(user_id PK, email, updated_at)`.
- Order's outbox payloads carry `buyer_id` alongside `brand_id`.
- A one-time backfill populates both caches for records that existed before
  Notification started consuming.

**Why:**
Same reasoning as the brand-email decision: Notification is the only service
that uses these emails, so it owns their read path, with no runtime
dependency on Identity. Firing on creation removes the "no row yet" gap.

**Trade-offs:**
A second cache table and consumer in Notification; staleness window between
an email change and its event being applied.

**Consequences:**
- `users` gains `updated_at`, matching `brands`.
- Identity needs an outbox (see "Every service that publishes events owns an
  outbox").
- Out-of-order upsert events are not handled yet (deliberately deferred — see
  Open Items). *(Superseded: handled by a timestamp-conditional upsert — see
  "Out-of-order `BrandUpserted`/`UserUpserted` delivery is handled by a
  timestamp-conditional upsert". That guard in turn depends on `updated_at`
  moving strictly forward — see "`updated_at` on `brands`/`users` is
  database-generated and strictly increasing".)*

---

## Decision: `UNIQUE(key_hash)` on `api_keys`, and index list per service

> **Note:** the rule (define indexes alongside the schema, each traceable to a
> named query) stands; three entries in the list below are stale. Inventory:
> `stock_reservations(status, expires_at)` becomes `(status, reserved_at)` —
> the expiry sweep moved to Order, and what Inventory now scans is
> *stale* reservations for the reconciler; and Inventory's
> `outbox(published, created_at)` index is gone with its outbox. Order gains
> two: `orders(status, expires_at)` for its own sweep, and
> `refunds(status, next_retry_at)` for the refund worker. See Revision 3 below.
>
> **A fourth, dropped when the entity was written:** the separate
> `stock_reservations(order_id)` index for confirm/release-all. It was
> redundant — `UNIQUE(order_id, flash_sale_item_id)` already creates an index
> whose *leading* column is `order_id`, which Postgres uses for
> `WHERE order_id = ?` on its own. Keeping both would add a second index write
> to every reserve, the hottest insert path in the service, for no read
> benefit. This does not weaken the rule below (define indexes alongside the
> schema, each traceable to a named query); it applies it — the query has an
> index, just not a dedicated one. To be revisited only if a real query plan
> shows the composite index being declined.
>
> **A fifth, decided 2026-10-02:** both outboxes' `(published, created_at)`
> index becomes a partial index on `(created_at) WHERE published_at IS NULL`
> — the `published` boolean itself was replaced by a nullable timestamp when
> the table was written. See "Identity's remaining tables".

**Context / Problem:**
Every brand API request looks up an API key by its hash, and none of the
hottest queries in the system had indexes defined.

**Options Considered:**
1. Leave indexing to implementation time.
2. Define the indexes for known hot queries now, alongside the schema.

**Chosen Approach:**
Option 2:
- Identity: `UNIQUE(api_keys.key_hash)`; `outbox(published, created_at)`.
- Inventory: `stock_reservations(status, expires_at)` for the expiry sweep;
  `stock_reservations(order_id)` for confirm/release of all items in an order;
  `outbox(published, created_at)`.
- Order: `UNIQUE(buyer_id, idempotency_key)` (also serves "list my orders");
  `outbox(published, created_at)`.
- Notification: its existing UNIQUE key on `notification_log`.

**Why:**
Each index maps to a specific query from a decision above. Without them, the
sweep, the outbox relays, and API-key auth become full-table scans as data
grows.

**Trade-offs:**
Every index slows writes slightly — relevant on `stock_reservations`, which is
written on every reserve. To be checked during load testing, not assumed.

**Consequences:**
Indexes are listed in each ERD's Notes box.

---

# Revision 3 — Order-authority redesign

The entries below came out of a single review pass that started from one
question: what happens if a payment is captured at the same moment a
reservation expires. The answer was that two services were each deciding the
same order's fate from their own clock and their own table, and could disagree
— selling one unit twice. Fixing that properly moved the deadline into Order
Service and collapsed several mechanisms (a second event, a compensating call,
a whole table) that existed only to keep the two views in sync.

Each entry either supersedes something above it or adds a mechanism that did
not exist before. Superseded entries above carry notes pointing here.

---

## Decision: Order Service is the sole authority on an order's fate; every `orders.status` write is a compare-and-set

**Context / Problem:**
A buyer holding the last unit pays at 09:59.8 with a 10-minute TTL. At 10:00.0
Inventory's sweep marks the reservation expired, returns the unit to
`available_stock`, and a second buyer immediately reserves it. At 10:00.3 the
`SUCCESS` webhook arrives; the order is still `PENDING_PAYMENT`, so it becomes
`PAID`. Both buyers have now paid for one unit. Neither service did anything
wrong by its own rules — Inventory owned the reservation deadline, Order owned
the order status, and they decided independently, from different clocks, in
different databases.

**Options Considered:**
1. Keep expiry in Inventory and patch the race (e.g. have Inventory re-check
   with Order before releasing).
2. Coordinate the two services — a distributed lock, or an agreed deadline
   protocol between them.
3. Move the deadline into Order Service and decide the race with a single
   conditional update on one row, in one database.

**Chosen Approach:**
Option 3. `orders` gains an `expires_at` column. Order Service runs the sweep:

```sql
UPDATE orders SET status = 'ORDER_EXPIRED'
WHERE id = ? AND expires_at < now() AND status IN ('CREATED', 'PENDING_PAYMENT')
```

The webhook handler sets `PAID` with an equally conditional update. Exactly one
of the two can affect a row. **Rule: every write to `orders.status` is a
compare-and-set, with no exceptions** — the request thread, the sweep, the
webhook handler and the circuit-breaker path all use one, and each checks
rows-affected. The status change and its outbox row share one local
transaction.

Inventory never expires anything on its own. It releases stock only when Order
tells it the order is dead.

**Why:**
Options 1 and 2 both try to make two databases agree about one fact. Option 3
removes the disagreement by making it one fact, in one place, decided by one
atomic operation the database already guarantees. Nothing has to be coordinated
because there is nothing to coordinate: whichever writer arrives first wins,
and the loser sees rows-affected = 0 and knows it lost.

**Trade-offs:**
- Stock is held slightly longer than before: the deadline, plus one sweep
  interval, plus the outbox relay interval, plus consumer lag. See "One
  order-death event" for why this direction is the safe one.
- Every code path touching `orders.status` must be written as a conditional
  update and must handle losing. That is a discipline the codebase has to keep;
  the schema cannot enforce it.

**Consequences:**
- `stock_reservations.expires_at` becomes meaningless and is dropped (see
  "Inventory keeps no deadline of its own").
- The loser of the race still has work to do: a webhook that loses must refund
  (see "An order keeps exactly one payment").
- `expires_at` is set twice in an order's life — see "The order row is created
  before the reservation loop".

---

## Decision: `RESERVATION_FAILED` added as a fifth terminal state

**Context / Problem:**
With the order row now created before any stock is reserved, an order whose
reservation loop fails on an out-of-stock item needs somewhere to land. Reusing
`ORDER_EXPIRED` would be wrong: nothing timed out, a product was simply
unavailable, and conflating the two makes the failure undiagnosable afterwards.

**Options Considered:**
1. Reuse `ORDER_EXPIRED` for out-of-stock failures.
2. Add a distinct terminal state.

**Chosen Approach:**
Option 2. `RESERVATION_FAILED` joins `PAID`, `PAYMENT_FAILED`, `ORDER_EXPIRED`
and `PAYMENT_UNAVAILABLE` as terminal states, reachable only from `CREATED`.

**Why:**
It passes the same test that kept `CONFIRMED` and `RESERVED` out of this state
machine: a specific transition in a specific flow sets it, and Order Service is
what sets it. An order that ran out of stock and an order whose buyer never
paid are different outcomes with different causes; the state should say which.

**Trade-offs:**
Five terminal states instead of four; every "already terminal?" guard must
include it.

**Consequences:**
An order stuck in `CREATED` because the process died mid-loop still goes to
`ORDER_EXPIRED`, not `RESERVATION_FAILED` — it did not fail for lack of stock,
it failed to finish in time.

---

## Decision: One order-death event — `OrderCancelled { order_id, reason }` — replaces `ReservationExpired` and the per-item `release()` call

**Context / Problem:**
Stock needed releasing after four different outcomes: out-of-stock mid-loop,
payment failed, payment gateway unavailable, and expiry. The design had
different mechanisms for different ones — a synchronous per-item `release()`
loop for the first, an Inventory-side sweep for the last, and nothing at all
for the middle two.

**Options Considered:**
1. Keep separate mechanisms per failure reason.
2. One event covering every way an order can die, with the reason as a field.

**Chosen Approach:**
Option 2. Order publishes `OrderCancelled { order_id, reason }` through its
outbox, in the same transaction as the status change. `reason` is one of
`RESERVATION_FAILED | PAYMENT_FAILED | PAYMENT_UNAVAILABLE | ORDER_EXPIRED`.
Inventory's handler is "release every reservation currently held for this
`order_id`" — it does not branch on the reason.

**Why:**
Inventory does the same thing in all four cases, so four mechanisms was three
too many. The name `OrderCancelled` is kept (over `OrderFailed`) because it
matches the existing sequence diagrams and would still fit if buyer-initiated
cancellation is ever added.

Releasing asynchronously rather than by a synchronous `release()` call is
strictly better, not merely equivalent: a failed `release()` call would need
its own retry and recovery logic, which the outbox already provides for free.
Nothing waits on the release — the buyer already has their response, every
failure state is terminal, and a late release can only ever *free* stock, never
oversell it.

**Trade-offs:**
- Stock stays held until the relay publishes and Inventory consumes. A buyer
  who immediately retries without the failed item can briefly compete with
  their own dead order's hold.
- The under-sell/over-sell trade-off is accepted deliberately: holding a unit a
  moment too long costs at most one sale that may still be made later;
  releasing it a moment too early means a paid buyer with no product. The costs
  are not symmetric, so the design errs toward holding.

**Consequences:**
- `ReservationExpired` is deleted; the event now flows Order → Inventory.
- Inventory's `release()` endpoint is not needed and is not built.
- Because Inventory's handler releases whatever exists *at consume time*, a
  reservation created after that moment is not covered — see the next entry.

---

## Decision: A late reservation is cleaned up by re-publishing `OrderCancelled`, not by a new mechanism

**Context / Problem:**
The reservation loop can have a `reserve()` call in flight when the sweep kills
the order. Sequence: items 1–2 reserved; sweep fires; `OrderCancelled` is
published *and consumed*, releasing items 1–2; only then does item 3's
`reserve()` commit in Inventory and return. Inventory's release query has
already run, so item 3's reservation is held by an order that is already dead
and will never be revisited. Stock leaks.

**Options Considered:**
1. Let the stale-reservation reconciler catch it hours later.
2. Have Inventory check order status inside `reserve()` before creating the row.
3. Have Order re-publish `OrderCancelled` when it notices it lost.

**Chosen Approach:**
Option 3. Every per-item write in the loop is guarded on
`orders.status = 'CREATED'`. When that guard matches zero rows *after* a
successful `reserve()` response, Order re-reads the order's status and
publishes `OrderCancelled` again, with `reason` set to that status.

The re-read must be restricted to the four terminal **failure** states. `PAID`
is also terminal, and publishing `OrderCancelled` for a paid order would
release stock that was legitimately sold. A correctly idempotent
order-creation path cannot reach that combination — the loop only runs while
the order is `CREATED`, and `PENDING_PAYMENT` is only entered after the loop
finishes — but the rule is stated explicitly so that an implementation which
re-enters the loop on an idempotency-key retry cannot turn a paid order into a
released one. If the status is `PAID`, publish nothing; any stray reservation
is left to the reconciler.

**Why:**
This works with certainty rather than probability, because `reserve()` is
synchronous: a success response proves Inventory already committed that
reservation before Order asked the question, so the republished event cannot
arrive too early. Inventory's handler is already idempotent ("release
everything currently held"), so this is the existing mechanism invoked once
more — no poll, no new endpoint, no widened hold time. Option 2 would have put
an order-status check on the hottest path in the system; option 1 leaves stock
stranded for hours in a case that arises in normal operation, not only after a
bug.

**Trade-offs:**
A duplicate `OrderCancelled` is published for these orders. Harmless for
Inventory, which is idempotent. If Notification is ever made a consumer,
`notification_log`'s `UNIQUE(order_id, event_type, recipient_type, channel)`
already prevents a double send.

**Consequences:**
Applies to whichever single item is in flight, at any position in the loop —
the loop stops issuing further `reserve()` calls once a guard fails, so at most
one item can be stranded this way.

---

## Decision: The order row is created before the reservation loop; `amount` is NULL until the last item is priced

**Context / Problem:**
The Notion log said the order row was created *after* reservation succeeded.
That cannot be true: `stock_reservations.order_id` needs a real ID to point at,
the idempotency key must be durable before any side effect happens, and Order's
own sweep can only expire an order it can see.

**Options Considered:**
1. Create the order row after the reservation loop completes.
2. Create it, committed, before the first `reserve()` call.

**Chosen Approach:**
Option 2:

```
Tx1 (commits before any Feign call):
  INSERT orders(status='CREATED', amount=NULL, expires_at=now()+<short>, idempotency_key=...)
  INSERT order_items x N   (product_id, quantity; unit_price=NULL, subtotal=NULL)

per item:  reserve(...) -> {reservation_id, sale_price, brand_id}
  Tx:      UPDATE order_items SET unit_price, subtotal   -- guarded on orders.status='CREATED'

all items ok:   CAS amount = SUM(subtotal)  (guarded on 'CREATED')
                initiate payment
                CAS CREATED -> PENDING_PAYMENT, save payment_ref,
                    reset expires_at to the payment window
any item fails: CAS CREATED -> RESERVATION_FAILED + OrderCancelled outbox row, one tx
```

`amount` is `NULL`, never `0`, until known — zero reads as a real price.
Enforced in the schema rather than trusted to code:

```sql
CHECK (status NOT IN ('PENDING_PAYMENT', 'PAID') OR amount IS NOT NULL)
```

`ORDER_EXPIRED` and `RESERVATION_FAILED` are deliberately excluded from that
check, since both are reachable from `CREATED` while `amount` is legitimately
still unknown.

**Why:**
The transaction must be committed, not merely open, before the first Feign
call — holding one open across an external call is the exact anti-pattern the
saga decision exists to avoid. Inserting all `order_items` up front gives the
loop rows to update and, as a side effect, makes "which items got priced" a
readable record of how far the order got.

`expires_at` is set twice because it means two different things: at `CREATED`
it bounds the reservation loop plus the initiate call, and it is reset to the
payment window when the order enters `PENDING_PAYMENT`. A crashed order
therefore frees its stock on the shorter of the two.

**Trade-offs:**
Orders exist in the database that never reserved anything. They are expired by
the sweep like any other, and their `order_items` rows keep NULL prices.

**Consequences:**
- Items reserved before a later failure keep their prices on `order_items` —
  harmless, and useful for audit.
- The short `CREATED` deadline must be **derived**, not picked — see "Deadlines
  and leases are derived from the timeouts they race".

---

## Decision: `reserve()` returns `sale_price`; money columns are `DECIMAL`; a live sale's price is immutable

**Context / Problem:**
`order_items.unit_price` and `orders.amount` are snapshotted at order time, but
the price lives in `flash_sale_items.sale_price`, in Inventory's database,
which Order cannot read.

**Options Considered:**
1. A separate lookup call from Order to fetch prices.
2. Return the price on the `reserve()` response Order already makes.

**Chosen Approach:**
Option 2 — `reserve()` returns `sale_price` alongside `reservation_id` and
`brand_id`. Order computes `subtotal = sale_price × quantity`. All money
columns — `flash_sale_items.sale_price`, `order_items.unit_price`,
`order_items.subtotal`, `orders.amount`, `refunds.amount` — are `DECIMAL`,
never floating point.

**Why:**
The same reasoning that put `brand_id` on this response: reuse a call already
being made rather than adding a dependency, a timeout budget and a failure mode
for one field.

**Trade-offs / known assumption:**
Each item is priced at whatever `sale_price` was when *its* reservation
committed. If a price could change mid-order, two items in one order could be
priced from different moments. This does **not** produce incorrect charging —
every item is charged exactly what it was reserved at, and `amount` is their
true sum. The only exposure is display-versus-charged drift: what the buyer saw
while browsing may differ from what they are charged.

This is closed off at the product level rather than left as an assumption:
**once a flash sale is live, its per-product sale price is immutable.** The
platform does not support editing a live sale's price. Recorded in
`docs/requirements.md` §7 as a constraint, because FR-BR3 only ever covered
*creating* a sale's price and was silent about editing one.

> **Correction:** this entry originally justified frozen prices on the grounds
> that the remedy was "cancel and recreate." That is no longer available once a
> sale is live — see "A sale cannot be cancelled once it has started" below. A
> mispriced live sale therefore runs to its `end_time` with no remedy at all.
> The decision stands; the consolation offered here does not.

**Consequences:**
Any future "edit live sale" feature reopens this decision explicitly rather
than silently violating an unwritten assumption.

---

## Decision: An order keeps exactly one payment; every other captured payment is refunded

> **Correction (2026-11-05, from "Day 6: webhook, one-payment edge cases,
> PaymentConfirmed, outbox relay", points 1 to 3):** the `PAID` CAS also matches
> the webhook's amount (numeric), a `FAILED` CAS also matches `payment_ref`, and
> a webhook for an order still `CREATED` gets `409` with no write. Implemented and
> tested on Day 6. The text below is unchanged.

**Context / Problem:**
The previous terminal-state guard enumerated cases by which terminal state an
order was in, and refunded only a late `SUCCESS` arriving after
`ORDER_EXPIRED`. It had a hole. If Order crashed after initiating payment P1
but before saving `PENDING_PAYMENT`, a retry could initiate a second payment
P2. With both captured, P1 wins the race to `PAID` and P2's webhook lands on an
already-`PAID` order — which the old guard treated as a no-op. The buyer paid
twice and got neither refund.

**Options Considered:**
1. Extend the case table with more rows.
2. State one invariant and let the compare-and-set enforce it.

**Chosen Approach:**
Option 2. **An order keeps exactly one payment; every other captured payment
for that order is refunded.** For a `SUCCESS` webhook carrying payment
reference `X`:

- CAS to `PAID` succeeds only if `status = 'PENDING_PAYMENT' AND payment_ref = X`.
- If it fails and the order is already `PAID` with `payment_ref = X`, this is a
  redelivery of the winning payment — no-op.
- In every other case, refund `X`.

Refunds are keyed `UNIQUE(payment_ref)`, not `UNIQUE(order_id)` — an order can
legitimately need two different payments refunded.

Duplicates are also prevented at the source: the `initiate` call carries an
idempotency key derived deterministically from the order ID, so a retried
initiate returns P1 rather than creating P2.

**Why:**
A case table has to be exhaustive to be correct, and the missing row is
invisible until it happens. An invariant is checkable by reading one sentence.
The CAS matching on `payment_ref` also does redelivery detection for free, with
no separate "have I seen this webhook" table.

**Trade-offs:**
A legitimate payment for an order that crashed before saving `payment_ref`
(leaving it `NULL`) cannot win the CAS, and is refunded. This is accepted: the
buyer gets their money back and can retry, which is strictly better than a
captured payment with no order behind it.

The refund must use the payment reference from the **webhook**, not from
`orders.payment_ref`, precisely because that column may be `NULL` in exactly
this case.

**Consequences:**
- Supersedes "Idempotent terminal-state guard" above.
- Recorded as FR-P4 in `docs/requirements.md` — automatic compensating refunds
  are in scope; a buyer-facing refunds workflow is not.
- Prevention at the source **depends on a system that does not exist yet.**
  Razorpay Clone is a separate, not-yet-built project; that it will honour an
  idempotency key on `initiate` (and on `refund`, and that it will expose a
  payment-status lookup) is a *requirement placed on it*, not an observed fact.
  This is designed, not verified — nothing in FlashMart's code or docs may
  assume it is proven until it is actually built and tested against.
- This is precisely why prevention and correction are independent here. If the
  gateway turns out not to honour the key, P2 gets created, and the
  refund-by-`payment_ref` path catches it anyway. Correctness does not rest on
  the unverified half.

---

## Decision: Refunds are issued through a `refunds` command-outbox table, not a direct call

> **Correction (2026-11-05, from "Day 6", B1):** the losing webhook's insert is
> `INSERT ... ON CONFLICT (payment_ref) DO NOTHING`, so a redelivery adds no
> second row; it leaves `next_retry_at` NULL, whose meaning is open item A2.
> Implemented and tested on Day 6; the worker below is still not built. The
> text below is unchanged.

**Context / Problem:**
The refund is an external HTTP call that must happen after a database commit —
the same dual-write problem the outbox pattern exists to solve. If Razorpay
Clone is down, or Order crashes between the commit and the call, the buyer's
money is silently kept.

**Options Considered:**
1. Call the refund API directly after committing.
2. Publish a refund event to Kafka and consume it.
3. A `refunds` table acting as a command outbox, drained by a worker.

**Chosen Approach:**
Option 3:

```
refunds(id, order_id, payment_ref UNIQUE, amount,
        status PENDING | PROCESSING | SUCCEEDED | NEEDS_MANUAL_REVIEW,
        attempts, next_retry_at, claimed_at, last_error, created_at, updated_at)
```

- The losing webhook inserts a `PENDING` row in the same transaction as its
  failed CAS, and returns 200 to Razorpay Clone only after that commit — so if
  Order crashes first, Razorpay's own webhook retry replays the whole thing.
- `amount` is taken from the amount reported on the webhook, not assumed equal
  to `orders.amount`.
- A worker claims a row by compare-and-set `PENDING → PROCESSING`, recording
  `claimed_at`, then makes the HTTP call **outside** any transaction.
- Retries use `next_retry_at` with exponential backoff, capped. After the cap
  the row becomes `NEEDS_MANUAL_REVIEW`, which pages a human — it never fails
  silently.
- A stale-claim sweep returns long-`PROCESSING` rows to `PENDING` and
  increments `attempts`.
- The worker's finishing write is fenced: `UPDATE refunds SET status = ?
  WHERE id = ? AND claimed_at = ?`, so a slow worker whose row was re-claimed
  writes nothing instead of overwriting the newer attempt.
- Before refunding, the worker asks Razorpay Clone whether this payment was
  already refunded — a fallback that does not depend on the gateway's
  idempotency key being honoured.

**Why:**
Kafka was rejected because Order is the only consumer; routing a message
through a broker so a service can consume its own command adds a hop and buys
nothing. `SELECT ... FOR UPDATE SKIP LOCKED` was rejected for claiming, because
it holds a row lock and a database connection open for the duration of an
external HTTP call — the precise failure mode the saga decision rejected 2PC
over.

**Trade-offs:**
Two sweeps to run and monitor (retry and stale-claim) instead of a direct call.
The stale-claim sweep can in principle reset a row whose worker is still alive,
so two attempts could overlap; the gateway idempotency key plus the
already-refunded check make an overlap harmless rather than a double refund.

**Consequences:**
- `orders` 1—N `refunds` is a real foreign key (same service, same database).
- Order needs an index on `refunds(status, next_retry_at)`.
- The stale-claim lease must exceed the HTTP client's total timeout — see
  "Deadlines and leases are derived from the timeouts they race".

---

## Decision: `saga_state` is dropped; orchestration state lives on `orders`

**Context / Problem:**
`saga_state(order_id, step, status, last_error, updated_at)` was to track saga
progress. With every status transition now a compare-and-set on `orders`, the
table duplicates what `orders` already says.

**Options Considered:**
1. Keep `saga_state` and update it in the same transaction as every status
   write.
2. Drop it and keep only what it uniquely held.

**Chosen Approach:**
Option 2. The one column with unique information, `last_error`, survives as a
nullable `orders.failure_detail` — for debugging, never for driving state.

**Why:**
Every other column was a less precise restatement of `orders`: `step` is
inferable from status plus which `order_items` have prices; `status`
(`IN_PROGRESS | SUCCESS | FAILED`) is strictly coarser than `orders.status`,
which says *which* failure; `updated_at` already exists.

The decisive argument is not duplication but divergence. Four separate writers
touch `orders.status` — the request thread, the sweep, the webhook handler and
the circuit-breaker path — and each would have to remember to update a second
table, forever. One missed path and the two disagree, with no way to tell which
is lying.

`COMPENSATING` fails the same test that removed `CONFIRMED` and `RESERVED` from
the state machine: a state is only real if something moves the entity in *and*
something moves it out. Nothing ever tells Order that Inventory finished
releasing, and inventing a `StockReleased` event to clear it would be an event
with no consumer that needs it. "Release in progress" is Inventory's fact,
visible in `stock_reservations.status` where it belongs — the same principle by
which `PAID` is terminal without waiting on Notification.

A separate saga table earns its keep when recovery *resumes* a saga from its
last step. Recovery here is abort: a stuck order hits its deadline, is expired,
and any payment that slipped through is refunded. Nothing ever reads the step.

**Trade-offs:**
Loses an explicit "how far did this order get" column. In practice the status,
plus which `order_items` carry prices, answers the same question from data that
cannot drift.

**Consequences:**
`orders` 1—1 `saga_state` disappears from the relationship model. `CLAUDE.md`'s
"Order Service schema: finalized" line needs `saga_state` removed.

**Correction: amended by "Order creation: retries, conflicts, deadlines and Inventory failures"** (2026-11-05): recovery is now abort by expiry
or resume on a retried create while `CREATED`.

---

## Decision: Inventory publishes nothing and owns no outbox

**Context / Problem:**
`ReservationExpired` was Inventory's only published event, and it no longer
exists.

**Options Considered:**
1. Keep the outbox for future use.
2. Drop it.

**Chosen Approach:**
Option 2. Inventory is now a pure consumer (`PaymentConfirmed`,
`OrderCancelled`) plus a Feign responder (`reserve`). It never writes to Kafka.

**Why:**
Keeping an unused outbox means a table, a relay and monitoring for something
nothing produces. The standing rule still applies in reverse: if Inventory ever
publishes an event, it gets an outbox again in the same change.

**Trade-offs:**
None identified. Reinstating it later is a migration, not a redesign.

**Consequences:**
- Publishers are now Order and Identity only.
- Inventory's `outbox(published, created_at)` index disappears.
- Inventory still makes one outbound synchronous call — the reconciler below —
  but that is a Feign request, not a publish.

---

## Decision: `OrderCreated` is dropped with no replacement

**Context / Problem:**
`OrderCreated` was listed as an Order outbox event, nominally consumed by
Inventory and Notification. Inventory cannot act on it — reservations are
confirmed by `PaymentConfirmed`, after payment. And with the order row now
created *before* any stock is reserved, the event would announce "an order was
requested" before it is known whether it will survive.

**Options Considered:**
1. Keep it and find a consumer.
2. Replace it with a later "order entered payment" event.
3. Drop it.

**Chosen Approach:**
Option 3.

**Why:**
The same test that dropped `StockReserved`: no consumer needs it. FR-B5 calls
for notifying the buyer of purchase *confirmation or failure*; "your order has
been requested" is neither. Option 2 would have been inventing a consumer to
justify an event, rather than the other way round.

**Trade-offs:**
If a genuine consumer appears later, the event has to be added back.

**Consequences:**
`PaymentConfirmed` becomes the sole Order outbox payload carrying
`brand_id`/`buyer_id` enrichment, and still carries the full item list — for
Notification's benefit, since Inventory looks reservations up by `order_id`.

---

## Decision: No status-transition job — liveness is computed, and `flash_sales.status` shrinks to `ACTIVE | CANCELLED`

**Context / Problem:**
`flash_sales.status` was stored as `SCHEDULED | LIVE | ENDED | CANCELLED`, and
nothing moved a sale between those values. `reserve()` must reject anything not
live, so the hot path depended on a value no mechanism maintained. The recorded
trade-off was that stored status "can drift from the time window unless
something moves it forward."

**Options Considered:**
1. A scheduled job that flips `SCHEDULED → LIVE → ENDED` on a timer.
2. Check-on-read: flip the row lazily when something reads it.
3. Stop storing the derivable states: compute liveness from the timestamps at
   every call, and keep stored status only for what timestamps cannot express.

**Chosen Approach:**
Option 3.

- `flash_sales.status` becomes **`ACTIVE | CANCELLED`** — two values, not four.
- Liveness is computed everywhere, never read: `status != 'CANCELLED'` and
  `start_time <= now < end_time`.
- `start_time` and `end_time` are editable while the sale has not opened, and
  frozen once it has — the same rule as `sale_price`, for the same reason.
- No job, no lazy flip.

**Why:**
The drift is not solved, it is **removed**. There is no gap between "the sale
should be live" and "the system knows it is," because nothing is asked to know
it in advance — every call answers the question fresh from two timestamps at
the instant it is asked.

The failure modes of option 1 are asymmetric and the worse one is a correctness
bug. A dead job that fails to *start* a sale is an inconvenience: buyers are
told `SALE_NOT_LIVE` during a window when they should be buying. A dead job
that fails to *end* one is different in kind: `reserve()` keeps accepting
purchases past `end_time`, selling stock outside the boundary the brand set and
taking real payments for a sale that was supposed to be closed.

More to the point, making the buy path's correctness depend on a background
job's uptime would reintroduce precisely the pattern Revision 3 spent its
effort removing — Order's expiry stopped depending on Inventory's sweep,
release stopped depending on a synchronous call. This is the same conclusion
applied one level down.

Granularity matters here too, not just availability. With a job on a 10-second
timer, the sale opens at "00:00:00 give or take," and two buyers who arrive
seconds apart get different answers for reasons unrelated to who was actually
first. In a system whose entire premise is fairness under contention for scarce
stock, the boundary should be the timestamp itself.

Against the requirements: FR-B2 is already written in timestamp language
("within its active time window"), and FR-B1's "active and upcoming" split is
`now < start_time` versus `start_time <= now < end_time`. Neither needs a
stored `SCHEDULED`/`LIVE`/`ENDED`. What genuinely cannot be derived is
`CANCELLED` — a human decision with no timestamp implying it. That, and only
that, is what the column is left holding.

**Two details that are part of the decision, not implementation notes:**

- **The interval is half-open: `start_time <= now < end_time`.** Inclusive at
  the start so the sale opens exactly on its timestamp; exclusive at the end so
  closing is exact and a sale cannot be both open and closed at one instant.
  (`BETWEEN` in SQL is inclusive at both ends and is therefore wrong here.)
- **`now` is the database's clock, not the application's.** `reserve()`
  evaluates liveness in the same statement as the stock decrement rather than
  computing a boolean in Java and passing it down. Several Inventory instances
  evaluating their own wall clocks would disagree at the boundary, and two
  buyers arriving in the same millisecond would get different answers depending
  which pod they hit — the same two-clocks problem Revision 3 removed between
  Order and Inventory. One clock, one answer. An application-side helper is
  fine for display and listing, but is not what the hot path relies on.

**Trade-offs:**
- Three of the four old status values now have no stored representation, so
  anything that previously read `status` to mean "live" must be changed to
  compute it. The definition must live in exactly one place — a shared helper
  for read paths, one predicate for the hot path — or callers will each invent
  a subtly different version.
- Frozen `start_time`/`end_time` means a brand who mistimed a live sale cannot
  fix it; the remedy is cancel and recreate. Same trade-off already accepted
  for `sale_price`.

**Consequences:**
- Supersedes "`flash_sales.status` is stored: `SCHEDULED | LIVE | ENDED |
  CANCELLED`" below. `SALE_NOT_LIVE` survives as a `reserve()` rejection
  reason — it is now a computed conclusion rather than a stored value.
- `flash_sales` needs an index supporting the listing queries over
  `(start_time, end_time)`.
- Raised the question of what happens to in-flight and already-`PAID` orders
  when a brand cancels a live sale — resolved by the next decision.

---

## Decision: Event payload shapes for `PaymentConfirmed`, `BrandUpserted`, `UserUpserted`; money crosses every boundary as a string; no envelope, no `event_id`

> **Correction (2026-11-05, from "Day 6: webhook, one-payment edge cases,
> PaymentConfirmed, outbox relay"):** `brand_id` is dropped from
> `PaymentConfirmed` for now (`orders` has no brand column; only the stretch
> Notification service would use it). The payload is now `{order_id, buyer_id,
> items[], amount}`. Designed, not implemented. Old text below is unchanged.

**Context / Problem:**
Three of the four surviving events had never been given a concrete shape.
`PaymentConfirmed`'s content was implied by two prior decisions (carries the
full item list; carries `brand_id`/`buyer_id` enrichment) but never assembled.
`BrandUpserted`/`UserUpserted` were named but not specified. Separately, no
convention existed for versioning payloads or for money's JSON representation
across any Feign response or Kafka event in the system.

**Chosen Approach:**

```json
PaymentConfirmed {
  "order_id": 42, "buyer_id": 7, "brand_id": 12,
  "items": [
    { "product_id": 501, "product_name": "Puma RS-X Shoes", "quantity": 2, "unit_price": "3999.00", "subtotal": "7998.00" },
    { "product_id": 502, "product_name": "Puma Classic Tee", "quantity": 1, "unit_price": "1499.00", "subtotal": "1499.00" }
  ],
  "amount": "9497.00"
}
```
```json
BrandUpserted { "brand_id": 12, "email": "contact@puma.com", "updated_at": "2026-09-27T10:00:00Z" }
UserUpserted  { "user_id": 7,  "email": "priya@example.com",  "updated_at": "2026-09-27T10:00:00Z" }
```

**Standing rule — money crosses every JSON boundary as a string, never a JSON
number.** Applies everywhere, not just here: `reserve()`'s `salePrice`,
`refunds.amount`, and every field above. JSON has no `DECIMAL` type; a bare
number is parsed into a floating-point type by default in most languages, and
floating-point cannot represent every decimal exactly. Summing several
`subtotal`s, across many orders, compounds that into a visible discrepancy on a
receipt — not cosmetic, a real defect. Serializing as `"3999.00"` forces the
receiving side to construct a `BigDecimal` (or equivalent) directly from the
digits, with nothing lost in between.

**No envelope, no `event_id`.** Every event stays a flat, bare payload — no
wrapper object, no `event_version`, no `event_id`. Decided, not deferred.

**Why — per piece:**

*`PaymentConfirmed`.* Inventory's confirm handler needs nothing from the
payload but `order_id`: its `stock_reservations` rows are already keyed by
`order_id` from `reserve()`, so confirmation is one statement —
`UPDATE stock_reservations SET status='CONFIRMED' WHERE order_id=? AND
status='RESERVED'`. This doubles as the idempotency guard for free: a
redelivered event finds the rows already `CONFIRMED`, matches zero, and does
nothing — `stock_reservations.status` is already the fact that says whether
the work happened, the same mechanism as every `orders.status` CAS, applied
here without a new construct.

Notification's receipt needs real line items, including a human-readable
product name — Order's own tables never stored one, only `product_id`. Rather
than add a lookup Notification would have to make (reopening the
no-sync-dependency-for-enrichment rule from "Brand email delivery to
Notification Service"), `order_items` gains `product_name`, snapshotted from
`reserve()`'s response at the same moment `unit_price` is. Same
piggyback-on-an-existing-call pattern already used for `brand_id` and
`sale_price`.

*`BrandUpserted`/`UserUpserted`.* The payload is exactly the three columns each
feeds (`brand_contacts`/`buyer_contacts`). One property is load-bearing even
though nothing reads it yet: `updated_at` must be the **source-side** timestamp
— when Identity made the change — not receipt time, because that is the only
thing that could ever guard against out-of-order delivery. The out-of-order
guard itself stays deferred (see "Deliberately deferred" in Open Items), but
the field has to carry the real timestamp now, or a future guard would need a
shape change to add it retroactively. *(Superseded: the guard is now decided —
"Out-of-order `BrandUpserted`/`UserUpserted` delivery is handled by a
timestamp-conditional upsert" — and "`updated_at` on `brands`/`users` is
database-generated and strictly increasing" settles how the timestamp is
produced, since "source-side" alone did not say which clock.)*

*No `event_version`.* Adding a field is harmless by default — old consumers
ignore what they were never told to read. The two ways a payload change
actually breaks a consumer are removing a field it relies on, or repurposing an
existing field's meaning without renaming it. Both are prevented by discipline
(only add optional fields; rename rather than repurpose), not by a version
number every consumer must branch on.

*No `event_id`.* Checked both mechanisms that might need one and found neither
does. `notification_log`'s content-based key cannot miss a duplicate, because
the one-payment invariant already guarantees at most one legitimate
`PaymentConfirmed` per order. Inventory's confirm handler is covered by its own
state guard. No correctness gap exists for an `event_id` to close, and it is
not added even for tracing.

**Trade-offs:**
- `order_items.product_name` is a second denormalized copy of product data
  (alongside `unit_price`), snapshotted once and never resynced if the brand
  later renames the product — accepted, since a receipt should show what the
  buyer bought at purchase time, not the product's current name.
- Rejecting `event_version` on discipline rather than a mechanism means a
  future contributor could still repurpose a field without noticing. Accepted
  as a documentation-enforced rule, consistent with how the rest of this
  system relies on conventions (every status write is a CAS) rather than
  compiler-enforced constraints.

**Consequences:**
- **Corrects the `reserve` API contract** (see "Inventory's `reserve` API
  contract" above): its response must also return `product_name`, and
  `salePrice` must be serialized as a string, not a JSON number. Neither
  changes what was decided there — `reserve()` already needed to supply this
  data — the concrete shape was simply written before this rule existed.
- The money-as-string rule applies retroactively to every Feign response and
  Kafka payload already specified in this document; none of them contradicted
  it, since none had committed to a JSON number representation.
- **New, correctly deferred, not decided here:** Kafka partition-key/ordering
  strategy — e.g. whether `BrandUpserted`/`UserUpserted` for the same
  `brand_id`/`user_id` are guaranteed to arrive in order, which the eventual
  out-of-order guard depends on being true or explicitly handled. This is
  producer/topic configuration, not payload shape, and belongs with the Kafka
  wiring phase — the same reasoning that deferred the derived time values to
  the Feign/Resilience4j config phase.

---

## Decision: Notification consumes `OrderCancelled`, but notifies only for the two asynchronous reasons

**Context / Problem:**
`OrderCancelled` carries four reasons, and it was undecided whether
Notification should consume it at all. FR-B5 requires notifying a buyer of
purchase confirmation *or failure*, so the question was never "should failures
be notified" — it was which failures, and by what mechanism.

**Options Considered:**
1. Notification does not consume `OrderCancelled`; failures are communicated
   only by the synchronous response.
2. Notification consumes it and notifies for every reason.
3. Notification consumes it and notifies only where the buyer is no longer
   there to have been told already.

**Chosen Approach:**
Option 3. The deciding question is *"is the buyer's HTTP request still open
when this reason is determined?"*

| `reason` | Reached from | Buyer still on the call? | Notify |
|---|---|---|---|
| `RESERVATION_FAILED` | `CREATED` | Yes — synchronous | No |
| `PAYMENT_UNAVAILABLE` | `CREATED` | Yes — the breaker trips inline on the call the buyer is waiting on | No |
| `PAYMENT_FAILED` | `PENDING_PAYMENT` | No — arrives by webhook, after the buyer left the payment page | Yes |
| `ORDER_EXPIRED` | either | No — both branches land after the buyer has gone | Yes |

Recipient is **the buyer only**, for every reason. Message content is specific
for `PAYMENT_FAILED` ("payment could not be processed") and deliberately
generic for `ORDER_EXPIRED` ("order was cancelled"), because that reason is
reachable from two different situations — an abandoned payment and a crashed
reservation loop — and the event does not carry which.

Final payload:

```json
OrderCancelled { "order_id": 42, "reason": "PAYMENT_FAILED", "buyer_id": 7 }
```

**Why:**
The split falls out of one question with no exceptions. `ORDER_EXPIRED` looks
ambiguous because it is reachable from two states, but it resolves the same way
from both: the buyer is gone either way.

The strongest argument against notifying on `RESERVATION_FAILED` is not
redundancy, it is **load shape**. Under NFR-P1's scenario — 5,000 concurrent
buyers against 500 units — `RESERVATION_FAILED` fires thousands of times,
clustered into the few seconds of peak contention. Emailing all of them would
give Notification a bursty, hot-path load profile, which is the precise
opposite of the cold, batch-tolerant service its boundary was drawn to be. That
is a service-boundary violation, not a user-experience complaint.

Brands are never notified on `OrderCancelled`. A brand has no action to take on
one buyer's failed checkout, and a per-order brand email would be unusable
noise the moment a sale opened. `recipient_type = BRAND` stays reserved for
`PaymentConfirmed`.

**On FR-B5:** not sending an email for `RESERVATION_FAILED` does not violate
"notify a buyer of purchase confirmation or failure." The buyer *is* notified —
synchronously, in the HTTP response to the request they are still waiting on.
"Notify" is not a synonym for "email". FR-B5 has been clarified in
`docs/requirements.md` to say so explicitly, so this does not read as an
unmet requirement later.

**Trade-offs:**
- A buyer who abandons a payment deliberately still receives an expiry email
  they may not want. Accepted: the alternative is staying silent about a
  reservation that was held and released, which is worse for the buyer who did
  *not* abandon it on purpose.
- Notification must now branch on `reason`, so it holds a small piece of
  knowledge about Order's failure taxonomy. Accepted as the cost of the filter
  living where the decision is (see below).

**Consequences:**
- **Order publishes `OrderCancelled` unconditionally, for all four reasons** —
  Inventory needs every one of them to release stock, so publishing cannot be
  conditional. The filter lives entirely in Notification's consumer.
- The payload gains `buyer_id` and deliberately does **not** gain `brand_id`,
  since brands are never a recipient here. This keeps the earlier rule intact:
  enrichment data flows only to the service that uses it.
- `buyer_id` is inert for Inventory, which reads only `order_id`.
- No new idempotency concern. Thread 1's republish can emit `OrderCancelled`
  twice for one order, but `notification_log`'s
  `UNIQUE(order_id, event_type, recipient_type, channel)` already makes the
  second send a no-op.

---

## Decision: Out-of-order `BrandUpserted`/`UserUpserted` delivery is handled by a timestamp-conditional upsert, not by requiring ordered delivery

**Context / Problem:**
`brand_contacts`/`buyer_contacts` are refreshed by consuming `BrandUpserted`/
`UserUpserted`. Nothing stopped an older event from overwriting a newer one if
two updates to the same brand or buyer were delivered out of order — a brand
changing their email twice in quick succession, with the two Kafka messages
landing in the wrong sequence.

**Options Considered:**
1. Guarantee same-key ordering at the Kafka/partition layer, and let the
   consumer apply events unconditionally in the order it receives them.
2. Guard the write itself with a timestamp comparison, so correctness does not
   depend on delivery order at all.

**Chosen Approach:**
Option 2. Every upsert is conditional on the event's source-side `updated_at`
being newer than what is already stored:

```sql
-- brand_contacts, on receiving BrandUpserted
INSERT INTO brand_contacts (brand_id, email, updated_at)
VALUES (:brand_id, :email, :updated_at)
ON CONFLICT (brand_id) DO UPDATE
SET email = EXCLUDED.email, updated_at = EXCLUDED.updated_at
WHERE brand_contacts.updated_at < EXCLUDED.updated_at;

-- buyer_contacts, on receiving UserUpserted — identical shape, keyed on user_id
```

A stale event (its `updated_at` older than, or equal to, what is stored) fails
the `WHERE` and is silently skipped — no error, no partial write. A first-ever
event for a brand or buyer never hits the conflict path, so creation is
unaffected.

**Why:**
This is the same pattern as every idempotent write already in this design —
the row's own state is the guard, not an external assumption about delivery. It
makes the *reason* option 1 was never actually necessary explicit: a
conditional write is not a workaround for missing ordering, it is a strictly
better mechanism than depending on ordering, because it stays correct even if
ordering guarantees change or are violated by an operational mistake later.

**Correction to a claim made when payload shapes were decided:** that entry
stated the eventual guard "depends on [same-key Kafka ordering] being either
true or explicitly handled." That was imprecise to the point of being
backwards. This guard *is* the "explicitly handled" case — it was never
waiting on ordering as a prerequisite. **The Kafka partition-key/ordering open
item is removed accordingly**; nothing in this design has a correctness
dependency on same-key delivery order. Choosing a partition key becomes an
ordinary throughput decision at Kafka-wiring time, not design debt.

**Trade-offs:**
None identified beyond the ordinary cost of a conditional write over an
unconditional one, which is negligible at this table's write volume.

**Consequences:**
- Closes the "out-of-order upsert events" item under Deliberately Deferred.
- Removes the Kafka partition-key/ordering open item — its stated dependency
  no longer holds, and no other correctness reason for it was ever identified.
- The one-time backfill for pre-existing brands/buyers (already decided under
  "Contact caches fed by `BrandUpserted`/`UserUpserted`") is unaffected: a
  backfill seeding initial state is a plain insert, not a race this guard needs
  to protect against.

---

## Decision: Notification retries `FAILED` sends with a small capped backoff; no escalation, no fencing, a claim but not the refund worker's full machinery

**Context / Problem:**
`notification_log.status` can be `FAILED`, but nothing retried a failed send.
It was tempting to reuse the refund worker's mechanism wholesale, since it
already solves "retry an at-least-once external call safely."

**Options Considered:**
1. Reuse the refund worker's full machinery unchanged: `PROCESSING` claim with
   `claimed_at`, a stale-claim sweep, a fenced finishing write, and escalation
   to a manual-review state after the cap.
2. No retry at all — a `FAILED` send stays `FAILED` forever.
3. A minimal retry: capped exponential backoff, a lightweight claim to prevent
   concurrent double-sends, no fencing, no escalation.

**Chosen Approach:**
Option 3. `notification_log` gains `attempts` and `next_retry_at`. A scheduled
job retries `FAILED` rows with backoff up to a small cap (5 attempts), then
leaves them `FAILED` with no further action.

A row is claimed before sending — `UPDATE notification_log SET
status='RETRYING' WHERE id=? AND status='FAILED'`, checked by rows-affected —
but there is no `claimed_at`, no stale-claim sweep, and no fenced finishing
write. No idempotency-key requirement is placed on the email provider.

**Why:**
The refund worker's machinery exists to survive two things: a crash leaving a
claim stuck forever, and a stale worker overwriting a newer attempt after
being re-claimed. Both are corner cases worth defending against **when the
work being protected is money**. Here it is a courtesy notification — a
duplicate or a missed email is an inconvenience, not a loss, so the cost of
building and operating that machinery (a sweep, a fenced write, a manual-review
queue someone has to watch) is not justified by what it protects.

The one piece kept — a minimal claim before sending — is not a relaxed version
of the refund worker's claim, it is answering a different, more common
scenario. Fencing defends against a **rare crash-recovery corner case**. A bare
claim defends against **ordinary horizontal scaling**: any deployment running
more than one Notification Service instance on the same retry schedule will
routinely have two instances pick up the same row at the same time. Without a
claim, that is a *normal-operation* double-send, not a rare one, and it costs
nothing to prevent with one conditional update.

Escalation is correctly dropped, not merely cheapened: nobody needs to take
action on one missed email the way someone must act on one stuck refund.
Ordinary service logging already makes a `FAILED` row visible if the volume of
failures ever signals something systemic (a provider outage), and that
observability needs no new mechanism — it falls out of logging the retry job
already needs for its own operation.

**Trade-offs:**
- A crash between claiming a row and finishing the send leaves it stuck at
  `RETRYING` forever, with no sweep to recover it — accepted, since the
  failure mode is "this one email might never retry," not "this refund is
  stuck and the buyer's money is gone."
- Concurrent claim attempts by two instances still have a narrow window (read
  before either claims) where both could pick the same row before either's
  `UPDATE` commits; the claim reduces this to the ordinary race any CAS
  resolves, it does not eliminate every theoretical interleaving. Acceptable
  at this table's volume and stakes.

**Consequences:**
- Closes "retry tracking for `FAILED` notifications" under Deliberately
  Deferred.
- Named `attempts`, matching `refunds.attempts` — same concept, same name,
  no reason for the two tables to disagree.

---

## Decision: A sale cannot be cancelled once it has started

**Context / Problem:**
Computing liveness left `CANCELLED` as the one stored state, and with it an
undefined case: if a brand cancels a sale that is already running, what happens
to reservations being held against it, and to orders that have already been
paid? Releasing held stock mid-sale, or refunding completed purchases, would
each be a substantial new mechanism.

**Options Considered:**
1. Allow cancellation at any time, and design the cascade — release
   outstanding reservations, refund paid orders.
2. Allow cancellation at any time and define it as affecting only *future*
   purchases, leaving existing orders untouched.
3. Permit cancellation only before the sale opens.

**Chosen Approach:**
Option 3. `CANCELLED` is reachable only while `now < start_time`.

**Why:**
Option 3 does not handle the undefined case — it makes it unreachable. Before
`start_time` a sale cannot be live, so `reserve()` has rejected every call
against it, no `stock_reservations` row can exist, and no payment can have been
captured. There is no cascade to design because there is nothing yet to cascade
to.

Option 1 would have meant building a sale-wide compensation path — mass release
plus mass refund — triggered by a brand's UI action, for a case with no
requirement behind it. Option 2 is the quiet kind of wrong: a "cancelled" sale
whose already-placed orders continue to completion means the word means
something different to the brand than to the buyer.

It also completes a pattern rather than adding an exception. `sale_price`,
`start_time` and `end_time` are each editable before the sale opens and frozen
once it does. Cancellation now follows the same boundary, so the rule
generalises to: **everything about a sale is mutable until it opens, and
nothing after.** One invariant to remember instead of three.

**Trade-offs:**
- **There is no emergency stop on a live sale.** A brand that opens a sale with
  a mispriced item — ₹10 instead of ₹10,000 — has no remedy: the sale runs to
  `end_time` and every purchase stands. This is strictly harsher than the
  trade-off accepted under the immutable-price decision, which justified frozen
  prices on the grounds that the remedy was "cancel and recreate." That remedy
  no longer exists once the sale is live, and this entry supersedes that
  reasoning.
- Accepted deliberately, because the alternative is worse for the buyer: a
  platform whose sale windows can be withdrawn mid-flight offers no guarantee
  worth relying on. The window a buyer sees is the window they get.
- The cost is bounded by the sale's own duration — flash sales are short by
  definition, so a mistake is survivable rather than open-ended.

**Consequences:**
- The `status != 'CANCELLED'` term remains necessary in the liveness check.
  Without it a sale cancelled before opening would become live the instant
  `start_time` passed.
- `CANCELLED` is terminal and pre-start only: a cancelled sale never becomes
  live, never holds a reservation, and never has an order against it.
- Cancellation needs the same enforcement point as the other pre-open edits —
  application-level, rejected once `now >= start_time`.
- Removes the "in-flight and paid orders on a cancelled sale" open item
  entirely; it is now an impossible state rather than an unhandled one.
- **Stock quantities sit on the same boundary** (added when `flash_sale_items`
  was written; the list above named price, times and cancellation but not
  stock). `total_stock` is editable pre-open and frozen after — "stays fixed"
  already implied it; this states it. `available_stock` changes only through
  reserve, release and confirm, never through an edit, so no brand-facing
  top-up or removal of stock mid-sale exists. Same enforcement point as the
  other pre-open edits: application-level, rejected once `now >= start_time`,
  checked against the database clock in the same transaction as the write.
  Side effect worth knowing: no entity-flush write to `flash_sale_items` occurs
  during a live sale, which leaves the `@Version`-vs-raw-SQL mixing hazard
  reachable only at the open boundary itself.

---

## Decision: Inventory's `reserve` API contract

> **Correction (from "`ITEM_NOT_IN_SALE` is a `200` outcome"):** the claim
> below that `ITEM_NOT_FOUND` "is also a bug" because "Order validates at
> creation that every item belongs to the order's `flash_sale_id`" is wrong:
> that check does not exist. `404 ITEM_NOT_FOUND` is replaced by the `200`
> outcome `ITEM_NOT_IN_SALE`.

> **Correction (from "Event payload shapes"):** two fields below are
> incomplete against a rule decided later. The response must also return
> `productName` — `order_items.product_name` is snapshotted from it, for
> Notification's receipt — and `salePrice` must be serialized as a **string**
> (`"3999.00"`), never a JSON number, per the money-as-string standing rule.
> Neither changes what this entry decided; the concrete shape was written
> before that rule existed.

> **Correction (from "Reserve contract revised: `outcome` replaces
> `success`/`reason`"):** the response shapes in the table below are
> superseded. Every `200` now carries an `outcome` enum instead of
> `success`/`reason`, a released reservation answers `RESERVATION_RELEASED`,
> malformed fields other than `quantity` answer `400 INVALID_REQUEST`, and
> every non-`200` body is `{ code, message }`. The evaluation order and the
> 200-vs-non-200 line decided here stand.

**Context / Problem:**
`reserve` is the hot path and the one call that carries the no-overselling
invariant. Several decisions had accumulated against it — return `brand_id`,
return `sale_price`, key by `(flash_sale_id, product_id)`, translate the
`UNIQUE(order_id, flash_sale_item_id)` violation into success — without the
contract ever being written down.

**Chosen Approach:**

```
POST /internal/flash-sale-items/reserve
{ "orderId": 42, "flashSaleId": 7, "productId": 501, "quantity": 2 }
```

| Situation | Response |
|---|---|
| Fresh reservation succeeds | `200 { success: true, reservationId, salePrice, brandId, productName }` |
| Replay — reservation already exists, same quantity | `200 { success: true, reservationId, salePrice, brandId, productName }` — same shape, existing row |
| Replay — reservation exists with a *different* quantity | `409 { success: false, reason: "QUANTITY_MISMATCH" }` |
| Not enough stock | `200 { success: false, reason: "OUT_OF_STOCK" }` |
| Sale not live (computed, see below) | `200 { success: false, reason: "SALE_NOT_LIVE" }` |
| No such `(flashSaleId, productId)` | `404 { success: false, reason: "ITEM_NOT_FOUND" }` |
| `quantity` < 1 | `400 { success: false, reason: "INVALID_QUANTITY" }` |

**Evaluation order is part of the contract**, not an implementation detail:

1. Validate `quantity`.
2. Resolve `(flashSaleId, productId)` → `flash_sale_items` row; 404 if absent.
3. **Look for an existing reservation for `(orderId, flash_sale_item_id)`.** If
   one exists, return it (or 409 on quantity mismatch) and stop.
4. Only now check the sale is live — **computed**, not read from a stored
   value: `status != 'CANCELLED' AND start_time <= now < end_time`, evaluated
   against the database clock in the same statement as step 5 (see "No
   status-transition job").
5. Only now check and decrement `available_stock`, and insert the reservation —
   both in one local transaction.

**Why:**
The replay check must precede the liveness and stock gates, and this is the
part most likely to be got wrong. Consider: a reservation succeeds while the
sale is `LIVE`, Order's Feign call times out, the sale ends, and Order retries.
If `SALE_NOT_LIVE` were evaluated first, the retry would fail, Order would read
that as a reservation failure, and it would cancel an order whose stock was
legitimately held — losing a sale that had actually been made. The same logic
applies to the stock gate: a replay must never re-evaluate stock, because its
units are already deducted and re-checking could reject a hold that exists.

A quantity mismatch on replay is a bug, not a retry. `order_items` fixes
quantity at order creation under `UNIQUE(order_id, product_id)`, so the same
order cannot legitimately ask for two different amounts of the same product.
Silently returning the existing row would reserve one quantity while Order
priced another. `409` says "this is not the request I already satisfied".

`ITEM_NOT_FOUND` is `404` rather than a `200` business outcome because it is
also a bug: Order validates at creation that every item belongs to the order's
`flash_sale_id`, so by reserve time the row must exist. It should alert, not be
handled as a normal branch. That is the line between the two response families
— `200` for outcomes the buyer can legitimately hit, non-`200` for states that
mean something upstream is wrong.

`success: true` appears on the success path even though `reservationId` implies
it, so a client discriminates on one field in every response rather than on the
presence of a field in some of them.

Returning `salePrice` on the replay path is only safe because a live sale's
price is immutable — a replay and the original therefore read the same value.
That makes the immutability rule load-bearing here, not merely tidy.

**Trade-offs:**
- Business failures return `200`, so a caller that only checks HTTP status will
  mistake `OUT_OF_STOCK` for success. Mitigated by `success` being present and
  mandatory in every response body.
- The evaluation order is a correctness requirement the type system cannot
  enforce; it needs a test (replay after sale end must still succeed).

**Consequences:**
- **Internal only.** Like the order-status endpoint, this must not be routable
  through the API Gateway: it takes an order ID with no ownership check and
  mutates stock. Gateway route configuration is the control; the `/internal/`
  prefix is only a convention.
- There is no `release` counterpart — release happens by consuming
  `OrderCancelled`.
- The decrement and the reservation insert share one local transaction, so a
  rejected insert rolls back the decrement (already decided under "Idempotent
  reserve via `UNIQUE(order_id, flash_sale_item_id)`").
- Needs an explicit test for the replay-after-sale-end case, which is the
  scenario the evaluation order exists to protect.

---

## Decision: Order's internal order-status endpoint — `GET /internal/orders/{orderId}/status`

**Context / Problem:**
Inventory's stale-reservation reconciler must ask Order what became of an
order before acting on a reservation it still holds. Order is the sole
authority on an order's fate, so the reconciler may not infer anything on its
own. The contract had never been specified.

**Options Considered:**
1. Return the full order (items, amounts, timestamps).
2. Return only the current status.
3. Return a pre-interpreted verdict (`CONFIRM` / `RELEASE` / `WAIT`), moving
   the decision into Order.

**Chosen Approach:**
Option 2.

```
GET /internal/orders/{orderId}/status
200 → { "orderId": "...", "status": "PAID" }
404 → order not found
```

`status` is whatever `orders.status` currently holds, unmodified. The
reconciler maps it:

| Order's answer | Reconciler's action |
|---|---|
| `PAID` | confirm the reservation |
| `ORDER_EXPIRED` / `PAYMENT_FAILED` / `PAYMENT_UNAVAILABLE` / `RESERVATION_FAILED` | release the reservation |
| `CREATED` / `PENDING_PAYMENT` | do nothing; re-check next pass — **and alert** |
| `404` | do nothing; alert a human, don't guess |
| no response / Order unreachable | do nothing; alert, retry next pass |

**Why:**
Option 1 sends data the reconciler has no use for and widens the surface for
no benefit. Option 3 was rejected because the verdict is Inventory's business,
not Order's: Order would have to know what a reservation is and what
confirming one means, re-coupling the two services that this design keeps
deliberately separate. Returning the raw status keeps Order the authority on
*the fact* while leaving Inventory the authority on *its own table*.

The contract is race-free for a reason worth stating: **every answer the
reconciler acts on is a terminal state.** `PAID` and the four failure states
cannot change after they are read, so there is no window between the read and
the reconciler's write. The two answers that *could* change —
`CREATED`/`PENDING_PAYMENT` — are exactly the two where the action is to do
nothing. No locking or re-checking is needed to make this safe.

`404` does not mean "release". A reservation exists only because Order
committed the order row before calling `reserve()`, so a missing order is an
anomaly — data loss, a bug, or Order reading the wrong database — not evidence
the stock is free. Releasing on it would risk freeing stock that was paid for,
and the system's standing bias is to hold rather than oversell. Alert instead.

`CREATED`/`PENDING_PAYMENT` alerts because of *when* it is seen. The reconciler
only examines reservations hours old, while Order's own deadlines are seconds
to minutes. An hours-old reservation whose order is still live means Order's
expiry sweep has stopped running. The action stays "do nothing" — Order remains
the authority — but silence would hide a dead sweep.

**Trade-offs:**
- One HTTP call per stale reservation. The earlier claim that this is
  "naturally rate-limited by how many stale rows exist" holds only while
  orphans are rare. It fails precisely when it matters: if Kafka is down for
  hours, every reservation goes stale at once and the reconciler would hammer
  Order with thousands of calls during an active incident. The reconciler
  therefore scans a **bounded number of rows per pass** (oldest first), draining
  a backlog over successive passes rather than in one burst. A batch endpoint
  was considered and rejected as a larger contract for a path that is allowed
  to be slow.
- Returning raw status means the mapping table lives in Inventory, so a new
  order state requires Inventory to be taught about it. Accepted: the state
  machine is stable, and the alternative moves reservation semantics into Order.

**Consequences:**
- The endpoint is **internal only** and must not be routable through the API
  Gateway. It takes an order ID and returns that order's status with no
  ownership check, so exposing it publicly would be a trivial IDOR. The
  `/internal/` prefix is a convention, not a control — the Gateway's route
  configuration is what enforces it.
- `orderId` is echoed in the response so a misrouted or mismatched answer
  cannot be applied to the wrong reservation.
- Responses must not be cached; status changes over time.

---

## Decision: Webhook-never-arrives is closed by a reconciliation sweep that queries the gateway by the order's deterministic idempotency key

**Context / Problem:**
Every refund in this design was triggered by an inbound webhook. If a webhook
never arrives at all — the gateway crashes before sending it, or delivery is
lost with no retry — a captured payment is never noticed. Order's sweep expires
the order, stock is released, and no refund row is created. The buyer has paid
and received nothing, silently, with nothing in the system aware of it. This
was the one remaining hole in the money path: every other failure mode
eventually corrects itself, and this one does not.

**Options Considered:**
1. Rely on the gateway's own webhook retries and treat total non-delivery as
   out of scope.
2. Sweep orders where `payment_ref IS NOT NULL`, asking the gateway whether
   that payment went through.
3. Mark intent with a new column written *before* the initiate call
   (`payment_attempted_at`), and sweep on that.
4. Sweep using the deterministic idempotency key already derived from the
   order ID, asking the gateway what exists for that key — no stored payment
   reference required.

**Chosen Approach:**
Option 4. A scheduled reconciliation sweep in Order Service:

- Scans orders in a terminal **failure** state (never `PAID`) that have not yet
  been reconciled.
- For each, asks the gateway what exists for `K(order_id)` — the same
  deterministic idempotency key the initiate call carries.
- If the gateway reports a captured payment, the sweep opens a `refunds` row
  exactly as a losing webhook would, and the existing refund worker drains it.
- If the gateway reports no payment, or an uncaptured one, nothing is owed.
- Either way the order is marked reconciled, so the scan set stays bounded and
  the sweep is naturally idempotent. A nullable `orders.payment_reconciled_at`
  is the simplest form of that marker; the alternative, a fixed age window,
  would silently stop looking at orders old enough to fall out of it.

**Why:**
Option 2 is the obvious filter and it is wrong: `payment_ref` is `NULL` in
exactly the case most likely to lack a webhook. If Order crashes between
calling `initiate()` and committing `payment_ref`/`PENDING_PAYMENT`, a real
payment exists upstream while our column is still empty — this is the same
window that forced the one-payment invariant to refund using the reference
*from the webhook* rather than from `orders`. A sweep keyed on `payment_ref`
would skip those orders entirely.

Option 3 closes that hole but costs a column and an extra committed write on
the checkout path, purely so the sweep has something to filter on.

Option 4 needs neither, because the idempotency key is already a pure function
of the order ID: the key exists whether or not the initiate call ever returned,
so the sweep can always ask the question, even for an order that crashed before
learning anything. It reuses a decision already made instead of adding one.

**Trade-offs:**
- Adds a third required behaviour to Razorpay Clone: its payment-status lookup
  must accept *our* idempotency key or merchant order reference, not only the
  gateway's own payment ID. Standard for a real processor, but another
  cross-project requirement that is designed, not verified.
- The sweep asks what exists for *this order*. If a second payment somehow
  existed and its webhook also never arrived, only a "list every payment for
  this order" query would surface it. With a deterministic key a second payment
  should be impossible — but this means this one correction path leans on
  prevention, which the rest of the design deliberately avoids. Recorded as a
  known limitation rather than solved.
- Reconciliation is delayed by the sweep interval, so a buyer owed a refund in
  this scenario waits longer than one whose webhook arrived normally.

**Consequences:**
- The refund path itself is unchanged — the sweep is a second *producer* of
  `refunds` rows, and the command-outbox worker, backoff, capped retries, and
  `NEEDS_MANUAL_REVIEW` escalation all apply identically.
- `orders` gains a nullable `payment_reconciled_at`, and an index supporting
  "terminal failure and not yet reconciled".
- Only orders in a terminal *failure* state are scanned. A `PAID` order has its
  one payment applied by definition, and scanning it could only produce a
  refund for money that was legitimately kept.
- The sweep interval is another derived value, subject to the same rule as the
  rest (see "Deadlines and leases are derived from the timeouts they race").

---

## Decision: The PRD's remaining event list is reduced to four events

**Context / Problem:**
The PRD's async event list (Section 3.2) named seven events. `StockReserved`
was dropped earlier, and `ReservationExpired` and `OrderCreated` were dropped
by entries above. Three were never formally examined: `PaymentInitiated`,
`PaymentFailed` and `OrderConfirmed`. Leaving them undecided means the PRD and
the derived design disagree about what the system publishes.

**Options Considered:**
1. Keep them as placeholders in case a consumer appears.
2. Apply the same test used for every other event — does a consumer actually
   need this — and drop what fails it.

**Chosen Approach:**
Option 2. All three are dropped, for two distinct reasons rather than one:

- `PaymentInitiated` — **no consumer.** The initiate call is synchronous; Order
  records `payment_ref` and moves to `PENDING_PAYMENT` off its own response. No
  other service needs to know that a payment was started, and Inventory
  specifically must not act on it (the stock is already held, and payment has
  not succeeded).
- `OrderConfirmed` — **no consumer, and nothing to announce.** It corresponded
  to the `CONFIRMED` order state, which was itself dropped because nothing ever
  set it (see "Order state machine" above). `PaymentConfirmed` already carries
  the buyer-facing success path.
- `PaymentFailed` — **absorbed, not merely unused.** It is covered by
  `OrderCancelled{reason=PAYMENT_FAILED}`. Inventory's reaction to a failed
  payment is identical to its reaction to every other way an order dies, so a
  dedicated event would mean a second handler doing the same work.

**Why:**
Same rule as `StockReserved` and `OrderCreated`: an event exists because a
consumer needs it, not because the domain has a noun for it. Distinguishing
"no consumer" from "absorbed" matters for future readers — the first two could
be added back cleanly if a consumer appeared; re-adding `PaymentFailed` would
mean re-splitting the death path that was deliberately unified.

**Trade-offs:**
A future consumer that genuinely needs "payment was initiated" (an analytics
or fraud path, say) has to add the event back, along with the outbox row on
what is a reasonably hot path.

**Consequences:**
The published event list is now exactly four: `PaymentConfirmed`,
`OrderCancelled` (Order), and `BrandUpserted` / `UserUpserted` (Identity).
The PRD's Section 3.2 list needs updating to match.

---

## Decision: Inventory keeps no deadline of its own; stale reservations are reconciled by asking Order

**Context / Problem:**
With expiry moved to Order, `stock_reservations.expires_at` no longer drives
anything. But a reservation could still be stranded by a lost event or a bug,
and Inventory would hold that stock forever.

**Options Considered:**
1. Keep a long backstop TTL in Inventory that releases stale reservations.
2. A reconciler that asks Order what happened before acting.

**Chosen Approach:**
Option 2. `stock_reservations.expires_at` is dropped in favour of
`reserved_at`, indexed `(status, reserved_at)`. For rows still `RESERVED` after
a few hours, Inventory calls Order (Feign) and acts on the answer:

| Order says | Reconciler does |
|---|---|
| `PAID` | confirm the reservation |
| a terminal failure state | release it |
| unreachable | nothing, and raise an alert |

**Why:**
A backstop that releases on its own reintroduces the original race, merely
stretched out in time: if Inventory's consumer has been down for hours, a
genuinely `PAID` order's reservation looks stale and would be released —
overselling again. The backstop must ask, not decide. Order remains the only
authority even in the fallback path.

**Trade-offs:**
Inventory now makes an outbound synchronous call it otherwise would not, and
Order must expose a status-check endpoint. The call is off the hot path.

> **Correction:** this entry originally claimed the call was "naturally
> rate-limited by how many stale rows exist." That is only true while orphans
> are rare bugs. Under a systemic failure — Kafka down for hours — every
> reservation goes stale at once and the reconciler would flood Order during an
> active incident. The reconciler therefore scans a bounded number of rows per
> pass; see "Order's internal order-status endpoint" below.

**Consequences:**
- Order must expose an internal order-status endpoint — now specified, see
  "Order's internal order-status endpoint" below.
- With the sweep and outbox working correctly, orphans should arise only from a
  bug or lost data, so this is defence in depth. Designing it now and building
  it later is reasonable.

---

## Decision: Deadlines and leases are derived from the timeouts they race, never picked

> **Correction (2026-11-05, from "Day 5: payment step, resilience and derived
> deadlines"):** the numbers now exist. The `CREATED` deadline is derived from
> the Feign and Resilience4j config (3 s per call, 6.5 s payment budget, 17
> lines; implemented and tested); the payment window (6 min), sweep interval
> (30 s) and refund lease (2 h) are decided as policy. All provisional and
> unmeasured, recheck Day 9; not load-tested. The text below is unchanged.

**Context / Problem:**
Two values in this design are deadlines racing an in-flight operation: the
short `CREATED` deadline (racing the reservation loop plus the initiate call)
and the refund worker's stale-claim lease (racing an HTTP call). A number
chosen by feel can be shorter than the operation it bounds, which turns a
healthy slow request into a spurious expiry or a duplicated attempt.

**Options Considered:**
1. Pick round numbers and tune them later under load.
2. Derive each from the configured timeouts of the operation it bounds.

**Chosen Approach:**
Option 2, as a standing rule.
- The `CREATED` deadline must exceed the loop's worst case — per-call timeout ×
  attempts × item count — plus the initiate call's own timeout × attempts, plus
  margin.
- The refund stale-claim lease must exceed the HTTP client's total ceiling
  (connect + read, across in-call retries), so a live worker cannot still be
  mid-call when the sweep reclaims its row.

**Why:**
Both numbers are meaningful only relative to the Feign and Resilience4j
configuration, which does not exist yet. Writing a concrete number now would be
a guess wearing the costume of a decision.

**Trade-offs:**
Neither value can be finalised until the timeout configuration is written, so
both stay open — deliberately, not accidentally.

**Consequences:**
The actual numbers are decided in the same change that configures the Feign
clients and circuit breakers, and are load-tested rather than assumed.

---

## Decision: Outbound brand webhooks — dropped

**Context / Problem:**
The original pre-pivot PRD carried a notion of FlashMart calling *out* to a
brand's own system on events (order placed, payment confirmed) — the mirror
image of the inbound payment webhook, direction reversed. It survived as an
unexamined leftover: grouped with vendor-onboarding as a deferred later-phase
item in the Notion log, then never revisited once the brand-platform pivot
and the Revision 3 redesign happened. No FR anywhere described it as a
feature, and it appeared nowhere in the Revision 3 design — it was neither
built nor formally cut, just present by omission.

**Options Considered:**
1. Keep it as a planned feature and design it properly: brand-registered
   webhook URLs, HMAC signing (mirroring the inbound webhook's own signature
   verification), a delivery-tracking table with retry/backoff (a third
   instance of the pattern `refunds` and `notification_log` already each
   implement once), and a new FR to justify it.
2. Drop it — remove the stray references, keep the email-based brand
   notification that already exists via `PaymentConfirmed` → Notification
   Service, and treat this as the extent of what a brand is told.

**Chosen Approach:**
Option 2. Dropped, with no replacement.

**Why:**
No requirement ever justified it — it was carried forward by inertia, not by
a stated need. A brand's own system already gets confirmation events via the
existing email path; nothing in this design commits FlashMart to giving
brands *programmatic* visibility into their own sales data on top of that.
Building it properly would mean a third, brand-facing implementation of the
signed-delivery-with-retry pattern this project already builds twice
(inbound payment webhook, refund worker) — no new distributed-systems
concept for a project whose purpose is learning exactly those concepts, just
more surface area repeating one already covered.

**Trade-offs:**
None identified. Nothing currently depends on this existing.

**Consequences:**
- No `requirements.md` FR needs writing or removing — none ever existed for
  this.
- The stray references (Notion log's original session-batching note,
  `Flashmart.prd`'s session 19–20 row, and this file's/`architecture.md`'s
  open-items entries) are cleaned up as part of this decision, not left as
  a dangling "keep or drop" question.

---

# Implementation phase — decisions made while building

The entries below were made while turning the design into code, not in the
Revision 3 review pass above: schema mechanics, conventions, test discipline,
and the gaps that writing real entities and the first API contract exposed.
Where one changes an earlier entry, the earlier entry carries a Correction
pointing here.

---

## Decision: Flyway (SQL-first migrations), not Hibernate `ddl-auto`, owns schema creation

**Context / Problem:**
`inventory-service` booted with `ddl-auto: create` as a deliberate, commented
stopgap — just to verify the datasource connects end-to-end before any
`@Entity` classes existed. That stopgap runs out the moment the JPA-entities
phase starts: `create` drops and recreates every mapped table on each boot,
and its safer-looking sibling `update` can't reliably express or evolve the
constraints this schema actually needs — composite `UNIQUE`s
(`UNIQUE(buyer_id, idempotency_key)`, `UNIQUE(order_id, flash_sale_item_id)`,
`UNIQUE(payment_ref)`), `CHECK` constraints
(`CHECK (status NOT IN ('PENDING_PAYMENT','PAID') OR amount IS NOT NULL)`),
and the immutability/versioning behaviour `flash_sale_items.version` depends
on. There are four services, each with its own database, all evolving schema
across many further build phases (transactions/locking, outbox, Kafka) — none
of that is reproducible across environments without a versioned history of
exactly what ran and when.

**Options Considered:**
1. `ddl-auto: update` — Hibernate diffs entities against the live schema and
   patches it. Never drops/renames columns safely, can't express `CHECK`
   constraints or many composite unique constraints, and leaves no history of
   what changed or why.
2. `ddl-auto: create` / `create-drop` — fine for the pre-entity connectivity
   check this project already used it for; unusable past that, since it wipes
   data on every boot.
3. **Flyway** — versioned, hand-written SQL migration files
   (`V1__description.sql`, ...), tracked in a `flyway_schema_history` table
   Flyway maintains itself; applies only new versions on boot, in order.
4. Liquibase — same category as Flyway (versioned migrations), but
   XML/YAML/JSON-first rather than SQL-first, and heavier tooling than this
   project needs.

**Chosen Approach:**
Flyway, with hand-written SQL migrations under
`src/main/resources/db/migration/`, one migration per schema change from here
on. `ddl-auto` flips to `validate` — Hibernate only checks that `@Entity`
mappings match what Flyway already created; it never generates or mutates
DDL. Applies to `inventory-service` now; the same pattern is expected to be
replicated in Order, Identity, and Notification Service when their own
entity phases begin.

**Why:**
Full control over constraints/indexes that Hibernate's auto-diff can't
express safely or at all. A real, inspectable history of every schema
change, reproducible across Akshansh's machine, CI (once it exists), and any
future environment — instead of the current schema being whatever Hibernate
inferred from entities at that moment. Migration files are immutable once
applied anywhere (Flyway checksums each file and errors on a mismatch),
which forces schema changes to be additive and explicit rather than edited
in place.

**Trade-offs:**
No auto-generation — every entity change needs a hand-written SQL migration
alongside it, which is more upfront work than letting Hibernate infer DDL.
Flyway Community has no built-in down-migration/rollback; a bad migration is
fixed by writing a new forward migration, not by reverting the old one.

**Consequences:**
- Every future `@Entity` in every service pairs 1:1 with a migration file.
  *(Held in all four services. Per-service migration ranges and entity lists
  live in `architecture.md` §8, the status source of truth — they are not
  repeated here, because this note had gone stale twice.)*
- `db/migration/` exists in every service, establishing the convention.
- **Correction found when this was first booted:** the Flyway *library*
  (`flyway-database-postgresql`) alone does nothing under Spring Boot 4 —
  Spring's auto-configuration lives in a separate module. The first commit
  added only the library, compiled fine, and never ran a migration; `mvn
  compile` proves classes are on the classpath, not that Spring wires them
  into boot. The fix is `spring-boot-starter-flyway` (which pulls the
  auto-configuration module) **plus** `flyway-database-postgresql`, now the
  dependency pair in all four services. Verified by booting against
  Postgres, not by compiling.
- The locking-strategy decision for `flash_sale_items`
  (`@Version`/pessimistic/Redis — still open, see Open Items) will itself
  eventually need a migration once settled; Flyway doesn't resolve that
  question, it just gives the eventual answer a clean place to land.

---

## Decision: `updated_at` on `brands`/`users` is database-generated and strictly increasing; emails are stored normalised

**Context / Problem:**
`BrandUpserted`/`UserUpserted` carry `updated_at` as a source-side timestamp, and
Notification's conditional upsert accepts an event only if
`brand_contacts.updated_at < EXCLUDED.updated_at`, silently discarding anything
that looks older (see "Out-of-order `BrandUpserted`/`UserUpserted` delivery").
That made `updated_at` load-bearing on Identity's two tables, which nothing had
examined. Stamped from each Identity pod's own clock, a later change can carry
an *earlier* time: pod A stamps a change 10:00:05, pod B — two seconds slow —
stamps the next change 10:00:04, and Notification discards the newer email
without error. `now()` does not fix it either, because it is the time the
transaction *started*, so a transaction that waited on the row lock can commit
with an older time than the one it overtook. Separately, a plain `UNIQUE(email)`
is case-sensitive in Postgres: `Priya@x.com` and `priya@x.com` both insert,
giving one mailbox two accounts.

**Options Considered:**
1. Let the application stamp `updated_at` (`@UpdateTimestamp`, as on `products`).
2. Database `DEFAULT`/`now()` only, with no rule on the old value.
3. A `BEFORE UPDATE` trigger that stamps
   `GREATEST(clock_timestamp(), OLD.updated_at + 1 microsecond)`.
4. Replace the timestamp comparison with an integer version counter on the row.
   Bulletproof, but it changes both event payloads, Notification's contact
   tables and its upsert.

**Chosen Approach:**
Option 3. The trigger function `bump_updated_at()` is created once and attached
to each table; Java never writes the column, and Hibernate reads the value back
(`@Generated`) so the outbox event can carry the exact stored timestamp. The
columns are `TIMESTAMPTZ`, read as `Instant` — the event contract is UTC
(`...Z`), and this comparison is the one place a zone-less timestamp could
silently skew it *(wrong: Inventory's liveness check is the same class of
comparison and was the worse instance — see "Every time column is
`TIMESTAMPTZ`/`Instant`")*. Emails are stored trimmed and lowercased, enforced by
`CHECK (email = lower(btrim(email)))`, with the entity normalising in its setter
so the check is a backstop rather than the first line of defence.

**Extended when `users` was written (2026-10-02).** That normalisation CHECK is
necessary but not sufficient: `''` satisfies it, because an empty string *is*
already lowercase and trimmed, so an empty email was insertable — demonstrated
against the live schema, not reasoned about. `name` had no check at all, and
nothing required an `@`, so `notanemail` stored happily and Notification would
mail nowhere. Three guards added to both tables (`users` from the start,
`brands` via a follow-up migration): `length(email) > 0`, an `@` with something
on each side, and `length(btrim(name)) > 0`. The `@` test is deliberately a
*minimal sanity gate*, not email validation — the real rules are famously messy
and the only true test is whether mail arrives, so proper checking belongs in
Bean Validation at the API plus a verification flow. A whitespace-only email was
already rejected by the original CHECK, since `btrim('   ')` is `''`.

**One address may be both a brand and a buyer.** `UNIQUE(email)` is per table,
so `contact@puma.com` can exist in each — two accounts, two password hashes.
Allowed deliberately: a brand's staff may also shop. The consequence is
load-bearing for the API phase — there can be **no single login endpoint**,
because one address and password could match a row in either table. Brand login
and buyer login must be separate, which the existing "the JWT must say whether
its subject is a brand or a user" item already implies.

**Why:**
The row's own previous value is the only reference that cannot disagree with
itself, and inside the trigger that value is read while the row is locked, so no
concurrent writer can slip between the read and the write — the same reasoning
as every other compare-and-set in this design, applied to a timestamp. It keeps
the already-decided event shape and Notification's guard unchanged.

**Trade-offs:**
- The project's first trigger: logic that lives in the database and is invisible
  from the Java side. Contained to one four-line function.
- Every `UPDATE` bumps `updated_at`, including ones that change nothing a
  consumer cares about. Harmless — only an email change publishes an event.
- `products.created_at`/`updated_at` keep the application clock. Nothing
  compares them across pods, so the weaker guarantee is sufficient there; the
  difference is deliberate, not an oversight.

**Consequences:**
- `users` reuses `bump_updated_at()` unchanged; only the trigger is added.
- Every write path must go through the normalising setter (or normalise
  itself); a raw insert with a mixed-case email is rejected by the CHECK.
- The signup path must turn the unique-violation into "email already taken",
  not a 500 — an API-phase concern recorded here so it is not forgotten.
- Verified, not assumed: a test seeds a row whose `updated_at` is an hour ahead
  of the clock, updates it, and asserts the new time is still later; weakening
  the function to plain `clock_timestamp()` fails exactly that test.

---

## Decision: Every time column is `TIMESTAMPTZ`/`Instant`; Inventory's zone-less columns converted by a forward migration

> **Note:** the "`updated_at` on `brands`/`users`" entry above chose
> `TIMESTAMPTZ` for Identity on the grounds that its comparison was "the one
> place a zone-less timestamp could silently skew". That was wrong: Inventory's
> liveness check is the same class of comparison and was the worse instance.

**Context / Problem:**
Inventory's `flash_sales.start_time`/`end_time`, `stock_reservations.reserved_at`
and `products`' audit columns were zone-less `TIMESTAMP`, mapped to
`LocalDateTime`. A zone-less value carries no label for whose clock it is, so
comparing it against `now()` (a `TIMESTAMPTZ`) makes Postgres guess from the
**connection's** `TimeZone` setting. Demonstrated on the live schema: a sale
starting `10:00` read as open from an `Asia/Kolkata` connection and not open
from a `UTC` one — same row, same instant. Two Inventory pods with different
connection settings would disagree about whether a sale is live. That is
exactly the two-clocks problem "`now` is the database's clock" was adopted to
remove, reintroduced through the column type, and it sits under the core
no-overselling guarantee.

**Options Considered:**
1. A new migration, `ALTER COLUMN ... TYPE TIMESTAMPTZ USING <col> AT TIME ZONE
   '<zone>'`.
2. Edit V1/V3/V5 in place and drop and recreate the local database. Cleaner
   history, but rewrites migration files already pushed, against the
   never-edit-an-applied-migration rule.
3. Leave the columns and set every connection's `TimeZone` explicitly. Fixes
   the symptom by convention, and a missed connection reintroduces the bug
   silently.

**Chosen Approach:**
Option 1, as `V6`. Every time column in every service is `TIMESTAMPTZ`, read as
`Instant`. `USING ... AT TIME ZONE` *names* the zone the old values meant
instead of inheriting the connection's, which is what makes the conversion
identical on every machine. The tables held no rows, so the zone chosen
(`UTC`) could not shift data; had they held rows, the correct zone would have
to be established from what the application wrote (`LocalDateTime` carries
none — it is whatever the JVM's zone was at save time), not assumed.

**Why:**
`TIMESTAMPTZ` settles the zone once, at write time, into one absolute moment;
a comparison then involves two absolute moments and the connection's setting
only affects display. Option 3 depends on every connection being configured
correctly forever. Option 2 would have been defensible on empty tables but
forms a habit the rule exists to prevent.

**Trade-offs:**
- `LocalDateTime` → `Instant` changes every entity field and test that touched
  these columns.
- `Instant` has no zone to display; anything user-facing must format it in the
  viewer's zone, which is correct but is now an API-phase concern.

**Consequences:**
- A new time column must be `TIMESTAMPTZ`. Comparing a zone-less column against
  `now()` is never acceptable. **Enforced, not just stated:**
  `NoZoneLessTimestampsTest` in every service fails, naming the column, if any
  `public` column is `timestamp without time zone`. Without it the fix covered
  five columns and nothing would have stopped the sixth. Order's, Notification's
  and Identity's remaining tables must be created `TIMESTAMPTZ` from the start,
  including `api_keys.created_at` and `outbox.created_at`, which the ERDs draw
  as plain `timestamp`.
- `TimeZoneConsistencyTest` pins the behaviour: an open and a not-yet-started
  sale are read identically from `Asia/Kolkata`, `UTC`, `America/Los_Angeles`
  and `Pacific/Auckland` connections, and a control on a zone-less temporary
  table asserts the bug *does* reproduce there, so the test cannot pass for
  the wrong reason. Putting `start_time` back to zone-less made both real tests
  fail with exactly the wrong answers; restoring it passed all 18.

---

## Decision: Identity's remaining tables — a fast hash for `api_keys.key_hash`, revoked keys kept, a partial index and `JSONB` for the outbox

**Context / Problem:**
`api_keys`, `users` and `outbox` are not written yet, but reasoning through the
four Identity tables (while writing `brands`) turned up choices that, got wrong
in the API phase, fail silently or only under load. Recorded now, as designed
and unbuilt, so they are not rediscovered.

**Options Considered and Chosen:**
1. **`key_hash` hashing.** A brand sends its API key on every request and the
   row is found with `WHERE key_hash = ?`, so hashing the same key must always
   give the same hash. A salted password hash (bcrypt) gives a different value
   every time: the lookup never matches and `UNIQUE(key_hash)` means nothing.
   **Chosen: a fast, deterministic hash (SHA-256).** Safe because API keys are
   long random values nobody can guess, which is exactly what bcrypt's slowness
   defends against and what passwords, being human-chosen, need.
2. **Revoking a key.** **Chosen: keep the row, set `status = 'REVOKED'`.** The
   row is the audit trail and keeps the hash reserved, so a revoked key can
   never be re-issued by collision. `CHECK (status IN ('ACTIVE','REVOKED'))`.
3. **Outbox index.** *(Written as `WHERE published_at IS NULL` once the
   boolean was replaced — see the extension below.)* The documented
   `(published, created_at)` indexes every
   row, including the ever-growing pile already published, which the relay
   never reads. **Chosen: a partial index on `(created_at) WHERE published =
   false`.** Publishing a row removes it from the index, so it stays tiny
   however old the system gets. Applies to Order's outbox as well.
4. **`outbox.payload_json`.** **Chosen: `JSONB`** — Postgres rejects invalid
   JSON when it is written, so a serialisation bug fails where it happened
   instead of at the consumer. Needs a Hibernate JSON mapping on the entity.
5. **`outbox.aggregate_id` has no foreign key** — it names a brand *or* a user,
   so no single FK can express it. Deliberate, not an omission.

**Why:**
Each is cheap to get right in the schema and expensive to retrofit: a wrong
`key_hash` scheme invalidates every issued key, and an index over published
rows is a cost that grows forever.

**Trade-offs:**
- SHA-256 without a salt means two identical keys would hash identically — made
  harmless by keys being random and `UNIQUE(key_hash)` rejecting a collision.
- A partial index can be used only by queries whose `WHERE` implies
  `published = false`; the relay's query does, and nothing else reads this table.
- `JSONB` normalises key order and whitespace, so the stored text is not
  byte-identical to what was written. Nothing reads it as text.

**Extended when `api_keys` was written (2026-10-02).** Choosing SHA-256 settled
the algorithm but not its *written form*, and hex has two of them. Postgres
compares text exactly, so `A3F…` and `a3f…` are different strings: a key stored
one way and hashed the other on lookup is simply never found, the brand is told
its valid key is invalid, and `UNIQUE(key_hash)` never sees the duplicate — the
same shape as the case-sensitive email bug. `CHECK (key_hash ~ '^[0-9a-f]{64}$')`
pins it to 64 lowercase hex characters, and the same check rejects two worse
mistakes: a **raw unhashed key** (anyone reading the database could then use the
keys directly) and a **bcrypt hash**, which is 60 characters starting `$2a$` and,
being salted, could never be looked up by equality at all — so the wrong
algorithm fails at the first write instead of silently breaking every lookup.

**`revoked_at` added, and tied to `status`.** Keeping revoked rows was justified
as "the row is the audit trail", but `created_at` alone never says *when* a key
was revoked. `revoked_at` is NULL while `ACTIVE` and set on revocation, with
`CHECK ((status = 'ACTIVE' AND revoked_at IS NULL) OR (status = 'REVOKED' AND
revoked_at IS NOT NULL))` so the two can never disagree — a half-applied
revocation is rejected rather than stored.

**Several `ACTIVE` keys per brand are allowed**, deliberately: rotation means
issuing the new key, moving traffic, then revoking the old one, which is
impossible if a brand may hold only one. **No index on `brand_id`** — listing a
brand's keys is rare and the table is tiny; the hot path is the
`UNIQUE(key_hash)` lookup.

**Extended again when `outbox` was written (2026-10-02), on two points the
earlier entry got wrong by omission:**

- **`published` becomes `published_at TIMESTAMPTZ`, NULL meaning unsent.** A
  boolean answers *whether* a row went out but never *when*, so relay lag is
  invisible and the cleanup job has nothing to delete by. The partial index
  becomes `WHERE published_at IS NULL`. Same shape as `revoked_at` above: a
  nullable timestamp carries the flag *and* the time for the same storage.
- **`attempts` and `last_error` added.** The relay reads oldest-first, so a row
  that can never be published — an oversized payload, say — would be retried
  forever and silently, **holding up every row behind it**: stock never
  confirmed, no receipt sent, because of one bad row. `refunds` and
  `notification_log` both count attempts; the outbox had nothing. With a count
  and the error recorded, a persistently failing row can be set aside and
  looked at. The retry/threshold logic itself belongs to the outbox phase.

**Three properties of the relay, none of which a constraint can enforce:**

- **It must claim rows with `SELECT ... FOR UPDATE SKIP LOCKED`.** Running two
  relay instances is ordinary scaling, not an edge case; without a claim both
  read the same unsent rows and both publish them. `SKIP LOCKED` makes the
  second instance step over rows the first already holds rather than block.
- **Delivery is at-least-once, by construction.** A relay that crashes after
  publishing but before stamping `published_at` republishes on restart. That is
  unavoidable short of a distributed transaction between Postgres and Kafka,
  and it is precisely why every consumer in this design is idempotent. Nothing
  here may ever be described as exactly-once.
- **Ordering is not guaranteed and nothing depends on it.** `BIGSERIAL` ids are
  assigned before commit, so a transaction with a lower id can commit after one
  with a higher id, and the relay can therefore publish out of order. This is
  safe only because the contact-cache upsert compares timestamps rather than
  trusting arrival order (see "Out-of-order `BrandUpserted`/`UserUpserted`
  delivery"). If a future consumer ever needs order, that is a new decision.

**`aggregate_id` holds a brand id or a user id**, told apart by `event_type`.
No FK is possible, and any query by `aggregate_id` alone is wrong without
`event_type` beside it. A test asserts brand 7 and user 7 can coexist.

**Examined on its own and found not to be a data bug (2026-10-02).** The
payloads carry the *typed* id — `BrandUpserted {"brand_id": 12, …}`,
`UserUpserted {"user_id": 7, …}` — and Notification reads the payload, never
`aggregate_id`, so nothing today reads the ambiguous column. The mapping
`event_type → aggregate type` is many-to-one and always decidable, so the data
is unambiguous; what is real is a trap for a *future* query.

**Options considered, and the one rejected on principle:** an `aggregate_type`
column (`BRAND`/`USER`) is the obvious fix and the wrong one — it is **fully
derivable from `event_type`**, so it is a second copy of existing state that can
drift from the first, needing a `CHECK` tying them together to store nothing new.
That is exactly why `saga_state` was dropped. Globally unique ids shared between
`brands` and `users` would also work but change two built tables for a problem no
code has.

**Chosen, as the least change that makes the disambiguation real:**
`CHECK (event_type IN ('BrandUpserted','UserUpserted'))`. `event_type` was any
string up to 50 characters, and the entire interpretation of `aggregate_id`
rests on it: a typo (`BrandUpdated`, `brandupserted`) left `aggregate_id`
pointing at nothing identifiable *and* produced an event no consumer
recognises, both silently. Pinning the vocabulary makes "`event_type`
determines the aggregate type" guaranteed rather than conventional. One
`ALTER TABLE`, no new column, no entity change.

**Trade-off:** adding an event type now needs a migration. Proportionate here —
the system's event list was deliberately cut to four and each addition is a
documented decision, so the migration keeps that decision explicit rather than
letting a new event type appear by free-text. Order's outbox should take the
same constraint with its own vocabulary (`PaymentConfirmed`, `OrderCancelled`).

**The security rule no constraint can carry:** authentication looks up
`WHERE key_hash = ?`, and the revoked row is still present, so it **must** add
`AND status = 'ACTIVE'`. Without it a revoked key keeps working, which is the
one thing revocation exists to prevent. This is a property of the query, not of
the schema, so it is recorded here and belongs to the API phase.

**Consequences:**
- `users` reuses `bump_updated_at()` and the same email rules as `brands`.
- For the API phase, not the schema: a lost signup race must surface as "email
  already taken", not a 500; the JWT must say whether its subject is a brand or
  a user, since brand 7 and user 7 share an id; and published `outbox` rows
  accumulate, so they need a cleanup job in the outbox phase.

---

## Decision: Entity and migration conventions, applied to every service

**Context / Problem:**
Inventory's four entities and Identity's two were written one piece at a time,
and the same small choices were made repeatedly and implicitly — several of
them only because an earlier piece had been bitten. None was recorded, so
Order, Notification and the rest of Identity would have re-derived them, or
quietly differed. Written down once, as rules, with the failure each prevents.

**Chosen Approach:**
1. **Identity columns:** `BIGSERIAL` with `GenerationType.IDENTITY` — the
   database generates the id; the application never supplies one.
2. **Migrations:** hand-written Flyway SQL, one file per schema change, with
   `ddl-auto: validate`. A migration is **never edited once it has been applied
   or pushed**; a fix is a *new* version. That is why `flash_sale_items` is
   V3 *and* V4, `brands` has V4 backfilling a guard, and Inventory's time
   columns were fixed by V6 and not by editing V1/V3/V5. Flyway checksums every
   file, so an edit fails on any database that already ran it.
3. **Foreign keys:** a same-service FK is a real `REFERENCES` in the database
   but is modelled in Java as a plain `Long` column, not `@ManyToOne` — nothing
   yet needs object-graph navigation, and this avoids fetch-type and N+1
   decisions before there is a query to base them on. A cross-service id is a
   plain `Long` with no FK at all.
4. **Enums:** `@Enumerated(EnumType.STRING)` *and* a database `CHECK` listing the
   values. Ordinal storage saves 0 or 1, so reordering the enum later silently
   corrupts every stored row; the CHECK means a bad value is rejected by the
   database even if the application is wrong.
5. **Money:** `DECIMAL(12,2)` / `BigDecimal`, never floating point.
6. **Time:** `TIMESTAMPTZ` / `Instant` — see "Every time column is
   `TIMESTAMPTZ`/`Instant`", enforced by a guard test in every service.
7. **Constraint names are chosen, not generated:** `uk_<table>_<columns>`,
   `ck_<table>_<rule>`, `idx_<table>_<columns>`, `trg_<table>_<purpose>`, so a
   violation names the rule in the error. A Java `@UniqueConstraint` repeats the
   name so the mapping and the schema visibly agree.
8. **Lombok:** `@Getter @Setter @NoArgsConstructor`. `@AllArgsConstructor` is
   **omitted where a setter normalises** (`Brand`, `User` lowercase the email),
   because the constructor would let a caller bypass the setter.
9. **A database-filled column must be told to Hibernate.** Hibernate puts every
   insertable column into its `INSERT`, sending an explicit `NULL` for an unset
   field — and a column `DEFAULT` only fires when the column is *omitted*. So
   `created_at TIMESTAMPTZ NOT NULL DEFAULT now()` plus a plain `@Column` fails
   on the first save with a not-null violation. The options are
   `insertable = false` (the database fills it; add `@Generated` to read it
   back) or a Hibernate generator (`@CreationTimestamp`). `insertable = false`
   alone is wrong for a column that must change on UPDATE.
10. **What `validate` does not check:** it compares table, column and type. It
    does not check triggers, `CHECK`s, defaults or indexes. A trigger attached to
    one table and forgotten on the next boots cleanly and fails silently, so
    anything `validate` cannot see needs a test.
11. **A rule a constraint cannot express is a service-layer rule, recorded as an
    open item** — e.g. "frozen once the sale opens" — never silently dropped.
12. **Workflow:** one branch per piece; one focused commit whose message
    explains *why*; merge with `--no-ff` so the piece stays visible in history;
    delete the branch; never amend a published commit unless asked.

**Why:**
Each rule exists because breaking it was either observed or cheaply
demonstrable here: the `NULL`-over-`DEFAULT` failure, the case-sensitive unique
email, the zone-less comparison, the trigger `validate` cannot see.

**Trade-offs:**
- No `@ManyToOne` means joins and navigation are explicit queries later; revisit
  when a query needs a graph.
- Hand-written SQL is more work than inferred DDL and cannot be rolled back
  automatically (Flyway Community has no down-migrations).
- Conventions are not enforced by tooling, with one exception (the time-column
  guard test). Drift is caught by review and by the tests below.

**Consequences:**
Order, Notification and the rest of Identity follow these without re-deciding
them. A convention that proves wrong is changed here first.

---

## Decision: Database `CHECK` constraints are the last line of defence — each one documented against the guard that comes first

**Context / Problem:**
Several invariants are enforced twice, in application logic and in a `CHECK`,
and which is primary was never written down. Without that, a `CHECK` looks
redundant and gets removed, or looks sufficient and the application check gets
skipped.

**Chosen Approach and the full list:**

| Table | Constraint | Primary guard (the `CHECK` is the backstop) |
|---|---|---|
| `flash_sales` | `status IN ('ACTIVE','CANCELLED')` | the enum type |
| `flash_sale_items` | `available_stock >= 0` (floor) | `reserve`'s `WHERE available_stock >= qty` |
| `flash_sale_items` | `available_stock <= total_stock` (**ceiling**) | the reservation status flip `RESERVED → RELEASED` — **application logic only, so this CHECK is the only database-level guard** |
| `flash_sale_items` | `total_stock > 0` | creation-time validation (not built) |
| `flash_sale_items` | `sale_price >= 0` | creation-time validation (not built) |
| `flash_sale_items` | `UNIQUE(flash_sale_id, product_id)` | — |
| `stock_reservations` | `quantity > 0` | `400 INVALID_QUANTITY` |
| `stock_reservations` | status in the three states; `UNIQUE(order_id, flash_sale_item_id)` | the enum; idempotent reserve |
| `products` | `UNIQUE(brand_id, name)` | — |
| `notification_log` | `event_type`/`recipient_type`/`channel`/`status` vocabularies | **nothing** — a typo'd `recipient_type` would both mislabel the row and defeat the unique key that stops duplicate emails |
| `notification_log` | `sent_at` non-null iff `SENT`; a `SENT` row has a `recipient_address` | the sending code setting them together |
| `notification_log` | `attempts >= 0` | the retry worker's own increment |
| `brand_contacts`, `buyer_contacts` | email normalised, non-empty, an `@` | the publishing service's own guards — which a separate database cannot assume were applied |
| `brands`, `users` | email already lowercase and trimmed; `length(email) > 0`; an `@` with something on each side; `length(btrim(name)) > 0`; `UNIQUE(email)` | the entity's normalising setter; Bean Validation at the API |
| `api_keys` | `status IN ('ACTIVE','REVOKED')`; `UNIQUE(key_hash)` | the enum |
| `api_keys` | `key_hash ~ '^[0-9a-f]{64}$'` | **nothing** — the hashing code is the only other guard, and an uppercase hash or a bcrypt hash would otherwise fail silently at lookup time |
| `api_keys` | `revoked_at` NULL iff `status = 'ACTIVE'` | the revocation code setting both together |
| `outbox` | `attempts >= 0` | the relay's own increment |
| `outbox` | `length(btrim(event_type)) > 0` | the publishing code |
| `outbox` | `event_type IN ('BrandUpserted','UserUpserted')` | **nothing** — `aggregate_id` is interpretable only through `event_type`, and a typo'd name would otherwise be stored silently and consumed by no one |
| `outbox` | `payload_json` is `JSONB`, so invalid JSON is rejected at write time | the serializer |

**Two lessons recorded with it:**
- **The ceiling matters more than the floor.** The floor has two guards (`reserve`'s
  `WHERE`, then the `CHECK`). The ceiling has one application-level guard and
  the `CHECK`, so a duplicate release — a redelivered Kafka event, a bug — is
  caught only by the database.
- **A "value equals its own normalised form" check lets already-normalised junk
  through.** `email = lower(btrim(email))` asks "are you identical to your
  cleaned-up self?" An empty string is, because trimming it changes nothing, so
  `''` was insertable until `length(email) > 0` was added. A whitespace-only
  string was always rejected, but only because `btrim` turns it into `''`, which
  differs from the input.

**What a `CHECK` cannot do:** express "now" relative to the row at update time —
which is why "frozen once the sale opens" is a service-layer rule — or validate
an email properly. The `@` test is a minimal sanity gate, not validation; real
checking is Bean Validation plus a verification flow.

**Consequences:**
A `CHECK` is never removed because it "duplicates" application logic. Every new
`CHECK` is entered in this table with its primary guard, and is exercised by a
test that tries to violate it.

---

## Decision: Who stamps a time column depends on who compares it

**Context / Problem:**
Four time columns were stamped three different ways, which looked inconsistent
and was easy to "tidy" into one wrong rule. The difference is deliberate.

**Chosen Approach:**
Ask: *does anything compare this value across pods or services?*

| Pattern | Used for | Why |
|---|---|---|
| **Java clock** — `@CreationTimestamp` / `@UpdateTimestamp` | `products.created_at`, `updated_at` | Audit only; nothing compares them across pods, so a slightly skewed pod clock harms nothing |
| **Database default**, `insertable = false` | `stock_reservations.reserved_at` | The reconciler compares it against the database's `now()`; a pod clock could write a future value and hide a stale reservation |
| **Database trigger, strictly increasing** | `brands.updated_at`, `users.updated_at` | Notification discards any event whose `updated_at` is not newer than what it holds; a later change stamped earlier is lost silently |

All three are `TIMESTAMPTZ`/`Instant`. `flash_sales.start_time`/`end_time` are
written by the application (they are business data, not audit stamps) but are
`TIMESTAMPTZ` for the reason in the time-zone entry.

**Why `insertable = false` alone was not the fix for `products`:** it fixes the
INSERT but leaves the UPDATE broken, since Postgres never bumps `updated_at` by
itself. And with `@CreationTimestamp` the column must stay insertable, because
Hibernate generates the value and has to write it.

**Which database function (added 2026-11-05):** the table above fixes the
*clock*; this fixes the *function*.
- **Deadlines** (a time later compared to fire a timeout, such as
  `orders.expires_at`) use `clock_timestamp()`. `now()` is fixed when the
  transaction starts, so in a long transaction it gives a shorter window than
  promised.
- **Audit and ordering stamps** may use `now()`.
- **`stock_reservations.reserved_at` stays `now()`**: Inventory's reconciler
  compares hours-old rows, so the offset is noise.
- Reasoning and the `expires_at` case: "Order creation: retries, conflicts, deadlines and Inventory failures".

**Consequences:**
A new time column is placed in this table before it is written. Changing a row's
pattern is a real decision, not a tidy-up.

---

## Decision: Service scaffolding — fixed ports, one shared credential pair, a separate database per service, and deferred dependencies

> **Correction (Inventory API contract piece):** Validation is no longer
> deferred in Inventory: `spring-boot-starter-validation` was added when its
> DTOs were built. It needs no infrastructure, so the reason for deferring the
> others does not apply to it. The sibling services add it in their own API
> phase; until then their dependency sets differ from Inventory's by this one
> entry, on purpose.

**Context / Problem:**
Identity, Order and Notification were added beside Inventory. The choices made
while doing so were recorded only in `.env.example` and a commit message.

**Chosen Approach:**
1. **Ports:** identity `8081`, inventory `8082`, order `8083`, notification
   `8084`; `8080` reserved for the API Gateway. Every Spring Boot app defaults to
   8080, so without fixed ports only one service could run at a time.
2. **One `DB_USERNAME`/`DB_PASSWORD` pair for all four**, against one local
   Postgres instance, supplied through each IDE run configuration and never
   committed (`.env.example` documents the shape). A deliberate call for a
   portfolio project; **each service still owns a separate *database***, so the
   "services never share a schema" rule is intact.
3. **Same dependency set in all four:** webmvc, data-jpa, actuator, Postgres,
   Lombok, `spring-boot-starter-flyway` and `flyway-database-postgresql`. The
   library alone is not enough under Spring Boot 4 — see the Flyway entry.
4. **Deferred on purpose:** Kafka, OpenFeign, Resilience4j, Spring Security,
   Validation. Each belongs to a later phase, and adding one early makes Spring
   try to reach infrastructure that does not exist.
5. **YAML configuration** (`application.yml`), matching Inventory; generated
   `static/` and `templates/` folders are left in place — empty and harmless in
   JSON-API services.
6. **IntelliJ must be told about each Maven project.** A folder added from
   outside shows a plain class icon and a disabled run button until its
   `pom.xml` is added as a Maven project; the IDE saves that state lazily, so
   the `.idea` files can lag the window.

**Trade-offs:**
- A shared credential weakens isolation between services — accepted for a
  portfolio project; split into per-service variables only if the services ever
  get separate credentials.
- Ports are a convention in each `application.yml`, not enforced centrally.

**Consequences:**
The four services boot independently and can run together. The API Gateway
takes 8080 when it exists.

---

## Decision: Entity tests run against real Postgres, roll back, and must be shown to fail

**Context / Problem:**
Hibernate's `validate` proved insufficient the first time a mapping was valid on
paper and failed on a real save (the `NULL`-over-`DEFAULT` bug in `products`).
`mvn compile` proved insufficient the first time Flyway silently never ran.
Neither check exercises behaviour.

**Chosen Approach:**
1. **Persistence tests save into the real local Postgres inside a rolled-back
   transaction**, so nothing persists. Per entity: round-trip of every field;
   enums stored as text; generated and trigger-maintained columns populated; and
   every constraint tried and shown to *reject* a bad row, not merely to exist.
2. **A passing test is not evidence; a test shown to fail is.** Each guard is
   checked by deliberately breaking the thing it protects and confirming the
   right test fails and nothing else:

   | Broken on purpose | Failed, exactly |
   |---|---|
   | removed `insertable = false` from `reserved_at` | all five reservation tests |
   | trigger weakened to plain `clock_timestamp()` | the "row ahead of the clock" test |
   | `users` trigger dropped | the two `updated_at` tests |
   | `start_time` returned to zone-less | both time-zone tests, with the wrong answers |
   | a zone-less column added to `products` | the guard test, naming `products.promo_starts_at` |
   | the two `outbox` CHECKs dropped | exactly those two tests |
   | **the `outbox` partial index dropped** | **nothing — see below** |

   A mutation that changes a *column type* is too blunt to isolate anything:
   `validate` then fails and every test in the class errors at startup rather
   than the one guarding that property. Drop the constraint, not the type.

3. **Known gap, accepted and left as is: no test guards an index.** Dropping
   `idx_outbox_unpublished` broke no test, because the relay's query still
   returns the right rows — just by scanning the table instead of the index.
   Every assertion here is about *correctness*, and an index is a
   *performance* property, invisible at ten rows and painful at ten million.
   So a later change that removed the partial index, or replaced it with an
   ordinary one over every row, would leave the suite green. The same is true
   of every other index in the project. A test reading `pg_indexes` could close
   it; that is deliberately **not** built, because the cost it guards against
   is unmeasured until load testing, and the load tests are where an index
   regression should surface with real numbers rather than as an assertion
   about schema shape. Recorded so it is a known limit rather than a surprise.
4. **A control accompanies any test that asserts the absence of a bug.**
   `TimeZoneConsistencyTest` includes a zone-less temporary table asserting the
   bug *does* reproduce there, so the real assertions cannot pass for the wrong
   reason.
5. **Tests that depend on a database property use an adversarial input.** The
   trigger test seeds a row whose `updated_at` is an hour *ahead* of the clock;
   an ordinary row cannot distinguish "old + 1 µs" from "copy the clock".
6. **`NoZoneLessTimestampsTest` exists in all four services.** It reads
   `information_schema` and fails, naming the column, if any `public` column is
   `timestamp without time zone` (excluding Flyway's own table). It turns the
   `TIMESTAMPTZ` rule from a convention into a check, and applies to Order and
   Notification from their first migration.

**Trade-offs:**
- Tests need a running local Postgres and the credentials in the environment;
  there is no Testcontainers setup, so a test that leaked rows could disturb
  another. Rollback is what prevents that, and a check for leftover rows was run
  after each piece.
- The guard test reads the whole schema, so it also fails on a column added by
  an unrelated piece — which is its purpose.
- `validate`, triggers and defaults remain invisible to Hibernate; these tests
  are what see them.

**Consequences:**
Totals: Inventory 19 (17 entity and time-zone tests, the guard, a context test),
Identity 47 (45, the guard, a context test), Notification 29 (27, the guard, a
context test), Order 2. All
persistence behaviour above is *tested*, not load-tested or verified under
concurrency — no `reserve` exists to test.

---

## Decision: `notification_log` records what happened, not what is currently true — `recipient_address` and `last_error` added

**Context / Problem:**
The documented shape —
`notification_log(..., order_id, event_type, recipient_type, channel, status, attempts, next_retry_at, sent_at)`
— opened with `...`, the same stub that hid `quantity` on `stock_reservations`.
Reasoning through what the table is actually *for* turned up two genuine gaps
and two underspecified areas. The `...` itself was only `id`.

**Chosen Approach:**

1. **`recipient_address` added.** The row said a receipt was sent to the buyer
   but not **to which address** — that lives in `buyer_contacts`, which is
   *overwritten* whenever the buyer changes their email. So "I never got my
   receipt" was unanswerable: the log says `SENT`, and the only address on hand
   is the current one, which may not be the one used. A delivery log must record
   what happened, not what is now true. Written at send time. Nullable, because
   a send that never happened has no address, with
   `CHECK (status <> 'SENT' OR recipient_address IS NOT NULL)` — one-directional
   on purpose.
2. **`last_error` added**, matching `refunds` and `outbox`. Without it a row
   sits at `FAILED` after five attempts with no diagnosis, and — more
   importantly — "the mail server is down" and "I have no contact cached for
   this buyer yet" are *indistinguishable in the data* while calling for
   different responses.
3. **Every vocabulary pinned by a `CHECK`** (`event_type`, `recipient_type`,
   `channel`, `status`), as decided for `outbox.event_type`. Here it is
   load-bearing twice over: a typo'd `recipient_type` would log the buyer's
   receipt against the brand *and* defeat the unique key, so the duplicate it
   exists to stop would get through.
4. **`status` is `PENDING | SENT | FAILED | RETRYING`** — the documented
   "`... | FAILED | RETRYING`" never named the first two. A row is inserted
   `PENDING` before the send is attempted, so the unique key claims idempotency
   *before* any mail leaves.
5. **`CHECK ((status = 'SENT') = (sent_at IS NOT NULL))`** so the two can never
   disagree, the same shape as `api_keys.revoked_at`.
6. **Partial index on `(next_retry_at) WHERE status = 'FAILED'`.** The index
   list named only the unique key, yet the retry worker scans failures exactly
   as the refund worker does. Almost every row ends `SENT`, so a partial index
   stays small — the same reasoning as the outbox's.

**A missing contact is treated as an ordinary failed send.** `PaymentConfirmed`
can arrive before `UserUpserted`, leaving Notification without an address. The
row is written `FAILED` with `last_error` naming the cause and is picked up by
the existing retry, by which time the contact has usually landed. No separate
state and no new machinery, in keeping with this service being deliberately
minimal. **Accepted cost:** it consumes one of the five attempts, and a contact
that arrives after all five leaves the row permanently `FAILED`. Acceptable
because a missed courtesy email needs nobody to act — the same premise as the
no-escalation decision.

**Trade-offs:**
- Two columns beyond the documented schema, one of which (`recipient_address`)
  duplicates data that also exists in the contact cache — deliberately, because
  the cache is mutable and the log must not be.
- Adding an event type, channel or recipient type now needs a migration. Same
  trade as `outbox.event_type`, and proportionate for the same reason.

**Consequences:**
- The claim before sending (`status='FAILED' → 'RETRYING'`, rows-affected
  checked) is unchanged and remains a query-shape rule no constraint can carry.
  Without it two Notification instances on the same retry schedule double-send.
- Order's `notification`-adjacent tables are unaffected; Notification's two
  contact caches are still to be written.
- Verified: dropping the `sent_at`/`recipient_address`/`recipient_type` CHECKs
  failed exactly the four tests guarding them and nothing else.

---

## Decision: the contact caches are keyed by the *other* service's id, and the conditional upsert's strict `<` depends on Identity's trigger

**Context / Problem:**
`brand_contacts`/`buyer_contacts` exist so Notification never has to ask
Identity for an address at send time — Identity being down must not stop
receipts. Writing them raised three questions the schema line never answered:
what the key is, whether one table or two, and whether the documented
`WHERE updated_at < EXCLUDED.updated_at` is actually safe.

**Chosen Approach:**
1. **The primary key is the other service's id** (`brand_id`, `user_id`), with
   no surrogate — the project's first deliberate exception to
   `BIGSERIAL`/`IDENTITY`. There is exactly one cache row per brand, so the
   brand's own id *is* the natural key; a generated id would permit two rows
   for one brand unless a `UNIQUE(brand_id)` were added as well, which is
   strictly worse for the same result.
2. **Two tables, not one with a `type` column.** A single table would need
   `(type, id)` as its key, reintroducing exactly the ambiguity that
   `outbox.aggregate_id` has — brand 7 and user 7 are different things that
   share a number. Two tables make the confusion unrepresentable.
3. **The same email guards as `brands`/`users`** (normalised, non-empty, an
   `@` with something each side). Notification is a *separate database* and
   cannot assume an event honoured the publisher's rules; without them a
   malformed address sits in the cache until a send fails.
4. **No `received_at`.** "Has my consumer stopped?" is a monitoring question,
   and Kafka consumer lag answers it for every topic at once rather than for
   one table by query. Rejected as a column that duplicates a better tool.

**Why the strict `<` is correct, and what it rests on:**
An equal timestamp is *skipped*. That is right only because Identity's
`bump_updated_at` trigger makes `updated_at` strictly increasing **per row**:
two genuine changes can never carry the same timestamp, so equal always means
a redelivery. **This is the payoff for that trigger** — had `updated_at` been
allowed to repeat, `<` would silently drop a real change and `<=` would
reinstate the lost-update problem the guard exists to prevent.

Verified rather than argued: with the `WHERE`, a stale event arriving after a
newer one leaves the cache holding the newer address; with an unconditional
upsert, the same two events leave it holding the **old** one. A
changed-then-changed-back pair also lands correctly in either arrival order.

**Trade-offs and limits, both recorded rather than closed:**
- **The conditional upsert is a *query*, not a constraint.** Nothing in the
  schema stops a future consumer writing an unconditional upsert; the tests
  pin the statement's behaviour, not the consumer's choice of statement. Same
  class as the "auth must filter on `status='ACTIVE'`" rule.
- **Rows are simply absent for brands and buyers that existed before
  Notification was deployed**, because nothing re-sends their upsert events.
  The first receipt for such a brand fails, retries five times and stays
  failed. The cure is a **one-off backfill republish from Identity** at
  deployment — no column fixes it, and a synchronous fallback to Identity on a
  cache miss would undo the entire reason the cache exists. Belongs to the
  Kafka phase; recorded here so it is not discovered in production.

**Consequences:**
Notification Service's three tables are complete. The deployment backfill is
now an Open Item.

---

## Decision: The payment webhook finds its order by our order id, never by `payment_ref`; `payment_ref` gets a partial `UNIQUE`

> **Correction (2026-11-05, from "Day 6", points 3 and 4):** the unknown-order
> case below is decided: `404` plus a WARN, nothing written. The `PAID` CAS also
> matches the amount. Implemented and tested on Day 6. The text below is
> unchanged.

**Context / Problem:**
No document said how an incoming payment webhook locates its `orders` row. The
obvious key, `payment_ref`, is `NULL` whenever Order crashed after the gateway
accepted `initiate` but before the reference was saved. That is exactly the
window in which a webhook is most likely to arrive for an order that "has no
payment", so a lookup by `payment_ref` would fail where it matters most.

**Options Considered:**
1. **By `payment_ref`.** Fails in the crash window above.
2. **By our order id, echoed back by the gateway.** A primary-key lookup that
   always works, because Order created the id before any call went out.
3. **By the idempotency key.** Works, but it is a function of the order id, so
   it is option 2 with an extra column read.

**Chosen Approach:**
Option 2. The webhook carries our order reference (the order id). The handler
loads the order by id, then applies the unchanged one-payment invariant: CAS to
`PAID` only if `status='PENDING_PAYMENT' AND payment_ref=X`; already `PAID` with
`payment_ref=X` is a redelivery no-op; otherwise refund `X`, taking `X` from the
webhook. With `payment_ref` still `NULL` the CAS matches zero rows and the
payment is refunded: the designed safe path, not a new case.

`orders.payment_ref` also gets a **partial unique index**, `UNIQUE (payment_ref)
WHERE payment_ref IS NOT NULL` (`uk_orders_payment_ref`). It is a safety net,
not a lookup path: one payment must never belong to two orders, and this makes
that a database fact, matching `refunds.UNIQUE(payment_ref)`. A bug that tried
it would fail loudly instead of mixing two orders up.

**Why:**
The lookup must work in the one state where `payment_ref` is known to be
absent. The order id is the only reference Order always has.

**Trade-offs:**
- A new requirement on Razorpay Clone: payments and webhooks must carry our
  order reference. Razorpay's own payments carry an `order_id`, so this is
  realistic, but the Clone is unbuilt, so it is **designed, not verified**.
- One more index on `orders`, written once per `PENDING_PAYMENT` transition.

**Consequences:**
- A webhook naming an order id that does not exist cannot insert a `refunds`
  row (`refunds.order_id` is a real FK). How that case is handled is **not yet
  decided**; it belongs to the webhook-handler piece, and must alert and never
  guess.
- The test that exercises this must cover a webhook arriving while
  `payment_ref IS NULL`, and a second order trying to save an already-used
  `payment_ref`.

---

## Decision: Order Service's guards ship in its first migration, and the database clock stamps every time column that is compared

**Context / Problem:**
Two things were open for the Order tables, which do not exist yet: which
`CHECK`s to add, and which clock writes each time column. Inventory's V1–V6
predated its `CHECK` philosophy, so V7 had to backfill guards as a separate
migration. Adding a constraint to a table that may hold rows costs a new
version, a pre-flight for bad rows and extra tests.

**Chosen Approach:**
*Guards, all in Order's first migration:*
- `order_items.quantity > 0`.
- `product_name`, `unit_price` and `subtotal` are all `NULL` or all non-`NULL`
  (an item is either unpriced or fully priced), and
  `subtotal = unit_price * quantity` when filled.
- `unit_price >= 0`, `subtotal >= 0`, `orders.amount >= 0`.
- `refunds.amount > 0`, `refunds.attempts >= 0`, and a `CHECK` on
  `refunds.status` for `PENDING | PROCESSING | SUCCEEDED | NEEDS_MANUAL_REVIEW`.
- `outbox.event_type IN ('PaymentConfirmed','OrderCancelled')` and
  `outbox.attempts >= 0`.
- `orders.status` `CHECK` for the seven states, and `orders.expires_at NOT NULL`
  (a `NULL` deadline is never "less than now", so that order would be invisible
  to the expiry sweep and hold stock forever).
- The existing `CHECK (status NOT IN ('PENDING_PAYMENT','PAID') OR amount IS NOT NULL)`
  and the `UNIQUE`s in `architecture.md` §4.

*Clocks (the rule from "Who stamps a time column depends on who compares it"):*
- **Database clock, written inside the SQL:** `expires_at` (`now() + <interval>`,
  Java supplies only the duration), `refunds.claimed_at` and `next_retry_at`,
  `payment_reconciled_at`, `outbox.published_at`. `expires_at` has no column
  `DEFAULT`, because a default would hard-code a deadline.
- **Database default, `insertable = false` + `@Generated`:** every `created_at`.
- **Database trigger:** `updated_at` on `orders` and `refunds`. Order's CAS
  writes are raw SQL, which skips Hibernate's `@UpdateTimestamp`, so a Java-side
  `updated_at` would silently stop moving on every status change. The trigger
  function is the same body as Identity's `bump_updated_at`, created again in
  Order's own migration because a function lives in one database. A trigger
  cannot be forgotten by a new code path; `validate` cannot see it, so a test
  must (and must be shown to fail without it).

The deadline *values* (the `CREATED` deadline, the payment window, the sweep
interval, the refund claim lease) stay open until the Feign/Resilience4j
timeouts exist. This entry fixes only which clock writes them.

**Why:**
Every guard is cheapest before the table exists. Every compared time must come
from the one clock the sweeps compare it against, or two pods can disagree at
the boundary, the two-clocks problem again.

**Trade-offs:**
A longer first migration, and each guard needs a test shown to fail. A guard
that duplicates application logic is kept anyway (`CHECK`s are the last line of
defence).

**Consequences:**
A racing test is required for the `CREATED → PENDING_PAYMENT` statement against
the expiry sweep (status, `payment_ref` and `expires_at` in one CAS).

---

## Decision: Reserve contract revised — `outcome` replaces `success`/`reason`

> **Correction (from "`ITEM_NOT_IN_SALE` is a `200` outcome"):** the
> `404 { code: ITEM_NOT_FOUND }` row below is replaced by
> `200 { outcome: ITEM_NOT_IN_SALE }`, and `ErrorCode.ITEM_NOT_FOUND` no longer
> exists. Separately, a body that is not JSON now answers
> `415 { code: INVALID_REQUEST, message }`: the controller-scoped handler can
> see a `415`, because the mapping declares no `consumes`. Only `405` keeps
> Spring's default body.

**Context / Problem:**
"Inventory's `reserve` API contract" answered every case with
`{ success, reason }`. Building the HTTP layer exposed four gaps in it:

1. **A released reservation had no answer.** The order is cancelled, its
   reservation is flipped to `RELEASED`, and then a delayed retry of `reserve`
   for that order arrives. "Return the existing reservation" would hand a
   released hold back as a success. Falling through to the normal path would
   reserve fresh stock for an order that is already dead.
2. **Only `quantity` had an error code.** A missing `orderId` or a broken body
   had none.
3. **Two field combinations were meaningless but possible:** `success: false`
   alongside a `reservationId`, or `success: true` alongside a `reason`.
4. **Jackson 3 silently truncated `quantity`.** It accepts a JSON float where
   an int is expected and truncates it (`ACCEPT_FLOAT_AS_INT` is on by default
   in 3.1.5), so `{"quantity": 2.5}` would reserve 2 units and return `200`.

**Options Considered:**
1. Keep `success` + `reason` and add the missing values.
2. One `outcome` enum on every `200`, and a separate `{ code, message }` body on
   every non-`200`.

**Chosen Approach:**
Option 2.

| Situation | Response |
|---|---|
| Fresh reservation, or a replay of a held or confirmed one | `200 { outcome: RESERVED, reservationId, salePrice, brandId, productName }` |
| Not enough stock | `200 { outcome: OUT_OF_STOCK }` |
| Sale not live | `200 { outcome: SALE_NOT_LIVE }` |
| Replay of a `RELEASED` reservation, same quantity | `200 { outcome: RESERVATION_RELEASED }` |
| Replay with a different quantity, in any status | `409 { code: QUANTITY_MISMATCH, message }` |
| Any problem with `quantity`, including a fraction | `400 { code: INVALID_QUANTITY, message }` |
| Any other malformed field or body | `400 { code: INVALID_REQUEST, message }` |
| No such `(flashSaleId, productId)` | `404 { code: ITEM_NOT_FOUND, message }` |

- The four `RESERVED` fields are **omitted**, not `null`, on every other
  outcome. `salePrice` is a string with exactly two decimals in plain notation.
  A replay returns exactly the shape of a first call; nothing marks it as one.
- `message` is for logs only; callers branch on `code`.
- **Replay order:** find the row for `(orderId, item)` in any status → compare
  quantity → if `RELEASED`, answer `RESERVATION_RELEASED` → otherwise return
  the stored reservation. The liveness and stock gates are skipped.
- **Concurrent first calls** are specified by outcome only: the loser ends on
  the replay path, never a `500`, and its stock change does not survive. How
  that happens depends on the open write-path decision.
- `spring.jackson.deserialization.accept-float-as-int: false`, service-wide.
- **A product's name is frozen while a live sale references it.**

**Why:**
- `outcome` keeps the property the old entry kept `success` for: there is one
  field, always present, to branch on. Splitting the two response families
  into two types removes the meaningless combinations.
- **Business outcomes stay `200` for a second reason the old entry did not
  give.** Order calls `reserve` through Feign behind Resilience4j. Feign turns
  a non-2xx into an exception by default, and the circuit breaker counts
  exceptions as failures. During a drop, `OUT_OF_STOCK` is the most common
  answer. As a `4xx` it could open the breaker and fail healthy orders for an
  outcome that is not a fault. (A reasoned claim, **unverified** until the
  Feign/Resilience4j configuration exists.)
- **Quantity is checked before `RELEASED`** because a different quantity is a
  bug whatever state the row is in, and `409` is the code that alerts.
- **`RELEASED` never falls through to a new reservation.** A fresh hold for an
  order that is already dead would stay held until the late-reservation
  republish of `OrderCancelled` cleaned it up.
- **The name freeze has the same reason as the price freeze.** `productName`
  is returned on every replay. A rename between a first call and its retry
  would give one reservation two names, and Order snapshots whichever it
  receives. Renames when no live sale references the product stay allowed, as
  "Event payload shapes" accepts.
- **A unit count must never change without an error.** Truncating `2.5` to `2`
  is exactly what `INVALID_QUANTITY` exists to catch. The numeric string `"2"`
  is still accepted: it is unambiguous.

**Trade-offs:**
- Order has a fourth outcome to handle. What it does with
  `RESERVATION_RELEASED` mid-loop is not decided (Open Items).
- A released row asked for with a different quantity answers `409`, not
  `RESERVATION_RELEASED`: the alert takes precedence over the business
  outcome.
- A brand cannot correct a typo in a product's name while one of its sales is
  live. This is the same accepted cost as the frozen price.
- The float-to-int setting is service-wide, so it also applies to every
  future Inventory endpoint. That is intended.

**Consequences:**
- `architecture.md` §3 carries the contract and the reserve implementation
  rules, which are recorded, not built.
- "Inventory's `reserve` API contract" carries a Correction pointing here. Its
  evaluation order and its 200-vs-non-200 line stand.
- The contract is tested at the HTTP layer only (`@WebMvcTest`, mocked
  service). Reserve's logic, replay, liveness and concurrency are untested
  because they do not exist yet.
- Enforcing the name freeze is a service-layer rule, listed in Open Items next
  to "frozen once the sale opens".
- **Decided: all three reserve "no" outcomes (`OUT_OF_STOCK`, `SALE_NOT_LIVE`,
  `ITEM_NOT_IN_SALE`) end the order as `RESERVATION_FAILED`.** A state exists
  only if something downstream reacts differently to it, and every reserve
  failure gets the same reaction: release everything, send no email, tell the
  buyer in the reply. Which outcome it was goes in `failure_detail` (debug
  only) and in the buyer's reply. No new state, no migration. Not built: Order
  has no API yet.

---

## Decision: Per-item quantity cap of 5, enforced at the API, not the database

**Context / Problem:**
`reserve` accepted any positive `quantity`, so one order item could take a
whole sale's stock.

**Options Considered:**
1. Cap at the API only (`@Max` on `ReserveRequest.quantity`).
2. Cap at the API plus a `CHECK (quantity <= 5)` on `stock_reservations`.

**Chosen Approach:**
Option 1. `ReserveRequest.MAX_QUANTITY_PER_ITEM = 5`; a violation answers
`400 INVALID_QUANTITY`, like any other problem with `quantity`.

**Why:**
The cap is a business rule that may change. A `CHECK` is for something that
must never be false, such as `quantity > 0`. A cap in a `CHECK` would make a
policy change a migration, and would make old rows invalid if the cap were
ever lowered.

**Trade-offs:**
- Nothing below the API stops a raw-SQL write of 6. Accepted: the database
  guards what is always wrong, the API guards what is currently policy.
- The cap is per item per order, not per buyer (Open Items).

**Consequences:**
- Order must check the same cap before calling `reserve`, or a buyer's
  mistake shows up as an Inventory alert (Open Items).
- Tested at the HTTP layer: 5 reaches the service, 6 does not.

---

## Decision: `ITEM_NOT_IN_SALE` is a `200` outcome, not `404 ITEM_NOT_FOUND`

**Context / Problem:**
`reserve` answered a `(flashSaleId, productId)` with no sale item as
`404 ITEM_NOT_FOUND`, on the grounds that it "can only be a bug because Order
checks every item at creation". That check does not exist. Order cannot read
Inventory's database, and no endpoint answers "is this product in this sale?".
So a buyer or bot sending a product that is not in the sale would fire an alert
on every request. A `4xx` may also count against the circuit breaker in front
of Inventory: a reasoned claim, **unverified** until the Feign/Resilience4j
configuration exists.

**Options Considered:**
1. A `200` business outcome, `ITEM_NOT_IN_SALE`.
2. An Order-side pre-check through a new Inventory endpoint.
3. Keep the `404` and fix the wording.

**Chosen Approach:**
Option 1. `ReserveOutcome.ITEM_NOT_IN_SALE`, returned as `200` with only
`{ "outcome": ... }`, like `OUT_OF_STOCK`. Order cancels with
`RESERVATION_FAILED`; there is no new Order state. `ItemNotFoundException`,
its `404` mapping and `ErrorCode.ITEM_NOT_FOUND` are removed. Inventory logs a
WARN each time it returns this outcome (recorded as a rule; reserve logic is
not built).

**Why:**
- It is input a client controls, which is the line between the two families:
  outcomes a caller can legitimately produce are `200`.
- A pre-check (option 2) adds a call, a timeout budget and a failure mode, and
  is still racy for nothing: `reserve` must handle the case anyway.
- Option 3 leaves a bot able to page someone at will.

**Trade-offs:**
- A real Order bug that sends the wrong sale id no longer pages. It shows up as
  a rise in `RESERVATION_FAILED`, and the WARN log makes a spike visible. Real
  metrics wait for the observability phase.

**Consequences:**
- `QuantityMismatchException` is now the only exception `reserve` throws on
  purpose.
- The "only the outcome" contract test covers `ITEM_NOT_IN_SALE`
  automatically (it runs every non-`RESERVED` outcome).
- Order's handling is an open item, to confirm in Order's API piece.

---

## Decision: One live-or-paid order per buyer per sale

**Context / Problem:**
Nothing stopped one buyer from creating many orders for the same sale. Each
live order holds reserved stock until it pays or expires, so one buyer could
hold stock for the whole payment window, and could pay twice.

**Options Considered:**
1. A partial unique index on `(buyer_id, flash_sale_id)` for the live-or-paid
   statuses (`CREATED`, `PENDING_PAYMENT`, `PAID`).
2. A partial unique index on `PAID` only.
3. A check in code (read the buyer's orders, then insert).

**Chosen Approach:**
Option 1: `uk_orders_buyer_sale_live_or_paid`, migration V6. The four failure
statuses (`PAYMENT_FAILED`, `ORDER_EXPIRED`, `PAYMENT_UNAVAILABLE`,
`RESERVATION_FAILED`) are outside the rule, so a failed buyer can try again.
`UNIQUE(buyer_id, idempotency_key)` stays as it is.

**Why:**
- Two tabs submitted together both pass a code check, so only the database can
  decide which insert wins. This rules out option 3.
- With `PAID` only, a buyer could open many orders and hold stock for the whole
  payment window, and a second payment would be taken before the unique rule
  fired. This rules out option 2.
- A plain (non-partial) unique would block the retry after a failure.

**Trade-offs:**
- A buyer cannot start a new order while an unpaid one is open, until it pays
  or expires. The wait is bounded by the payment window (value still open).
- Order's API must turn the unique violation on insert into a `409` that
  returns the existing order, so the buyer can finish paying it.
- It is per `buyer_id`. One person with two accounts is not covered.

**Consequences:**
- V6 only; V1 to V5 are untouched. The orders table was empty when V6 was
  applied locally.
- Tested against real Postgres with native-SQL status changes, since
  production moves status with a raw compare-and-set. Shown to fail when the
  index is dropped (the 4 rejection tests) and when it is made non-partial
  (the 4 "slot freed" tests).
- Not tested: two threads racing the insert. That is the database's job and is
  not separately exercised.
- Other services need no similar rule: Inventory's per-order uniqueness is on
  `(order_id, item)`, and Identity and Notification hold no per-buyer orders.

---

## Decision: CAS-owned columns are insert-only in the entities (A1)

**Context / Problem:**
By default Hibernate's `UPDATE` writes every column, using the values the
entity loaded. Say the sweep wins a CAS and sets an order to `ORDER_EXPIRED`
while a request thread still holds that order as `CREATED`. If the thread then
saves an unrelated field (`failureDetail`), its `UPDATE` also writes
`status = 'CREATED'` and silently undoes the sweep. The same hole exists on
`refunds` for the worker's claim.

**Options Considered:**
1. `updatable = false` on the CAS-owned columns, plus `@DynamicUpdate`.
2. `@DynamicUpdate` alone (UPDATE names only changed columns).
3. No entity write at all: a CAS-only repository.
4. `@Version` (optimistic locking) on `orders` / `refunds`.

**Chosen Approach:**
Option 1 now, with option 3 as how the columns are written later (raw CAS
queries in a repository, not built in this piece).
- `Order`: `status`, `amount`, `payment_ref`, `expires_at`,
  `payment_reconciled_at`.
- `Refund`: `status`, `attempts`, `next_retry_at`, `claimed_at`, `last_error`
  (all written by the claim and the `claimed_at`-fenced finishing SQL).
  `order_id`, `payment_ref` and `amount` are untouched by this decision.
- `INSERT` still writes them. No migration change.

**Why:**
- Option 2 alone only helps while the entity never changes the column. One
  `setStatus()` on a stale entity writes the stale value again.
- Option 4 adds a second guard that fails after any concurrent write, not just
  a conflicting one; `Order`'s javadoc already rejects it for that reason.
- Option 3 alone is not enough while the entity can still write the column.
  Option 1 makes the database column writable only by the CAS.

**Trade-offs:**
- `updatable = false` ignores a setter silently after insert. Nothing warns
  the next developer, so tests guard it.
- `@DynamicUpdate` builds the UPDATE per flush, a small cost on a path that
  will rarely use it.

**Consequences:**
- Any later normal write to these columns must be a raw conditional `UPDATE`.
- `OrderCasOwnedColumnsTest` and `RefundCasOwnedColumnsTest` are **written, not
  run** (no Postgres on the machine they were written on).
- Break-and-restore is **not done yet**. With `@DynamicUpdate` present, the
  stale-save test alone does not fail when only `updatable = false` is removed
  (the stale status is not dirty, so it is not written). The silent-ignore test
  is the one that guards `updatable = false`.

**Amendment (2026-11-03, after the tests were run): `@DynamicUpdate` removed,
set-once columns locked.**
The two Consequences bullets above ("written, not run" and "break-and-restore
not done yet") are superseded by this amendment.
- The tests were run and break-and-restore was done. Of the six original guards,
  five were killed by a named test. The survivor was `@DynamicUpdate`: removing
  it failed nothing, because the columns left writable (`orders.buyer_id`,
  `flash_sale_id`, `idempotency_key`, `failure_detail`; `refunds.order_id`,
  `payment_ref`, `amount`) never change after insert, so it protected nothing.
  It is removed from `Order` and `Refund`.
- `buyer_id`, `flash_sale_id`, `idempotency_key` (`Order`) and `order_id`,
  `payment_ref`, `amount` (`Refund`) are now `updatable = false` as well. They
  are written once by `INSERT`. Left writable, a stale entity flushed for
  another field would rewrite them with stale values.
- After this, `Order.failure_detail` is the only column an entity `save()` can
  write. `Refund` has no writable column at all.
- Each of the six new `updatable = false` is killed by its own test (a setter on
  a managed entity, flush, native read-back, value unchanged). The option list
  above is unchanged; the chosen approach is now option 1 without
  `@DynamicUpdate`.

---

## Decision: Reserve write path: READ COMMITTED, one atomic conditional UPDATE, one short transaction

**Context / Problem:**
Thousands of buyers race for a small fixed stock on one hot `flash_sale_items`
row. Two entries specified the decrement incompatibly (`WHERE id = ? AND
version = ?` versus one statement checking `available_stock` and liveness
against the database clock), and the isolation level and locking strategy were
open. All of it had to be settled before `reserve` is built.

**Options Considered:**
1. READ COMMITTED with one atomic conditional `UPDATE` (the Postgres default).
2. REPEATABLE READ or SERIALIZABLE.
3. The `WHERE version = ?` form as the reserve path.
4. Pessimistic `FOR UPDATE`, a Redis lock, a Redis counter, or a queue.

**Chosen Approach:**
Option 1. **Status: implemented in `ReservationServiceImpl` /
`ReservationTransaction`, tested single-threaded and raced in a contention
test; load-measured on one laptop (first baseline only, not the 5,000-concurrent
target). See `architecture.md` §8 for what was and was not exercised.**
1. **Isolation:** READ COMMITTED (Postgres default, no config change).
2. **Decrement:** one native `UPDATE`:
   `SET available_stock = available_stock - :qty, version = version + 1
   WHERE id = :id AND available_stock >= :qty AND <liveness by DB clock>`.
   1 row = reserved. 0 rows = classify: not live first (`SALE_NOT_LIVE`), else
   `OUT_OF_STOCK`. The version bump is required by the raw-SQL rule and is
   free; `@Version` stays for entity saves.
3. **One short transaction:** replay check, then insert the reservation, then
   the stock `UPDATE` last, then commit. 0 rows rolls back the insert. No
   network call inside the transaction.
4. **Idempotency:** the replay check, then `UNIQUE(order_id,
   flash_sale_item_id)`. Two concurrent duplicates: the second waits on the
   unique index; if the first commits, the second gets a unique violation
   before touching stock; reserve catches it, re-reads in a fresh transaction
   and returns the stored result, never a 500.
5. **Clock:** `now()` (transaction start) decides liveness and `reserved_at`.
   Rule: live when the transaction began plus stock remaining means the buyer
   gets the unit. A purchase may commit after `end_time` by up to the lock
   wait, so the wait must be bounded by `lock_timeout`. This settles the clock
   choice for reserve; `clock_timestamp()` stays for Identity's trigger and
   Order's DB-stamped columns.
6. **Pool and timeouts:** small fixed pool; every wait bounded (connection
   wait, `lock_timeout`, `statement_timeout`); no infinite waits. No such
   config exists today (defaults). The numbers come from the load test, not
   picked, and feed Order's Feign timeout and the `CREATED` deadline.
7. **Not chosen:** pessimistic `FOR UPDATE`, Redis lock, Redis counter, queue,
   SERIALIZABLE. A Redis counter or queue in front is the fallback if the load
   test misses the NFR.
8. **Many instances:** correctness lives in the database (row lock, atomic
   `UPDATE`, unique key, DB clock), so it holds. Limits: the single hot row,
   and instances x pool size under Postgres `max_connections`.

**Why:**
- REPEATABLE READ / SERIALIZABLE: a frozen snapshot goes stale, and Postgres
  aborts a write to a row changed after the snapshot, so the hot row would
  give a retry storm.
- Under READ COMMITTED a blocked `UPDATE` re-checks its `WHERE` against the
  newly committed row, so no retry is needed. The `WHERE version = ?` form is
  rejected as the reserve path.

**Trade-offs:**
- "Insert first" in (3) is reasoning, **NOT measured**: the reservation FK to
  the hot `flash_sale_items` row takes a shared lock on the parent (it does not
  block the non-key `UPDATE`) and may add MultiXact overhead. The load test
  must compare insert-first vs update-first.
- A purchase may commit after `end_time` by up to the lock wait (see 5).

**Consequences:**
- First tests: two threads same order id; two buyers on the last unit;
  liveness boundary; replay after the sale ended; concurrent duplicate gives
  one decrement.
- Pool size and timeout values come from load testing (Open Items).
- Insert-first vs update-first is measured in the load test (Open Items).

---

## Decision: Order creation: retries, conflicts, deadlines and Inventory failures

**Context / Problem:**
Order's create endpoint is the first piece that has to behave when things go
wrong: a client retries after a timeout, two tabs submit together, the process
crashes mid-loop, Inventory is slow, or a reservation is released under the
loop's feet. The schema (V2 to V7) already has the guards
(`uk_orders_buyer_id_idempotency_key`, `uk_orders_buyer_sale_live_or_paid`, the
CAS rule). What was undecided is what the request does when a guard fires. This
closes the open items B2, B3 and B5, the two "retried create" items, the
`RESERVATION_RELEASED` item, the `CREATED` deadline, and the two CHECK items
from the Oct 29 review. All ten points below were settled with Akshansh before
writing.

**Options Considered:**
1. Unique violation: (a) SELECT before INSERT; (b) catch the violation and parse
   the constraint name; (c) catch any unique violation and re-query.
2. Retry on `RESERVATION_FAILED`: (a) re-run the loop; (b) replay the failure;
   (c) add a column holding the original outcome.
3. Retry on `CREATED`: (a) abort only (today's rule); (b) re-run the loop.
4. `CREATED` deadline: (a) a capped deadline alone; (b) no cap; (c) a cap on
   item count derived from a policy, plus a deadline derived from the timeouts.
5. Inventory failure mid-loop: (a) `ORDER_EXPIRED`; (b) `RESERVATION_FAILED`
   with a cause; (c) leave it `CREATED` for the sweep.
6. `expires_at` clock: `now()` or `clock_timestamp()`; JPA `save` or a native
   `INSERT ... RETURNING`.

**Chosen Approach:**

1. **Unique-violation losers.** Catch *any* unique violation on the order
   insert. The transaction is aborted by Postgres, so re-query in a **new**
   transaction. Never parse the constraint name.
   - Found by `(buyer_id, idempotency_key)`: it is a replay (see 2, 3, 5).
   - Else a live-or-paid order exists for `(buyer, sale)`: `409
     ORDER_ALREADY_OPEN`, returning the existing order. The message depends on
     its status; `PAID` means "you already bought this".
   - Else `409 CONFLICT_RETRY`: the client retries with the same key.
   - Rejected: SELECT before INSERT. The two indexes are the real guard; a
     code check passes for both of two simultaneous tabs.
2. **Retry on `RESERVATION_FAILED`.** Replay the failure with no reason. Never
   call Inventory again. A new attempt needs a new idempotency key. The replay
   body is less detailed than the first response (accepted). No extra column;
   `failure_detail` stays debug-only.
3. **Retry on `CREATED`: re-run the reserve loop.** This changes "recovery is
   abort" to "abort by expiry **or** resume on retry". It is safe only if:
   every write is a CAS or write-once and losing is not an error; the outbox row
   is written only when the CAS wins; the re-run happens only while the order is
   `CREATED` and before `expires_at`. It is a **hypothesis** until a two-thread
   test (a retry racing the original request, and the sweep) proves it.
4. **`RESERVATION_RELEASED` mid-loop.** Someone else already decided this
   order's fate. Stop the loop. No CAS, no event. Re-read the real status and
   report it. If it is still `CREATED`, alert; do not guess.
5. **Same key, different request: `409 IDEMPOTENCY_KEY_REUSED`.** Compare the
   request with the stored `orders.flash_sale_id` and `order_items`, merged and
   order-insensitive. No fingerprint column. This check runs **before** the
   replay rules in 2 and 3.
6. **The `expires_at` clock (B5).** The database stamps it with
   `clock_timestamp() + duration`, through a native `INSERT ... RETURNING id`
   (not a JPA `save`). Rule: **deadlines use `clock_timestamp()`; audit stamps
   may use `now()`; `reserved_at` stays `now()`** (the reconciler compares rows
   that are hours old, so the offset is noise there).
7. **The `CREATED` deadline.** The policy is "the longest a crashed order may
   block a buyer" = **60 s** (config). The per-call timeout to Inventory is
   **2 s, provisional**. So `maxLines = floor(60 / 2) = 30` (config), and
   `expires_at = clock_timestamp() + 2 s * lines + margin`, margin **5 s,
   provisional**. Day 5 multiplies by the retry attempts. Rejected: a capped
   deadline alone (it silently expires a big healthy order mid-loop) and no cap
   (the worst case is unbounded).
   - **Correction (2026-11-05): see "Day 5: payment step, resilience and derived
     deadlines".** The 2 s per call used here is the read timeout only; the
     client ceiling is connect 1 s + read 2 s = **3 s**. The Day 5 entry
     re-derives `maxLines` and the deadline. Text above kept as written.
   - Evidence for 2 s, from the Inventory load runs (one laptop, load tool
     co-located; runs (b) and (c) not repeated): 1,000 ramped buyers OK, p99
     13 / 89 ms; 5,000 buyers p99 9.4 to 11.6 s with refusals. Recheck on Day 9.
8. **Inventory timeout or refusal mid-loop.** In one transaction: CAS
   `CREATED -> RESERVATION_FAILED` and an outbox
   `OrderCancelled{RESERVATION_FAILED}`; `failure_detail` = the cause; respond
   `503 INVENTORY_UNAVAILABLE`. Not `ORDER_EXPIRED`: that label belongs to the
   sweep and emails the buyer (Notification's table), and the sweep does not
   exist until Day 7.
   **Known gap:** a timed-out call can still commit *after* the release and
   orphan a reservation. Covered only by the Day 7 republish and the reconciler,
   which is designed, **not built, not verified**.
   *(Correction / see Day 7: "Day 7: Inventory consumers, late-reservation
   cleanup, expiry sweep and its index", point 5. The republish covers only one
   of the two versions of this gap; the old text above is left as written.)*
9. **HTTP client.** Spring Cloud OpenFeign through the 2025.1.x release train
   (2025.1.2 states compatibility with Boot 4.0.7 and 4.1.0). Order is on Boot
   4.1.1 (`order-service/pom.xml`), so compatibility is **unverified until it
   resolves and starts**. Explicit connect and read timeouts; no reliance on
   defaults.
10. **V8 migration (V7 is taken)** adds two CHECKs: `refunds`: `status =
    'PROCESSING'` implies `claimed_at IS NOT NULL`; `orders`:
    `payment_reconciled_at` may be set only when `status` is `PAYMENT_FAILED`,
    `PAYMENT_UNAVAILABLE`, `ORDER_EXPIRED` or `RESERVATION_FAILED`. No payment
    can exist for `RESERVATION_FAILED`; it is included so the rule is "any
    failure state" and a later scan needs no migration.

**Why:**
- The indexes decide races; code can only react. Catching any violation
  survives an insert that breaks both indexes and a later constraint rename.
- Replaying a failure without calling Inventory keeps a retry from
  re-reserving stock for an order already ended.
- Resuming `CREATED` helps the buyer whose request timed out client-side: the
  retry finishes the order instead of waiting out the deadline while the
  one-live-order rule blocks them.
- The cap is a policy a human can read ("60 s"); the deadline is derived from
  the timeouts it races, as the standing deadlines rule requires.
- `now()` is fixed at the start of the transaction. In a long transaction it
  gives a deadline earlier than intended, so a healthy order gets a shorter
  window than it was promised.

**Trade-offs:**
- The `RESERVATION_FAILED` replay tells the buyer less than the first answer.
- Resume on retry adds a concurrency path that abort-only did not have.
- A 30-item cap and a 60 s policy are provisional numbers with a thin evidence
  base (one laptop).
- The orphan-reservation window in point 8 stays open until Day 7.

**Consequences:**
- **Amends** "`saga_state` is dropped" (its "Recovery here is abort" sentence)
  and the recorded-not-built resume note under `order_items` (architecture §4):
  a resume path now exists, still limited to rows `WHERE unit_price IS NULL`
  with write-once pricing `UPDATE`s.
- **Extends** architecture §6: `RESERVATION_FAILED` now also covers an
  Inventory timeout or refusal, not only the three reserve "no" outcomes. Still
  no new state, no migration. Its `failure_detail` says which.
- **Conflicts with V2's comment** on `expires_at` ("written as `now() +
  interval`"). V2 is applied and never edited; this entry wins, and
  architecture §4 is corrected. No schema change is needed for it.
- **Refines** "Deadlines and leases are derived": the `CREATED` deadline is now
  *provisionally* derived (values above); the refund claim lease, sweep
  interval and payment window stay open.
- Closes the open "two database clock functions" item (DECIDED 2026-11-05):
  the rule is now written into "Who stamps a time column depends on who
  compares it".
- V8 is applied against existing rows: check them first (CLAUDE.md
  Verification 4). Each CHECK needs a test that rejects a bad row.
- Tests required: the two-thread resume test (3); a retry after
  `RESERVATION_FAILED` that never reaches Inventory; the key-reuse check running
  before replay (5); a unique-violation loser re-querying in a new transaction
  (1); `expires_at` stamped by the DB (6).
- Status: all ten points are **implemented, tested (races mock Inventory)** (Day 4, `feat/day4-order-create`;
  Inventory is mocked in every race, and a JDK stub stands in for it in the
  Feign client test). Nothing here is load-tested or verified under failure
  against a real Inventory. The 2 s timeout is provisional.
- **Amendments (2026-11-05, found while implementing):**
  - A `RESERVED` answer without `salePrice` or `productName` is treated as
    Inventory unavailable (point 8): the order is cancelled, not guessed at.
  - `RESERVATION_RELEASED` while Order still says `CREATED` is `500
    INCONSISTENT_STATE` with no CAS and no outbox row (point 4); the Day 7
    reconciler owns repairing it.
  - Item `subtotal` is computed in SQL (`unit_price * quantity`), not in Java;
    V3's `CHECK` backstops it.

---

## Decision: Day 5: payment step, resilience and derived deadlines

**Status:** points 1 and 6 **implemented and tested** (`feat/day5-fee-deadline`,
merged); points 2 to 5 and the payment window (7) **implemented and tested
against a JDK HttpServer stub and mocks only** (`feat/day5-payment-step`): not
against the real payment-stub, not against a real gateway, not load-tested.
Points 8 and 9 are not built (sweep Day 7, refund worker stretch). **Update
2026-11-06:** points 2 to 5 were since also **verified under failure against
the real payment-stub on one laptop** (`docs/testing.md`, Day 5 failure demo);
point 10's stub is **built and tested** (`feat/day5-payment-stub`, and its
webhook sender on Day 6), so "Designed, not built" in point 10 and "not
started" under Consequences are out of date. Every number
below is **PROVISIONAL and unmeasured**; recheck on Day 9. The Razorpay Clone
does not exist, so no stub gives real latency data. Nothing here may be read as
verified (CLAUDE.md, Working style 7).

**Context / Problem:**
After the reserve loop, Order must start the payment (`initiate`), move the
order to `PENDING_PAYMENT`, and answer the buyer, all while the gateway can be
slow, down or wrong. Five things were open: what the buyer pays (and the
zero-amount case), what happens when `initiate` times out, the retry and
breaker numbers, the `CREATED` deadline once retries exist, and the three
leftover leases and windows (payment window, sweep interval, refund claim
lease). Day 4 derived the `CREATED` deadline with 2 s per reserve call, but the
Feign client's real ceiling per call is connect 1 s + read 2 s = 3 s.

**Options Considered:**
1. Fee: (a) none, so a free item gives a zero-amount payment; (b) flat fee on
   every order; (c) a `fee` column.
2. `initiate` times out: (a) retry with the same key, then fail the order;
   (b) a new "unknown outcome" state; (c) fail at once, no retry.
3. `CREATED` deadline: (a) keep 2 s per call; (b) 3 s per call and derive
   `maxLines`; (c) raise the 60 s policy to 90 s.
4. Payment window, sweep interval, refund lease: derive them, or decide them
   as policy.

**Chosen Approach (all PROVISIONAL):**

1. **Fee.** A flat **₹49.00 per order**, on every order, held in config as
   `BigDecimal`, crossing every boundary as a JSON string. `amount =
   SUM(subtotal) + fee`, written once in the existing status-guarded
   `setAmount` CAS. **No fee column, no migration:** fee = `amount −
   SUM(order_items.subtotal)`. Because the fee makes the payable amount > 0, the
   zero-amount item case is **closed** as a product rule. Free items stay allowed
   (`@PositiveOrZero` unchanged). An `amount > 0` assertion before `initiate` is
   added as a **bug guard only**, not a business rule.
2. **Timed-out `initiate`.** Retry with the same order-id-derived key (RZP-1).
   Retries exhausted or breaker open: CAS `CREATED → PAYMENT_UNAVAILABLE` and an
   `OrderCancelled{PAYMENT_UNAVAILABLE}` outbox row, **same transaction**. No new
   "unknown outcome" state. A payment that did exist is caught by the Day 6
   webhook handler (refund taken from the webhook's ref). The reconciliation
   sweep stays **designed only**.
3. **Retry.** 2 attempts, connect 1 s, read 2 s, fixed 500 ms wait. Worst case
   = 2 × (1 + 2) + 0.5 = **6.5 s**. Retry only timeouts and 5xx, **never 4xx**.
   No transaction is open across the call.
4. **Breaker (Resilience4j).** Count-based window 10, minimum 5 calls, 50%
   failure rate, open for 10 s, 3 half-open calls. Timeouts and 5xx count; 4xx do
   not. **No retry while open**; open means an instant failure.
5. **Buyer outcomes.**
   - Payment started and the CAS to `PENDING_PAYMENT` won: `201
     {orderId, status, amount, paymentRef, expiresAt}`.
   - Retries exhausted or breaker open: `503 PAYMENT_UNAVAILABLE` (retryable).
   - Gateway 4xx: `500 PAYMENT_REJECTED`, not retryable, ERROR log. The order
     still ends `PAYMENT_UNAVAILABLE` so the stock is released.
   - The `PENDING_PAYMENT` CAS loses: report the real state (e.g.
     `ORDER_EXPIRED`); the Day 6 webhook refunds any payment that exists.
   - Same key replayed while `PENDING_PAYMENT`: `200` with the same body. This
     **closes the open "replay status code" item**.
   - Same key while `PAYMENT_UNAVAILABLE`: replay the `503`. A new attempt needs
     a new key.
6. **`CREATED` deadline (option A).** `perReserveCall = connect + read = 3 s`.
   `maxLines = floor((60 − 6.5) / 3) = 17`. `expires_at = clock_timestamp() +
   3 s × lines + 6.5 s + 5 s margin`: 3 lines = **20.5 s**, 17 lines =
   **62.5 s**. `maxLines` and the deadline are **computed in code from config**
   (connect, read, attempts, wait, policy, margin), not typed in twice.
   Rejected: keep 2 s (repeats the gap), raise the policy to 90 s. The 62.5 s
   is above the 60 s policy only by the margin; the policy bounds the work, the
   margin is slack.
7. **Payment window: 6 min.** This is a **policy, not derivable**. Too short
   expires people who are still paying (a refund race with the webhook). Too
   long holds stock and blocks the buyer's one live order.
8. **Sweep interval: 30 s** (lag about 8% of the window). Real hold = window +
   interval. The sweep is built Day 7.
9. **Refund claim lease: 2 h**, set by business tolerance (refunds are not
   urgent). Code gives only a **lower bound**: lease ≫ (connect + read) ×
   attempts + waits. The finishing write is already fenced on `claimed_at`. Used
   only when the refund worker (stretch) exists.
10. **Payment stub: a separate small Spring Boot app on port 8085, its own
    process; not in-process.** It stands in for the Razorpay Clone until that
    exists. Being a real process over real HTTP means the connect and read
    timeouts, the retries and the breaker are actually exercised (an in-process
    fake would skip the network and never time out). It can be made slow or
    failing on purpose. Designed, not built; it is not the Razorpay Clone and
    proves none of RZP-1 to RZP-9 until it implements them.

**Why:**
- A flat fee closes the zero-amount case without a column, a migration or a
  gateway-specific rule; the amount stays derivable from rows that already exist.
- Reusing the order-id-derived key makes a retried `initiate` safe **if** the
  gateway honours RZP-1, which is designed, not verified. The webhook handler is
  the backstop when it does not.
- A deadline built from 3 s per call matches the client ceiling; the Day 4 value
  of 2 s ignored the connect timeout.
- Window and lease are business policy; pretending to derive them would be
  picking by feel with extra steps.

**Trade-offs:**
- All numbers are provisional, from a stub with no real latency.
- A buyer whose `initiate` timed out but succeeded at the gateway sees `503`,
  not a clean "unknown" state. The Day 6 webhook handler only **records** the
  refund (a `refunds` row). The money is paid back only if the stretch refund
  worker is built; until then it is recorded, not paid out.
- `maxLines` drops from 30 to 17 (the cap on distinct products per order).
- Real hold on stock = 6 min + 30 s; the buyer is blocked for that long.
- The fee is always derivable per order as `amount − SUM(order_items.subtotal)`,
  whatever the config says later. The only loss is a standalone fee-revenue
  query, which needs that subtraction instead of a column to sum.

**Consequences:**
- **Refines** "Deadlines and leases are derived": the `CREATED` deadline is
  re-derived (Day 4 point 7 is corrected, see the Correction there); payment
  window, sweep interval and refund lease are now **decided** (provisional).
  Pool size and DB timeout values stay open.
- **Closes** the zero-amount-order item and the replay-status-code item.
- `maxLines` 17 and the 3 s-per-call deadline are in the code since
  `feat/day5-fee-deadline` (the old "config still says 30" note is obsolete).
- Tests required when built: retry only on timeout/5xx; no retry on 4xx; breaker
  opens at the configured rate; CAS to `PAYMENT_UNAVAILABLE` and its outbox row
  in one transaction; the `PENDING_PAYMENT` CAS losing to the sweep; a replay in
  each state; `amount = SUM(subtotal) + fee` equals the stored amount; the
  deadline computed from config.
- **New port 8085** for the payment stub (planned; CLAUDE.md and architecture
  §8 ports lines updated). A new module to build; not started.
- Not verified: anything involving the real gateway (RZP-1 and the other
  Razorpay Clone behaviours). The stub only exercises our side's timeouts,
  retries and breaker.
- **Loser and replay rules (added 2026-11-05 with the payment step; they
  refine point 5):**
  - **201 means "this request won the PENDING_PAYMENT CAS"**, also for a
    resumed retry. Every other success is `200`.
  - The idempotency key sent to the gateway is `"order-" + orderId`, derived and
    never regenerated: the same on every attempt, every retried request and
    every resume after a crash.
  - **A payment-step CAS loses** (`PENDING_PAYMENT` or `PAYMENT_UNAVAILABLE`):
    re-read the order. `ORDER_EXPIRED` → `409 ORDER_EXPIRED`, message "Something
    went wrong. If any money was deducted, it will be refunded." (the refund is
    the Day 6 webhook's job; it is recorded, and paid out only if the stretch
    refund worker exists). `PENDING_PAYMENT` (a same-key request won) → `200`
    with the winner's body. Anything else → the existing mapping of the stored
    state. The loser writes nothing.
  - **Replays** (same key, after the same-request check): `PENDING_PAYMENT` →
    `200` with `paymentRef` and `expiresAt` read from the row; no gateway call.
    `PAYMENT_UNAVAILABLE` → the `503` again, with no reason, whatever the
    original cause was (a 4xx too: the status does not record which). `CREATED`
    before its deadline → resume: replay the reserves, the amount CAS is a
    no-op, initiate again with the same key, then the CAS.
  - `expiresAt` appears in the body only while `PENDING_PAYMENT` (it is the end
    of the payment window); `paymentRef` whenever it is set.
  - The breaker **ignores** a 4xx (neither failure nor success). A 2xx without
    a `paymentRef` is treated as `PAYMENT_UNAVAILABLE`, not retried
    (implementation choice, not in the original decision).

---

## Open Items (not yet decided or not yet built)

- **Accepted, permanently — not scheduled for fix.** A second captured payment
  can escape correction entirely, in two distinct shapes, if the initiate
  idempotency key ever fails to dedupe (against its own status as a verified
  requirement) *and* a webhook is separately lost:
  - **Both payments' webhooks lost:** the order expires normally into a
    terminal failure state, so the reconciliation sweep *does* examine it —
    but it asks the gateway what exists for *this order's* key, and a second
    payment would need a "list every payment for this order" query to surface,
    which was never built.
  - **One payment's webhook wins, the other is lost:** the order reaches
    `PAID`. The sweep **never examines `PAID` orders at all** — its whole
    premise is that a `PAID` order's one payment is applied by definition,
    which is false exactly here. This is the worse of the two shapes: nothing
    ever looks at this order again.

  Both require the same three independent failures to stack: idempotency
  failing on `initiate`, a webhook being lost (the subject of "Webhook-never-arrives
is closed by a reconciliation sweep"), and, in
  the second shape, webhook arrival landing in the specific order that
  produces `PAID` rather than expiry. Each is already managed on its own;
  stacking all of them is markedly rarer than any single one. The cost of
  building "list every payment for this order" plus teaching the sweep to
  also check `PAID` orders is judged not worth it against that likelihood.
  Accepted as a permanent, documented residual risk — this is the one place
  in the design that leans on prevention rather than independent correction.
- **Done — sequence diagrams, state diagrams and ERDs, in the Notion
  FlashMart page; checked against the code on 2026-10-22.** Both invalidated
  diagrams (TTL expiry, payment-failure compensation) were redrawn to the
  CAS-based flow; the reconciliation-sweep and partial-item-reservation-failure
  diagrams were added; all four ERDs were regenerated. Drawn as Mermaid blocks,
  which Notion renders natively. On 2026-10-22 they were updated for the
  implementation-phase reserve changes (item-in-sale check, replay quantity
  check, the quantity cap, `ITEM_NOT_IN_SALE` → `RESERVATION_FAILED`).
- **UPDATED: see "Day 5: payment step, resilience and derived deadlines". The deadline, payment window and retry/breaker values are implemented and tested (payment ones against a stub only); the sweep interval and refund lease are unused (sweep and refund worker not built).** The `CREATED` deadline is re-derived (3 s per call, 17 lines, 6.5 s retry ceiling); payment window (6 min), sweep interval (30 s) and refund lease (2 h) are decided, all provisional. Earlier text: The `CREATED` deadline was provisionally derived (60 s policy, 2 s per call, 5 s margin; see "Order creation: retries, conflicts, deadlines and Inventory failures"). Original text: **The two derived deadlines** — the short `CREATED` deadline and the refund
  stale-claim lease — plus the Order sweep interval and the payment window.
  Blocked on the Feign/Resilience4j timeout configuration by design, not by
  oversight.
- **DECIDED 2026-11-04: see "Reserve write path: READ COMMITTED, one atomic conditional UPDATE, one short transaction" (READ COMMITTED, one atomic conditional
  `UPDATE`; no pessimistic or Redis lock; a Redis counter or queue is the
  fallback if the load test misses the NFR). Original text kept below.**
  **Locking strategy for the hot `flash_sale_items` row** — optimistic alone,
  pessimistic, or Redis lock in front. To be decided and load-tested.
  **Paired with it, surfaced by the PRD review and not previously tracked
  here:** the transaction isolation level on the same write path. The two
  interact directly — optimistic locking's safety depends on what isolation
  level sits underneath it — so they should be decided together, not as two
  separate open questions.
- **DECIDED 2026-11-04 (this item, and the isolation-level half of the one
  above): see "Reserve write path: READ COMMITTED, one atomic conditional UPDATE, one short transaction". Original text kept below.**
  **The reserve write path is specified two different ways, and they are not
  equivalent.** "Optimistic-lock `version` column on `flash_sale_items`" says
  the decrement is `... WHERE id = ? AND version = ?`; "Inventory's `reserve`
  API contract" (steps 4–5) says liveness and the stock check/decrement happen
  in one statement against the database clock, which implies
  `... WHERE available_stock >= ? AND <liveness>`. Under READ COMMITTED —
  Postgres's default, and what the service currently runs — these behave very
  differently. When an `UPDATE` unblocks after a concurrent writer commits,
  Postgres re-evaluates the `WHERE` clause against the *newly committed* row
  rather than the blocked transaction's stale snapshot. A value-based clause
  therefore re-checks against fresh stock and cleanly matches zero rows when
  stock ran out — correct with no retry. A `version = ?` clause always fails
  after any concurrent write, turning every conflict into a retry, which is
  the opposite of what the 5,000-concurrent target needs. Must be settled
  before `reserve` is implemented; belongs with the locking-strategy and
  isolation-level items above, which it interacts with directly. Recorded, not
  decided — the strategy is to be load-tested, not assumed. A related trap:
  a native or `@Modifying` `UPDATE` does not bump `version`, so mixing one with
  an entity save of the same row can silently overwrite a concurrent decrement
  with a stale in-memory `available_stock`; any raw SQL must do
  `version = version + 1` itself.
- **Pool size and timeout values come from load testing.** Connection pool
  size, connection wait, `lock_timeout` and `statement_timeout` for reserve are
  not picked; they feed Order's Feign timeout and the `CREATED` deadline. See
  "Reserve write path: READ COMMITTED, one atomic conditional UPDATE, one short transaction".
- **Insert-first vs update-first in reserve is to be measured.** The load test
  must compare them. See "Reserve write path: READ COMMITTED, one atomic conditional UPDATE, one short transaction".
- **A backfill republish for the contact caches (designed, not built).**
  Nothing re-sends `BrandUpserted`/`UserUpserted` for brands and buyers that
  existed before Notification was deployed, so their cache rows are absent and
  their first receipt fails, retries five times and stays failed. Identity must
  republish an upsert event for every existing brand and user as a one-off
  deployment step. No schema change fixes it, and a synchronous fallback to
  Identity on a cache miss would undo the reason the cache exists. Kafka phase.
- **DECIDED 2026-11-05: see "Who stamps a time column depends on who compares
  it" and "Order creation: retries, conflicts, deadlines and Inventory failures". Original text kept below.**
  **Two database clock functions are in use for the same job (recorded, not
  decided; the reserve part is DECIDED 2026-11-04 in "Reserve write path: READ COMMITTED, one atomic conditional UPDATE, one short transaction": `now()` decides
  liveness and `reserved_at` for reserve, `clock_timestamp()` stays for
  Identity's trigger and Order's DB-stamped columns. Still open for any other
  "compared against `now()`" column, and for B5.)**
  `stock_reservations.reserved_at` defaults to `now()`, which is
  `transaction_timestamp()` — the moment the transaction *began*. Identity's
  `bump_updated_at` trigger and every DB-stamped column in Order use
  `clock_timestamp()`, the moment the *statement* runs. Neither is a bug, and
  each is defensible where it sits: a strictly increasing `updated_at` requires
  `clock_timestamp()`, because `now()` is constant within a transaction and two
  updates in one transaction would tie, which is exactly what the strict `<` in
  Notification's conditional upsert cannot survive.

  The gap is that "Who stamps a time column depends on who compares it" settles
  *which clock* (Java, DB default, DB trigger) but is silent on *which DB
  function*, so the choice has been made twice by accident rather than once on
  purpose. It has a real consequence for `reserved_at`, which the
  stale-reservation reconciler compares against the database's `now()`: under a
  long transaction, `now()` stamps a time earlier than the write actually
  happened, so the reservation reads as older than it is and becomes eligible
  for reconciliation sooner. Harmless at current transaction lengths and
  arguably the conservative direction, but it is a silent offset nobody chose.

  To settle before the next "compared against `now()`" column is added: pick
  the function deliberately and write it into the third column of that
  decision's table, so the next such column inherits a rule instead of a
  precedent.

  Noted alongside it, and **not** a defect: `products` uses
  `@CreationTimestamp`/`@UpdateTimestamp`, so its `DEFAULT now()` is
  unreachable from the application — Hibernate always sends an explicit value.
  That is correct under "Java clock for audit-only"; the default only matters
  to a raw-SQL insert, and is therefore untested.

  Found by the sibling sweep the Order schema piece ran, not by a failure.
- **Enforcing "frozen once the sale opens" (designed, not built).** Price,
  `total_stock`, `start_time`, `end_time` and cancellation are mutable until
  `start_time` and frozen after. A `CHECK` cannot express it (it would have to
  know "now" relative to the row's own `start_time` at update time), so it is a
  service-layer rule: reject any edit once `now >= start_time`, evaluated
  against the database clock in the same transaction as the write — otherwise
  an edit landing at the boundary races the first reservation.
- **Enforcing "a product's name is frozen while a live sale references it"
  (designed, not built).** Same shape as the item above. See "Reserve
  contract revised".
- **TODO: reject a sale whose start is in the past, at the API.** Put it on the
  create request only (on update it would hide the real "frozen" reason), with
  a fixed clock in tests. The authoritative rule stays the service-layer check
  against the DB clock.
- **CLOSED by decision ("Day 5", point 1); fee and `amount > 0` guard implemented and tested. Original text kept below.** **A zero-amount order is untested.** A free item is allowed (`sale_price >=
  0`, and the API's `@PositiveOrZero` matches), so an order can total `0.00`,
  and a payment gateway may refuse a zero-amount payment. Decide in the
  payment phase.
- **FR-B1 (buyer browse) has no endpoint.** It waits for security and
  caching. Any future stock hint is a coarse `soldOut` flag, never an exact
  count.
  **Correction (Day 8 docs pass):** browse is now *designed*: uncached, paged,
  with an `inStock` flag (not an exact count). It does not wait for caching.
  Still no endpoint (not built). See "Day 8: auth, gateway identity, rate
  limiting, browse", point 8. The "coarse flag, never an exact count" rule
  stands; the flag is named `inStock` there, not `soldOut`.
- **DECIDED 2026-11-05 (implemented, tested (races mock Inventory)): see "Order creation: retries, conflicts, deadlines and Inventory failures".** **Order's handling of `RESERVATION_RELEASED`** mid-loop: stop, no CAS, no event, re-read the real status.
- **Decided; implemented and tested (Day 4):** all three reserve "no" outcomes end the order as
  `RESERVATION_FAILED`. See "Reserve contract revised".
- **DECIDED 2026-11-05 (implemented, tested (races mock Inventory)): see "Order creation: retries, conflicts, deadlines and Inventory failures".** **For Order's API piece: what a retried create with the same idempotency key
  returns when the order is already `RESERVATION_FAILED`.** `failure_detail` is
  debug only, so the retry cannot read the original outcome from it. Not
  decided.
- **Bean Validation exists in Inventory and Order** (Order added it on Day 4).
  Identity and Notification add `spring-boot-starter-validation` in their own
  API phase (see the Correction on "Service scaffolding").
- **Done (Day 4), tested.** **Order's create-order request must reject `quantity > 5`** before any
  `reserve` call, so a buyer's mistake gets a `400` instead of firing an
  Inventory alert. See "Per-item quantity cap of 5".
- **DECIDED 2026-11-05 (implemented, tested (races mock Inventory)): see "Order creation: retries, conflicts, deadlines and Inventory failures".** **For Order's API piece: a unique violation on order insert must map to
  `409`** and return the existing live-or-paid order, so the buyer can finish
  paying it. Never a `500`. See "One live-or-paid order per buyer per sale".
- **A buyer cannot start a new order while an unpaid one is open**, until it
  pays or expires. The wait is bounded by the payment window (6 min, provisional,
  "Day 5" point 7; plus up to one sweep interval once the sweep exists).
- **One person with two accounts is not covered** by the one-live-order rule.
  The cap of 5 is also still per item per order.
- **DECIDED 2026-11-05 (implemented, tested (races mock Inventory)): see "Order creation: retries, conflicts, deadlines and Inventory failures".** **A retried create while the order is still `CREATED`** now re-runs the reserve loop (amends "abort").
  Original text: Recovery is abort ("`saga_state` is dropped"). If a resume path is ever
  added, it runs only over `order_items` rows `WHERE unit_price IS NULL`, and
  each item's pricing `UPDATE` is write-once (`WHERE unit_price IS NULL`).
- **Identity, API phase (designed, not built):** a lost signup race must read
  "email already taken", not a 500; the JWT must say whether its subject is a
  brand or a user; published `outbox` rows need a cleanup job. See "Identity's
  remaining tables". Since Day 6 Order's relay marks its own `outbox` rows
  published, so Order has the same unbuilt cleanup need (noted, not decided).
  **Correction (Day 8 docs pass):** the signup race and the JWT subject type are
  now *designed* (not built): "Day 8: auth, gateway identity, rate limiting,
  browse", points 2 and 6. The outbox cleanup job is still not decided.
- **Razorpay Clone is not built yet**, and three of its behaviours are
  requirements FlashMart's design leans on: idempotency keys honoured on
  `initiate`, idempotency keys honoured on `refund`, and a payment-status
  lookup endpoint — which must be queryable by *our* idempotency key or
  merchant order reference, not only by the gateway's own payment ID, since
  the reconciliation sweep has no payment ID to offer. A fourth: payments and
  their webhooks must carry **our order id**, since that is how the webhook
  finds its order (see "The payment webhook finds its order by our order id").
  These are specified in that project's own requirements and
  are **designed, not verified** — no FlashMart code or doc may treat them as
  proven. Correctness does not depend on them (the one-payment invariant and
  the already-refunded check cover the failure), but the duplicate-prevention
  and retry-safety *optimisations* do.
- **Found by independent review, Oct 29, not decided.** An independent static
  review of Order V1-V6 happened on 2026-10-29; the reviewer could not run the
  tests. What it found and V7 fixed is in §4 (V7). The items below were left
  alone on purpose, each waiting for a decision; each line says where it
  stands now (A2 and A3 are still undecided):
  - **A1:** entity updatability and `@DynamicUpdate`. **Decided** ("CAS-owned
    columns are insert-only in the entities (A1)"); implemented, run on
    2026-11-03, break-and-restore done, later amended.
  - **A2:** `refunds.next_retry_at` NULL handling and the refund claim query.
    Not decided. **Now live:** since Day 6 the webhook handler writes `PENDING`
    refund rows with `next_retry_at` NULL; whoever builds the refund worker must
    decide what NULL means before writing the claim query (a claim on
    `next_retry_at <= now()` alone would never pick these rows).
  - **A3:** redesign of the sweep and refund indexes. **Expiry-sweep part
    decided, implemented and tested (Day 7, V9)** in "Day 7: Inventory consumers, late-reservation
    cleanup, expiry sweep and its index", point 7. The refund-index part is
    still undecided.
  - **B1:** `ON CONFLICT DO NOTHING` for the refund insert. **Decided 2026-11-05
    (derived); implemented and tested on Day 6** ("Day 6: webhook, one-payment edge cases, PaymentConfirmed, outbox relay").
  - **B2, B3:** the retried create (and the `409` it returns) and a request
    fingerprint. **Decided 2026-11-05; implemented, tested (races mock Inventory)** ("Order creation: retries, conflicts, deadlines and Inventory failures"; no fingerprint column).
  - **B4:** a webhook arriving before the order is `PENDING_PAYMENT`.
    **Decided 2026-11-05: answer `409`, change nothing; implemented and tested on Day 6** ("Day 6: webhook, one-payment edge cases, PaymentConfirmed, outbox relay").
  - **B5:** which clock function stamps `expires_at`. **Decided 2026-11-05:**
    `clock_timestamp()`, DB-stamped ("Order creation: retries, conflicts, deadlines and Inventory failures"). Implemented and tested (Day 4).
  - A `CHECK` on `payment_reconciled_at`, and a `CHECK` that `PROCESSING`
    implies `claimed_at`. **Decided 2026-11-05; V8 implemented and tested.**
- **Test isolation (noted, not fixed):** 4 Order entity tests
  (`OrderPersistenceTest`, `OrderSchemaHardeningTest`, two in
  `OutboxEventPersistenceTest`) count absolute rows in the shared dev database,
  so they fail after a manual run leaves orders or outbox rows behind. Run
  `scripts/seed/seed.sh` before the suite until they count only their own rows.
- **New with Day 6 (recorded, none decided here; details in "Day 6: webhook,
  one-payment edge cases, PaymentConfirmed, outbox relay"):**
  - **OPEN:** `brand_contacts` and `BrandUpserted` have no consumer (no
    `brand_id` in `PaymentConfirmed`).
  - **Relay lock time unmeasured:** a slow broker that still acks can hold the
    claim transaction for up to batch x ack timeout (50 x 5 s); recheck Day 9.
  - **Duplicate observed** with Kafka paused (2 of 2 clean end-to-end runs);
    cause likely, not proven; consumers must be idempotent.
  - **Known edge:** wrong amount, then the same ref with the right amount, would
    leave a `PAID` order with a refund row for its own payment; the refund
    worker should check before paying out.

## Decision: Day 6: webhook, one-payment edge cases, PaymentConfirmed, outbox relay

**Status:** **DECIDED 2026-11-05.** **Update 2026-11-06:** points 1 to 5
implemented and tested on real Postgres, the webhook raced against a simulated
sweep (`feat/day6-webhook`); point 6 implemented and tested against an embedded
broker (`feat/day6-relay`); both run once end to end on real processes and the
docker-compose Kafka, with the payment-stub as gateway, on one laptop
(`docs/testing.md`, "Day 6 end to end"). Not load-tested, no consumer, not a real
gateway. See `architecture.md` §8. Every number in
point 6 is **PROVISIONAL and unmeasured**; recheck on Day 9. The Razorpay Clone
does not exist, so every behaviour asked of the gateway here is **designed, not
verified** (CLAUDE.md, Working style 7).

**Context / Problem:**
Day 5 left the webhook handler as the backstop for a payment that exists but
that Order never recorded. Writing it exposed six open edges: a webhook that
arrives while the order is still `CREATED` (B4); a `FAILED` webhook for a
payment other than the one the order holds; a `SUCCESS` webhook whose amount is
wrong; what the webhook looks like on the wire and how it is authenticated;
what `PaymentConfirmed` carries; and how the outbox row gets to Kafka. Also
derived here: the `refunds` insert uses `ON CONFLICT DO NOTHING` (B1), so a
redelivered webhook cannot insert a second refund row.

**Options Considered:**
1. Early webhook (order still `CREATED`): (a) answer `409`, change nothing, let
   the gateway retry; (b) refund the payment; (c) jump `CREATED → PAID`;
   (d) store the webhook and apply it later.
2. `FAILED` CAS: (a) match `status = 'PENDING_PAYMENT'` only; (b) also match
   `payment_ref`.
3. `SUCCESS` with a wrong amount: (a) ignore the amount; (b) also match the
   amount in the `PAID` CAS.
4. Webhook authentication: (a) HMAC over the raw body; (b) HMAC plus a
   timestamp window; (c) none (stub only).
5. `PaymentConfirmed` payload: (a) with `brand_id`; (b) without.
6. Relay: (a) set `published_at` before publishing; (b) publish, wait for ack,
   then set it; (c) a transactional Kafka producer.

**Chosen Approach:**

1. **Early webhook (B4): `409`, change nothing.** If the order is still
   `CREATED`, a `SUCCESS` (and equally a `FAILED`) webhook gets `409`. No write,
   no refund. The gateway retries, by which time Order has normally moved the
   order to `PENDING_PAYMENT`. Rejected: (b) refund it, because the buyer loses
   the unit on a timing blip (the webhook beat Order's own CAS by milliseconds);
   (c) `CREATED → PAID`, because it breaks the state machine; (d) store and apply
   later, because it is a new table for a rare race.
2. **`FAILED` CAS also matches `payment_ref`.** The CAS is `PENDING_PAYMENT →
   FAILED` only `WHERE status = 'PENDING_PAYMENT' AND payment_ref = X`, like
   `markPaid`. A `FAILED` for another payment `Y` must not fail an order that
   holds `X`. Such a webhook matches zero rows and is a no-op (nothing was
   captured).
3. **`markPaid` also matches the amount.** `... AND amount = :webhookAmount`,
   compared as numbers (`NUMERIC(14,2)`), never as strings, so `499` equals
   `499.00`. A mismatch loses the CAS and takes the existing "otherwise refund"
   path: the refund row uses the **webhook's** amount, the order stays
   `PENDING_PAYMENT`, and the sweep expires it. A WARN is logged.
4. **Webhook contract.** Body: `{order_id, payment_ref, status, amount}`, with
   `status` = `SUCCESS | FAILED` and `amount` a JSON string.
   - **Signature:** HMAC-SHA256 over the **raw body bytes**, checked **before**
     the JSON is parsed, compared in constant time. The secret comes from an env
     var (`PAYMENT_WEBHOOK_SECRET`, added to `.env.example`); the payment stub
     uses the same one.
   - **Codes:** `401` bad or missing signature; `400` malformed body; `404`
     unknown order (plus a WARN); `409` early (point 1); `200` **only after the
     transaction commits**.
   - **No timestamp window.** A replayed webhook is harmless because redelivery
     is already a no-op: the CAS matches nothing, and the refund insert is `ON
     CONFLICT DO NOTHING` (B1).
5. **`PaymentConfirmed` for now:** `{order_id, buyer_id, items[], amount}`, with
   items as in `architecture.md` §3. **No `brand_id`**: `orders` has no brand
   column, and only Notification (a stretch item) would use it. Adding an
   optional field later is safe under the flat-payload convention. The per-order
   brand email is therefore not needed.
6. **Outbox relay (all PROVISIONAL).** One topic per event: `payment-confirmed`
   and `order-cancelled`; **key = order id**, so one order's events keep their
   order within a partition. Producer: `acks=all`, idempotent. The relay
   **publishes, waits for the broker ack, and only then sets `published_at`**. A
   crash between the two means a duplicate, which consumers already tolerate
   (Kafka is at-least-once; consumers are idempotent by unique key and CAS).
   Claim with `FOR UPDATE SKIP LOCKED`; **stop the batch at the first failure**,
   so a failing pass holds the row locks for about one ack timeout; record
   `attempts` and `last_error`. *(Corrected 2026-11-06; the earlier wording said
   this "bounds the time the row locks are held to one ack timeout". That holds
   only once a send fails. A slow broker that still acks within the timeout can
   hold the claim transaction, its row locks and its pooled connection, for up to
   batch x ack timeout (50 x 5 s = 250 s). Unmeasured; recheck Day 9.)* Values: poll **1 s**, batch **50**, ack timeout
   **5 s**.

**Why:**
- A `409` is the cheapest correct answer to "too early": no state changes, and
  the gateway's own retry does the waiting.
- Matching `payment_ref` (point 2) and amount (point 3) in the CAS keeps the
  rule "every write to `orders.status` is a compare-and-set" and sends every
  mismatch down the one refund path that already exists.
- Checking the HMAC on raw bytes first means no parser runs on unauthenticated
  input, and re-serialising JSON cannot change the bytes being signed.
- Ack-then-mark trades a possible duplicate for never losing an event.

**Trade-offs:**
- Point 1 **depends on the gateway retrying a `409`**. The Razorpay Clone does
  not exist, so this is unverified; the payment stub must be built to retry. If
  the gateway gives up, nothing recovers the payment: the reconciliation sweep is
  designed only.
- Point 3: a gateway bug that sends wrong amounts would cause correct payments
  to be refunded. Accepted: refunding is safer than marking `PAID` on money we
  cannot match.
- No timestamp on the webhook (point 4) means a captured valid request can be
  replayed forever; accepted because replay changes nothing.
- Point 6: a duplicate `PaymentConfirmed` or `OrderCancelled` is expected, not a
  bug. One failed row stops the whole batch for that poll.
- **Observed (2026-11-05, end-to-end run, added 2026-11-06):** with Kafka paused,
  the same order's `PaymentConfirmed` was on the topic **twice in 2 of 2 clean
  runs** (offsets and timestamps in `docs/testing.md`, "Day 6 end to end"). Likely
  cause, consistent with Order's log but **not proven**: the paused broker
  appended the timed-out, client-cancelled request when it resumed, and the
  relay's re-send went out under a new producer epoch, which the idempotent
  producer does not deduplicate. No decision follows; it is the at-least-once
  case above, seen for real.
- **Known edge (added 2026-11-06, no decision):** a wrong-amount `SUCCESS`
  (point 3) leaves the order `PENDING_PAYMENT` with a `refunds` row for the ref
  it holds. If the same ref later arrived with the right amount, the `PAID` CAS
  would win while that refund row still exists. It needs a gateway that reports
  two amounts for one payment. The future refund worker should check that the
  order is not `PAID` with that ref before paying out. Not built, not tested.

**Consequences:**
- **Closes B4 and B1** in the open items (decided; since implemented and tested,
  see Status).
- `architecture.md` corrected: a late `FAILED` is guarded by `payment_ref`
  (it no longer "needs no such care"), and `PaymentConfirmed` has no `brand_id`
  for now.
- **OPEN, not decided here:** `brand_contacts` and `BrandUpserted` now have no
  consumer. Whether to drop them, keep them for the Notification stretch, or add
  `brand_id` back is a separate decision.
- Tests required when built: `409` on an early `SUCCESS` and an early `FAILED`
  with no row changed; a `FAILED` for another `payment_ref` leaves the order
  alone; a wrong amount loses the CAS and writes a refund row with the webhook
  amount; `499` equals `499.00`; a bad signature gives `401` before any parse;
  a tampered body gives `401`; redelivery of the same webhook is a no-op and adds
  no second refund row; two relays on one outbox never claim the same row at
  once; a crash after publish and before `published_at` gives a duplicate, not a
  loss.
- Not verified: anything involving the real gateway (retry on `409`, signing
  scheme, payments and webhooks carrying our order id).


## Decision: Day 7: Inventory consumers, late-reservation cleanup, expiry sweep and its index

**Status:** **decided; Inventory consumers, Order expiry sweep (with drain loop), V9 partial index and late-reservation republish implemented and tested; Inventory stale-reservation reconciler and reconciliation sweep designed only.** The reasoning below is the Day 7 design as written; what was built is in architecture.md §8. Every value that is a number or a timing is **provisional and
unmeasured**. The Razorpay Clone does not exist, so nothing here that touches
the gateway is verified.

**Context / Problem:**
Order already writes `OrderCancelled` and `PaymentConfirmed` outbox rows and the
relay sends them to Kafka. Nobody reads them yet. Day 7 needs three things:
Inventory must react to those events (give stock back, or confirm it); Order
must clean up when a reservation lands after the order is already dead; and
Order needs the expiry sweep that makes orders die at all. Each one races
something: a duplicate message, a reservation that commits late, a payment that
starts as the sweep picks the same order. The guards (`CHECK available_stock <=
total_stock`, the CAS rule, `uk_` keys) already exist; what was undecided is how
each consumer and the sweep use them.

**Options Considered:**
1. Release: (a) read the reservation in Java, then act; (b) add stock back
   first, then update the status; (c) update the status first, then add stock
   back from the rows that update returned.
2. Confirm when the guard matches nothing: (a) force the row back to
   `CONFIRMED`; (b) throw; (c) read the state, then stay quiet or log.
3. Offset commit: (a) commit before the work; (b) commit after the DB
   transaction; (c) Kafka transactions across broker and DB.
4. Failing messages: (a) retry forever; (b) skip bad payloads, retry transient
   errors; (c) add a dead-letter topic.
5. Late reservation: (a) re-read status and republish `OrderCancelled`; (b) a
   delayed second `OrderCancelled`; (c) Inventory asks Order on every reserve.
6. Expiry sweep: (a) one big `UPDATE ... RETURNING`; (b) select due ids, then
   one short transaction per order; (c) `FOR UPDATE SKIP LOCKED` on the select.
7. A3 index: (a) keep `idx_orders_expiry_sweep (status, expires_at)`; (b) a
   partial index on `(expires_at)` for live statuses only.

**Chosen Approach (all decided, not implemented):**

1. **Release (`OrderCancelled`).** Option (c), in one transaction:
   `UPDATE stock_reservations SET status = 'RELEASED' WHERE order_id = ? AND
   status = 'RESERVED' RETURNING id, flash_sale_item_id, quantity`. Then, for
   each returned row, add its quantity back to `available_stock` with `version =
   version + 1` (raw SQL does not bump `@Version` by itself). Rejected: (a) a
   Java read-then-act has a gap where two consumers both see `RESERVED`; (b)
   adding stock first can give stock back for a reservation that another
   consumer already released.
2. **Confirm (`PaymentConfirmed`).** Same guard: `RESERVED -> CONFIRMED`. If it
   matches 0 rows, read the state. All rows `CONFIRMED`: a quiet duplicate, do
   nothing. Any row `RELEASED`, or no rows at all: log ERROR with the order id
   and change nothing. Rejected: (a) forcing the row back to `CONFIRMED` would
   hide an oversell (stock was already given to someone else); (b) throwing
   would block the consumer on one bad order.
3. **Offset commit.** Auto-commit off. Acknowledge a message only after the DB
   transaction commits. Group id `inventory-service`. Rejected: (a) commit
   before the work, because a crash then loses a release and the stock stays
   stuck; (c) Kafka transactions across broker and DB. A duplicate is harmless
   by points 1 and 2. Kafka stays at-least-once; this never claims exactly-once.
4. **Failing messages.** A bad payload (broken JSON, missing `order_id`) is
   skipped at once, with an ERROR log holding the order id (if there is one) and
   the raw message. A transient error (for example the DB is down) is retried
   with growing backoff, and each failure is logged. **No dead-letter topic;
   recorded as a known limitation.** Cost: a skipped message leaves that order's
   stock `RESERVED` until someone fixes it by hand, because the stale-reservation
   reconciler is designed, not built. Backoff values are provisional and
   unmeasured.
5. **Late reservation, two versions.**
   - **Version A** (Order's loop gets `RESERVED`, but the order is already dead,
     so `priceItem` returns 0 and the status is a failure state): re-read the
     status and write a new `OrderCancelled` outbox row, only for
     `RESERVATION_FAILED`, `PAYMENT_UNAVAILABLE`, `ORDER_EXPIRED`,
     `PAYMENT_FAILED`. Never for `PAID` (the load-bearing rule). A duplicate
     `OrderCancelled` is harmless by point 1.
   - **Version B** (the Inventory call times out, Order cancels, Inventory
     commits the reservation later): Order never sees `RESERVED`, so the
     republish cannot see it either. **Accepted as a documented known gap, not
     fixed.** How often it happens is **not measured**. An earlier note called
     it "rare"; that was a guess. The repo's own 5,000-buyer run had p99 above 9
     s on one laptop, so it may cluster at peak load. Plan: after the Day 9 load
     test, count `RESERVED` rows in Inventory whose orders Order has cancelled
     (two databases, compare order ids by hand).
   - Rejected for now: (b) a delayed second `OrderCancelled`; (c) Inventory
     asking Order on every reserve.
   - A pointer ("Correction / see Day 7") was added under point 8 of "Order
     creation: retries, conflicts, deadlines and Inventory failures"; its old
     text is unchanged.
6. **Expiry sweep.** Runs every 30 s (already decided in "Day 5: payment step,
   resilience and derived deadlines"). It selects up to N due order ids with
   their status, where N is a provisional config value; leftovers wait for the
   next pass. Then **each order gets its own short transaction**: `expire(id,
   status)`, and write the `OrderCancelled{ORDER_EXPIRED}` outbox row **only if
   the CAS returned 1**. Rejected: (a) one big `UPDATE ... RETURNING`, because it
   skips the `expire` CAS that is already tested and raced. **No `SKIP LOCKED`:**
   if two instances pick the same order, one wins the CAS; the other gets 0 rows
   and writes nothing. If a payment starts just as the sweep picks a `CREATED`
   order, there is one row and one winner. If the sweep loses, it matches 0 rows.
   If the sweep wins, the payment CAS gets 0 rows, the request answers `409
   ORDER_EXPIRED`, and a later `SUCCESS` webhook becomes a `refunds` row.
   **Amendment (2026-11-06, while building; the points above are unchanged):**
   after a pass that picked a FULL batch, the tick runs another pass at once. It
   stops when a pass picks fewer than `batch-size`, or expires nothing (all
   failed or lost the CAS), and it is capped at `order.sweep.max-passes-per-tick`
   (provisional, unmeasured). Why: otherwise a backlog drains at about
   `batch-size` orders per interval, and stock would stay held well past
   "window + interval". Implemented and tested; not exercised on a real backlog.
7. **A3 index (expiry-sweep part).** A new **V9** migration creates a partial
   index on `(expires_at) WHERE status IN ('CREATED', 'PENDING_PAYMENT')`, then
   drops `idx_orders_expiry_sweep (status, expires_at)`. The applied V2 is never
   edited. The sweep `SELECT` must use those **literal** statuses, not a bind
   parameter, or Postgres will not use the partial index; verify with `EXPLAIN`
   when the query is written. `idx_orders_reconciliation_sweep` is untouched.
   A3 (expiry-sweep part) is marked decided in the open-items list.

**Why:**
- Under READ COMMITTED, a conditional `UPDATE` that matches the status decides
  the winner. A duplicate message matches 0 rows and adds 0 stock; a `CONFIRMED`
  row is skipped without extra code. The existing `CHECK available_stock <=
  total_stock` is the backstop if this reasoning is wrong.
- Inventory never guesses (point 2): when state is surprising, it logs and
  waits for a human.
- Acknowledging after commit means a crash costs a duplicate, never a lost
  release (point 3).
- One transaction per order (point 6) means a failure in one order cannot roll
  back the others, and every expiry goes through the one CAS that is already
  proven.
- The sweep only reads live orders and almost all rows are terminal, so a
  partial index holds only what the sweep reads (point 7).

**Trade-offs:**
- Point 4: a skipped message strands stock until fixed by hand. No dead-letter
  topic means the raw message exists only in the log.
- Point 5, Version B stays open. Stock for a late-committed reservation can be
  stuck `RESERVED` for a cancelled order until the reconciler exists.
- Point 6: without `SKIP LOCKED`, two instances do some duplicate reading. The
  CAS keeps it correct, but it is wasted work.
- Point 7: the gain (a smaller index over time) is **unmeasured**. Either index
  is fast at this size; this is a cheap change made now, not a measured fix.

**Consequences:**
- Inventory has no Kafka dependency and no consumer yet; `spring-kafka` must be
  added.
- Topics stay single-partition. Key = order id already keeps one order's events
  in order; more partitions would only help scaling. Noted as a limitation.
- Closes the expiry-sweep part of A3. The refund-index part and A2 stay open.
- **Correction (Day 8 docs pass):** this entry's "no Kafka dependency and no
  consumer yet" (first bullet above) and "Nothing here is implemented or tested"
  (next bullet) are the original design-time wording and are out of date. The
  Status line at the top of this entry is current: Inventory's consumers, the
  sweep (with drain loop), V9 and Version A of the republish are implemented and
  tested (`architecture.md` §8); the reconciler and the reconciliation sweep are
  designed only.
- **Nothing here is implemented or tested.** Tests required when built: a
  duplicate `OrderCancelled` adds no stock the second time; a `CONFIRMED`
  reservation is not released; a duplicate `PaymentConfirmed` is quiet; confirm
  on a `RELEASED` row logs ERROR and changes nothing; a bad payload is skipped
  and a transient error is retried; the republish fires for the four failure
  states and never for `PAID`; two sweep instances on one order write one outbox
  row; the sweep racing a payment start gives one winner; `EXPLAIN` shows the
  partial index in use.

## Decision: Day 8: auth, gateway identity, rate limiting, browse

**Status:** **DESIGNED, not built.** Nothing in this entry is implemented, tested,
load-tested or verified under failure. No gateway module, no Identity endpoint, no
Spring Security, no Redis use and no browse endpoint exists yet (`architecture.md`
§8 is the status source of truth). Every number (token lifetimes, rate limits, the
60 s header age, bcrypt cost, page size) is **provisional and unmeasured**; recheck
the rate limits on Day 9. Day 7 is the last built piece.

**Context / Problem:**
Today anyone can call Order with any buyer id: `POST /api/orders` reads a
temporary, forgeable `X-Buyer-Id` header (Day 4 TODO). Buyers cannot sign up or
log in (Identity has entities only, and the seeded users have placeholder hashes
that can never match). Nothing limits how fast one client can hit Order, and
FR-B1 (browse) has no endpoint. Day 8 has to answer four linked questions: who is
the caller, how does Order learn that safely, how fast may they call, and what may
a buyer see before they buy. Example: buyer 7 must not be able to place an order
as buyer 8 by typing `X-Buyer-Id: 8`; and one script must not be able to send
10,000 order requests a second just because it holds a valid login.

**Options Considered:**
1. Token signing: (a) HS256 with a shared secret; (b) RS256 with a key pair;
   (c) opaque session ids looked up in a store.
2. Token contents and lifetime: (a) one long-lived token; (b) a short access token
   plus a refresh token.
3. Refresh token storage: (a) Redis; (b) Postgres in Identity; (c) stateless
   (a long-lived JWT).
4. Refresh rotation: (a) none, reuse the same token for 7 days; (b) rotate with a
   compare-and-set, no grace window; (c) rotate with a short grace window.
5. How Order learns the buyer: (a) Order validates the JWT itself; (b) the Gateway
   validates it and forwards a plain `X-Buyer-Id`; (c) the Gateway validates it and
   forwards `X-Buyer-Id` plus a signature.
6. Passwords and signup: bcrypt cost, and how a lost signup race is reported.
7. Rate limiting: (a) Spring Cloud Gateway `RedisRateLimiter` (token bucket);
   (b) a hand-written Redis counter; (c) none until Day 9. And what happens when
   Redis is down: fail open or fail closed.
8. Browse: (a) offset paging; (b) cursor paging; (c) no paging with a cap;
   (d) cached or uncached.

**Chosen Approach (all decided, none built):**

1. **JWT, HS256.** The secret comes from the `JWT_SECRET` environment variable.
   Only Identity (signs) and the Gateway (verifies) hold it, and **both refuse to
   start if it is blank**, the same rule as `PAYMENT_WEBHOOK_SECRET`. Rejected:
   RS256, because the point of a key pair is that the verifier cannot sign, and
   here both parties are services we own and deploy together. Rejected: opaque
   session ids, because every request would need a store lookup.
2. **Lifetimes and claims.** Access token 15 minutes, refresh token 7 days, both
   config values. Claims are exactly `sub` (the id), `typ` (`user` or `brand`),
   `iat`, `exp` and nothing else. `typ` exists because brand 7 and user 7 share an
   id (they are separate tables). **Only `user` tokens are issued on Day 8**; the
   Gateway rejects any token whose `typ` is not `user` on `/api/orders/**`. Brand
   login is not designed here (brands and users cannot share a login endpoint;
   `architecture.md` §4, Identity).
3. **Refresh token = an opaque random value, stored as a hash only, in Postgres.**
   Identity migration **V8** adds `refresh_tokens(id, user_id, token_hash UNIQUE,
   expires_at, revoked_at, created_at)`, all times `TIMESTAMPTZ`. The database
   never holds a usable token, so a leaked table cannot be replayed. Postgres, not
   Redis: refresh is a cold path (once per 15 minutes per buyer), and what we need
   is durability and a revoke that commits in the same transaction as the new
   token, not speed. Cleaning up expired rows is **designed only** (no job, no
   decision on its schedule), the same open item as the outbox cleanup.
4. **Rotate every refresh token with a compare-and-set:**
   `UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = ? AND
   revoked_at IS NULL AND expires_at > now()`. **1 row** = this call won: issue a
   new access + refresh pair in the same transaction. **0 rows** = look the hash
   up: if the row exists and is already revoked, that token is being **reused**,
   so revoke **all** of that user's refresh tokens; if it is unknown or only
   expired, answer `401` and change nothing. **No grace window.** Endpoints:
   signup, login, refresh, logout (logout is the same CAS on the presented token;
   a repeated logout must be harmless, its exact response is chosen when built).
   **No access-token revocation:** an access token stays valid until its 15
   minutes end. Accepted: two browser tabs refreshing with the same token at the
   same moment look exactly like theft, so the loser's tab logs the buyer out
   everywhere. We chose a rule that is safe and sometimes annoying over a grace
   window that makes stolen-token reuse harder to see.
5. **The Gateway validates the JWT once, then signs the buyer id for Order.** It
   strips any client-supplied `X-Buyer-Id`, then forwards `X-Buyer-Id` plus an
   `HMAC-SHA256` over the buyer id and a timestamp (header names and the
   separator in the signed text are chosen when built; the signed text must have
   a delimiter, so buyer `1` at time `23` and buyer `12` at time `3` cannot
   produce the same input). The key is a **separate** secret,
   `GATEWAY_SIGNING_SECRET`, held only by the Gateway and Order, so a leak of the
   JWT secret does not let someone sign buyer ids and the reverse. Order answers
   `401` for a missing signature, a bad signature, or a timestamp older than 60 s,
   and **refuses to start with a blank secret**. The payment webhook keeps its own
   HMAC (`PAYMENT_WEBHOOK_SECRET`) and no JWT. `/internal/**` is never routed
   through the Gateway. **Limit, stated plainly:** Inventory (`:8082`) and Order
   (`:8083`) are still reachable directly on localhost, and Inventory has no auth
   at all, so this design protects the front door only. Anyone who can reach a
   service port directly can skip the Gateway (Order still rejects an unsigned
   request; Inventory does not). Closing it needs a network boundary, which is out
   of scope here. A signed header can also be replayed for up to 60 s.
6. **Passwords.** bcrypt through Spring's `DelegatingPasswordEncoder` (so the hash
   carries its own algorithm id and cost can change later), cost 10 (config),
   password length 8 to 72 **bytes** (bcrypt ignores everything past 72 bytes, so
   a longer password would silently be a shorter one). Email is trimmed and
   lowercased **before insert and before lookup**, which is what
   `ck_users_email_normalized` already requires. **Signup race:** two simultaneous
   signups with the same email: the loser's insert fails with SQLState `23505` on
   `uk_users_email`; map it to `409` "email already taken", never a `500`
   (the same catch-any-`23505` rule as Order's create). **Login** answers the same
   `401` for an unknown email and a wrong password, and for an unknown email it
   still checks the password against a dummy hash, so response time does not tell
   an attacker which emails exist. **Seeded users keep their unusable
   placeholder hashes**; they cannot log in.
7. **Rate limiting: `RedisRateLimiter` (a token bucket) in the Gateway.**
   `/api/orders/**` is limited **per buyer id** at **5 requests/s, burst 10**;
   `/api/auth/**` is limited **per client IP** at **1/s, burst 5**. The JWT filter
   runs **before** the limiter, because the order key is the buyer id read from
   the validated token. Browse is not limited. **If Redis is down the limiter
   fails open** (requests pass). Reason: the limiter is protection, not
   correctness; it does **not** prevent overselling, which the conditional
   `UPDATE` in Inventory guarantees on its own. The fail-open behaviour of the
   chosen limiter is a claim to be checked when built, not assumed. **Day 9
   trap:** the oversell load test (5,000 concurrent requests, 500 units) must use
   many different buyers, or a raised limit, or almost every request will be a
   `429` before it reaches Inventory and the test will measure the limiter.
8. **Browse: `GET /api/flash-sales?page=N`** in Inventory. `page` is 0-based and
   the page size is fixed at 10 (no earlier page-size cap exists in these docs to
   replace). Rows shown: not `CANCELLED` and `end_time > now()`, with `now()` from
   the database clock as in `reserve`, ordered by `start_time, id`. So a sale
   shows from creation until it ends: scheduled and live, never ended or
   cancelled. It reuses the existing `FlashSaleResponse`, `FlashSaleItemResponse`
   and `FlashSaleState`. Stock is a coarse **`inStock` flag, never an exact
   number**. `hasNext` comes from asking for 11 rows and returning 10, so there is
   no `COUNT(*)`. Items load in **one extra query** for the page's sales (not one
   per sale). A bad `page` (negative, not a number) is `400 INVALID_REQUEST`.
   **Uncached.** Rejected: caching, until a measurement says browse needs it.
   Accepted: offset paging can skip a sale. If a sale ends between a buyer's
   page 0 and page 1, every later row moves up one place and the first row of the
   next page is skipped. A cursor (`start_time, id` of the last row seen) is the
   fix at scale; not built, not needed at this size.

**Why:**
- The risk on the order path is a forged buyer id, so the Gateway checks the JWT
  once and Order checks only a cheap signature. Order never needs the JWT secret.
- Two secrets mean two separate blast radii. A leaked `JWT_SECRET` forges logins
  but cannot make Order trust a buyer id; a leaked `GATEWAY_SIGNING_SECRET` cannot
  mint a token.
- A refresh CAS makes "who used this token first" one atomic row update, the same
  idea as the order-status CAS: the loser knows it lost. Hashing means the table is
  not a list of working credentials.
- Failing open keeps buying possible during a Redis outage, which matters more than
  the limit. Overselling is stopped by the database, not the limiter.
- Browse is read-only and cheap at 10 rows; a cache would add an invalidation
  problem (a sale cancelled or ending must vanish at once) for no measured gain.

**Trade-offs:**
- HS256: the Gateway **can mint tokens**, not just check them. If the Gateway is
  compromised, so are all logins. Accepted: we own both sides.
- Point 4: the two-tab race logs the buyer out everywhere. Access tokens cannot be
  revoked early: a stolen access token works for up to 15 minutes.
- Point 5: `:8082` and `:8083` stay reachable directly; Inventory has no auth.
  Replay of a signed header works for 60 s.
- Point 7: the per-IP key on `/api/auth/**` is unfair on a shared network (one
  office or one mobile carrier can lock out its neighbours at 1 request/s). Fail
  open means a Redis outage removes all limits. The 5/s per buyer value is a guess.
- Point 8: offset paging can skip a sale (above). `inStock` hides how much is left.
- bcrypt at cost 10 on the signup/login path is deliberately slow; the cost in
  milliseconds on this laptop is **not measured**.

**Consequences:**
- New required environment variables: `JWT_SECRET` (Identity, Gateway) and
  `GATEWAY_SIGNING_SECRET` (Gateway, Order). No defaults, a blank value stops
  start-up. `.env.example` and `development.md` list them.
- Identity gets migration **V8** (`refresh_tokens`), the first signup/login/
  refresh/logout code, and Spring Security (or equivalent) for bcrypt; Identity
  still publishes `UserUpserted` on signup (existing rule).
- A new Gateway module on port `8080` (reserved until now). Spring Cloud Gateway
  and its Redis dependency are first used here.
- **Redis is used for the first time.** The compose Redis has never been started
  (`development.md`).
- Order drops the forgeable `X-Buyer-Id` as a trusted input: it will accept the
  header only with a valid signature.
- Inventory gains its first public read endpoint (`GET /api/flash-sales`).
- **Day 9 trap (also in point 7):** the oversell load test needs many buyers or a
  raised limit. Because seeded users cannot log in, the load test also needs real
  signups or a seed that writes real bcrypt hashes; not decided.
- Supersedes the "temporary, forgeable `X-Buyer-Id`" TODO (`architecture.md` §8,
  Day 4), the "FR-B1 waits for security and caching" open item and the Identity
  API-phase open item (Correction pointers added on each; their text is unchanged).
- **Limits of this design, in one list:** items 4, 5, 7 and 8 above; the HS256
  "Gateway can mint" property; the per-IP key being unfair on shared networks.
- Tests required when built are listed in `docs/testing.md`, "Day 8 planned proofs
  (NOT run)".

**DESIGNED, not built.**
