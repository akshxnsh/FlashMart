# Diagrams

Three diagrams: the architecture, the order state machine, and the purchase flow (happy path, then the failure branches). They were **derived from `architecture.md` sections 2, 3 and 6, then each arrow was checked against the code** (controllers, Feign clients, Kafka listeners, the `OrderStatus` enum and the six conditional `UPDATE` queries in `OrderRepository`, the Gateway routes, `monitoring/prometheus`). Where the code and the design differ, the diagram shows the code and says so.

**Legend.** A solid arrow is built. A **dashed grey box or dashed arrow marked "NOT BUILT"** is designed but not built. Nothing here has been checked by running it, apart from what `docs/testing.md` records for each piece.

Whether these Mermaid blocks render depends on your viewer (GitHub renders them). See the note at the end for what was and was not validated.

---

## (a) Architecture

Services, databases, Kafka, Redis and who calls whom. The thick split to remember: **everything under `/internal/**`, the payment initiate call, the metrics endpoints and Order's webhook default target are direct calls, never routed through the Gateway.**

```mermaid
flowchart TB
    BUYER([Buyer])

    subgraph EDGE["Edge"]
        GW["api-gateway :8080<br/>JWT check, signed buyer id,<br/>rate limiter (fails open)"]
        EUR["eureka-server :8761<br/>discovery for the Gateway only"]
        REDIS[("Redis<br/>rate-limit buckets")]
    end

    subgraph SVC["Business services (database per service)"]
        ID["identity-service :8081"]
        INV["inventory-service :8082"]
        ORD["order-service :8083"]
        NOT["notification-service :8084<br/>NOT BUILT: no consumers"]
        IDDB[("identity-service DB")]
        INVDB[("inventory-service DB")]
        ORDDB[("order-service DB<br/>orders, order_items,<br/>refunds, outbox")]
        NOTDB[("notification-service DB")]
    end

    PAY["payment-stub :8085<br/>stands in for the real gateway<br/>(Razorpay Clone, NOT BUILT)"]
    KAFKA{{"Kafka<br/>topics: payment-confirmed,<br/>order-cancelled"}}

    subgraph CFG["Configuration"]
        CS["config-server :8888"]
        CREPO[("GitHub repo<br/>flashmart-config")]
    end

    subgraph OBS["Observability"]
        PROM["Prometheus :9090"]
        GRAF["Grafana :3000"]
    end

    BUYER -->|"HTTPS"| GW
    GW -->|"/api/auth/**"| ID
    GW -->|"/api/flash-sales/**"| INV
    GW -->|"/api/orders/**<br/>and /webhooks/payment route"| ORD
    GW -.->|"token bucket"| REDIS
    GW -.->|"looks up lb:// routes"| EUR
    ID -.->|"registers"| EUR
    INV -.->|"registers"| EUR
    ORD -.->|"registers"| EUR

    ID --- IDDB
    INV --- INVDB
    ORD --- ORDDB
    NOT --- NOTDB

    ORD ==>|"Feign, fixed URL, per item:<br/>POST /internal/flash-sale-items/reserve"| INV
    ORD ==>|"Feign, fixed URL:<br/>POST /api/payments/initiate"| PAY
    PAY ==>|"signed webhook<br/>POST /webhooks/payment<br/>(default target is Order directly)"| ORD
    INV ==>|"reconciler, JDK HTTP client:<br/>GET /internal/orders/id/status"| ORD

    ORD -->|"outbox relay publishes"| KAFKA
    KAFKA -->|"consumers: confirm, release"| INV
    KAFKA -.->|"NOT BUILT"| NOT
    ID -.->|"NOT BUILT: outbox table exists,<br/>no events written"| KAFKA

    ORD -.->|"NOT BUILT: refund worker<br/>(refunds table exists)"| PAY

    ID -.->|"config at start"| CS
    INV -.->|"config at start"| CS
    ORD -.->|"config at start"| CS
    GW -.->|"config at start"| CS
    CS --> CREPO

    PROM -.->|"scrapes /actuator/prometheus<br/>on each own port, no auth"| GW
    PROM -.-> ID
    PROM -.-> INV
    PROM -.-> ORD
    GRAF --> PROM

    classDef notbuilt stroke-dasharray: 5 5,stroke:#888,color:#666;
    class NOT,NOTDB notbuilt;
```

