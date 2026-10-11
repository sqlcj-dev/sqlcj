-- Outside the supported set: write the join condition with ON.
-- name: ListNaturallyJoinedTasks :many
SELECT t.title,
       p.name
FROM tasks t
         NATURAL JOIN projects p;
