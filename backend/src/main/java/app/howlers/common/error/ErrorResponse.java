package app.howlers.common.error;

import java.time.Instant;
import java.util.Map;

public record ErrorResponse(int status, String code, String message, Map<String, String> fieldErrors,
                            String traceId, Instant timestamp) {}
