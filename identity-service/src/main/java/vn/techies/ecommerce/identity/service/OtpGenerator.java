package vn.techies.ecommerce.identity.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Six decimal digits, leading zeros kept. Decimal rather than the alphabet used for gift codes
 * because this one is typed from an email into a phone keypad.
 */
@Component
public class OtpGenerator {

    static final int DIGITS = 6;

    private static final int BOUND = 1_000_000;

    // SecureRandom, not Random: a predictable code is the same as no code at all.
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        return String.format("%0" + DIGITS + "d", random.nextInt(BOUND));
    }
}
