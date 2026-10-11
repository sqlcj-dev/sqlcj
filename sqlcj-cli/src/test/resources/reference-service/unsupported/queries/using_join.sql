-- Outside the supported set: write the join condition with ON.
-- name: ListTaskComments :many
SELECT t.title,
       c.body
FROM tasks t
         JOIN comments c USING (id);
