ALTER TABLE authors ADD COLUMN created_at TIMESTAMP;

CREATE INDEX books_author_id_idx ON books (author_id);
