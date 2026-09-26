package vn.techies.ecommerce.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A user's profile image.
 *
 * <p>Its own table rather than columns on {@link User}: the bytes are needed only when
 * rendering the image, and putting them on the user row would drag up to 2 MB into every
 * login and profile read.
 */
@Entity
@Table(name = "user_avatars")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAvatar {

    /** Also the foreign key: one avatar per user, replaced rather than versioned. */
    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "content_type", nullable = false, length = 32)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(nullable = false)
    private byte[] data;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static UserAvatar create(UUID userId, String contentType, byte[] data) {
        UserAvatar avatar = new UserAvatar();
        avatar.userId = userId;
        avatar.replaceWith(contentType, data);
        return avatar;
    }

    public void replaceWith(String contentType, byte[] data) {
        this.contentType = contentType;
        this.data = data;
        this.sizeBytes = data.length;
        this.updatedAt = Instant.now();
    }
}