How to read it:

- **Thick arrows are direct service-to-service calls.** Order's Feign clients use fixed URLs, not Eureka (the derived deadlines assume one attempt per call). Eureka is used by the Gateway only.
- **Nothing under `/internal/**` has a Gateway route** (checked in `api-gateway/src/main/resources/application.yml`; `ActuatorNotRoutedTest` pins `/internal` and actuator paths). The payment initiate call and the metrics endpoints are direct too.
- **The Gateway does have a `/webhooks/payment` route** (no JWT on it, the webhook has its own HMAC). The payment stub's default `ORDER_WEBHOOK_URL` skips the Gateway and posts straight to Order on `:8083`.
- **Config server values apply on restart.** Each client keeps a local fallback copy, so a config server that is down only costs a log line and a few seconds at start.
- **Not built:** Notification's Kafka consumers, Identity's events, the refund worker, the real payment gateway.

---

## (b) Order state machine

Seven statuses (`OrderStatus.java`): two transient, five terminal. **Every arrow is one `UPDATE orders SET status = ... WHERE id = ? AND status = <from state> ...` followed by a rows-affected check** (the six queries in `OrderRepository`). If the check returns 0 the caller lost the race and does nothing. Four writers race on this one row: the request thread, the expiry sweep, the webhook handler and the circuit-breaker path.

```mermaid
stateDiagram-v2
    [*] --> CREATED: order row inserted first,<br/>then the reserve loop

    CREATED --> PENDING_PAYMENT: all items RESERVED,<br/>payment initiated (202)<br/>WHERE status = CREATED
    CREATED --> RESERVATION_FAILED: OUT_OF_STOCK, SALE_NOT_LIVE,<br/>ITEM_NOT_IN_SALE, or Inventory timeout<br/>WHERE status = CREATED
    CREATED --> PAYMENT_UNAVAILABLE: breaker open, retries exhausted,<br/>or gateway 4xx<br/>WHERE status = CREATED
    CREATED --> ORDER_EXPIRED: expiry sweep, past its deadline<br/>WHERE status = CREATED

    PENDING_PAYMENT --> PAID: SUCCESS webhook for the held payment<br/>WHERE status = PENDING_PAYMENT<br/>AND payment_ref = X AND amount = webhook amount
    PENDING_PAYMENT --> PAYMENT_FAILED: FAILED webhook for the held payment<br/>WHERE status = PENDING_PAYMENT<br/>AND payment_ref = X
    PENDING_PAYMENT --> ORDER_EXPIRED: expiry sweep, payment window passed<br/>WHERE status = PENDING_PAYMENT

    PAID --> [*]
    PAYMENT_FAILED --> [*]
    ORDER_EXPIRED --> [*]
    PAYMENT_UNAVAILABLE --> [*]
    RESERVATION_FAILED --> [*]

    note right of PAID
        Terminal. Writes a PaymentConfirmed outbox row
        in the same transaction. Never cancelled.
    end note
    note right of ORDER_EXPIRED
        Terminal failure states write an OrderCancelled
        outbox row (reason = the state) in the same
        transaction. Inventory releases the stock later.
    end note
```

Things the diagram cannot show:

- **A late `SUCCESS` webhook for an order that is not `PENDING_PAYMENT`** (for example already `ORDER_EXPIRED`) does not change the status. Its CAS fails, and the handler writes a `refunds` row for the webhook's payment reference in the same transaction. The **refund worker that would send it is NOT BUILT.** An already-`PAID` order with the same `payment_ref` is a redelivery and is a no-op.
- **A webhook for an order still `CREATED`** is answered `409` with no write; the gateway's retry delivers it later.
- **`RESERVATION_RELEASED`** from Inventory makes Order stop without a CAS or event and report the real status.
- **`OrderCancelled` is never published for a `PAID` order.**

---

## (c) Purchase flow

### Happy path

