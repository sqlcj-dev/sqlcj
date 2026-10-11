-- Outside the supported set: write JOIN ... ON with the join condition.
-- name: ListCommaJoinedTasks :many
SELECT t.title,
       p.name
FROM tasks t, projects p
WHERE p.id = t.project_id;
