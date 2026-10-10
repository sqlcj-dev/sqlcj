-- Comments, non-overlapping time entries, and the partitioned audit log.

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE public.comments
(
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id    bigint      NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    author_id  bigint      NOT NULL REFERENCES users (id),
    body       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE time_entries
(
    id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id bigint    NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    during  tstzrange NOT NULL,
    EXCLUDE USING gist (task_id WITH =, during WITH &&)
);

CREATE TABLE audit_log
(
    id          bigint      NOT NULL DEFAULT nextval('audit_log_id_seq'),
    occurred_at timestamptz NOT NULL,
    actor_id    bigint,
    action      text        NOT NULL,
    detail      jsonb,
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE audit_log_2026 PARTITION OF audit_log
    FOR VALUES FROM ('2026-01-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');

CREATE TABLE audit_log_default PARTITION OF audit_log DEFAULT;

CREATE TABLE audit_log_archive
(
    LIKE audit_log INCLUDING ALL
);
