CREATE TABLE sessions (
    token_hash bytea PRIMARY KEY,
    account_id bigint NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '7 days')
);

CREATE INDEX sessions_expiry ON sessions (expires_at);
