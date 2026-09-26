-- Profile images, stored as bytes in their own table rather than as a column on `users`.
--
-- Keeping them out of `users` matters: a 2 MB bytea column would be dragged into every
-- `SELECT * FROM users`, every login and every profile read, even though the bytes are
-- wanted only when rendering the image.
CREATE TABLE user_avatars (
    user_id      UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    content_type VARCHAR(32)  NOT NULL,
    size_bytes   INT          NOT NULL,
    data         BYTEA        NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_avatar_content_type CHECK (content_type IN ('image/png', 'image/jpeg')),
    -- 2 MB. Enforced here as well as in the service, so a bug cannot store something larger.
    CONSTRAINT ck_avatar_size CHECK (size_bytes > 0 AND size_bytes <= 2097152)
);
