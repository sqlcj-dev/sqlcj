-- name: GetUser :one
SELECT *
FROM users
WHERE id = $1;

-- name: ListUsers :many
SELECT *
FROM users
WHERE 1 = 1;

-- name: ListUsersByIdAndUsername :many
SELECT *
FROM users
WHERE id = $1
  AND name = $2;

-- name: GetUserById :one
SELECT id, name
FROM users
WHERE id = ?;
