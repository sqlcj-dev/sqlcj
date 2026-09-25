-- name: CreateAuthor :one
INSERT INTO authors (name, bio)
VALUES ($1, $2)
RETURNING *;

-- name: GetAuthor :one
SELECT *
FROM authors
WHERE id = $1;

-- name: FindAuthor :optional
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

-- name: SearchAuthors :many
SELECT *
FROM authors
WHERE name ILIKE $1
ORDER BY id;

-- name: CountAuthors :one
SELECT COUNT(*) AS total
FROM authors;

-- name: ListAuthorPage :many
SELECT *
FROM authors
ORDER BY id
LIMIT $1 OFFSET $2;

-- name: CreateBook :exec
INSERT INTO books (author_id, title)
VALUES ($1, $2);

-- name: ListAuthorBooks :many
SELECT a.name, b.title
FROM authors a
LEFT JOIN books b ON b.author_id = a.id
ORDER BY a.id, b.id;
