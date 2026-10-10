-- The service roles, their privileges, and row-level security.

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tracker_reader') THEN
            CREATE ROLE tracker_reader;
        END IF;
    END
$$;

CREATE ROLE tracker_writer;

GRANT SELECT ON ALL TABLES IN SCHEMA public TO tracker_reader;

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO tracker_reader;

ALTER TABLE time_entries OWNER TO tracker_writer;

ALTER TABLE time_entries ENABLE ROW LEVEL SECURITY;

CREATE POLICY time_entries_read ON time_entries FOR SELECT USING (true);
