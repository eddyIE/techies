package vn.techies.ecommerce.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.techies.ecommerce.common.security.UserPrincipal;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts the correlation id and caller id into the MDC so every log line written while
 * handling a request carries them.
 *
 * <p>Named LoggingContextFilter, not RequestContextFilter: Spring Boot already registers
 * a bean of that name for its own filter, and a clash silently breaks context startup.
 *
 * <p>This is what makes the log files joinable. One checkout touches order, identity,
 * catalog and inventory; without a shared id their four log files cannot be lined up, and
 * a failure looks like four unrelated events.
 */
public class LoggingContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Normally set by the gateway. Generated here as a fallback so a service called
        // directly (in tests, or service-to-service) still produces traceable logs.
        String requestId = request.getHeader(RequestContext.HEADER_REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        }

        String userId = request.getHeader(UserPrincipal.HEADER_USER_ID);

        MDC.put(RequestContext.MDC_REQUEST_ID, requestId);
        if (userId != null && !userId.isBlank()) {
            MDC.put(RequestContext.MDC_USER_ID, userId);
        }
        // Echo it back so a client (or a marker) can quote the id from a failed response.
        response.setHeader(RequestContext.HEADER_REQUEST_ID, requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are pooled and reused: leaving these set would mislabel the next request.
            MDC.remove(RequestContext.MDC_REQUEST_ID);
            MDC.remove(RequestContext.MDC_USER_ID);
        }
    }
}
