-- Outside the supported set: write JOIN ... ON TRUE.
-- name: ListOrganizationProjectPairs :many
SELECT o.name AS organization_name,
       p.name AS project_name
FROM organizations o
         CROSS JOIN projects p;
