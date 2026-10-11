-- Outside the supported set: sqlcj models no INSERT ... SELECT.
-- name: ArchiveAuditLog :exec
INSERT INTO audit_log_archive (occurred_at, actor_id, action, detail)
SELECT occurred_at, actor_id, action, detail
FROM audit_log;
