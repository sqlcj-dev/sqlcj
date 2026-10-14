-- Outside the supported set: sqlcj analyzes no recursive CTE.
-- name: ListPriorityLevels :many
WITH RECURSIVE levels AS (SELECT 1 AS level
                          UNION ALL
                          SELECT level + 1
                          FROM levels
                          WHERE level < 5)
SELECT level
FROM levels;
