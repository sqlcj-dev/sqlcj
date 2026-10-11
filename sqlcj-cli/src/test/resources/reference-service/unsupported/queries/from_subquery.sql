-- Outside the supported set: sqlcj models no subquery source.
-- name: ListRecentTaskTitles :many
SELECT recent.title
FROM (SELECT t.title
      FROM tasks t
      ORDER BY t.id DESC
      LIMIT 10) recent;
