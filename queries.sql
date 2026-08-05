-- name: GetUser :one
SELECT *
FROM users
WHERE id = $1;

-- name: ListUsers :many
SELECT *
FROM users;

-- name: ListUsersByIdAndUsername :many
SELECT *
FROM users
WHERE id = $1
  AND username = $2;
