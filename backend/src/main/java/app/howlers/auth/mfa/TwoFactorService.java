package app.howlers.auth.mfa;

import app.howlers.auth.AccountSecurityProperties;
import app.howlers.common.error.ApiException;
import app.howlers.common.text.Tokens;
import app.howlers.user.User;
import app.howlers.user.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TOTP two-factor authentication (D-042): enrolment, recovery codes, the second login step and the lockout.
 *
 * <ul>
 *   <li>Secrets are stored AES-GCM encrypted ({@link AccountSecretsCipher}), bound to the user id.</li>
 *   <li>Codes are accepted one 30-second step either side of now. The highest accepted step is stored and nothing at
 *       or before it is accepted again, so a code can't be replayed within its window.</li>
 *   <li>Every wrong code (TOTP or recovery, at login, on disable or on regenerate) counts towards the account's
 *       lockout: {@code maxAttempts} in a row lock the second step for {@code lockout}. A second-step token also dies
 *       after {@code maxAttempts} wrong codes.</li>
 *   <li>Recovery codes: 10 per set, about 50 bits each, stored as SHA-256 of (user id, normalised code), each usable once.</li>
 * </ul>
 */
@Service
public class TwoFactorService {
    private static final Logger log = LoggerFactory.getLogger(TwoFactorService.class);
    public static final int RECOVERY_CODES = 10;
    static final Duration PENDING_TTL = Duration.ofMinutes(15);
    private static final String RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Method { TOTP, RECOVERY }

    public record SetupResult(String secret, String otpauthUri, String qrSvg) {}

    public record Status(boolean enabled, Instant enabledAt, int recoveryCodesRemaining) {}

    public record LoginStep(long userId, Method method) {}

    private final JdbcClient jdbc;
    private final AccountSecretsCipher cipher;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AccountSecurityProperties.Mfa mfa;
    private volatile Clock clock = Clock.systemUTC();

