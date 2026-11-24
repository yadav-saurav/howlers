package app.howlers.ai;

import app.howlers.ai.AiDtos.Intent;
import app.howlers.ai.AiDtos.Source;
import java.util.List;

/**
 * Answer-generation backend. Implementations receive only already-permission-filtered sources, and must answer
 * from those alone, citing them as [n]. The service validates citations afterwards, whatever the provider.
 */
public interface AiProvider {
    String id();

    /** Human-readable model label shown with each answer, e.g. "Hosted AI model". */
    String label();

    boolean available();

    record Turn(String role, String text) {}

    record Request(String question, Intent intent, List<Source> sources, List<Turn> history, String scopeNote) {}

    record Answer(String text, List<String> followUps, String notice, Integer inputTokens, Integer outputTokens) {}

    Answer answer(Request request);
}
