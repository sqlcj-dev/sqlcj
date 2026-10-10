-- The first organization, the view the status column changed, and a refresh.

SELECT pg_catalog.set_config('search_path', 'public', false);

INSERT INTO organizations (name)
VALUES ('Reference Industries');

INSERT INTO users (organization_id, email, display_name)
SELECT id, 'ada@example.com', 'Ada Lovelace'
FROM organizations
WHERE name = 'Reference Industries';

UPDATE users
SET handle = 'ada'
WHERE display_name = 'Ada Lovelace';

TRUNCATE audit_log;

DROP VIEW unfinished_tasks;

CREATE VIEW unfinished_tasks AS
SELECT t.id,
       t.project_id,
       t.title,
       t.status,
       t.priority
FROM tasks t
WHERE t.status <> 'done';

REFRESH MATERIALIZED VIEW project_task_counts;
