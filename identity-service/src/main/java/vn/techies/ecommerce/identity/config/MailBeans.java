package vn.techies.ecommerce.identity.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties(VerificationProperties.class)
public class MailBeans {

    /**
     * One virtual thread per message. An SMTP send is almost entirely waiting on a socket,
     * which is exactly what virtual threads are cheap at, and there is no pool size to guess
     * wrong: the resend cooldown already caps how many of these can exist.
     */
    @Bean
    Executor otpMailExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
