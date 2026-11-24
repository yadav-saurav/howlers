package app.howlers.community;

import app.howlers.topic.Topic;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "communities")
public class Community {
    public enum Visibility { PUBLIC, RESTRICTED, PRIVATE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, updatable = false)
    private String slug;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private String description = "";
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "topic_id")
    private Topic topic;
    private String iconUrl;
    private String bannerUrl;
    @Column(nullable = false)
    private String visibility = Visibility.PUBLIC.name();
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", nullable = false)
    private List<String> tags = new ArrayList<>();
    @Column(insertable = false, updatable = false)
    private int memberCount;
    @Column(insertable = false, updatable = false)
    private int postCount;
    private Long createdBy;
    /** ACTIVE | QUARANTINED | BANNED, set by platform admins with SQL (see AdminService). */
    @Column(insertable = false, updatable = false)
    private String status = "ACTIVE";
    @Column(insertable = false, updatable = false)
    private String statusReason;
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "community", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<CommunityRule> rules = new ArrayList<>();

    @OneToMany(mappedBy = "community", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<CommunityFlair> flairs = new ArrayList<>();

    protected Community() {}

    public Community(String slug, String name, String description, Topic topic, Visibility visibility,
                     List<String> tags, Long createdBy) {
        this.slug = slug;
        this.name = name;
        this.description = description == null ? "" : description;
        this.topic = topic;
        this.visibility = visibility.name();
        this.tags = new ArrayList<>(tags);
        this.createdBy = createdBy;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Visibility visibility() {
        return Visibility.valueOf(visibility);
    }

    public Long getId() { return id; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Topic getTopic() { return topic; }
    public void setTopic(Topic topic) { this.topic = topic; }
    public String getIconUrl() { return iconUrl; }
    public void setIconUrl(String iconUrl) { this.iconUrl = iconUrl; }
    public String getBannerUrl() { return bannerUrl; }
    public void setBannerUrl(String bannerUrl) { this.bannerUrl = bannerUrl; }
    public void setVisibility(Visibility v) { this.visibility = v.name(); }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = new ArrayList<>(tags); }
    public int getMemberCount() { return memberCount; }
    public int getPostCount() { return postCount; }
    public String getStatus() { return status == null ? "ACTIVE" : status; }
    public String getStatusReason() { return statusReason; }
    public boolean isBanned() { return "BANNED".equals(status); }
    public Instant getCreatedAt() { return createdAt; }
    public List<CommunityRule> getRules() { return rules; }
    public List<CommunityFlair> getFlairs() { return flairs; }
}
