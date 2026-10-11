-- Outside the supported set: sqlcj models no DELETE ... USING.
-- name: DeleteProjectComments :exec
DELETE
FROM comments c
    USING tasks t
WHERE t.id = c.task_id
  AND t.project_id = :projectId;
