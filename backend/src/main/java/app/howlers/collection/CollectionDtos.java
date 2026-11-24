package app.howlers.collection;

import app.howlers.post.dto.PostDtos.PostSummary;
import app.howlers.user.dto.UserDtos.UserSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class CollectionDtos {
    private CollectionDtos() {}

    public static final String TAG = "^[a-z0-9-]{1,24}$";

    public record CollectionDto(long id, String name, String description, boolean shared, String shareToken,
                                int itemCount, List<String> previewImages, Instant updatedAt) {}

    public record CommentRef(long id, String excerpt, UserSummary author, int score, boolean deleted) {}

    public record ItemDto(long id, long collectionId, String type, PostSummary post, CommentRef comment, String note,
                          List<String> tags, double position, Instant createdAt) {}

    public record TagCount(String tag, int count) {}

    public record SharedCollection(CollectionDto collection, UserSummary owner, List<ItemDto> items) {}

    public record CreateCollection(@NotBlank @Size(max = 60) String name, @Size(max = 500) String description) {}

    public record UpdateCollection(@Size(min = 1, max = 60) String name, @Size(max = 500) String description, Boolean shared) {}

    public record AddItem(@NotNull @Pattern(regexp = "POST|COMMENT") String type, @NotNull Long postId, Long commentId,
                          @Size(max = 2000) String note, @Size(max = 8) List<@Pattern(regexp = TAG, message = "tags are lowercase words with dashes") String> tags) {}

    public record UpdateItem(@Size(max = 2000) String note, @Size(max = 8) List<@Pattern(regexp = TAG) String> tags, Long collectionId) {}

    public record Reorder(@NotNull @Size(max = 500) List<Long> itemIds) {}

    /** Which of the viewer's collections already contain a post/comment (for the "Add to collection" dialog). */
    public record Membership(long collectionId, long itemId) {}
}
