-- The reporting views and the reporting schema no query uses.

CREATE VIEW unfinished_tasks AS
SELECT t.id,
       t.project_id,
       t.title,
       t.status
FROM tasks t
WHERE t.status <> 'done';

CREATE MATERIALIZED VIEW project_task_counts AS
SELECT p.id          AS project_id,
       count(t.id)   AS task_count
FROM projects p
         LEFT JOIN tasks t ON t.project_id = p.id
GROUP BY p.id;

CREATE SCHEMA reporting;

CREATE TABLE reporting.projects
(
    id          bigint      NOT NULL PRIMARY KEY,
    name        text        NOT NULL,
    exported_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE reporting.projects ADD COLUMN task_count bigint;
