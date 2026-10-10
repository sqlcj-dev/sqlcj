-- The lifecycle rename, the replaced membership role, and the column changes.

ALTER TYPE task_status RENAME VALUE 'in_progress' TO 'doing';

ALTER TYPE task_status ADD VALUE 'blocked' BEFORE 'done';

CREATE TYPE membership_role_v2 AS ENUM ('viewer', 'member', 'maintainer', 'owner');

ALTER TABLE memberships ALTER COLUMN role DROP DEFAULT;

ALTER TABLE memberships
    ALTER COLUMN role TYPE membership_role_v2 USING role::text::membership_role_v2;

ALTER TABLE memberships ALTER COLUMN role SET DEFAULT 'member';

DROP TYPE membership_role;

ALTER TYPE membership_role_v2 RENAME TO membership_role;

ALTER TABLE tasks ALTER COLUMN priority SET DEFAULT 2;

ALTER TABLE tasks ALTER COLUMN estimate_hours DROP DEFAULT;

ALTER TABLE projects ALTER COLUMN code TYPE varchar(16);

ALTER TABLE organizations ADD COLUMN retention_period interval;

ALTER TABLE audit_log ADD COLUMN request_id uuid;
