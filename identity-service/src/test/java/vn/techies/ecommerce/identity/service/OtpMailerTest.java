package vn.techies.ecommerce.identity.service;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import vn.techies.ecommerce.identity.config.VerificationProperties;
import vn.techies.ecommerce.identity.domain.VerificationCode.Purpose;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the message itself, which the Spring tests cannot see because they replace this class
 * with a mock. No Spring context is needed: the executor is a constructor argument, so passing
 * {@code Runnable::run} runs the send inline instead of racing it on a virtual thread.
 */
class OtpMailerTest {

    private static final VerificationProperties PROPERTIES = new VerificationProperties(
            Duration.ofMinutes(10), Duration.ofSeconds(60), 5, "Techies");

    /** Only ever used for its {@code createMimeMessage}, which needs no SMTP connection. */
    private final JavaMailSenderImpl messageFactory = new JavaMailSenderImpl();

    private JavaMailSender senderThatWorks() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(messageFactory.createMimeMessage());
        return sender;
    }

    private MimeMessage sent(Purpose purpose) {
        JavaMailSender sender = senderThatWorks();
        new OtpMailer(sender, Runnable::run, PROPERTIES, "techies@gmail.com")
                .send("buyer@example.com", "Nguyen Van A", "482913", purpose);

        ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(message.capture());
        return message.getValue();
    }

    @Test
    @DisplayName("the registration mail carries the code, the name and the expiry")
    void registrationMail() throws Exception {
        MimeMessage message = sent(Purpose.REGISTRATION);

        assertThat(message.getSubject()).isEqualTo("Mã xác thực tài khoản Techies");
        assertThat(message.getContent().toString())
                .contains("482913")
                .contains("Nguyen Van A")
                .contains("10 phút");
    }

    @Test
    @DisplayName("the reset mail is distinguishable from the registration one")
    void resetMail() throws Exception {
        assertThat(sent(Purpose.PASSWORD_RESET).getSubject())
                .isEqualTo("Mã đặt lại mật khẩu Techies");
    }

    @Test
    @DisplayName("with no SMTP username configured nothing is sent and nothing throws")
    void unconfiguredIsASilentNoOp() {
        JavaMailSender sender = mock(JavaMailSender.class);

        new OtpMailer(sender, Runnable::run, PROPERTIES, "")
                .send("buyer@example.com", "A", "482913", Purpose.REGISTRATION);

        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("a refused send is swallowed: the code row is already committed either way")
    void sendFailureDoesNotPropagate() {
        JavaMailSender sender = senderThatWorks();
        doThrow(new MailSendException("gmail said no")).when(sender).send(any(MimeMessage.class));

        assertThatCode(() -> new OtpMailer(sender, Runnable::run, PROPERTIES, "t@gmail.com")
                .send("buyer@example.com", "A", "482913", Purpose.REGISTRATION))
                .doesNotThrowAnyException();
    }
}
