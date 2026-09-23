package vn.techies.ecommerce.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import vn.techies.ecommerce.common.logging.RequestContext;

import java.util.UUID;

/**
 * Issues the correlation id for every request and records how each one ended.
 *
 * <p>The id is generated here, at the only public entry point, and forwarded to whichever
 * service handles the request. Every service then logs under the same id, so one user
 * action can be followed across four log files.
 *
 * <p>This filter also logs any non-2xx response, which catches problems no service can
 * report: a route that matched nothing (404), and a service that was unreachable (502).
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Honour an incoming id so a retry from the app stays on one trace, else mint one.
        String incoming = exchange.getRequest().getHeaders()
                .getFirst(RequestContext.HEADER_REQUEST_ID);
        String requestId = (incoming == null || incoming.isBlank())
                ? UUID.randomUUID().toString().substring(0, 8)
                : incoming;

        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(RequestContext.HEADER_REQUEST_ID, requestId)
                .build();
        exchange.getResponse().getHeaders().set(RequestContext.HEADER_REQUEST_ID, requestId);

        String method = request.getMethod().name();
        String path = request.getURI().getRawPath();

        return chain.filter(exchange.mutate().request(request).build())
                .doFinally(signal -> {
                    HttpStatusCode status = exchange.getResponse().getStatusCode();
                    if (status != null && status.isError()) {
                        // The MDC is not carried across reactor threads, so set it just around
                        // this call to keep the log pattern consistent with the other services.
                        MDC.put(RequestContext.MDC_REQUEST_ID, requestId);
                        try {
                            log.warn("GATEWAY PROBLEM {} {} -> {}", method, path, status.value());
                        } finally {
                            MDC.remove(RequestContext.MDC_REQUEST_ID);
                        }
                    }
                });
    }

    @Override
    public int getOrder() {
        // Ahead of authentication, so even a rejected request carries an id.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
