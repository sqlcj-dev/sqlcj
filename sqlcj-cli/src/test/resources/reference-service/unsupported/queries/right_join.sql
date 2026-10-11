-- Outside the supported set: write a LEFT JOIN with the sources swapped.
-- name: ListProjectsWithTasks :many
SELECT p.name,
       t.title
FROM tasks t
         RIGHT JOIN projects p ON p.id = t.project_id;
