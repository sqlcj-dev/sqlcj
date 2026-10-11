-- Outside the supported set: sqlcj models no FULL JOIN.
-- name: ListProjectsAndTasks :many
SELECT p.name,
       t.title
FROM projects p
         FULL JOIN tasks t ON t.project_id = p.id;
