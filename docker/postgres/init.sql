-- Runs once, on first initialization of the data volume.
-- One physical database, one schema per service: each service owns its schema and never
-- reads another's (docs/SPEC.md, Boundaries).

CREATE SCHEMA IF NOT EXISTS identity;
CREATE SCHEMA IF NOT EXISTS catalog;
CREATE SCHEMA IF NOT EXISTS inventory;
CREATE SCHEMA IF NOT EXISTS orders;

-- The application user owns all four so Flyway can migrate each independently.
DO $$
BEGIN
    EXECUTE format('GRANT ALL ON SCHEMA identity, catalog, inventory, orders TO %I',
                   current_setting('POSTGRES_USER', true));
EXCEPTION WHEN OTHERS THEN
    -- current_setting is unavailable in some entrypoint contexts; the bootstrap user already
    -- owns these schemas in that case, so there is nothing to grant.
    NULL;
END $$;
