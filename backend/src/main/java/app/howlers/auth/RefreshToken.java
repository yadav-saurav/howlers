package app.howlers.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One step in a rotation chain. All rows sharing {@code familyId} form one device session. */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long userId;
    @Column(nullable = false)
    private UUID familyId;
    @Column(nullable = false, length = 64)
    private String tokenHash;
    @Column(nullable = false)
    private Instant expiresAt;
    private Instant revokedAt;
    private Instant rotatedAt;
    private String userAgent;
    private String ip;
    /** PASSWORD | PASSWORD_TOTP | PASSWORD_RECOVERY | PASSKEY; carried through rotation. Null for sessions before V18. */
    @Column(length = 20)
    private String authMethod;
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(nullable = false)
    private Instant lastUsedAt = Instant.now();

    protected RefreshToken() {}

    public RefreshToken(Long userId, UUID familyId, String tokenHash, Instant expiresAt, String userAgent, String ip) {
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent;
        this.ip = ip;
    }

    public RefreshToken(Long userId, UUID familyId, String tokenHash, Instant expiresAt, String userAgent, String ip,
                        String authMethod) {
        this(userId, familyId, tokenHash, expiresAt, userAgent, ip);
        this.authMethod = authMethod;
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && rotatedAt == null && expiresAt.isAfter(now);
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public UUID getFamilyId() { return familyId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getRotatedAt() { return rotatedAt; }
    public void markRotated(Instant at) { this.rotatedAt = at; }
    public String getUserAgent() { return userAgent; }
    public String getIp() { return ip; }
    public String getAuthMethod() { return authMethod; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
}
