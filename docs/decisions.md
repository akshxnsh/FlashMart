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
  Open Items).

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
shape change to add it retroactively.

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

---

## Decision: Inventory's `reserve` API contract

> **Correction (from "Event payload shapes"):** two fields below are
> incomplete against a rule decided later. The response must also return
> `productName` — `order_items.product_name` is snapshotted from it, for
> Notification's receipt — and `salePrice` must be serialized as a **string**
> (`"3999.00"`), never a JSON number, per the money-as-string standing rule.
> Neither changes what this entry decided; the concrete shape was written
> before that rule existed.

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

## Open Items (not yet designed)

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
  failing on `initiate`, a webhook being lost (gap 1's whole subject), and, in
  the second shape, webhook arrival landing in the specific order that
  produces `PAID` rather than expiry. Each is already managed on its own;
  stacking all of them is markedly rarer than any single one. The cost of
  building "list every payment for this order" plus teaching the sweep to
  also check `PAID` orders is judged not worth it against that likelihood.
  Accepted as a permanent, documented residual risk — this is the one place
  in the design that leans on prevention rather than independent correction.
- **Done — all sequence diagrams and ERDs, in the Notion FlashMart page.**
  Both invalidated diagrams (TTL expiry, payment-failure compensation) were
  redrawn to match the current CAS-based flow; the reconciliation-sweep and
  partial-item-reservation-failure diagrams (never drawn before) now exist;
  all four ERDs were regenerated against the current schema. Drawn as Mermaid
  blocks (`sequenceDiagram`/`erDiagram`/`stateDiagram-v2`), which Notion
  renders natively, since no image-generation tool exists to reproduce the
  page's original pasted-image style.
- **The two derived deadlines** — the short `CREATED` deadline and the refund
  stale-claim lease — plus the Order sweep interval and the payment window.
  Blocked on the Feign/Resilience4j timeout configuration by design, not by
  oversight.
- **Locking strategy for the hot `flash_sale_items` row** — optimistic alone,
  pessimistic, or Redis lock in front. To be decided and load-tested.
  **Paired with it, surfaced by the PRD review and not previously tracked
  here:** the transaction isolation level on the same write path. The two
  interact directly — optimistic locking's safety depends on what isolation
  level sits underneath it — so they should be decided together, not as two
  separate open questions.
- **Razorpay Clone is not built yet**, and three of its behaviours are
  requirements FlashMart's design leans on: idempotency keys honoured on
  `initiate`, idempotency keys honoured on `refund`, and a payment-status
  lookup endpoint — which must be queryable by *our* idempotency key or
  merchant order reference, not only by the gateway's own payment ID, since
  the reconciliation sweep has no payment ID to offer. These are specified in that project's own requirements and
  are **designed, not verified** — no FlashMart code or doc may treat them as
  proven. Correctness does not depend on them (the one-payment invariant and
  the already-refunded check cover the failure), but the duplicate-prevention
  and retry-safety *optimisations* do.
