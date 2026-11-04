# Local development

## Local infra

- Docker Desktop must be running. Stop any Postgres already using port 5432 first (a local Windows service, for example).
- Set `DB_USERNAME` and `DB_PASSWORD` in your shell (see `.env.example`), then: `docker compose up -d postgres redis`
- The first start on an empty volume creates the four service databases (`scripts/docker/postgres-init/`). Those init scripts never re-run on an existing volume; `docker compose down -v` deletes the volume and all data.
- Kafka is needed only from Day 6: `docker compose up -d kafka`. It creates no topics.
- The services still run on the host (IDE or jar). Each infra service starts on its own by name.
