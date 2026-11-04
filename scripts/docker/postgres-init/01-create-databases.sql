-- Creates the four service databases, one per service (database-per-service).
-- The names are the ones in each service's application.yml (spring.datasource.url):
--   identity-service, inventory-service, order-service, notification-service
-- They contain hyphens, so they must be double-quoted.
--
-- IMPORTANT: Postgres runs files in /docker-entrypoint-initdb.d ONLY when the
-- data directory is empty, i.e. the first start on a fresh volume. Editing this
-- file later does nothing on an existing volume. To re-run it you must delete
-- the volume (`docker compose down -v`), which also deletes all data.
--
-- Tables are not created here; each service's Flyway migrations do that.
-- The databases are owned by POSTGRES_USER (the DB_USERNAME the services use).

CREATE DATABASE "identity-service";
CREATE DATABASE "inventory-service";
CREATE DATABASE "order-service";
CREATE DATABASE "notification-service";
