-- Reference service, first migration: the tenants and their people.

SET lock_timeout = '5s';

SET search_path TO public, pg_catalog;

CREATE EXTENSION IF NOT EXISTS citext;

CREATE DOMAIN email_address AS citext;

CREATE TABLE organizations
(
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        text        NOT NULL,
    preferences jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE users
(
    id              bigserial PRIMARY KEY,
    organization_id bigint        NOT NULL REFERENCES organizations (id),
    email           email_address NOT NULL,
    handle          citext,
    display_name    text          NOT NULL,
    last_login_ip   inet,
    created_at      timestamptz   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX users_email_key ON users (lower(email::text));

COMMENT ON TABLE users IS 'One person inside one organization.';
