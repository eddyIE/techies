package vn.techies.ecommerce.common.error;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the logging policy: no failed request is silent, 5xx carries a stack trace and 4xx
 * does not. Both levels are >= WARN so both reach logs/&lt;service&gt;-error.log.
 */
class GlobalExceptionHandlerLoggingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    private ILoggingEvent onlyEvent() {
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0);
    }

    @Test
    @DisplayName("a business failure is logged at WARN, without a stack trace")
    void businessFailureLoggedAtWarn() {
        handler.handleApi(new ApiException(ErrorCode.INSUFFICIENT_STOCK, "not enough"),
                request("POST", "/checkout"));

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getThrowableProxy()).as("4xx needs no stack trace").isNull();
    }

    @Test
    @DisplayName("the log line carries method, path, status and error code")
    void logLineCarriesRequestContext() {
        handler.handleApi(new ApiException(ErrorCode.INSUFFICIENT_STOCK, "not enough"),
                request("POST", "/checkout"));

        assertThat(onlyEvent().getFormattedMessage())
                .contains("POST", "/checkout", "409", "INSUFFICIENT_STOCK", "not enough");
    }

    @Test
    @DisplayName("the query string is logged too, since it often explains the failure")
    void logsQueryString() {
        MockHttpServletRequest request = request("GET", "/products");
        request.setQueryString("keyword=x&size=9999");

        handler.handleApi(new ApiException(ErrorCode.VALIDATION_ERROR, "bad"), request);

        assertThat(onlyEvent().getFormattedMessage()).contains("/products?keyword=x&size=9999");
    }

    @Test
    @DisplayName("an unexpected failure is logged at ERROR, with the stack trace")
    void unexpectedFailureLoggedAtError() {
        handler.handleUnexpected(new IllegalStateException("boom"), request("GET", "/orders"));

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getThrowableProxy()).as("5xx is a defect, keep the stack").isNotNull();
    }

    @Test
    @DisplayName("an unknown endpoint is logged as a problem, not swallowed")
    void unknownEndpointLogged() {
        handler.handleNoResource(
                new org.springframework.web.servlet.resource.NoResourceFoundException(
                        org.springframework.http.HttpMethod.GET, "/nope"),
                request("GET", "/nope"));

        assertThat(onlyEvent().getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    @DisplayName("a malformed body is logged as a problem")
    void malformedBodyLogged() {
        handler.handleUnreadable(
                new org.springframework.http.converter.HttpMessageNotReadableException(
                        "bad json", (org.springframework.http.HttpInputMessage) null),
                request("POST", "/cart/items"));

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("MALFORMED_REQUEST");
    }

    @Test
    @DisplayName("the response body never leaks internal detail that only belongs in the log")
    void responseBodyStaysGeneric() {
        var response = handler.handleUnexpected(
                new IllegalStateException("connection string user=admin password=hunter2"),
                request("GET", "/orders"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
        assertThat(onlyEvent().getFormattedMessage()).as("detail is kept, in the log only")
                .contains("hunter2");
    }
}
