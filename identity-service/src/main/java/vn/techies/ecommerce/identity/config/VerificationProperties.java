package vn.techies.ecommerce.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param ttl            how long a mailed code stays usable. Short on purpose: the code is only
 *                       six digits, so its lifetime is most of its strength.
 * @param resendCooldown minimum gap between two codes for the same user and purpose. Without it
 *                       one impatient user is enough to spend the Gmail daily send allowance.
 * @param maxAttempts    wrong guesses allowed before the code is burned. Six digits is a million
 *                       combinations, which a script works through in minutes if nothing counts.
 * @param fromName       display name on the message. The address itself is the SMTP username,
 *                       because Gmail rewrites a From it did not authenticate.
 */
@ConfigurationProperties(prefix = "techies.verification")
public record VerificationProperties(Duration ttl, Duration resendCooldown, int maxAttempts,
                                     String fromName) {
}
