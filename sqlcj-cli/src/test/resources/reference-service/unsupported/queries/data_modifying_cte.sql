-- Outside the supported set: a WITH body writes no rows.
-- name: DeleteDoneTasks :many
WITH removed AS (DELETE FROM tasks
                 WHERE status = 'done'
                 RETURNING id)
SELECT id
FROM removed;
