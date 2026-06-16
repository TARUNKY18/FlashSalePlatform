-- =============================================================================
-- sales_db — init script
-- Runs automatically when the container first starts (empty volume).
-- Flyway takes over for all subsequent migrations.
-- This file is idempotent: safe to re-run.
-- =============================================================================

-- Enable pg_stat_statements for slow query monitoring
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Useful for UUID generation in queries / stored procedures
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Set timezone
ALTER DATABASE sales_db SET timezone TO 'UTC';

-- Grant full access to the flashsale user
GRANT ALL PRIVILEGES ON DATABASE sales_db TO flashsale;
GRANT ALL ON SCHEMA public TO flashsale;

-- Log a marker so we know init ran
DO $$
BEGIN
    RAISE NOTICE 'sales_db initialised at %', NOW();
END $$;