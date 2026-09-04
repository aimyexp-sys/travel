-- Phase 1 baseline migration.
-- Proves Flyway is wired up and the backend can create/read schema in
-- PostgreSQL on startup. The real canonical schema (trips, vendors,
-- employees, delay_records, etc. - see /data/SCHEMA.md) lands in Phase 2's
-- migration (V2__canonical_schema.sql) via the SyntheticSourceAdapter /
-- SampleDatasetAdapter loaders.

CREATE TABLE IF NOT EXISTS system_health_check (
    id          SERIAL PRIMARY KEY,
    checked_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO system_health_check (checked_at) VALUES (now());
