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

-- name: ListTasksPage :many
SELECT *
FROM tasks
WHERE project_id = :projectId
ORDER BY CASE WHEN :sort::text = 'title' THEN title END,
         CASE WHEN :sort::text = 'priority' THEN priority END,
         id
LIMIT :pageSize::int OFFSET :pageOffset::int;

-- name: ListVisibleProjects :many
SELECT p.id,
       p.code,
       p.name
FROM projects p
WHERE EXISTS (SELECT 1
              FROM memberships m
              WHERE m.organization_id = p.organization_id
                AND m.user_id = :userId::bigint)
ORDER BY p.id;

-- name: ListOrganizationTasks :many
SELECT t.id,
       t.title
FROM tasks t
WHERE t.project_id IN (SELECT p.id
                       FROM projects p
                       WHERE p.organization_id = :organizationId::bigint)
ORDER BY t.id;

-- name: ListTasksWithRecentComments :many
SELECT t.id,
       (SELECT COUNT(*)
        FROM comments c
        WHERE c.task_id = t.id
          AND c.created_at >= :since::timestamptz)::bigint AS recent_comments
FROM tasks t
WHERE t.project_id = :projectId
ORDER BY t.id;

-- name: ListAssignedTasks :many
SELECT t.id,
       t.title,
       m.role
FROM tasks t
         JOIN projects p ON p.id = t.project_id
         JOIN memberships m ON m.organization_id = p.organization_id AND m.user_id = t.assignee_id
WHERE t.project_id = :projectId
ORDER BY t.id;

-- name: ListTasksWithAssignee :many
SELECT t.id,
       t.title,
       u.display_name AS assignee_name
FROM tasks t
         LEFT JOIN users u ON u.id = t.assignee_id AND u.deleted_at IS NULL
WHERE t.project_id = :projectId
ORDER BY t.id;

-- name: JoinOrganization :exec
INSERT INTO memberships (organization_id, user_id)
VALUES (:organizationId, :userId)
ON CONFLICT DO NOTHING;

-- name: ChangeMemberRole :exec
INSERT INTO memberships (organization_id, user_id, role)
VALUES (:organizationId, :userId, :role)
ON CONFLICT ON CONSTRAINT memberships_pkey DO UPDATE
    SET role = EXCLUDED.role
WHERE memberships.role <> EXCLUDED.role;

-- name: ClaimHandle :optional
INSERT INTO users (organization_id, email, handle, display_name)
VALUES (:organizationId, :email::text, :handle::text, :displayName)
ON CONFLICT (organization_id, handle) WHERE deleted_at IS NULL DO NOTHING
RETURNING id, display_name;

-- name: AssignTask :optional
UPDATE tasks
SET assignee_id = :assigneeId,
    version     = version + 1
WHERE id = :id
  AND version = :version
RETURNING id AS task_id, version AS new_version;

-- name: SoftDeleteUser :exec
UPDATE users
SET deleted_at = now()
WHERE id = :id
  AND deleted_at IS NULL;
