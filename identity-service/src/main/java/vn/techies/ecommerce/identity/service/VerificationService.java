package vn.techies.ecommerce.identity.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;
import vn.techies.ecommerce.identity.config.VerificationProperties;
import vn.techies.ecommerce.identity.domain.User;
import vn.techies.ecommerce.identity.domain.VerificationCode;
import vn.techies.ecommerce.identity.domain.VerificationCode.Purpose;
import vn.techies.ecommerce.identity.repository.VerificationCodeRepository;

/**
 * Issues and checks the 6-digit codes that prove someone reads the address they registered.
 *
 * <p>Both flows that use it - finishing a registration and resetting a password - share one
 * table and one set of rules, distinguished only by {@link Purpose}. A code is deleted the
 * moment it is spent or replaced, which is what makes it single-use.
 */
@Service
@RequiredArgsConstructor
public class VerificationService {

    private final VerificationCodeRepository codes;
    private final OtpGenerator otpGenerator;
    private final PasswordEncoder passwordEncoder;
    private final OtpMailer mailer;
    private final VerificationProperties properties;

    /**
     * Replaces any outstanding code for this user and purpose with a fresh one, and mails it.
     *
     * @param enforceCooldown false for the code sent during registration, which no one asked
     *                        for twice, and true for every code a caller can request on demand.
     */
    @Transactional
    public void issueAndSend(User user, Purpose purpose, boolean enforceCooldown) {
        codes.findByUserIdAndPurpose(user.getId(), purpose).ifPresent(existing -> {
            // An expired code is replaced freely: the cooldown exists to limit mail volume,
            // not to make a user who waited out the TTL wait again.
            long wait = existing.secondsUntilResendAllowed(properties.resendCooldown());
            if (enforceCooldown && !existing.isExpired() && wait > 0) {
                throw new ApiException(ErrorCode.VERIFICATION_CODE_REQUESTED_TOO_SOON,
                        "Vui lòng đợi " + wait + " giây trước khi yêu cầu mã mới");
            }
            codes.delete(existing);
            // Without this the insert below races the delete inside one transaction and trips
            // the unique constraint, because JPA is free to order the two statements itself.
            codes.flush();
        });

        String code = otpGenerator.generate();
        codes.save(VerificationCode.issue(user.getId(), purpose,
                passwordEncoder.encode(code), properties.ttl()));
        mailer.send(user.getEmail(), user.getFullName(), code, purpose);
    }

    /**
     * Spends the outstanding code for this user and purpose, or throws.
     *
     * <p>The transaction annotation is the whole reason the attempt cap works, and both halves
     * of it matter. {@code noRollbackFor} keeps a refusal's effects — a higher attempt count,
     * or the code deleted — instead of letting the default rollback-on-RuntimeException undo
     * exactly the thing being recorded. {@code REQUIRES_NEW} makes that hold no matter who
     * calls: joining the caller's transaction would put its rollback rules in charge, and a
     * caller annotated plainly {@code @Transactional} would roll the increment back anyway.
     * Without both, an attacker gets unlimited free guesses at the same six digits.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class)
    public void consume(User user, Purpose purpose, String submittedCode) {
        VerificationCode code = codes.findByUserIdAndPurpose(user.getId(), purpose)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_VERIFICATION_CODE,
                        "Mã xác thực không đúng hoặc đã được sử dụng"));

        if (code.isExpired()) {
            codes.delete(code);
            throw new ApiException(ErrorCode.VERIFICATION_CODE_EXPIRED,
                    "Mã xác thực đã hết hạn, vui lòng yêu cầu mã mới");
        }
        if (code.getAttempts() >= properties.maxAttempts()) {
            codes.delete(code);
            throw new ApiException(ErrorCode.TOO_MANY_VERIFICATION_ATTEMPTS,
                    "Đã nhập sai quá nhiều lần, vui lòng yêu cầu mã mới");
        }
        if (!passwordEncoder.matches(submittedCode, code.getCodeHash())) {
            if (code.recordFailedAttempt() >= properties.maxAttempts()) {
                // The cap is reached by this guess, so burn the code now rather than let the
                // next request be the one that notices. Asking for a new one is the way back.
                codes.delete(code);
                throw new ApiException(ErrorCode.TOO_MANY_VERIFICATION_ATTEMPTS,
                        "Đã nhập sai quá nhiều lần, vui lòng yêu cầu mã mới");
            }
            codes.save(code);
            throw new ApiException(ErrorCode.INVALID_VERIFICATION_CODE,
                    "Mã xác thực không đúng");
        }
        codes.delete(code);
    }
}
