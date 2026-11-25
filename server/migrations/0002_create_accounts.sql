CREATE TABLE accounts (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username text NOT NULL UNIQUE CHECK (char_length(username) BETWEEN 1 AND 64),
    password_hash text NOT NULL,
    is_admin boolean NOT NULL DEFAULT false
);

CREATE TABLE admin_setup (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    code uuid NOT NULL DEFAULT gen_random_uuid()
);

INSERT INTO admin_setup DEFAULT VALUES;
