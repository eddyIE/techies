CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(72)  NOT NULL,
    full_name     VARCHAR(120) NOT NULL,
    phone         VARCHAR(11)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

-- Email uniqueness is case-insensitive: Bob@x.com and bob@x.com are the same account.
CREATE UNIQUE INDEX ux_users_email_lower ON users (LOWER(email));

CREATE TABLE addresses (
    id             UUID PRIMARY KEY,
    user_id        UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    recipient_name VARCHAR(120) NOT NULL,
    phone          VARCHAR(11)  NOT NULL,
    line1          VARCHAR(255) NOT NULL,
    ward           VARCHAR(120) NOT NULL,
    district       VARCHAR(120) NOT NULL,
    province       VARCHAR(120) NOT NULL,
    is_default     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_addresses_user_id ON addresses (user_id);

-- At most one default address per user. Enforced here as well as in the service, because a
-- concurrent pair of "set as default" calls would otherwise slip past the service check.
CREATE UNIQUE INDEX ux_addresses_one_default_per_user
    ON addresses (user_id) WHERE is_default;
