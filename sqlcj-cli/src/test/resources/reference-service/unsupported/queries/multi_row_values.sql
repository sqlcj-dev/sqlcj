-- Outside the supported set: INSERT writes a single VALUES row.
-- name: CreateTwoProjects :exec
INSERT INTO projects (organization_id, code, name)
VALUES (:organizationId, :firstCode, :firstName),
       (:organizationId, :secondCode, :secondName);
