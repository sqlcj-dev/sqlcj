-- Outside the supported set: write one query per SELECT.
-- name: ListEveryProjectAndTaskTitle :many
SELECT name AS title
FROM projects
UNION ALL
SELECT title
FROM tasks;
