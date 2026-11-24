package app.howlers.common.error;

import app.howlers.common.web.TraceIdFilter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every failure to the single error shape documented in docs/API.md. Never leaks internals. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> api(ApiException e) {
        return build(e.status(), e.code(), e.getMessage(), e.fieldErrors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are invalid", fields);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorResponse> invalidParams(HandlerMethodValidationException e) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid request parameters", Map.of());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    ResponseEntity<ErrorResponse> unreadable(Exception e) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Malformed request", Map.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> tooLarge(MaxUploadSizeExceededException e) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "BAD_REQUEST", "File is too large", Map.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> integrity(DataIntegrityViolationException e) {
        log.info("Integrity violation: {}", e.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, "CONFLICT", "The request conflicts with existing data", Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> denied(AccessDeniedException e) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to do that", Map.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ErrorResponse> unauthenticated(AuthenticationException e) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required", Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> method(HttpRequestMethodNotSupportedException e) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "BAD_REQUEST", "Method not allowed", Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> noResource(NoResourceFoundException e) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong on our side", Map.of());
    }

    public static ErrorResponse body(HttpStatus status, String code, String message) {
        return new ErrorResponse(status.value(), code, message, Map.of(), MDC.get(TraceIdFilter.MDC_KEY), Instant.now());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message, Map<String, String> fields) {
        return ResponseEntity.status(status).body(
                new ErrorResponse(status.value(), code, message, fields, MDC.get(TraceIdFilter.MDC_KEY), Instant.now()));
    }
}
