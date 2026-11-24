package app.howlers.common.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Business exception translated into the standard error body by {@link GlobalExceptionHandler}. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, String> fieldErrors;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, String> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = fieldErrors;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Map<String, String> fieldErrors() { return fieldErrors; }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    public static ApiException conflict(String field, String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message,
                field == null ? Map.of() : Map.of(field, message));
    }

    public static ApiException field(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, Map.of(field, message));
    }

    public static ApiException rateLimited() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "You're doing that too often. Try again shortly.");
    }
}
