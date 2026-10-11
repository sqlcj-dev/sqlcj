-- Outside the supported set: the placeholder states no type.
-- name: FindUserByEmail :optional
SELECT id,
       display_name
FROM users
WHERE lower(email::text) = lower(:email);
