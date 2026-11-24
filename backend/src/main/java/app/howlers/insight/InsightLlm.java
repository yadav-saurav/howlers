package app.howlers.insight;

import java.util.Map;

/**
 * The model behind LLM-backed digests and stance suggestions. Production uses {@link ProviderInsightLlm}; tests use
 * a fake so the suite never calls the real API. Implementations return raw JSON text: callers always validate it.
 */
public interface InsightLlm {

    /** True when a model is configured and AI_PROVIDER is not "offline". */
    boolean available();

    /** Reader-facing name used in notes, e.g. "the AI model". */
    String label();

    record Completion(String json, boolean truncated, boolean refused, long inputTokens, long outputTokens,
                      long cacheReadTokens, long cacheWriteTokens) {}

    /** One schema-constrained call. Throws on transport/API errors. */
    Completion complete(String operation, String system, String user, Map<String, Object> schema, long maxTokens);
}
