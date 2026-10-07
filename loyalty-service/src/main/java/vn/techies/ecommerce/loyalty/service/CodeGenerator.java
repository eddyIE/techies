package vn.techies.ecommerce.loyalty.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Codes are worth money, so they are random rather than sequential: a guessable `GIFT-000124`
 * would let someone collect a reward they never earned.
 *
 * <p>The alphabet drops every character a human misreads off a phone screen and mistypes at a
 * till: no {@code O} or {@code 0}, no {@code I}, {@code 1} or {@code L}.
 */
@Component
public class CodeGenerator {

    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

    private final SecureRandom random = new SecureRandom();

    /** {@code TIER1-XXXXXXXX}, distinguishable from a coupon code at a glance. */
    public String voucherCode(int tier) {
        return "TIER" + tier + "-" + block(8);
    }

    private String block(int length) {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
