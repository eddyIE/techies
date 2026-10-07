package vn.techies.ecommerce.order.client;

import feign.FeignException;
import feign.Request;
import feign.Response;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Builds the FeignException a real HTTP error response would produce, so a mocked client can
 * stand in for a service that answered 404 or 409 rather than one that was unreachable. The
 * saga reads {@code status()} to tell those apart.
 */
public final class FeignErrors {

    private FeignErrors() {
    }

    public static FeignException status(int status) {
        Map<String, Collection<String>> headers = Map.of();
        Request request = Request.create(Request.HttpMethod.POST, "http://loyalty-service/x",
                headers, new byte[0], null, null);
        Response response = Response.builder()
                .status(status)
                .reason("stubbed " + status)
                .request(request)
                .headers(Map.of("Content-Type", List.of("application/json")))
                .build();
        return FeignException.errorStatus("LoyaltyClient#consume", response);
    }
}
