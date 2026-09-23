package vn.techies.ecommerce.common.error;

/**
 * Base for every deliberate, business-level failure. Carries the ErrorCode, which decides
 * the HTTP status — handlers never pick a status independently.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ApiException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.NOT_FOUND, what + " not found");
    }

    public static ApiException forbidden(String what) {
        return new ApiException(ErrorCode.FORBIDDEN, "Not permitted: " + what);
    }

    public static ApiException conflict(ErrorCode code, String message) {
        return new ApiException(code, message);
    }
}
