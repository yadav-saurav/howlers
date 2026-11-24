package app.howlers.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "email_tokens")
public class EmailToken {
    public enum Purpose { VERIFY_EMAIL, RESET_PASSWORD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long userId;
    @Column(nullable = false)
    private String purpose;
    @Column(nullable = false, length = 64)
    private String tokenHash;
    @Column(nullable = false)
    private Instant expiresAt;
    private Instant usedAt;
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected EmailToken() {}

    public EmailToken(Long userId, Purpose purpose, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.purpose = purpose.name();
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public Long getUserId() { return userId; }
    public String getPurpose() { return purpose; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public void markUsed() { this.usedAt = Instant.now(); }
}