```mermaid
sequenceDiagram
    autonumber
    actor B as Buyer
    participant G as api-gateway
    participant O as order-service
    participant I as inventory-service
    participant P as payment-stub
    participant K as Kafka

    B->>G: POST /api/orders<br/>JWT, Idempotency-Key, items
    G->>G: verify JWT, rate limit, sign buyer id
    G->>O: forward with X-Buyer-Id and signature
    O->>O: insert order CREATED and its items
    loop each item, one Feign call per item
        O->>I: POST /internal/flash-sale-items/reserve
        I->>I: one short transaction:<br/>conditional stock UPDATE on the DB clock,<br/>insert reservation (unique order+item)
        I-->>O: 200 outcome RESERVED
    end
    O->>P: POST /api/payments/initiate<br/>Idempotency-Key = order-id
    P-->>O: 202 with payment_ref
    O->>O: CAS CREATED to PENDING_PAYMENT
    O-->>B: 201 PENDING_PAYMENT
    P->>O: signed webhook SUCCESS (order id, payment_ref, amount)
    O->>O: verify HMAC, CAS PENDING_PAYMENT to PAID<br/>plus PaymentConfirmed outbox row, one transaction
    O-->>P: 200
    O->>K: outbox relay publishes payment-confirmed
    K->>I: consumer confirms the reservations (status-guarded)
```

### Failure branches

Each box is one branch. They run independently.

```mermaid
sequenceDiagram
    autonumber
    participant O as order-service
    participant I as inventory-service
    participant P as payment-stub
    participant K as Kafka
    participant S as expiry sweep (in Order)

    rect rgb(245, 245, 245)
    Note over O,I: 1. Reserve times out or is refused mid-loop
    O->>I: reserve (item 2)
    I--x O: no answer within the call timeout
    O->>O: CAS CREATED to RESERVATION_FAILED<br/>plus OrderCancelled outbox row
    O-->>O: buyer gets 503 INVENTORY_UNAVAILABLE
    O->>K: OrderCancelled
    K->>I: release every reservation of the order
    Note over O,I: Version A: Inventory commits late, Order's loop sees the order is dead,<br/>Order republishes OrderCancelled (terminal failure states only).<br/>Version B: the call timed out but Inventory committed anyway.<br/>The reconciler (branch 4) closes it.
    end

    rect rgb(245, 245, 245)
    Note over O,P: 2. Payment unavailable
    O->>P: initiate (retries, then the circuit breaker opens)
    P--x O: error or no answer
    O->>O: CAS CREATED to PAYMENT_UNAVAILABLE<br/>plus OrderCancelled outbox row
    O->>K: OrderCancelled
    K->>I: release
    end

    rect rgb(245, 245, 245)
    Note over O,P: 3. Webhook arrives after the order expired
    S->>O: CAS PENDING_PAYMENT to ORDER_EXPIRED<br/>plus OrderCancelled outbox row
    O->>K: OrderCancelled
    K->>I: release
    P->>O: signed webhook SUCCESS for the same order
    O->>O: CAS to PAID fails (rows affected 0)<br/>write a refunds row for the webhook's payment_ref, same transaction
    O-->>P: 200
    Note over O,P: NOT BUILT: the refund worker that sends the refund.<br/>NOT BUILT: the payment reconciliation sweep for a lost webhook.
    end

    rect rgb(245, 245, 245)
    Note over I,O: 4. Reconciler (Inventory asks Order, never guesses)
    I->>I: every 60 s: RESERVED rows older than the 570.5 s threshold
    I->>O: GET /internal/orders/id/status
    alt PAID
        O-->>I: PAID
        I->>I: confirm the reservation
    else a terminal failure state
        O-->>I: RESERVATION_FAILED, PAYMENT_FAILED, ORDER_EXPIRED or PAYMENT_UNAVAILABLE
        I->>I: release the reservation (status-guarded)
    else CREATED, PENDING_PAYMENT, unknown status, 404 or Order unreachable
        O-->>I: anything else
        I->>I: do nothing, ERROR log and alert counter
    end
    end
```

Notes on the failure branches:

- Branch 1 and the reconciler: Version A is implemented and tested; the reconciler is implemented, tested and was demonstrated once with the late reservation forced. How often Version B happens under real overload is not measured (`docs/testing.md`).
- Branch 3: the sweep and the webhook are raced in tests on real Postgres. The refund worker and the reconciliation sweep are designed (`architecture.md` section 6) and **not built**.
- The breaker, retry and deadline values are provisional and were checked against the stub, not a real gateway.

---

## Rendering

All four Mermaid blocks in this file (and the short one in the README) were rendered without errors by `npx @mermaid-js/mermaid-cli` on 2026-11-09. Only the architecture and state-machine renderings were looked at by eye; the two sequence diagrams were parsed and rendered but not inspected.
