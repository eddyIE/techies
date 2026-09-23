package vn.techies.ecommerce.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * The single error envelope returned by every service. Shape is fixed by docs/SPEC.md;
 * ApiErrorSerializationTest pins it field-for-field.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        Map<String, String> fieldErrors) {

    public static ApiError of(ErrorCode code, String message, String path) {
        return new ApiError(Instant.now(), code.status(), code.name(), message, path, null);
    }

    public static ApiError of(ErrorCode code, String message, String path, Map<String, String> fieldErrors) {
        return new ApiError(Instant.now(), code.status(), code.name(), message, path,
                fieldErrors == null || fieldErrors.isEmpty() ? null : fieldErrors);
    }
}
