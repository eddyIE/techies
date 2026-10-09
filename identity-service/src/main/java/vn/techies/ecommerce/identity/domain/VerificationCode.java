package vn.techies.ecommerce.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One outstanding 6-digit code, for one user, for one purpose. The digits are not here: only
 * their BCrypt hash is, so the row is useless to anyone who reads the table.
 */
@Entity
@Table(name = "verification_codes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VerificationCode {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Purpose purpose;

    @Column(name = "code_hash", nullable = false, length = 72)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public enum Purpose {
        REGISTRATION,
        PASSWORD_RESET
    }

    public static VerificationCode issue(UUID userId, Purpose purpose, String codeHash,
                                         Duration ttl) {
        VerificationCode code = new VerificationCode();
        code.id = UUID.randomUUID();
        code.userId = userId;
        code.purpose = purpose;
        code.codeHash = codeHash;
        code.createdAt = Instant.now();
        code.expiresAt = code.createdAt.plus(ttl);
        code.attempts = 0;
        return code;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }

    /**
     * Counts one wrong guess. Returned so the caller can persist the new total before it
     * rejects the attempt, which is what makes the cap hold across requests.
     */
    public int recordFailedAttempt() {
        return ++attempts;
    }

    /** Seconds until a resend is allowed, given how long a code has to sit before replacing. */
    public long secondsUntilResendAllowed(Duration cooldown) {
        long remaining = Duration.between(Instant.now(), createdAt.plus(cooldown)).toSeconds();
        return Math.max(remaining, 0);
    }
}
