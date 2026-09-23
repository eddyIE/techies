package vn.techies.ecommerce.order.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import feign.Feign;
import feign.Retryer;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.techies.ecommerce.order.config.InventoryFeignConfig;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the retry policy that protects the saga's most important call.
 *
 * <p>Retrying inventory is only safe because deduct and restore are idempotent on
 * (order_ref, type) — a retry returns the original movement instead of moving stock twice.
 */
class InventoryFeignRetryTest {

    private WireMockServer wireMock;

    @BeforeEach
    void startServer() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
    }

    @AfterEach
    void stopServer() {
        wireMock.stop();
    }

    private InventoryClient clientWithRetry() {
        ObjectMapper mapper = new ObjectMapper();
        return Feign.builder()
                .contract(new SpringMvcContract())
                .encoder(new JacksonEncoder(mapper))
                .decoder(new JacksonDecoder(mapper))
                .retryer(new InventoryFeignConfig().inventoryRetryer())
                .target(InventoryClient.class, wireMock.baseUrl() + "/stock");
    }

    private InventoryClient clientWithoutRetry() {
        ObjectMapper mapper = new ObjectMapper();
        return Feign.builder()
                .contract(new SpringMvcContract())
                .encoder(new JacksonEncoder(mapper))
                .decoder(new JacksonDecoder(mapper))
                .retryer(Retryer.NEVER_RETRY)
                .target(InventoryClient.class, wireMock.baseUrl() + "/stock");
    }

    private InventoryClient.StockMovementRequest request() {
        return new InventoryClient.StockMovementRequest("ORD-20260920-0001",
                List.of(new InventoryClient.StockLine(UUID.randomUUID(), 1)));
    }

    @Test
    @DisplayName("a dropped connection on deduct is retried once and then succeeds")
    void deductRetriesOnceThenSucceeds() {
        // A transport fault, not a 5xx response: Feign's default ErrorDecoder only raises a
        // RetryableException for connection-level failures, which is exactly the case that
        // would otherwise strand stock.
        wireMock.stubFor(post(urlEqualTo("/stock/deduct"))
                .inScenario("flaky").whenScenarioStateIs("Started")
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("recovered"));

        wireMock.stubFor(post(urlEqualTo("/stock/deduct"))
                .inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"orderRef":"ORD-20260920-0001",
                                 "movementId":"11111111-1111-1111-1111-111111111111",
                                 "deducted":true,"restored":false}""")));

        InventoryClient.MovementResponse response = clientWithRetry().deduct(request());

        assertThat(response.deducted()).isTrue();
        wireMock.verify(2, postRequestedFor(urlEqualTo("/stock/deduct")));
    }

    @Test
    @DisplayName("the retry is bounded: a persistently unreachable deduct gives up after two attempts")
    void deductGivesUpAfterTwoAttempts() {
        wireMock.stubFor(post(urlEqualTo("/stock/deduct"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> clientWithRetry().deduct(request()))
                .isInstanceOf(Exception.class);

        wireMock.verify(2, postRequestedFor(urlEqualTo("/stock/deduct")));
    }

    @Test
    @DisplayName("a 409 INSUFFICIENT_STOCK is a business answer, not a transient fault, so it is not retried")
    void businessConflictIsNotRetried() {
        wireMock.stubFor(post(urlEqualTo("/stock/deduct"))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"INSUFFICIENT_STOCK\"}")));

        assertThatThrownBy(() -> clientWithRetry().deduct(request()))
                .isInstanceOf(Exception.class);

        wireMock.verify(1, postRequestedFor(urlEqualTo("/stock/deduct")));
    }

    @Test
    @DisplayName("a 5xx response is NOT retried, only transport faults are")
    void serverErrorResponseIsNotRetried() {
        // Documents the boundary of the policy: a service that answers 503 has been reached
        // and gave an answer, so Feign treats it as a decided outcome rather than a fault.
        wireMock.stubFor(post(urlEqualTo("/stock/deduct"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> clientWithRetry().deduct(request()))
                .isInstanceOf(Exception.class);

        wireMock.verify(1, postRequestedFor(urlEqualTo("/stock/deduct")));
    }

    @Test
    @DisplayName("without the inventory config there is no retry, confirming it is opt-in per client")
    void otherClientsDoNotRetry() {
        wireMock.stubFor(post(urlEqualTo("/stock/deduct"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> clientWithoutRetry().deduct(request()))
                .isInstanceOf(Exception.class);

        wireMock.verify(1, postRequestedFor(urlEqualTo("/stock/deduct")));
    }
}
