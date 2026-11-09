# Local development

## Environment

- Copy `.env.example` to a repo-root `.env` (gitignored) and fill in
  `DB_USERNAME`, `DB_PASSWORD`, `PAYMENT_WEBHOOK_SECRET`, `JWT_SECRET`,
  `GATEWAY_SIGNING_SECRET` and `GRAFANA_ADMIN_PASSWORD`. Optional:
  `KAFKA_BOOTSTRAP` (default `localhost:9092`) and `ORDER_WEBHOOK_URL` (default
  `http://localhost:8083/webhooks/payment`). Never commit `.env`.
- **Spring does not read `.env`.** Export the values into the shell that starts
  each service, or put them in the IDE run configuration. Order and the payment
  stub both fail at startup if `PAYMENT_WEBHOOK_SECRET` is blank, and both must
  use the same value. (`docker compose` does read the repo-root `.env`, for its
  own `${...}` values.)
- **Day 8 secrets.** `JWT_SECRET` (Identity and the Gateway only) and
  `GATEWAY_SIGNING_SECRET` (the Gateway and Order only) have **no defaults**, and
  a service that needs one **refuses to start** if it is blank or shorter than
  32 bytes, the same rule as `PAYMENT_WEBHOOK_SECRET` for blank. They must be two
  different values, and each pair must match across its services. Use a random value of your own locally; never commit
  one. (`decisions.md`, "Day 8: auth, gateway identity, rate limiting, browse".)

## Local infra

- **Postgres is a local install** (PostgreSQL 18 on port 5432), one database per
  service. Do **not** start `postgres` from `docker-compose.yml`: it publishes
  5432 too and clashes with the local one, and it has never been started or
  tested. Create the four databases with `psql` (README, step 2).
- **Redis** is the compose one: `docker compose up -d redis`. The Gateway's rate
  limiter uses it (and fails open without it), and the Gateway's rate-limiter
  tests need it.
- **Kafka:** `docker compose up -d kafka`
  (Docker Desktop running; wait for `healthy`). It is needed only to run Order
  with its outbox relay against a real broker, not for the test suites.
  Auto-creation is off on the broker; Order creates `payment-confirmed` and
  `order-cancelled` itself at startup (`kafka.create-topics`, default `true`).
  `docker compose stop kafka` keeps the topics; `docker compose down` removes
  the container and its data.
- **Monitoring (Day 9, Part B):** set `GRAFANA_ADMIN_PASSWORD` in `.env` (no default; **every** `docker compose`
  command now needs it, because compose checks required variables for the whole file), then
  `docker compose up -d prometheus grafana`. Prometheus: `http://localhost:9090` (the targets page shows each service
  `up`/`down`); Grafana: `http://localhost:3000`, user `admin`; the dashboard "FlashMart overview" is already there
  (provisioned from `monitoring/`; edit the JSON, not the UI). A service that is not running shows `down` until it
  starts (Prometheus scrapes every 5 s). Prometheus reaches the host services at `host.docker.internal`; the metrics
  are on each service's own port at `/actuator/prometheus`, never through the Gateway, and have no authentication.
  **Reading it:** the headline p99 is Gatling's (buyer view); Grafana's p99 is server-side and excludes the Gateway,
  the network and the client's wait. `RateLimitFailOpenTest` runs `docker compose` itself: it needs the variable above,
  and from another folder (a git worktree) `COMPOSE_PROJECT_NAME=flashmart`.
- Order's relay is on by default (`outbox.relay.enabled`, default `true`). Set
  it to `false` to run Order with no broker at all; outbox rows then stay
  unpublished until a relay runs.
- The services run on the host (IDE or jar): identity 8081, inventory 8082,
  order 8083, notification 8084, payment stub 8085 (127.0.0.1 only).
- **Start-up order (built; Day 9 added the config server):** Config server
  (`8888`) first, then Eureka server (`8761`), then Identity / Inventory / Order,
  then the API Gateway (`8080`) last. The config server is *optional* for every
  client: if it is down a service starts on its own local `application.yml` and
  logs `CONFIG SOURCE: local fallback`. Values apply only at a service's next
  restart, never live. Precedence: env var > config server > local file. The
  config server needs internet at start (it clones the public GitHub repo
  `flashmart-config`; override with `CONFIG_REPO_URI`). Reason for the Eureka
  part: the Gateway finds services only through
  Eureka; a Gateway started while Eureka is down has an empty registry and
  answers `503 SERVICE_UNAVAILABLE` on every discovered route until Eureka is
  back. A business service started before Eureka still starts (it warns and
  retries registration). With the default Eureka timings a newly started or
  stopped service can take tens of seconds to minutes to appear in or leave the
  Gateway's view; stop services gracefully (not a hard kill) in a demo.
  `decisions.md`, "Day 8", addendum "Gateway and discovery".

## Things that have gone wrong before

