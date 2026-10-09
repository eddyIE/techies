-- Sign-up and password reset now both prove ownership of the address with a 6-digit code
-- mailed to it. Until this migration, reset-password needed nothing but the email.

ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;

-- Accounts that existed before verification did are grandfathered in: they were created when
-- registering was the only step, so locking them out now would break every existing login,
-- including the demo accounts in docs/DEMO.md. Only registrations from here on verify.
UPDATE users SET email_verified = TRUE;

CREATE TABLE verification_codes (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose    VARCHAR(16) NOT NULL,
    -- BCrypt of the six digits. The code itself is never stored: it exists in memory long
    -- enough to reach the mail body and nowhere else, so a database dump cannot be replayed.
    code_hash  VARCHAR(72) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts   INT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_verification_codes_purpose
        CHECK (purpose IN ('REGISTRATION', 'PASSWORD_RESET')),
    CONSTRAINT ck_verification_codes_attempts CHECK (attempts >= 0),
    -- At most one outstanding code per user per purpose. A code is deleted when it is used
    -- or superseded, so this one constraint is both the single-use rule and the reason the
    -- resend cooldown can be read straight off created_at of the row that is still there.
    CONSTRAINT ux_verification_codes_user_purpose UNIQUE (user_id, purpose)
);

-- Expired rows are swept by the service on the next issue for the same user, not globally;
-- this index keeps that lookup, and any manual cleanup, off a sequential scan.
CREATE INDEX ix_verification_codes_expires_at ON verification_codes (expires_at);

-- The demo account docs/DEMO.md logs in with, seeded already verified.
--
-- Without it the walkthrough stops at step 1: it registers demo@techies.vn, and techies.vn is
-- not a real domain, so no code could ever arrive to finish the registration. Everything after
-- step 1 needs that token. The credentials are the ones DEMO.md already prints in plaintext,
-- so seeding them exposes nothing that was not already public; the OTP flow itself is
-- demonstrated in DEMO.md with a real address instead.
--
-- Guarded on the email rather than the id: a database that has run the walkthrough before
-- already holds this account under a random id, and the backfill above has just verified it.
INSERT INTO users (id, email, password_hash, full_name, phone, email_verified,
                   created_at, updated_at)
SELECT '11111111-1111-1111-1111-111111111111', 'demo@techies.vn',
       -- BCrypt of 'password1', strength 10.
       '$2y$10$LVX/ULE.Tr1awb0js2hsmOo42VuLl6QdwDqWfq6.ZVv8n6trS/.8C',
       'Nguyen Van Demo', '0901234567', TRUE,
       '2020-01-01 00:00:00+07', '2020-01-01 00:00:00+07'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE LOWER(email) = 'demo@techies.vn');
