package vn.techies.ecommerce.common.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the error envelope shape from docs/SPEC.md. The mobile client parses these fields,
 * so a change here is a breaking API change and should fail loudly.
 */
class ApiErrorSerializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mapper.configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    @Test
    @DisplayName("serializes exactly the five documented fields when there are no field errors")
    void serializesDocumentedShape() throws Exception {
        ApiError error = ApiError.of(ErrorCode.INSUFFICIENT_STOCK, "Not enough stock", "/api/checkout");

        JsonNode json = mapper.readTree(mapper.writeValueAsString(error));

        assertThat(json.get("status").asInt()).isEqualTo(409);
        assertThat(json.get("code").asText()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(json.get("message").asText()).isEqualTo("Not enough stock");
        assertThat(json.get("path").asText()).isEqualTo("/api/checkout");
        assertThat(json.get("timestamp").asText()).isNotBlank();
        assertThat(json.has("fieldErrors")).as("omitted when null").isFalse();
    }

    @Test
    @DisplayName("includes fieldErrors only when validation actually failed")
    void includesFieldErrors() throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("quantity", "must be greater than 0");

        ApiError error = ApiError.of(ErrorCode.VALIDATION_ERROR, "Request validation failed",
                "/api/cart/items", fields);
        JsonNode json = mapper.readTree(mapper.writeValueAsString(error));

        assertThat(json.get("status").asInt()).isEqualTo(400);
        assertThat(json.get("fieldErrors").get("quantity").asText()).isEqualTo("must be greater than 0");
    }

    @Test
    @DisplayName("an empty field-error map is omitted rather than serialized as {}")
    void emptyFieldErrorsOmitted() throws Exception {
        ApiError error = ApiError.of(ErrorCode.CONFLICT, "boom", "/x", Map.of());
        JsonNode json = mapper.readTree(mapper.writeValueAsString(error));

        assertThat(json.has("fieldErrors")).isFalse();
    }

    @Test
    @DisplayName("every ErrorCode maps to a sane HTTP status")
    void everyCodeHasValidStatus() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.status())
                    .as("status for %s", code)
                    .isBetween(400, 599);
        }
    }
}
