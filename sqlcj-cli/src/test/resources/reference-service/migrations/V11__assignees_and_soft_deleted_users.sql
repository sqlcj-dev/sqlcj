-- The task assignee, the soft-deleted user, and the live handle index.

ALTER TABLE users ADD COLUMN deleted_at timestamptz;

ALTER TABLE tasks ADD COLUMN assignee_id bigint REFERENCES users (id) ON DELETE SET NULL;

CREATE UNIQUE INDEX users_live_handle_key ON users (organization_id, handle) WHERE deleted_at IS NULL;