    public TwoFactorService(JdbcClient jdbc, AccountSecretsCipher cipher, UserRepository users, PasswordEncoder encoder,
                            AccountSecurityProperties props) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.users = users;
        this.encoder = encoder;
        this.mfa = props.mfa();
    }

    /** Tests move time to exercise skew, reuse and lockout expiry. */
    public void useClock(Clock c) {
        this.clock = c;
    }

    private Instant now() {
        return clock.instant();
    }

    private record Row(String secretEnc, Instant enabledAt, String pendingEnc, Instant pendingAt, Long lastStep,
                       int failed, Instant lockedUntil) {}

    private Optional<Row> row(long userId, boolean lock) {
        return jdbc.sql("""
                        SELECT totp_secret_enc, totp_enabled_at, totp_pending_enc, totp_pending_at, totp_last_step,
                               mfa_failed_attempts, mfa_locked_until
                        FROM account_security WHERE user_id = ?""" + (lock ? " FOR UPDATE" : ""))
                .param(userId)
                .query((rs, n) -> new Row(rs.getString(1), ts(rs.getTimestamp(2)), rs.getString(3), ts(rs.getTimestamp(4)),
                        (Long) rs.getObject(5), rs.getInt(6), ts(rs.getTimestamp(7))))
                .optional();
    }

    private static Instant ts(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    void ensureRow(long userId) {
        jdbc.sql("INSERT INTO account_security (user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING").param(userId).update();
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(long userId) {
        return row(userId, false).map(r -> r.enabledAt() != null && r.secretEnc() != null).orElse(false);
    }

    @Transactional(readOnly = true)
    public Status status(long userId) {
        Row r = row(userId, false).orElse(null);
        boolean on = r != null && r.enabledAt() != null && r.secretEnc() != null;
        int left = on ? jdbc.sql("SELECT count(*) FROM totp_recovery_codes WHERE user_id = ? AND used_at IS NULL")
                .param(userId).query(Integer.class).single() : 0;
        return new Status(on, on ? r.enabledAt() : null, left);
    }

    // ---------------------------------------------------------------- enrolment

    @Transactional
    public SetupResult beginSetup(long userId, String password) {
        User u = requirePassword(userId, password);
        ensureRow(userId);
        Row r = row(userId, true).orElseThrow();
        if (r.enabledAt() != null) throw ApiException.conflict(null, "Two-factor authentication is already on");
        String secret = Totp.newSecret();
        jdbc.sql("UPDATE account_security SET totp_pending_enc = ?, totp_pending_at = ?, updated_at = now() WHERE user_id = ?")
                .params(cipher.encrypt(secret, purpose(userId)), java.sql.Timestamp.from(now()), userId).update();
        String uri = Totp.otpauthUri("Howlers", u.getUsername(), secret);
        return new SetupResult(secret, uri, QrSvg.render(uri));
    }

    /** Confirms the pending secret with a code from the app, turns 2FA on and returns the first recovery codes. */
    @Transactional(noRollbackFor = ApiException.class)
    public List<String> confirmSetup(long userId, String code) {
        ensureRow(userId);
        Row r = row(userId, true).orElseThrow();
        if (r.enabledAt() != null) throw ApiException.conflict(null, "Two-factor authentication is already on");
        if (r.pendingEnc() == null || r.pendingAt() == null || r.pendingAt().plus(PENDING_TTL).isBefore(now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SETUP_EXPIRED", "Setup expired. Start again to get a new QR code.");
        }
        requireNotLocked(r);
        String secret = cipher.decrypt(r.pendingEnc(), purpose(userId));
        OptionalLong step = Totp.verify(secret, code, now().getEpochSecond(), null);
        if (step.isEmpty()) {
            recordFailure(userId, r);
            throw invalidCode();
        }
        jdbc.sql("""
                        UPDATE account_security SET totp_secret_enc = totp_pending_enc, totp_enabled_at = now(),
                          totp_pending_enc = NULL, totp_pending_at = NULL, totp_last_step = ?, mfa_failed_attempts = 0,
                          mfa_locked_until = NULL, updated_at = now() WHERE user_id = ?""")
                .params(step.getAsLong(), userId).update();
        log.info("Two-factor authentication enabled for user {}", userId);
        return replaceRecoveryCodes(userId);
    }

    /** New set of recovery codes (the old ones stop working). Needs a current authenticator code. */
    @Transactional(noRollbackFor = ApiException.class)
    public List<String> regenerateRecoveryCodes(long userId, String code) {
        verify(userId, code, false);
        return replaceRecoveryCodes(userId);
    }

    /** Turns 2FA off: needs the password and a code (authenticator or recovery). */
    @Transactional(noRollbackFor = ApiException.class)
    public void disable(long userId, String password, String code) {
        requirePassword(userId, password);
        verify(userId, code, true);
        jdbc.sql("""
                        UPDATE account_security SET totp_secret_enc = NULL, totp_enabled_at = NULL, totp_pending_enc = NULL,
                          totp_pending_at = NULL, totp_last_step = NULL, updated_at = now() WHERE user_id = ?""")
                .param(userId).update();
        jdbc.sql("DELETE FROM totp_recovery_codes WHERE user_id = ?").param(userId).update();
        jdbc.sql("DELETE FROM mfa_login_challenges WHERE user_id = ?").param(userId).update();
        log.info("Two-factor authentication disabled for user {}", userId);
    }

    // ---------------------------------------------------------------- login second step

    /** After a correct password: a short-lived token for the code step. Not a session; grants nothing else. */
    @Transactional
    public String startLoginChallenge(long userId) {
        String raw = Tokens.random(32);
        jdbc.sql("INSERT INTO mfa_login_challenges (user_id, token_hash, expires_at) VALUES (?, ?, ?)")
                .params(userId, Tokens.sha256(raw), java.sql.Timestamp.from(now().plus(mfa.loginTokenTtl()))).update();
        return raw;
    }

    public Duration loginTokenTtl() {
        return mfa.loginTokenTtl();
    }

    /** Checks the second factor for a login token; the token is consumed on success. */
    @Transactional(noRollbackFor = ApiException.class)
    public LoginStep completeLoginChallenge(String rawToken, String code) {
        record Challenge(long id, long userId, int attempts, Instant expiresAt, Instant usedAt) {}
        Challenge c = rawToken == null ? null : jdbc.sql("""
                        SELECT id, user_id, attempts, expires_at, used_at FROM mfa_login_challenges
                        WHERE token_hash = ? FOR UPDATE""")
                .param(Tokens.sha256(rawToken))
                .query((rs, n) -> new Challenge(rs.getLong(1), rs.getLong(2), rs.getInt(3), ts(rs.getTimestamp(4)),
                        ts(rs.getTimestamp(5))))
                .optional().orElse(null);
        if (c == null || c.usedAt() != null || !c.expiresAt().isAfter(now()) || c.attempts() >= mfa.maxAttempts()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "MFA_TOKEN_EXPIRED", "That sign-in attempt expired. Enter your password again.");
        }
        try {
            Method m = verify(c.userId(), code, true);
            jdbc.sql("UPDATE mfa_login_challenges SET used_at = now() WHERE id = ?").param(c.id()).update();
            return new LoginStep(c.userId(), m);
        } catch (ApiException e) {
            jdbc.sql("UPDATE mfa_login_challenges SET attempts = attempts + 1 WHERE id = ?").param(c.id()).update();
            throw e;
        }
    }

    // ---------------------------------------------------------------- verification core

    /**
     * Verifies an authenticator code (6 digits) or, when allowed, a recovery code. Wrong codes count towards the
     * lockout; the row is locked so two requests can't both spend the same code.
     */
    Method verify(long userId, String code, boolean allowRecovery) {
        Row r = row(userId, true).filter(x -> x.enabledAt() != null && x.secretEnc() != null)
                .orElseThrow(() -> ApiException.badRequest("Two-factor authentication is not on"));
        requireNotLocked(r);
        String c = code == null ? "" : code.replaceAll("[\\s-]", "").toLowerCase(Locale.ROOT);
        if (c.matches("\\d{" + Totp.DIGITS + "}")) {
            String secret = cipher.decrypt(r.secretEnc(), purpose(userId));
            OptionalLong step = Totp.verify(secret, c, now().getEpochSecond(), r.lastStep());
            if (step.isPresent()) {
                jdbc.sql("""
                                UPDATE account_security SET totp_last_step = ?, mfa_failed_attempts = 0, mfa_locked_until = NULL,
                                  updated_at = now() WHERE user_id = ?""")
                        .params(step.getAsLong(), userId).update();
                return Method.TOTP;
            }
        } else if (allowRecovery && !c.isEmpty()) {
            int used = jdbc.sql("""
                            UPDATE totp_recovery_codes SET used_at = now()
                            WHERE user_id = ? AND code_hash = ? AND used_at IS NULL""")
                    .params(userId, recoveryHash(userId, c)).update();
            if (used == 1) {
                jdbc.sql("UPDATE account_security SET mfa_failed_attempts = 0, mfa_locked_until = NULL WHERE user_id = ?")
                        .param(userId).update();
                log.info("Recovery code used by user {}", userId);
                return Method.RECOVERY;
            }
        }
        recordFailure(userId, r);
        throw invalidCode();
    }

    private void requireNotLocked(Row r) {
        if (r.lockedUntil() != null && r.lockedUntil().isAfter(now())) {
            long minutes = Math.max(1, (Duration.between(now(), r.lockedUntil()).toSeconds() + 59) / 60);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "MFA_LOCKED",
                    "Too many wrong codes. Try again in " + minutes + (minutes == 1 ? " minute." : " minutes."));
        }
    }

    private void recordFailure(long userId, Row r) {
        int failed = r.failed() + 1;
        if (failed >= mfa.maxAttempts()) {
            jdbc.sql("UPDATE account_security SET mfa_failed_attempts = 0, mfa_locked_until = ?, updated_at = now() WHERE user_id = ?")
                    .params(java.sql.Timestamp.from(now().plus(mfa.lockout())), userId).update();
            log.warn("Two-factor step locked for user {} after {} wrong codes", userId, failed);
        } else {
            jdbc.sql("UPDATE account_security SET mfa_failed_attempts = ?, updated_at = now() WHERE user_id = ?")
                    .params(failed, userId).update();
        }
    }

    private static ApiException invalidCode() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CODE", "That code isn't right. Check your authenticator app and try again.");
    }

    private List<String> replaceRecoveryCodes(long userId) {
        jdbc.sql("DELETE FROM totp_recovery_codes WHERE user_id = ?").param(userId).update();
        List<String> codes = new ArrayList<>(RECOVERY_CODES);
        while (codes.size() < RECOVERY_CODES) {
            String code = newRecoveryCode();
            if (codes.contains(code)) continue;
            codes.add(code);
            jdbc.sql("INSERT INTO totp_recovery_codes (user_id, code_hash) VALUES (?, ?)")
                    .params(userId, recoveryHash(userId, code.replace("-", ""))).update();
        }
        return codes;
    }

    /** xxxxx-xxxxx from a 31-letter alphabet without look-alikes (no 0/o, 1/l/i). */
    static String newRecoveryCode() {
        StringBuilder sb = new StringBuilder(11);
        for (int i = 0; i < 10; i++) {
            if (i == 5) sb.append('-');
            sb.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
        }
        return sb.toString();
    }

    static String recoveryHash(long userId, String normalised) {
        return Tokens.sha256("recovery|" + userId + "|" + normalised);
    }

    private User requirePassword(long userId, String password) {
        User u = users.findById(userId).filter(User::isActive).orElseThrow(() -> ApiException.notFound("User"));
        if (password == null || !encoder.matches(password, u.getPasswordHash())) {
            throw ApiException.field("password", "Password is incorrect");
        }
        return u;
    }

    private static String purpose(long userId) {
        return "totp:" + userId;
    }

    @Scheduled(cron = "0 23 3 * * *")
    @Transactional
    public void purgeExpiredChallenges() {
        jdbc.sql("DELETE FROM mfa_login_challenges WHERE expires_at < now() - interval '1 day'").update();
    }
}
