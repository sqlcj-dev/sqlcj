CREATE TABLE authors
(
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    bio        TEXT,
    created_at TIMESTAMP
);

CREATE TABLE books
(
    id        BIGSERIAL PRIMARY KEY,
    author_id BIGINT       NOT NULL REFERENCES authors (id),
    title     VARCHAR(255) NOT NULL
);
