package vn.techies.ecommerce.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.util.List;
import java.util.Map;

/**
 * Answers `GET /` with a short index instead of a 404.
 *
 * <p>Every real route lives under `/api`, so opening the base URL in a browser previously
 * returned a bare 404 that reads like an outage — particularly confusing behind a tunnel,
 * where a 404 and a dead tunnel look the same to someone testing by hand.
 */
@Configuration
public class RootEndpoint {

    @Bean
    public RouterFunction<ServerResponse> rootRoute() {
        return RouterFunctions.route()
                .GET("/", request -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of(
                                "service", "Techies E-Commerce API",
                                "status", "up",
                                "docs", "docs/API.md in the repository",
                                "publicEndpoints", List.of(
                                        "GET  /api/categories",
                                        "GET  /api/products?keyword=&page=&size=",
                                        "GET  /api/products/{id}",
                                        "GET  /api/stock/{productId}",
                                        "POST /api/auth/register",
                                        "POST /api/auth/login"),
                                "authenticatedEndpoints", List.of(
                                        "GET  /api/users/me",
                                        "GET  /api/addresses",
                                        "GET  /api/cart",
                                        "POST /api/checkout",
                                        "GET  /api/orders"),
                                "note", "Send Authorization: Bearer <token> for authenticated routes")))
                .build();
    }
}
