-- Outside the supported set: a common table expression is not a source.
-- name: ListOpenTaskTitles :many
WITH open_tasks AS (SELECT id, title
                    FROM tasks
                    WHERE status = 'open')
SELECT title
FROM open_tasks;
