# FlashMart — Architectural Decisions

> **Naming note:** the service that owns brand/seller identity has been renamed
> twice: **Vendor Service → Brand Service** (product-vision pivot) → **Identity
> Service** (now also owns buyers). Entries written before those changes keep
> their original names to preserve history; notes on each entry point to what
> superseded it.
>
> **Status note:** everything here is *designed*, not implemented or tested,
> unless an entry says otherwise.

---

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

> **Note:** still valid, but incomplete as originally written. `stock_reservations`
> is Inventory's table, while `ORDER_EXPIRED` is Order's state, and Order cannot
> read Inventory's database. The sweep therefore runs inside Inventory and must
> tell Order about the expiry — see "Order expiry via a `ReservationExpired`
> event" below.

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
- `outbox` event payloads (`OrderCreated`, `PaymentConfirmed`, etc.) must carry
  the full item list, not a single product/quantity pair.
- A new sequence diagram is needed for partial-item-reservation-failure, which
  did not exist as a scenario under the single-item model.

---

## Decision: Reservation strategy for multi-item orders — all-or-nothing, via a sequential loop of per-item Feign calls

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
> below.

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
  Open Items).

---

## Decision: `UNIQUE(key_hash)` on `api_keys`, and index list per service

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

## Open Items (not yet designed)

- **Regenerate the four ERDs** from the issued prompts, then re-check them
  against this file.
- **Update the PRD**: drop `StockReserved`; add `ReservationExpired`,
  `BrandUpserted`, `UserUpserted`; Identity Service replaces Brand Service;
  three outboxes.
- **Event payload shapes** for `ReservationExpired`, `BrandUpserted`,
  `UserUpserted`, and the enriched `OrderCreated` / `PaymentConfirmed`.
- **Inventory `reserve` / `release` API contract**, including returning
  `brand_id` and translating the reservation UNIQUE violation into success.
- **Locking strategy for the hot `flash_sale_items` row** — optimistic alone,
  pessimistic, or Redis lock in front. To be decided and load-tested.
- **Mechanism that moves `flash_sales.status`** through
  `SCHEDULED → LIVE → ENDED`.
- **Partial-item-reservation-failure sequence diagram.**
- **TTL sweep interval.**
- **Webhook-never-arrives reconciliation** with Razorpay Clone.
- **Deliberately deferred:** out-of-order upsert events overwriting newer
  emails in the contact caches; retry tracking (`retry_count`,
`next_retry_at`) for `FAILED` notifications.