package vn.techies.ecommerce.identity.service;

import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;

/**
 * Password rules from docs/SPEC-identity.md: at least 8 characters, at least one letter and
 * one digit. Kept separate from AuthService so both register and change-password enforce the
 * identical rule.
 */
final class PasswordPolicy {

    private static final int MIN_LENGTH = 8;

    private PasswordPolicy() {
    }

    static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new ApiException(ErrorCode.WEAK_PASSWORD,
                    "Password must be at least " + MIN_LENGTH + " characters");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new ApiException(ErrorCode.WEAK_PASSWORD,
                    "Password must contain at least one letter and one digit");
        }
    }
}
