CREATE TABLE invitations (
    code_hash bytea PRIMARY KEY,
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '24 hours')
);
