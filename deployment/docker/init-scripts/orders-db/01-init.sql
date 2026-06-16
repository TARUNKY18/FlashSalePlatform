-- orders_db init
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
ALTER DATABASE orders_db SET timezone TO 'UTC';
GRANT ALL PRIVILEGES ON DATABASE orders_db TO flashsale;
GRANT ALL ON SCHEMA public TO flashsale;
DO $$ BEGIN RAISE NOTICE 'orders_db initialised at %', NOW(); END $$;