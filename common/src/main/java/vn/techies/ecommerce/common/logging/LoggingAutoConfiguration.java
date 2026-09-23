package vn.techies.ecommerce.common.logging;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class LoggingAutoConfiguration {

    @Bean
    public FilterRegistrationBean<LoggingContextFilter> loggingContextFilter() {
        FilterRegistrationBean<LoggingContextFilter> registration =
                new FilterRegistrationBean<>(new LoggingContextFilter());
        // First in the chain: anything logged later, including failures, must carry the id.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
