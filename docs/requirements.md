# FlashMart — Requirements

## 1. Purpose

FlashMart is a flash-sale infrastructure platform for brands (e.g. Puma,
Nike) — not a multi-vendor marketplace — built primarily as a learning
project for backend engineering and distributed-systems patterns:
concurrency, consistency, and failure handling under real scarcity. Brands
run their own flash sales and manage their own products, pricing, and
inventory directly on the platform. Every requirement below exists because
it creates a genuine engineering problem worth solving, not because a
typical marketplace "should" have it.

> **Supersedes earlier framing:** FlashMart does not manage independent
> marketplace sellers, seller payouts, or vendor settlements. The vendor
> settlement engine, commission calculation, and payout batching described
> in the original PRD are explicitly out of scope (see Section 8).

## 2. Actors

- **Buyer** — browses flash sales and attempts to purchase limited-stock
  items during a live sale window. A single order may contain multiple
  different products, each with its own quantity, drawn from one or more
  active flash sales.
- **Brand** *(formerly "Vendor")* — lists products, configures flash sales,
  and manages their own product/pricing/inventory data. Does not receive
  payouts through FlashMart — settlement is out of scope.
- **Platform/Admin** — owns cross-cutting concerns: reservation expiry and
  the payment-webhook trust boundary. No longer owns commission or
  settlement (removed).

## 3. Functional Requirements

### Buyer
- FR-B1: The system shall let a buyer browse active and upcoming flash sales.
- FR-B2: The system shall let a buyer attempt a purchase only while the
  relevant sale is within its active time window.
- FR-B3: The system shall let a buyer place a single order containing
  multiple different products, each with its own quantity, potentially
  spanning multiple flash sales — not limited to one product per order.
- FR-B4: The system shall let a buyer query the status of their order.
- FR-B5: The system shall notify a buyer of purchase confirmation or
  failure.

### Brand
- FR-BR1: The system shall let a brand onboard and authenticate.
- FR-BR2: The system shall let a brand list products.
- FR-BR3: The system shall let a brand create a flash sale with price,
  stock quantity, and start/end time.

### Platform
- FR-P1: The system shall expire stock reservations that are not completed
  within their TTL and release the stock back to the pool.
- FR-P2: The system shall verify the authenticity of inbound payment
  webhooks before acting on them.
- FR-P3: For a multi-item order, the system shall reserve stock for every
  item using an all-or-nothing strategy: if any item's reservation fails,
  any items already reserved for that order shall be released, and the
  order shall not proceed to payment.

## 4. Non-Functional Requirements — Correctness & Consistency

- NFR-C1: No combination of successful stock reservations for a given
  flash sale may exceed that sale's `total_stock`, regardless of
  concurrent request volume or how many different orders/order-items
  reference it. This is a hard invariant, not a best-effort goal.
- NFR-C2: A retried order-creation or payment-initiation request (due to
  client timeout or network failure) must not result in a duplicate order
  or a duplicate charge — idempotency is enforced at the order level
  regardless of how many items the order contains.
- NFR-C3: The order, inventory, and payment state for a single purchase —
  including every item within a multi-item order — must reach a
  consistent terminal state even if any one participating service fails
  mid-flow, without relying on a distributed transaction (2PC) across
  services. This is what necessitates saga-style orchestration with
  compensating actions; it is not satisfied merely by using Kafka or
  Redis.
- NFR-C4: A multi-item order must never reach `PAID` while only a subset
  of its items were successfully reserved — partial reservation success
  must be resolved (via compensation, per FR-P3) before payment is ever
  attempted.

## 5. Non-Functional Requirements — Performance (TARGETS, unverified until load-tested)

These are targets to be measured via load testing (JMeter/Gatling), not
requirements assumed to be met by design alone. See PRD Section 7 for the
full metrics table.

- NFR-P1: Zero overselling incidents under 5,000 concurrent buy requests
  against 500 units of stock, for a single flash sale.
- NFR-P2: p99 latency on the buy endpoint documented under peak simulated
  load, target < 500ms.
- NFR-P3: Zero duplicate charges under simulated client retry storms.
- NFR-P4: 100% eventual webhook delivery, verified with induced failures.

## 6. Non-Functional Requirements — Resilience

- NFR-R1: The system shall degrade gracefully (fail fast rather than hang
  request threads) when the payment gateway is slow or unavailable.
- NFR-R2: A stock reservation shall reliably release its held stock on TTL
  expiry even if the reserving client never returns or crashes.

## 7. Constraints

- Each business service owns its own database; no service accesses another
  service's database directly.
- Payment is a real out-of-process HTTP integration (via OpenFeign to the
  Razorpay Clone service), not simulated in-process.
- All external client traffic enters exclusively through the API Gateway;
  no service is called directly by a client.

## 8. Out of Scope

- Vendor/brand settlement engine, platform commission calculation, and
  payout batching — permanently out of scope under the brand-platform
  vision, not merely deferred to a later phase.
- Independent third-party marketplace sellers — brands manage their own
  catalog directly; FlashMart does not aggregate unrelated sellers.
- Anything beyond the PRD's Section 6 build-plan phases 1–19 — including
  multi-region deployment, fraud detection, and a refunds workflow — is
  out of scope unless explicitly added later.