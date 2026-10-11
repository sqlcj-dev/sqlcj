-- Outside the supported set: sqlcj models no UPDATE ... FROM.
-- name: RaiseProjectTaskPriority :exec
UPDATE tasks t
SET priority = 1
FROM projects p
WHERE p.id = t.project_id
  AND p.code = :code;
