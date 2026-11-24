package app.howlers.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {
    public enum Status { ACTIVE, DEACTIVATED, DELETED, SUSPENDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 21)
    private String username;
    @Column(nullable = false)
    private String email;
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    private String displayName;
    private String bio;
    private String avatarUrl;
    private String bannerUrl;
    @Column(nullable = false)
    private String role = "USER";
    @Column(nullable = false)
    private String status = Status.ACTIVE.name();
    private boolean emailVerified;
    private boolean onboardingCompleted;
    @Column(insertable = false, updatable = false)
    private int postKarma;
    @Column(insertable = false, updatable = false)
    private int commentKarma;
    @Column(insertable = false, updatable = false)
    private int followerCount;
    @Column(insertable = false, updatable = false)
    private int followingCount;

    private String theme = "dark";
    private boolean showNsfw;
    private boolean profilePublic = true;
    private boolean showCommunities = true;
    private boolean emailNotifications = true;
    @Column(name = "dm_policy", nullable = false, length = 10)
    private String dmPolicy = "EVERYONE";

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
    private Instant deletedAt;

    protected User() {}

    public User(String username, String email, String passwordHash, String displayName) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public boolean isActive() {
        return Status.ACTIVE.name().equals(status);
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public String getBannerUrl() { return bannerUrl; }
    public void setBannerUrl(String bannerUrl) { this.bannerUrl = bannerUrl; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getStatus() { return status; }
    public void setStatus(Status status) { this.status = status.name(); }
    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
    public boolean isOnboardingCompleted() { return onboardingCompleted; }
    public void setOnboardingCompleted(boolean v) { this.onboardingCompleted = v; }
    public int getPostKarma() { return postKarma; }
    public int getCommentKarma() { return commentKarma; }
    public int getFollowerCount() { return followerCount; }
    public int getFollowingCount() { return followingCount; }
    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }
    public boolean isShowNsfw() { return showNsfw; }
    public void setShowNsfw(boolean showNsfw) { this.showNsfw = showNsfw; }
    public boolean isProfilePublic() { return profilePublic; }
    public void setProfilePublic(boolean profilePublic) { this.profilePublic = profilePublic; }
    public boolean isShowCommunities() { return showCommunities; }
    public void setShowCommunities(boolean showCommunities) { this.showCommunities = showCommunities; }
    public boolean isEmailNotifications() { return emailNotifications; }
    public void setEmailNotifications(boolean emailNotifications) { this.emailNotifications = emailNotifications; }
    public String getDmPolicy() { return dmPolicy; }
    public void setDmPolicy(String dmPolicy) { this.dmPolicy = dmPolicy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
}