- **Rebuild all jars before a load run** (`./mvnw -q -DskipTests package` in every
  module). Stale jars in `*/target` once bundled openfeign 5.0.2 and a pre-warm-up
  Inventory; a run on them would have been invalid (`docs/testing.md`, "Day 9: R2
  re-run on the final build").
- **Start-up order and the Eureka wait.** Config server, Eureka, Identity /
  Inventory / Order, then the Gateway. After a business service starts, the Gateway
  can take about 30 s (Eureka's default fetch interval; up to about a minute with the 35 s load-balancer cache, and longer after a hard kill) to see it; until then it answers
  `503` on that route. Stop services gracefully in a demo.
- **Leftover load rows fail tests.** Run `bash load-tests/reset-run.sh` (with `.env`
  exported) **and have Redis up** before the full suite. Four Order tests failed on
  1,550 orders left by load runs, and without `.env` the four database modules fail
  with connection errors.
- **`GRAFANA_ADMIN_PASSWORD` must be set for every `docker compose` command**, not
  only for Grafana (compose checks required variables for the whole file).
- **On Windows, sample connections with `netstat -ano` and read the TCP
  failed-connection counters** (`Get-Counter '\TCPv4\Connection Failures'` and the
  `TCPv6` one). **Never `netstat -s` without `-n`**: it looks up a host name for
  every connection, took about 2 minutes under load, and made a sampler miss a whole
  run (`docs/testing.md`, "Run 1 (18:29)"). Even `netstat -ano` took 4 to 7 s during
  a ramp, so expect few samples.

## API docs (OpenAPI / Swagger UI)

Identity, Inventory and Order serve their own docs on their own port. **Not routed through the Gateway.**

| Service | Swagger UI | OpenAPI JSON |
|---|---|---|
| Identity | `http://localhost:8081/swagger-ui/index.html` | `http://localhost:8081/v3/api-docs` |
| Inventory | `http://localhost:8082/swagger-ui/index.html` | `http://localhost:8082/v3/api-docs` |
| Order | `http://localhost:8083/swagger-ui/index.html` | `http://localhost:8083/v3/api-docs` |

- `/internal/**` and `/webhooks/payment` are `@Hidden`, so they are not listed.
- Order's `POST /api/orders` needs a Gateway-signed `X-Buyer-Id` (with `X-Auth-Timestamp` and `X-Auth-Signature`). "Try it out" on Order returns `401` unless you send validly signed headers; the check was not relaxed for the docs. Identity's routes are public.
- Library: springdoc `3.1.1` (built on Boot 4.1.0; our poms use 4.1.1). Added and unit/integration suites run; the Swagger pages themselves have **not** been opened on running services yet.

## Running the tests

- `bash load-tests/reset-run.sh` first (with `.env` exported and the services' databases
  created). It re-seeds, empties Order's tables and checks they are empty. (`bash
  scripts/seed/seed.sh` does the reseed without the check.) 4 Order entity tests count
  absolute rows in the shared dev database, so they fail after a manual or load run leaves
  rows behind.
- `docker compose up -d redis` (the Gateway's tests also need Docker).
- Then `./mvnw test` in each module. No Kafka container is needed: the relay tests use
  an in-JVM embedded broker (`@EmbeddedKafka`), and the test config turns the
  scheduled relay off and skips topic creation. The tests run against the
  local Postgres.
- Last full run: 686 tests, 0 failures (`docs/testing.md`, "Day 9: the Gateway's own
  rate-limiter script").
- Manual multi-process runs (Day 5 failure demo, Day 6 end to end) and their
  scripts are in `docs/testing.md`.

## Running the order load test (`PlaceOrders`, Day 9 Part C)

Buyers place orders through the Gateway (`POST /api/orders` on `:8080`): one buyer, one token, one order, quantity 1.
`ReserveOneItem` (Inventory only, `:8082`) is unchanged. All commands from Git Bash, repo root, with the `.env` values
exported (`set -a; . ./.env; set +a`).

1. **Stack up**, in the start-up order above: config server, Eureka, Identity, Inventory, Order, payment-stub, then
   the Gateway. Compose: `redis`, `kafka`, and optionally `prometheus grafana`. Identity and the Gateway need the same
   `JWT_SECRET`; the Gateway and Order need the same `GATEWAY_SIGNING_SECRET`.
2. **Payments end PAID:** `curl -X POST http://127.0.0.1:8085/admin/auto-webhook -H 'Content-Type: application/json' -d '{"status":"SUCCESS"}'`.
   The stub starts with auto-webhooks `OFF` and forgets the setting when it restarts. Without it, every order waits
   in `PENDING_PAYMENT` until the sweep expires it.
3. **Reset:** `bash scripts/seed/seed.sh` (item 1: 500 units, item 2: 50 units, Order's tables emptied).
4. **Buyers, once per database:** `bash load-tests/create-users.sh` (default 5,000 measured + 200 warm-up; `BUYERS`,
   `WARMUP`, `CONCURRENCY` to change). These are real signups straight on Identity `:8081`, never through the Gateway,
   because its auth limiter allows 1 request/s per IP. A second run skips existing buyers (409). Emails and the shared
   password are fixed and test-only (`load-tests/buyers.py`).
5. **Tokens, right before the run:** `bash load-tests/make-tokens.sh` writes `load-tests/tokens/` (git-ignored).
   Access tokens live **15 min**. The script prints how much of that is left, and the simulation prints it at the start
   and end and refuses to start if the tokens have expired. For 5,200 buyers the logins took 145.5 s once (12.6 min left).
6. **Run:** `cd load-tests && ../order-service/mvnw gatling:test -Dgatling.simulationClass=com.flashmart.load.PlaceOrders -Dbuyers=5000 -DrampSeconds=10`.
   A 200-buyer warm-up on item 2 runs first. Read the `place-order` request, not the global line. The run prints
   `STATUS place-order ...` with a count per HTTP status. 200/201/409/429/503 count as answered; anything else is KO.
7. **Check, within 6 minutes of the last order** (the payment window; after it the expiry sweep releases stock):
   `bash load-tests/verify.sh` (Inventory: stock arithmetic, item 1) and `bash load-tests/check-orders.sh`
   (Order: no buyer has two live-or-paid orders, none stuck `CREATED`, order units = Inventory's held units).
   Use `ITEM_ID=2` / `PRODUCT_ID=2 ITEM_ID=2` for the warm-up item.
8. **Reset again** with `seed.sh`. It does not delete the test buyers. Their refresh tokens pile up in Identity,
   one row per login.
