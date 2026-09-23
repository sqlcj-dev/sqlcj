-- name: CreateAuthor :one
INSERT INTO authors (name, bio)
VALUES ($1, $2)
RETURNING id, name, bio;

-- name: GetAuthor :one
SELECT id, name, bio
FROM authors
WHERE id = $1;

-- name: ListAuthors :many
SELECT id, name
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
