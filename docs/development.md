# Local development

## Environment

- Copy `.env.example` to a repo-root `.env` (gitignored) and fill in
  `DB_USERNAME`, `DB_PASSWORD` and `PAYMENT_WEBHOOK_SECRET`. Day 8 adds two more
  (`JWT_SECRET`, `GATEWAY_SIGNING_SECRET`), below. Optional:
  `KAFKA_BOOTSTRAP` (default `localhost:9092`) and `ORDER_WEBHOOK_URL` (default
  `http://localhost:8083/webhooks/payment`). Never commit `.env`.
- **Spring does not read `.env`.** Export the values into the shell that starts
  each service, or put them in the IDE run configuration. Order and the payment
  stub both fail at startup if `PAYMENT_WEBHOOK_SECRET` is blank, and both must
  use the same value. (`docker compose` does read the repo-root `.env`, for its
  own `${...}` values.)
- **Day 8 secrets.** `JWT_SECRET` (Identity and the Gateway only) and
  `GATEWAY_SIGNING_SECRET` (the Gateway and Order only) have **no defaults**, and
  a service that needs one **refuses to start** if it is blank, the same rule as
  `PAYMENT_WEBHOOK_SECRET`. **Identity reads `JWT_SECRET` now** (it signs access
  tokens) and refuses to start if it is blank or shorter than 32 bytes, so
  Identity needs it exported before it starts. The Gateway and Order's signed-header
  check are designed, not built, so nothing reads `GATEWAY_SIGNING_SECRET` yet. They must be two different values, and each pair must
  match across its services. Use a random value of your own locally; never commit
  one. (`decisions.md`, "Day 8: auth, gateway identity, rate limiting, browse".)

## Local infra

- **Postgres is a local install** (PostgreSQL 18 on port 5432), one database per
  service. Do **not** start `postgres` (or `redis`) from `docker-compose.yml`:
  it publishes 5432 too and clashes with the local one. The compose `postgres`
  and `redis` services have never been started or tested. **Redis is first used
  on Day 8** (the Gateway's rate limiter; designed, not built), so until then
  nothing needs it. When Day 8 is built, `docker compose up -d redis` is the
  likely way to start it (the compose service is untested; check port 6379 is
  free first).
- **Kafka is the only compose service in use:** `docker compose up -d kafka`
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

## Running the tests

- `bash scripts/seed/seed.sh` first (with `DB_USERNAME`/`DB_PASSWORD` exported).
  It also empties Order's tables, and 4 Order entity tests count absolute rows
  in the shared dev database, so they fail after a manual run leaves rows behind.
- Then `./mvnw test` in each service. No Docker is needed: the relay tests use
  an in-JVM embedded broker (`@EmbeddedKafka`), and the test config turns the
  scheduled relay off and skips topic creation. The tests run against the
  local Postgres.
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
