package vn.techies.ecommerce.common.logging;

/** MDC keys and the header that carries the correlation id between services. */
public final class RequestContext {

    /** Correlation id. Generated at the gateway and propagated to every service. */
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_USER_ID = "userId";

    private RequestContext() {
    }
}
