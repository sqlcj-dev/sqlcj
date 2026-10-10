-- Tasks, their lifecycle type, their indexes, and the title trigger.

CREATE TYPE task_status AS ENUM ('open', 'in_progress', 'done');

CREATE SEQUENCE audit_log_id_seq;

CREATE TABLE tasks
(
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id     bigint      NOT NULL,
    title          text        NOT NULL,
    status         task_status NOT NULL DEFAULT 'open',
    priority       smallint    NOT NULL DEFAULT 3,
    labels         text[]      NOT NULL DEFAULT '{}',
    metadata       jsonb,
    version        integer     NOT NULL DEFAULT 1,
    estimate_hours numeric              DEFAULT 0,
    title_length   integer GENERATED ALWAYS AS (length(title)) STORED,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX tasks_unfinished_idx ON tasks (project_id) WHERE status <> 'done';

CREATE INDEX tasks_labels_idx ON tasks USING gin (labels);

CREATE INDEX tasks_title_idx ON tasks (lower(title));

COMMENT ON INDEX tasks_unfinished_idx IS 'Serves the unfinished task list.';

CREATE FUNCTION trim_task_title() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    NEW.title := btrim(NEW.title);

    RETURN NEW;
END;
$$;

CREATE TRIGGER tasks_trim_title
    BEFORE INSERT OR UPDATE
    ON tasks
    FOR EACH ROW
EXECUTE FUNCTION trim_task_title();
