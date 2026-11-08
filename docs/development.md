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
