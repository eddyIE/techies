package vn.techies.ecommerce.identity.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.techies.ecommerce.identity.AbstractAuthTest;
import vn.techies.ecommerce.identity.domain.VerificationCode.Purpose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "techies.jwt.secret=test-secret-that-is-definitely-long-enough-32",
        // Three guesses instead of five, so the cap can be reached without three extra
        // BCrypt comparisons per test. The rule under test is the cap, not its value.
        "techies.verification.max-attempts=3"
})
@AutoConfigureMockMvc
class EmailVerificationTest extends AbstractAuthTest {

    @Autowired
    private JdbcTemplate jdbc;

    private String resetBody(String email, String code, String newPassword) {
        return """
                {"email":"%s","code":"%s","newPassword":"%s"}"""
                .formatted(email, code, newPassword);
    }

    private int liveCodes(String email, Purpose purpose) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM verification_codes c
                JOIN users u ON u.id = c.user_id
                WHERE LOWER(u.email) = ? AND c.purpose = ?
                """, Integer.class, email.toLowerCase(), purpose.name());
    }

    // ---- registration ----

    @Test
    @DisplayName("registering mails a six-digit code and leaves the account unverified")
    void registrationIssuesCode() throws Exception {
        String email = uniqueEmail();
        register(email, "password1");

        assertThat(codeSentTo(email, Purpose.REGISTRATION)).matches("[0-9]{6}");
        assertThat(liveCodes(email, Purpose.REGISTRATION)).isEqualTo(1);
    }

    @Test
    @DisplayName("an unverified account cannot log in, even with the right password")
    void unverifiedCannotLogIn() throws Exception {
        String email = uniqueEmail();
        register(email, "password1");

        login(email, "password1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    @DisplayName("a wrong password on an unverified account still reports INVALID_CREDENTIALS")
    void verificationIsNotAnOracle() throws Exception {
        String email = uniqueEmail();
        register(email, "password1");

        // The order of the two checks is the point: EMAIL_NOT_VERIFIED after a correct
        // password only, so the code cannot be used to confirm an address exists.
        login(email, "wrongpassword1")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("verifying returns a usable token straight away, with no second login")
    void verifyReturnsToken() throws Exception {
        String email = uniqueEmail();
        register(email, "password1");

        verifyEmail(email, codeSentTo(email, Purpose.REGISTRATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value(email));
    }

    @Test
    @DisplayName("the code is single use: the same digits are refused the second time")
    void codeIsSingleUse() throws Exception {
        String email = uniqueEmail();
        register(email, "password1");
        String code = codeSentTo(email, Purpose.REGISTRATION);

        verifyEmail(email, code).andExpect(status().isOk());
        assertThat(liveCodes(email, Purpose.REGISTRATION)).isZero();

        verifyEmail(email, code)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_VERIFIED"));
    }

    @Test
    @DisplayName("a wrong code is refused and the account stays unverified")
    void wrongCodeRefused() throws Exception {
        String email = uniqueEmail();
        register(email, "password1");
        String wrong = next(codeSentTo(email, Purpose.REGISTRATION));

        verifyEmail(email, wrong)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"));

        login(email, "password1")
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    @DisplayName("a wrong code for an address with no account is refused like any other")
    void wrongCodeUnknownAccount() throws Exception {
        verifyEmail(uniqueEmail(), "123456")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"));
    }

    @Test
    @DisplayName("an expired code is refused and removed, with its own error code")
    void expiredCodeRefused() throws Exception {
        String email = uniqueEmail();
        register(email, "password1");
        String code = codeSentTo(email, Purpose.REGISTRATION);
        expire(email);

        verifyEmail(email, code)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_CODE_EXPIRED"));
        assertThat(liveCodes(email, Purpose.REGISTRATION)).isZero();
    }

    // ---- the attempt cap ----

    @Test
    @DisplayName("the code is burned once the attempt cap is reached, and stays burned")
    void attemptCapBurnsTheCode() throws Exception {
        String email = uniqueEmail();
        register(email, "password1");
        String code = codeSentTo(email, Purpose.REGISTRATION);
        String wrong = next(code);

        // max-attempts is 3 here: two refusals that count, then the third trips the cap.
        verifyEmail(email, wrong).andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"));
        verifyEmail(email, wrong).andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"));
        verifyEmail(email, wrong)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_VERIFICATION_ATTEMPTS"));

        // The real code is worthless now. This is the assertion that fails if the failed
        // attempts are rolled back with the exception that reports them.
        verifyEmail(email, code)
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"));
        assertThat(liveCodes(email, Purpose.REGISTRATION)).isZero();
    }

    @Test
    @DisplayName("each wrong guess is persisted, so the cap survives separate requests")
    void attemptsArePersisted() throws Exception {
        String email = uniqueEmail();
        register(email, "password1");
        String wrong = next(codeSentTo(email, Purpose.REGISTRATION));

        verifyEmail(email, wrong).andExpect(status().isBadRequest());

        assertThat(attempts(email)).isEqualTo(1);
    }

    @Test
    @DisplayName("a fresh code after the cap works, so a locked-out user is not stuck")
    void resendRecoversFromTheCap() throws Exception {
        String email = uniqueEmail();
        register(email, "password1");
        String wrong = next(codeSentTo(email, Purpose.REGISTRATION));
        for (int i = 0; i < 3; i++) {
            verifyEmail(email, wrong);
        }

        resendOtp(email, Purpose.REGISTRATION).andExpect(status().isNoContent());
        verifyEmail(email, codeSentTo(email, Purpose.REGISTRATION)).andExpect(status().isOk());
    }

    // ---- resend ----

    @Test
    @DisplayName("resending within the cooldown is refused, and the old code still works")
    void resendCooldown() throws Exception {
        String email = uniqueEmail();
        register(email, "password1");
        String first = codeSentTo(email, Purpose.REGISTRATION);

        resendOtp(email, Purpose.REGISTRATION)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("VERIFICATION_CODE_REQUESTED_TOO_SOON"));

        verifyEmail(email, first).andExpect(status().isOk());
    }

    @Test
    @DisplayName("resending past the cooldown replaces the code, and only one stays live")
    void resendSupersedes() throws Exception {
        String email = uniqueEmail();
        register(email, "password1");
        String first = codeSentTo(email, Purpose.REGISTRATION);
        age(email, "70 seconds");

        resendOtp(email, Purpose.REGISTRATION).andExpect(status().isNoContent());
        String second = codeSentTo(email, Purpose.REGISTRATION);

        assertThat(second).isNotEqualTo(first);
        assertThat(liveCodes(email, Purpose.REGISTRATION)).isEqualTo(1);
        verifyEmail(email, first).andExpect(status().isBadRequest());
        verifyEmail(email, second).andExpect(status().isOk());
    }

    @Test
    @DisplayName("an expired code is replaced without waiting out the cooldown")
    void expiredCodeIgnoresCooldown() throws Exception {
        String email = uniqueEmail();
        register(email, "password1");
        codeSentTo(email, Purpose.REGISTRATION);
        expire(email);

        resendOtp(email, Purpose.REGISTRATION).andExpect(status().isNoContent());
        verifyEmail(email, codeSentTo(email, Purpose.REGISTRATION)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("resend for an unknown address is 204 and mails nothing, leaking no existence")
    void resendUnknownAddressIsSilent() throws Exception {
        String unknown = uniqueEmail();

        resendOtp(unknown, Purpose.REGISTRATION).andExpect(status().isNoContent());

        verify(mailer, never()).send(eq(unknown), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("resend of a registration code for an already verified account mails nothing")
    void resendForVerifiedAccountIsSilent() throws Exception {
        String email = uniqueEmail();
        registerAndVerify(email, "password1");

        resendOtp(email, Purpose.REGISTRATION).andExpect(status().isNoContent());

        // One send only: the code from registration. Nothing new was issued.
        verify(mailer).send(eq(email), anyString(), anyString(), eq(Purpose.REGISTRATION));
    }

    @Test
    @DisplayName("a reset code is not offered for an account that never verified")
    void noResetCodeBeforeVerification() throws Exception {
        String email = uniqueEmail();
        register(email, "password1");

        resendOtp(email, Purpose.PASSWORD_RESET).andExpect(status().isNoContent());

        verify(mailer, never()).send(eq(email), anyString(), anyString(),
                eq(Purpose.PASSWORD_RESET));
    }

    // ---- password reset ----

    @Test
    @DisplayName("reset-password without a valid code refuses and leaves the password alone")
    void resetNeedsTheCode() throws Exception {
        String email = uniqueEmail();
        registerAndVerify(email, "password1");

        mvc.perform(post("/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content(resetBody(email, "123456", "newpassword9")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"));

        login(email, "password1").andExpect(status().isOk());
    }

    @Test
    @DisplayName("a registration code cannot be spent on a password reset")
    void purposesDoNotCross() throws Exception {
        String email = uniqueEmail();
        register(email, "password1");
        String registrationCode = codeSentTo(email, Purpose.REGISTRATION);

        mvc.perform(post("/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content(resetBody(email, registrationCode, "newpassword9")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"));
    }

    @Test
    @DisplayName("a weak new password is refused without spending the code")
    void weakPasswordDoesNotSpendTheCode() throws Exception {
        String email = uniqueEmail();
        registerAndVerify(email, "password1");
        String code = requestResetCode(email);

        mvc.perform(post("/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content(resetBody(email, code, "weak")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));

        mvc.perform(post("/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content(resetBody(email, code, "newpassword9")))
                .andExpect(status().isNoContent());
        login(email, "newpassword9").andExpect(status().isOk());
    }

    @Test
    @DisplayName("a spent reset code cannot reset the password a second time")
    void resetCodeIsSingleUse() throws Exception {
        String email = uniqueEmail();
        registerAndVerify(email, "password1");
        String code = requestResetCode(email);

        mvc.perform(post("/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content(resetBody(email, code, "newpassword9")))
                .andExpect(status().isNoContent());

        mvc.perform(post("/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content(resetBody(email, code, "thirdpassword9")))
                .andExpect(status().isBadRequest());
        login(email, "newpassword9").andExpect(status().isOk());
    }

    @Test
    @DisplayName("the six digits are never stored, only their hash")
    void codeIsNotStoredInPlaintext() throws Exception {
        String email = uniqueEmail();
        register(email, "password1");
        String code = codeSentTo(email, Purpose.REGISTRATION);

        String hash = jdbc.queryForObject("""
                SELECT c.code_hash FROM verification_codes c
                JOIN users u ON u.id = c.user_id
                WHERE LOWER(u.email) = ?
                """, String.class, email);

        assertThat(hash).doesNotContain(code).startsWith("$2");
    }

    // ---- helpers ----

    /** A code guaranteed to differ from the real one, so "wrong" is never accidentally right. */
    private String next(String code) {
        return "%06d".formatted((Integer.parseInt(code) + 1) % 1_000_000);
    }

    private int attempts(String email) {
        return jdbc.queryForObject("""
                SELECT c.attempts FROM verification_codes c
                JOIN users u ON u.id = c.user_id
                WHERE LOWER(u.email) = ?
                """, Integer.class, email);
    }

    /** Backdates a code so the cooldown reads as elapsed, without the test sleeping. */
    private void age(String email, String interval) {
        jdbc.update("""
                UPDATE verification_codes SET created_at = created_at - INTERVAL '%s'
                WHERE user_id = (SELECT id FROM users WHERE LOWER(email) = ?)
                """.formatted(interval), email.toLowerCase());
    }

    private void expire(String email) {
        jdbc.update("""
                UPDATE verification_codes SET expires_at = now() - INTERVAL '1 minute'
                WHERE user_id = (SELECT id FROM users WHERE LOWER(email) = ?)
                """, email.toLowerCase());
    }
}
