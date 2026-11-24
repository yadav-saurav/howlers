package app.howlers.common.security;

import app.howlers.common.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/** Issues and verifies short-lived HS256 access tokens. Refresh tokens are opaque and live in the DB. */
@Service
public class JwtService {
    private final SecretKey key;
    private final AppProperties.Jwt cfg;

    public JwtService(AppProperties props) {
        this.cfg = props.jwt();
        byte[] bytes = Decoders.BASE64.decode(cfg.secret());
        if (bytes.length < 32) {
            throw new IllegalStateException("howlers.jwt.secret must be at least 256 bits (base64)");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    public String issueAccessToken(long userId, String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(cfg.issuer())
                .subject(Long.toString(userId))
                .claim("username", username)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(cfg.accessTtl())))
                .signWith(key)
                .compact();
    }

    public long accessTtlSeconds() {
        return cfg.accessTtl().toSeconds();
    }

    public Optional<AuthUser> verify(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).requireIssuer(cfg.issuer()).build()
                    .parseSignedClaims(token).getPayload();
            return Optional.of(new AuthUser(Long.parseLong(c.getSubject()),
                    c.get("username", String.class), c.get("role", String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
