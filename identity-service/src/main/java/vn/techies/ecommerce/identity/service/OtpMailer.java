package vn.techies.ecommerce.identity.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import vn.techies.ecommerce.identity.config.VerificationProperties;
import vn.techies.ecommerce.identity.domain.VerificationCode.Purpose;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;

/**
 * Delivers a code by email, off the request thread.
 *
 * <p>The SMTP handshake with Gmail takes a second or two, and
 * {@code POST /auth/register} has no reason to wait on it: the code is already committed, and
 * the client's next move is to show the "enter the code" screen either way. So the send is
 * handed to an executor rather than annotated {@code @Async} — an injected {@link Executor} is
 * the same thing without a proxy, and lets a test run it inline instead of racing it.
 *
 * <p>A failed send is logged and dropped. It cannot roll anything back, because the code row is
 * already committed by then, and the user's recovery is the same either way: ask for another.
 */
@Component
@Slf4j
public class OtpMailer {

    private final JavaMailSender mailSender;
    private final Executor executor;
    private final VerificationProperties properties;
    private final String fromAddress;

    public OtpMailer(JavaMailSender mailSender,
                     @Qualifier("otpMailExecutor") Executor executor,
                     VerificationProperties properties,
                     @Value("${spring.mail.username:}") String fromAddress) {
        this.mailSender = mailSender;
        this.executor = executor;
        this.properties = properties;
        this.fromAddress = fromAddress;
    }

    public void send(String toAddress, String fullName, String code, Purpose purpose) {
        if (fromAddress.isBlank()) {
            // No SMTP credentials configured. The endpoints still work and still issue codes,
            // which is what the test suite and an offline demo run on; only delivery is off.
            log.warn("Mail not configured, no {} code delivered to {}", purpose, toAddress);
            return;
        }
        executor.execute(() -> deliver(toAddress, fullName, code, purpose));
    }

    private void deliver(String toAddress, String fullName, String code, Purpose purpose) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress, properties.fromName());
            helper.setTo(toAddress);
            helper.setSubject(subject(purpose));
            helper.setText(body(fullName, code, purpose), true);
            mailSender.send(message);
            log.info("Sent {} code to {}", purpose, toAddress);
        } catch (MessagingException | UnsupportedEncodingException | MailException ex) {
            log.error("Failed to send {} code to {}: {}", purpose, toAddress, ex.getMessage());
        }
    }

    private String subject(Purpose purpose) {
        return purpose == Purpose.REGISTRATION
                ? "Mã xác thực tài khoản Techies"
                : "Mã đặt lại mật khẩu Techies";
    }

    /**
     * Inline styles only, no stylesheet and no images: mail clients strip the first and block
     * the second, and the code has to be readable when they do.
     */
    private String body(String fullName, String code, Purpose purpose) {
        String lead = purpose == Purpose.REGISTRATION
                ? "Cảm ơn bạn đã đăng ký tài khoản Techies. Nhập mã bên dưới trong ứng dụng "
                  + "để hoàn tất đăng ký."
                : "Bạn vừa yêu cầu đặt lại mật khẩu. Nhập mã bên dưới trong ứng dụng để "
                  + "tiếp tục.";
        return """
                <div style="font-family:Arial,Helvetica,sans-serif;font-size:15px;color:#222">
                  <p>Xin chào %s,</p>
                  <p>%s</p>
                  <p style="font-size:32px;font-weight:bold;letter-spacing:6px;margin:24px 0">%s</p>
                  <p>Mã có hiệu lực trong %d phút và chỉ dùng được một lần.</p>
                  <p style="color:#777;font-size:13px">Nếu bạn không thực hiện yêu cầu này, hãy
                  bỏ qua email. Không chia sẻ mã cho bất kỳ ai.</p>
                  <p style="color:#777;font-size:13px">Techies</p>
                </div>
                """.formatted(fullName, lead, code, properties.ttl().toMinutes());
    }
}
