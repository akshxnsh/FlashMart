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
  different products, each with its own quantity, but all drawn from
  exactly one active flash sale.
- **Brand** *(formerly "Vendor")* — lists products, configures flash sales,
  and manages their own product/pricing/inventory data. Does not receive
  payouts through FlashMart — settlement is out of scope.
- **Platform/Admin** — owns cross-cutting concerns: order expiry (which
  releases the order's held stock) and the payment-webhook trust boundary. No longer owns commission or
  settlement (removed).

## 3. Functional Requirements

### Buyer
- FR-B1: The system shall let a buyer browse active and upcoming flash sales.
- FR-B2: The system shall let a buyer attempt a purchase only while the
  relevant sale is within its active time window.
- FR-B3: The system shall let a buyer place a single order containing
  multiple different products, each with its own quantity, all belonging
  to the same flash sale — not limited to one product per order. A buyer
  who wants items from two different sales must place two separate orders.
- FR-B4: The system shall let a buyer query the status of their order.
- FR-B5: The system shall notify a buyer of purchase confirmation or
  failure. Notification may be **synchronous or asynchronous** depending on
  whether the buyer is still awaiting a response: a failure determined while
  their request is open (e.g. out of stock, payment gateway unavailable) is
  communicated in that response, and no further message is required. A
  failure determined after they have gone (e.g. payment declined, order
  expired) must reach them out-of-band.

### Brand
- FR-BR1: The system shall let a brand onboard and authenticate.
- FR-BR2: The system shall let a brand list products.
- FR-BR3: The system shall let a brand create a flash sale with price,
  stock quantity, and start/end time.

### Platform
- FR-P1: The system shall expire any order that does not complete within
  its time limit, and release all stock held for it back to the pool.
  Stock must never be held indefinitely by an order that never reaches a
  successful outcome.
- FR-P2: The system shall verify the authenticity of inbound payment
  webhooks before acting on them.
- FR-P3: For a multi-item order, the system shall reserve stock for every
  item using an all-or-nothing strategy: if any item's reservation fails,
  any items already reserved for that order shall be released, and the
  order shall not proceed to payment.
- FR-P4: The system shall automatically refund any captured payment that
  cannot be applied to its order — either because the order already
  reached a terminal failure state before the payment arrived, or because
  a second payment was captured for an order that already has one. An
  order retains exactly one payment; every other captured payment for that
  order is refunded without buyer action.

## 4. Non-Functional Requirements — Correctness & Consistency

- NFR-C1: No combination of successful stock reservations for a given
  product within a flash sale may exceed the units put on sale for that
  product, regardless of concurrent request volume or how many different
  orders/order-items reference it. The invariant is per product-in-sale
  (`flash_sale_items`), not per sale as a whole: each product has its own
  independent stock pool, and the working counter (`available_stock`) must
  never drop below zero or exceed the original `total_stock`. This is a
  hard invariant, not a best-effort goal.
- NFR-C2: A retried order-creation or payment-initiation request (due to
  client timeout or network failure) must not result in a duplicate order
  or a duplicate charge — idempotency is enforced at the order level
  regardless of how many items the order contains. Where a duplicate
  charge is nonetheless captured by the payment gateway, it must be
  refunded automatically (FR-P4) rather than retained.
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
requirements assumed to be met by design alone. See PRD Section 8 for the
full metrics table.

- NFR-P1: Zero overselling incidents under 5,000 concurrent buy requests
  against 500 units of stock, for a single product within a flash sale
  (the unit the NFR-C1 invariant is defined over).
- NFR-P2: p99 latency on the buy endpoint documented under peak simulated
  load, target < 500ms.
- NFR-P3: Zero duplicate charges under simulated client retry storms.
- NFR-P4: 100% eventual webhook delivery, verified with induced failures.

## 6. Non-Functional Requirements — Resilience

- NFR-R1: The system shall degrade gracefully (fail fast rather than hang
  request threads) when the payment gateway is slow or unavailable.
- NFR-R2: Held stock shall be reliably released when its order expires,
  even if the buyer never returns, the client crashes, or the service
  handling the purchase dies mid-flow.

## 7. Constraints

- Each business service owns its own database; no service accesses another
  service's database directly.
- Payment is a real out-of-process HTTP integration (via OpenFeign to the
  Razorpay Clone service), not simulated in-process.
- All external client traffic enters exclusively through the API Gateway;
  no service is called directly by a client.
- A flash sale is fully mutable until it opens and immutable afterwards. Its
  per-product sale price, its start and end times, and its cancellation are
  all configurable by the brand (FR-BR3) only while the sale has not yet
  started. Once it is open, none of them can change: the price cannot be
  edited, the window cannot be moved, and the sale cannot be cancelled. A live
  sale always runs to its end time. The buyer-facing guarantee this creates —
  that an open sale's terms cannot shift while they are transacting against
  it — is the reason for the constraint; the cost, accepted deliberately, is
  that a brand has no remedy for a mistake discovered after opening.
- A product's name cannot change while a live sale includes it, for the same
  reason: a buyer's reservation and receipt must name what they actually
  reserved.
- A buyer may order at most 5 units of one product in one order. This is a
  per-item, per-order limit; a limit per buyer across orders is not a
  requirement today.

## 8. Out of Scope

- Vendor/brand settlement engine, platform commission calculation, and
  payout batching — permanently out of scope under the brand-platform
  vision, not merely deferred to a later phase.
- Independent third-party marketplace sellers — brands manage their own
  catalog directly; FlashMart does not aggregate unrelated sellers.
- A buyer-facing refunds workflow — returns, buyer-initiated
  cancellations, partial refunds, refund status UI — is out of scope.
  This is distinct from the *compensating* refund of FR-P4, which the
  system issues automatically to correct a payment it cannot apply; that
  path is in scope and is required for correctness, not a convenience
  feature.
- Card data and PCI-scoped storage of any kind. FlashMart never receives,
  stores, or transmits a buyer's card details — payment is completed
  entirely on Razorpay Clone's side, and FlashMart's only involvement is
  initiating the payment and receiving its result (§7). No vault, tokenization
  layer, or card-data handling of any kind belongs in this codebase.
- Anything beyond the PRD's Section 7 build plan — including multi-region
  deployment and fraud detection — is out of scope unless explicitly added
  later.

## 9. Razorpay Clone — Required Behaviors

Razorpay Clone is a separate project, not part of this repository, called by
Order Service via OpenFeign (§7). It is **not built yet**. The requirements
below are what FlashMart's design places on it — derived from
`docs/decisions.md`, not aspirational — and belong in Razorpay Clone's own
requirements doc, not just here. Until it exists and is tested against,
every item below is **designed, not verified**: FlashMart's code and docs
must not assume any of it is proven.

- RZP-1: The payment-initiation endpoint shall accept a caller-supplied
  idempotency key and, given the same key more than once, shall return the
  original payment rather than creating a new one. FlashMart derives this
  key deterministically from its own order ID (never regenerated per retry).
- RZP-2: The refund endpoint shall accept a caller-supplied idempotency key
  and, given the same key more than once, shall not issue a second refund
  for the same payment. FlashMart supplies its own internal refund-record ID
  as this key.
- RZP-3: The payment-status lookup endpoint shall be queryable by the
  caller's own idempotency key or merchant order reference — **not only by
  Razorpay Clone's own payment ID**. FlashMart uses this for two purposes
  that never have a payment ID to offer: confirming a payment has not
  already been refunded before issuing one, and its reconciliation sweep
  asking what payment, if any, exists for an order whose webhook never
  arrived.
- RZP-4: Payment initiation shall respond `202 Accepted` to confirm receipt
  only, never final outcome. The actual result (success or failure) shall
  be delivered exclusively via the webhook (RZP-5), never inferable from the
  initiation response itself.
- RZP-5: Payment-result webhooks shall be cryptographically signed, so the
  receiving service can verify authenticity before acting on them (FR-P2).
- RZP-6: Every payment-result webhook shall carry Razorpay Clone's own
  payment reference for the payment it describes. FlashMart's compensation
  logic keys off this field directly from the webhook payload, not from its
  own stored state, specifically to remain correct if its own record of that
  reference was never saved (e.g. a crash between initiating and persisting).
- RZP-7: Webhooks and status-lookup responses reporting a captured amount
  shall report the exact amount actually captured. FlashMart's refund
  records are seeded from this reported amount, not recomputed locally.
- RZP-8: All monetary values in requests and responses shall be exact
  decimal values, never floating-point-lossy — serialized as strings over
  JSON (`"3999.00"`, not `3999.00`), consistent with the rule FlashMart
  applies to every monetary field crossing its own service boundaries.
- RZP-9: Every payment and every payment-result webhook shall carry
  FlashMart's own order id. The webhook handler loads the order by that id,
  never by the gateway's payment reference, which FlashMart may not have saved
  yet in exactly the crash window a webhook is most likely to arrive in.

**Status (2026-11-06):** still **designed, not verified** for the Razorpay
Clone. The local `payment-stub` (not the Clone, not a real gateway) imitates
the shape of RZP-1, 4, 5, 6, 7, 8 and 9 (idempotent initiate keyed by
`Idempotency-Key`, `202` only, HMAC-SHA256-signed webhooks carrying our order id,
its payment ref and the amount as a string), so FlashMart's own side of those
contracts is tested against it (`architecture.md` §8). That proves nothing about
the Clone. The stub has no refund and no status lookup, so RZP-2 and RZP-3 are not
exercised at all.

**Correctness does not depend on RZP-1–3 being honoured** — if idempotency
ever fails to dedupe, FlashMart's one-payment invariant and already-refunded
check catch the failure independently (see "An order keeps exactly one
payment" and the accepted residual risk at the top of the Open Items in
`docs/decisions.md`). Prevention (RZP-1–3) and correction are deliberately
independent; RZP-4–9 are not optional in the same way, since FlashMart has no
fallback for a payment result it cannot trust, sign-verify, read the amount
of, or attach to its order.