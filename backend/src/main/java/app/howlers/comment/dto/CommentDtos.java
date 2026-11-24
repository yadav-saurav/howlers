package app.howlers.comment.dto;

import app.howlers.community.dto.CommunityDtos.CommunityRef;
import app.howlers.user.dto.UserDtos.UserSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class CommentDtos {
    private CommentDtos() {}

    public record CommentNode(long id, long postId, Long parentId, int depth, String body, UserSummary author,
                              int score, int myVote, boolean deleted, boolean removed, Instant createdAt,
                              Instant editedAt, int replyCount, List<CommentNode> replies, int moreReplies, boolean blocked,
                              boolean pending, boolean muted) {}

    public record CreateCommentRequest(@NotBlank @Size(max = 10_000) String body, Long parentId) {}

    public record UpdateCommentRequest(@NotBlank @Size(max = 10_000) String body) {}

    public record PostRef(long id, String title, CommunityRef community) {}

    public record UserComment(CommentNode comment, PostRef post) {}
}
