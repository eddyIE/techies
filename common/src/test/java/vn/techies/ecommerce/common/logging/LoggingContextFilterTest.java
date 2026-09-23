package vn.techies.ecommerce.common.logging;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import vn.techies.ecommerce.common.security.UserPrincipal;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingContextFilterTest {

    private final LoggingContextFilter filter = new LoggingContextFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** Captures what the MDC held *during* the request, since it is cleared afterwards. */
    private FilterChain capturing(AtomicReference<String> requestId, AtomicReference<String> userId) {
        return (req, res) -> {
            requestId.set(MDC.get(RequestContext.MDC_REQUEST_ID));
            userId.set(MDC.get(RequestContext.MDC_USER_ID));
        };
    }

    @Test
    @DisplayName("an incoming correlation id is reused, keeping one action on one trace")
    void reusesIncomingRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/cart");
        request.addHeader(RequestContext.HEADER_REQUEST_ID, "abc12345");
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), capturing(seen, new AtomicReference<>()));

        assertThat(seen.get()).isEqualTo("abc12345");
    }

    @Test
    @DisplayName("a missing correlation id is generated, so no request is untraceable")
    void generatesRequestIdWhenAbsent() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest("GET", "/cart"), new MockHttpServletResponse(),
                capturing(seen, new AtomicReference<>()));

        assertThat(seen.get()).isNotBlank();
    }

    @Test
    @DisplayName("the caller id is exposed to the logs when the gateway supplied one")
    void putsUserIdInMdc() throws Exception {
        UUID userId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/cart");
        request.addHeader(UserPrincipal.HEADER_USER_ID, userId.toString());
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), capturing(new AtomicReference<>(), seen));

        assertThat(seen.get()).isEqualTo(userId.toString());
    }

    @Test
    @DisplayName("the correlation id is echoed back so a failed response can be quoted")
    void echoesRequestIdOnResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/cart"), response, (req, res) -> {
        });

        assertThat(response.getHeader(RequestContext.HEADER_REQUEST_ID)).isNotBlank();
    }

    @Test
    @DisplayName("the MDC is cleared afterwards, so a pooled thread cannot mislabel the next request")
    void clearsMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/cart");
        request.addHeader(RequestContext.HEADER_REQUEST_ID, "abc12345");
        request.addHeader(UserPrincipal.HEADER_USER_ID, UUID.randomUUID().toString());

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
        });

        assertThat(MDC.get(RequestContext.MDC_REQUEST_ID)).isNull();
        assertThat(MDC.get(RequestContext.MDC_USER_ID)).isNull();
    }

    @Test
    @DisplayName("the MDC is cleared even when the handler throws")
    void clearsMdcWhenHandlerThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/cart");
        request.addHeader(RequestContext.HEADER_REQUEST_ID, "abc12345");

        try {
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception expected) {
            // The point of the test is what the MDC looks like afterwards.
        }

        assertThat(MDC.get(RequestContext.MDC_REQUEST_ID)).isNull();
    }
}
