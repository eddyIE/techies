package vn.techies.ecommerce.order.config;

import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.techies.ecommerce.common.logging.RequestContext;

/**
 * Carries the correlation id onto every outbound service call.
 *
 * <p>A {@code @Configuration} class, so it applies to all Feign clients rather than one.
 * Without it the trace stops at order-service: identity, catalog and inventory would log a
 * checkout under their own generated ids and the four files could not be joined.
 */
@Configuration
public class FeignTracingConfig {

    @Bean
    public RequestInterceptor correlationIdInterceptor() {
        return template -> {
            String requestId = MDC.get(RequestContext.MDC_REQUEST_ID);
            if (requestId != null && !requestId.isBlank()) {
                template.header(RequestContext.HEADER_REQUEST_ID, requestId);
            }
            String userId = MDC.get(RequestContext.MDC_USER_ID);
            if (userId != null && !userId.isBlank()) {
                template.header(vn.techies.ecommerce.common.security.UserPrincipal.HEADER_USER_ID, userId);
            }
        };
    }
}
