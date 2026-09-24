-- name: CreateAuthor :one
INSERT INTO authors (name, bio)
VALUES ($1, $2)
RETURNING *;

-- name: GetAuthor :one
SELECT *
FROM authors
WHERE id = $1;

-- name: ListAuthors :many
SELECT *
FROM authors
ORDER BY id;

-- name: UpdateAuthorBio :exec
UPDATE authors
SET bio = $2
WHERE id = $1;

-- name: DeleteAuthor :exec
DELETE
FROM authors
WHERE id = $1;
