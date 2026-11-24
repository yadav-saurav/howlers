package app.howlers.post;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Write model for posts. Vote/comment counters and hot_rank are maintained with atomic SQL updates by
 * VoteService/CommentService and are therefore read-only here. Reads go through PostQueryService.
 */
@Entity
@Table(name = "posts")
public class Post {
    public enum Type { TEXT, LINK, IMAGE, POLL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, updatable = false)
    private Long communityId;
    private Long authorId;
    @Column(nullable = false, updatable = false)
    private String type;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false)
    private String bodyHtml = "";
    @Column(nullable = false)
    private String bodyText = "";
    private String url;
    private Long flairId;
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", nullable = false)
    private List<String> tags = new ArrayList<>();
    private double hotRank;
    private boolean pinned;
    private boolean locked;
    private boolean removed;
    /** Held for review by the spam heuristics (always together with removed = TRUE); written with SQL only. */
    @Column(insertable = false, updatable = false)
    private boolean pending;
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    private Instant editedAt;
    private Instant deletedAt;

    protected Post() {}

    public Post(Long communityId, Long authorId, Type type, String title, String bodyHtml, String bodyText, String url,
                Long flairId, List<String> tags, double hotRank) {
        this.communityId = communityId;
        this.authorId = authorId;
        this.type = type.name();
        this.title = title;
        this.bodyHtml = bodyHtml;
        this.bodyText = bodyText;
        this.url = url;
        this.flairId = flairId;
        this.tags = new ArrayList<>(tags);
        this.hotRank = hotRank;
    }

    public Long getId() { return id; }
    public Long getCommunityId() { return communityId; }
    public Long getAuthorId() { return authorId; }
    public Type type() { return Type.valueOf(type); }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBodyHtml() { return bodyHtml; }
    public String getBodyText() { return bodyText; }
    public void setBody(String html, String text) { this.bodyHtml = html; this.bodyText = text; }
    public String getUrl() { return url; }
    public Long getFlairId() { return flairId; }
    public void setFlairId(Long flairId) { this.flairId = flairId; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = new ArrayList<>(tags); }
    public boolean isLocked() { return locked; }
    public boolean isRemoved() { return removed; }
    public boolean isPending() { return pending; }
    public Instant getCreatedAt() { return createdAt; }
    public void markEdited() { this.editedAt = Instant.now(); }
    public Instant getDeletedAt() { return deletedAt; }
    public void softDelete() { this.deletedAt = Instant.now(); }
    public boolean isLive() { return deletedAt == null && !removed; }
}
