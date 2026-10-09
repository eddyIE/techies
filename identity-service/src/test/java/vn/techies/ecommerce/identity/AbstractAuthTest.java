package vn.techies.ecommerce.identity;

import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MvcResult;
import vn.techies.ecommerce.identity.domain.VerificationCode.Purpose;
import vn.techies.ecommerce.identity.service.OtpMailer;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared plumbing for the tests that have to get past email verification.
 *
 * <p>The mailer is mocked rather than the {@code JavaMailSender} underneath it, for two
 * reasons: no test can open an SMTP connection, and the plaintext code is deliberately
 * nowhere else. It exists only between being generated and being handed to the mailer, so
 * intercepting that handoff is the only way a test can learn it, which is the same thing as
 * saying the database holds nothing replayable. {@code OtpMailerTest} covers the message
 * itself.
 */
public abstract class AbstractAuthTest extends AbstractPostgresTest {

    @Autowired
    protected MockMvc mvc;

    @MockitoBean
    protected OtpMailer mailer;

    protected String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    protected String registerBody(String email, String password) {
        return """
                {"email":"%s","password":"%s","fullName":"Nguyen Van A","phone":"0901234567"}
                """.formatted(email, password);
    }

    protected MvcResult register(String email, String password) throws Exception {
        return mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email, password)))
                .andReturn();
    }

    /** The most recent code the service tried to mail to this address for this purpose. */
    protected String codeSentTo(String email, Purpose purpose) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailer, atLeastOnce())
                .send(eq(email.toLowerCase()), anyString(), code.capture(), eq(purpose));
        return code.getValue();
    }

    /** Registers and completes verification, leaving an account that can log in. */
    protected void registerAndVerify(String email, String password) throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email, password)))
                .andExpect(status().isCreated());
        verifyEmail(email, codeSentTo(email, Purpose.REGISTRATION))
                .andExpect(status().isOk());
    }

    protected ResultActions verifyEmail(String email, String code) throws Exception {
        return mvc.perform(post("/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","code":"%s"}""".formatted(email, code)));
    }

    protected ResultActions resendOtp(String email, Purpose purpose) throws Exception {
        return mvc.perform(post("/auth/resend-otp").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","purpose":"%s"}""".formatted(email, purpose)));
    }

    /** Starts a password reset and returns the code that would have been mailed. */
    protected String requestResetCode(String email) throws Exception {
        resendOtp(email, Purpose.PASSWORD_RESET).andExpect(status().isNoContent());
        return codeSentTo(email, Purpose.PASSWORD_RESET);
    }

    protected ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}""".formatted(email, password)));
    }
}
