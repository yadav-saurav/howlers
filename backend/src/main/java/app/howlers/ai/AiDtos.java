package app.howlers.ai;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class AiDtos {
    private AiDtos() {}

    /** What the question is mainly asking for; nudges retrieval ranking and the answer's shape. */
    public enum Intent { QUESTION, RESOURCES, COMPARE, DEBATE, SUMMARY, TRENDS }

    /**
     * One retrieved piece of community content, numbered in the order shown to the model so that "[n]"
     * in an answer always maps to sources[n-1]. {@code cited} marks the ones the answer actually used.
     */
    public record Source(int n, String kind, long postId, Long commentId, String title, String communitySlug,
                         String author, int score, Instant createdAt, String excerpt, String link, boolean cited) {}

    public record Scope(Long communityId, String communitySlug, Long postId, String postTitle, Integer sinceDays) {}

    public record MessageDto(long id, String role, String content, String engine, String intent, List<Source> sources,
                             List<String> followUps, String notice, int feedback, Instant createdAt) {}

    public record ConversationSummary(long id, String title, String communitySlug, Long postId, Instant updatedAt) {}

    public record ConversationDto(long id, String title, String communitySlug, Long postId, Instant createdAt,
                                  List<MessageDto> messages) {}

    public record AskRequest(Long conversationId, @NotBlank @Size(max = 1000) String question,
                             @Size(max = 24) String communitySlug, Long postId,
                             @Min(1) @Max(3650) Integer sinceDays) {}

    public record AskResponse(ConversationSummary conversation, MessageDto question, MessageDto answer) {}

    public record FeedbackRequest(@NotNull @Min(-1) @Max(1) Integer value) {}

    public record Status(String provider, String model, boolean llmConfigured, String note) {}
}
