CREATE TABLE users
(
    id         BIGINT NOT NULL,
    name       VARCHAR(255),
    active     BOOLEAN,
    birth_date DATE,
    created_at TIMESTAMP,
    balance    DECIMAL(10, 2)
);
