package app.howlers.insight;

import app.howlers.ai.HostedLlmProvider;
import app.howlers.ai.HostedLlmProvider.StructuredText;
import app.howlers.common.config.AppProperties;
import java.util.Map;
import org.springframework.stereotype.Component;

/** {@link InsightLlm} backed by the shared {@link HostedLlmProvider} client (same key, model, effort and fallbacks). */
@Component
public class ProviderInsightLlm implements InsightLlm {
    private final HostedLlmProvider provider;
    private final boolean offline;

    public ProviderInsightLlm(HostedLlmProvider provider, AppProperties props) {
        this.provider = provider;
        this.offline = "offline".equals(props.ai().provider());
    }

    @Override
    public boolean available() {
        return !offline && provider.available();
    }

    @Override
    public String label() {
        return "the AI model";
    }

    @Override
    public Completion complete(String operation, String system, String user, Map<String, Object> schema, long maxTokens) {
        StructuredText t = provider.generateStructured(operation, system, user, schema, maxTokens);
        return new Completion(t.json(), t.truncated(), t.refused(), t.inputTokens(), t.outputTokens(), t.cacheReadTokens(),
                t.cacheWriteTokens());
    }
}
