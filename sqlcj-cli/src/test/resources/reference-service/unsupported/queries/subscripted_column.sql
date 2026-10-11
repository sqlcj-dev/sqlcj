-- Outside the supported set: the placeholder states no type.
-- name: ListTasksByFirstLabel :many
SELECT id,
       title
FROM tasks
WHERE labels[1] = :label;
