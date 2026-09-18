-- name: GetUser :one
SELECT *
FROM users
WHERE created_at = $1;

-- name: ListUsers :many
SELECT id, birth_date, created_at, balance
FROM users
WHERE created_at = $1

-- name: ListUsersByIdAndUsername :many
SELECT *
FROM users
WHERE id = $1
  AND name = $2;

-- name: GetUserById :one
SELECT id, name
FROM users
WHERE id = $1;

-- name: FindUsers :many
SELECT id, name
FROM users
WHERE id IN ($1, $2)
  AND (active = $3 OR name = $4);
