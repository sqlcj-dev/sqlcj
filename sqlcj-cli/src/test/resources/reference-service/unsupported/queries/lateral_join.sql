-- Outside the supported set: sqlcj models no LATERAL source.
-- name: ListProjectsWithLatestTask :many
SELECT p.name,
       latest.title
FROM projects p
         JOIN LATERAL (SELECT t.title
                       FROM tasks t
                       WHERE t.project_id = p.id
                       ORDER BY t.id DESC
                       LIMIT 1) latest ON TRUE;
