-- The named SQL of the reference service's Tracker group.

-- name: CreateOrganization :one
INSERT INTO organizations (name, preferences)
VALUES (:name, :preferences)
RETURNING id, name, preferences, created_at;

-- name: CreateUser :one
INSERT INTO users (organization_id, email, display_name)
VALUES (:organizationId, :email::text, :displayName)
RETURNING id, organization_id, display_name, created_at;

-- name: CreateProject :one
INSERT INTO projects (organization_id, code, name)
VALUES (:organizationId, :code, :name)
RETURNING *;

-- name: GetProject :optional
SELECT *
FROM projects
WHERE id = :id;

-- name: AddMember :exec
INSERT INTO memberships (organization_id, user_id, role)
VALUES (:organizationId, :userId, :role)
ON CONFLICT (organization_id, user_id) DO UPDATE
    SET role = EXCLUDED.role;

-- name: ListMembers :many
SELECT u.id,
       u.display_name,
       m.role,
       m.created_at
FROM memberships m
         JOIN users u ON u.id = m.user_id
WHERE m.organization_id = :organizationId
ORDER BY u.id;

-- name: CreateTask :one
INSERT INTO tasks (project_id, title, status, priority, labels, metadata)
VALUES (:projectId, :title, :status, :priority, :labels, :metadata)
RETURNING *;

-- name: ListProjectTasks :many
SELECT *
FROM tasks
WHERE project_id = :projectId
  AND status = ANY (:statuses)
ORDER BY id
LIMIT :limit;

-- name: MoveTask :exec
UPDATE tasks
SET status  = :status,
    version = version + 1
WHERE id = :id
  AND version = :version;

-- name: CountTasksByStatus :many
SELECT status,
       COUNT(*) AS total
FROM tasks
WHERE project_id = :projectId
GROUP BY status
ORDER BY status;

-- name: AddComment :one
INSERT INTO comments (task_id, author_id, body)
VALUES (:taskId, :authorId, :body)
RETURNING *;

-- name: RecordAudit :exec
INSERT INTO audit_log (occurred_at, actor_id, action, detail, request_id)
VALUES (:occurredAt, :actorId, :action, :detail, :requestId);

-- name: ListAudit2026 :many
SELECT *
FROM audit_log_2026
ORDER BY id;

-- name: ListArchivedAudit :many
SELECT *
FROM audit_log_archive
ORDER BY id;
