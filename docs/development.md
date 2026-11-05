# Local development

## Environment

- Copy `.env.example` to a repo-root `.env` (gitignored) and fill in
  `DB_USERNAME`, `DB_PASSWORD` and `PAYMENT_WEBHOOK_SECRET`. Optional:
  `KAFKA_BOOTSTRAP` (default `localhost:9092`) and `ORDER_WEBHOOK_URL` (default
  `http://localhost:8083/webhooks/payment`). Never commit `.env`.
- **Spring does not read `.env`.** Export the values into the shell that starts
  each service, or put them in the IDE run configuration. Order and the payment
  stub both fail at startup if `PAYMENT_WEBHOOK_SECRET` is blank, and both must
  use the same value. (`docker compose` does read the repo-root `.env`, for its
  own `${...}` values.)

## Local infra

- **Postgres is a local install** (PostgreSQL 18 on port 5432), one database per
  service. Do **not** start `postgres` (or `redis`) from `docker-compose.yml`:
  it publishes 5432 too and clashes with the local one. The compose `postgres`
  and `redis` services have never been started or tested.
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
