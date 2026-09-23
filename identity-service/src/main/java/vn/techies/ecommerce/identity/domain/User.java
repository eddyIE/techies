package vn.techies.ecommerce.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 72)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(nullable = false, length = 11)
    private String phone;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static User create(String email, String passwordHash, String fullName, String phone) {
        User user = new User();
        user.id = UUID.randomUUID();
        // Stored lowercased so the unique index and every lookup agree on one representation.
        user.email = email.toLowerCase();
        user.passwordHash = passwordHash;
        user.fullName = fullName;
        user.phone = phone;
        Instant now = Instant.now();
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
