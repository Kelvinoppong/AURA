-- AURA Postgres bootstrap. Runs once on first container start.
-- Schema is then evolved via Flyway migrations under core/src/main/resources/db/migration.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Seeded demo user is created by Flyway V1 once bcrypt hash is known.
