package app.howlers.insight;

import app.howlers.insight.ThreadLoader.Node;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Hand-built thread nodes and a scriptable fake model for unit tests (the suite never calls the real API). */
final class InsightFixtures {
    private InsightFixtures() {}

    static Node node(long id, Long parent, String body, int score, double best, Stance stance, StanceSource source, Instant created) {
        return new Node(id, parent, parent == null ? 0 : 1, body, id * 10, new UserSummary(id * 10, "user" + id, null, null), score,
                Math.max(score, 0), 0, best, 0, 0, created, stance, source != StanceSource.READERS, Map.of(), null, source,
                ThreadLoader.contentHash("T", "P", body));
    }

    static Node node(long id, String body, Stance stance) {
        return node(id, null, body, 5, 0.5, stance, StanceSource.RULES, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(id));
    }

    /** Fake {@link InsightLlm}: returns whatever the script produces for the user turn and records every call. */
    static final class FakeLlm implements InsightLlm {
        final List<String> userTurns = new ArrayList<>();
        final List<String> systems = new ArrayList<>();
        Function<String, Completion> script;
        boolean available = true;

        FakeLlm(Function<String, Completion> script) {
            this.script = script;
        }

        static Completion ok(String json) {
            return new Completion(json, false, false, 1200, 300, 0, 0);
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String label() {
            return "AI model (fake)";
        }

        @Override
        public Completion complete(String operation, String system, String user, Map<String, Object> schema, long maxTokens) {
            systems.add(system);
            userTurns.add(user);
            return script.apply(user);
        }
    }
}
