-- Outside the supported set: write one query per SELECT.
-- name: ListProjectAndTaskTitles :many
SELECT name AS title
FROM projects
UNION
SELECT title
FROM tasks;
