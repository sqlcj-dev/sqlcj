-- The status index, added without locking writes, and the type comment.

CREATE INDEX CONCURRENTLY tasks_status_idx ON tasks (status);

COMMENT ON TYPE task_status IS 'The lifecycle of one task.';
