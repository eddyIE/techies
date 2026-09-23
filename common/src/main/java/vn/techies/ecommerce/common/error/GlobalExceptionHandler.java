package vn.techies.ecommerce.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns exceptions into the one error envelope from docs/SPEC.md, and makes sure every
 * failed request is written to the log file.
 *
 * <p>Registered by ErrorHandlingAutoConfiguration, which is gated on a servlet web
 * application so the reactive api-gateway never loads it. The gateway renders the same
 * envelope through its own handler.
 *
 * <p>Logging policy: nothing that fails is silent. 5xx is logged at ERROR with a stack
 * trace, because it is a defect. 4xx is logged at WARN without one, because it is expected
 * control flow that still needs to be visible — a stack trace for every declined payment
 * would bury the real faults. Both levels reach {@code logs/<service>-error.log}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest request) {
        logProblem(request, ex.code(), ex.getMessage(), ex);
        return respond(ex.code(), ApiError.of(ex.code(), ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex,
                                                     HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));

        // The field map is the whole point of logging this one: it says what the client sent wrong.
        logProblem(request, ErrorCode.VALIDATION_ERROR, "invalid fields " + fieldErrors, ex);

        return respond(ErrorCode.VALIDATION_ERROR, ApiError.of(ErrorCode.VALIDATION_ERROR,
                "Request validation failed", request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex,
                                                     HttpServletRequest request) {
        logProblem(request, ErrorCode.MALFORMED_REQUEST, "unparseable request body", ex);
        return respond(ErrorCode.MALFORMED_REQUEST, ApiError.of(ErrorCode.MALFORMED_REQUEST,
                "Request body is missing or malformed", request.getRequestURI()));
    }

    /**
     * An unknown URL is a 404, not an incident. Without this the catch-all below would turn
     * every mistyped path into a 500 with a logged stack trace.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex,
                                                     HttpServletRequest request) {
        logProblem(request, ErrorCode.NOT_FOUND, "no such endpoint", ex);
        return respond(ErrorCode.NOT_FOUND, ApiError.of(ErrorCode.NOT_FOUND,
                "No endpoint " + request.getRequestURI(), request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        logProblem(request, ErrorCode.INTERNAL_ERROR, ex.toString(), ex);
        // The caller gets a generic message: internal detail stays in the log file.
        return respond(ErrorCode.INTERNAL_ERROR, ApiError.of(ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred", request.getRequestURI()));
    }

    /**
     * The single place every failed request is recorded, so no handler can forget to log.
     * requestId and userId are added by the log pattern from the MDC.
     */
    private static void logProblem(HttpServletRequest request, ErrorCode code,
                                   String detail, Exception ex) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        String query = request.getQueryString();
        String fullPath = query == null ? path : path + "?" + query;

        if (code.status() >= 500) {
            log.error("API FAILURE {} {} -> {} {} | {}", method, fullPath, code.status(), code, detail, ex);
        } else {
            log.warn("API PROBLEM {} {} -> {} {} | {}", method, fullPath, code.status(), code, detail);
        }
    }

    private static ResponseEntity<ApiError> respond(ErrorCode code, ApiError body) {
        return ResponseEntity.status(code.status()).body(body);
    }
}
