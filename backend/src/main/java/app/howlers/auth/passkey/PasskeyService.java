package app.howlers.auth.passkey;

import app.howlers.auth.AccountSecurityProperties;
import app.howlers.auth.device.UserAgentFamily;
import app.howlers.common.config.AppProperties;
import app.howlers.common.error.ApiException;
import app.howlers.common.text.Tokens;
import app.howlers.user.User;
import app.howlers.user.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.InvalidSignatureCountException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import java.io.IOException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Passkeys (WebAuthn) with Yubico's {@code webauthn-server-core} (D-041).
 *
 * <ul>
 *   <li>Relying party id and allowed origins come from {@code howlers.account-security.webauthn} (defaulting to the
 *       host and origin of {@code APP_PUBLIC_URL}, so {@code localhost} works in development).</li>
 *   <li>Credentials are discoverable (resident) so sign-in needs no username, and user verification (PIN or
 *       biometric on the device) is required, which is why a passkey sign-in satisfies two-factor authentication.</li>
 *   <li>Challenges are stored server-side, expire after {@link #CHALLENGE_TTL}, are consumed by the first finish
 *       attempt whatever its outcome, and are bound to the browser that started them by an HttpOnly cookie (only its
 *       hash is stored). Registration challenges are also bound to the signed-in user.</li>
 *   <li>A signature counter that fails to increase refuses the sign-in and flags the passkey; flagged passkeys are
 *       refused until the user removes them.</li>
 * </ul>
 * Only public keys, counters, flags and the random user handle are stored. Biometrics never leave the device.
 */
@Service
public class PasskeyService {
    private static final Logger log = LoggerFactory.getLogger(PasskeyService.class);
    static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    static final long CEREMONY_TIMEOUT_MS = 120_000;
    public static final int MAX_PASSKEYS = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record CeremonyStart(String requestId, JsonNode publicKey) {}

    public record PasskeyDto(long id, String name, Instant createdAt, Instant lastUsedAt, boolean synced,
                             boolean flagged, List<String> transports) {}

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final ObjectMapper mapper;
    private final RelyingParty rp;
    private final String rpId;
    private final Set<String> origins;

    public PasskeyService(JdbcClient jdbc, UserRepository users, ObjectMapper mapper, AccountSecurityProperties props,
                          AppProperties app) {
        this.jdbc = jdbc;
        this.users = users;
        this.mapper = mapper;
        AccountSecurityProperties.Webauthn w = props.webauthn();
        this.rpId = w.resolvedRpId(app.app().publicUrl());
        this.origins = w.resolvedOrigins(app.app().publicUrl());
        this.rp = RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder().id(rpId).name(w.resolvedRpName()).build())
                .credentialRepository(new PasskeyCredentialRepository(jdbc))
                .origins(origins)
                .build();
        log.info("Passkeys: relying party id {}, origins {}", rpId, origins);
    }

    public String rpId() { return rpId; }

    public Set<String> origins() { return origins; }

    // ---------------------------------------------------------------- registration

    @Transactional
    public CeremonyStart startRegistration(long userId, String binding) {
        User u = users.findById(userId).filter(User::isActive).orElseThrow(() -> ApiException.notFound("User"));
        int count = jdbc.sql("SELECT count(*) FROM user_passkeys WHERE user_id = ?").param(userId).query(Integer.class).single();
        if (count >= MAX_PASSKEYS) throw ApiException.badRequest("You can add up to " + MAX_PASSKEYS + " passkeys");
        PublicKeyCredentialCreationOptions options = rp.startRegistration(StartRegistrationOptions.builder()
                .user(UserIdentity.builder()
                        .name(u.getUsername())
                        .displayName(u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getUsername() : u.getDisplayName())
                        .id(userHandle(userId))
                        .build())
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .build())
                .timeout(CEREMONY_TIMEOUT_MS)
                .build());
        try {
            String requestId = saveChallenge("REGISTER", userId, binding, options.toJson());
            return new CeremonyStart(requestId, mapper.readTree(options.toCredentialsCreateJson()).get("publicKey"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional(noRollbackFor = ApiException.class)
    public PasskeyDto finishRegistration(long userId, String requestId, String binding, JsonNode credential, String name,
                                         String userAgent) {
        String request = consumeChallenge(requestId, "REGISTER", binding, userId);
        RegistrationResult result;
        try {
            PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> pkc =
                    PublicKeyCredential.parseRegistrationResponseJson(mapper.writeValueAsString(credential));
            result = rp.finishRegistration(FinishRegistrationOptions.builder()
                    .request(PublicKeyCredentialCreationOptions.fromJson(request))
                    .response(pkc)
                    .build());
        } catch (RegistrationFailedException e) {
            log.info("Passkey registration refused for user {}: {}", userId, e.getMessage());
            throw invalid("The passkey couldn't be verified. Try again.");
        } catch (IOException | IllegalArgumentException | NullPointerException e) {
            throw invalid("The browser sent a passkey response this server can't read.");
        }
        String label = cleanName(name, userAgent);
        String transports = result.getKeyId().getTransports()
                .map(ts -> ts.stream().map(AuthenticatorTransport::getId).collect(Collectors.joining(","))).orElse(null);
        Long id;
        try {
            id = jdbc.sql("""
                            INSERT INTO user_passkeys (user_id, credential_id, public_key_cose, sign_count, name, transports,
                              discoverable, backup_eligible, backed_up)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                    .params(userId, result.getKeyId().getId().getBytes(), result.getPublicKeyCose().getBytes(),
                            result.getSignatureCount(), label, transports, result.isDiscoverable().orElse(null),
                            result.isBackupEligible(), result.isBackedUp())
                    .query(Long.class).single();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict(null, "That passkey is already registered");
        }
        log.info("Passkey {} added for user {}", id, userId);
        return list(userId).stream().filter(p -> p.id() == id).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- authentication (usernameless)

    @Transactional
    public CeremonyStart startAuthentication(String binding) {
        AssertionRequest req = rp.startAssertion(StartAssertionOptions.builder()
                .userVerification(UserVerificationRequirement.REQUIRED)
                .timeout(CEREMONY_TIMEOUT_MS)
                .build());
        try {
            String requestId = saveChallenge("AUTHENTICATE", null, binding, req.toJson());
            return new CeremonyStart(requestId, mapper.readTree(req.toCredentialsGetJson()).get("publicKey"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Verifies an assertion and returns the user id it proves. */
    @Transactional(noRollbackFor = ApiException.class)
    public long finishAuthentication(String requestId, String binding, JsonNode credential) {
        String request = consumeChallenge(requestId, "AUTHENTICATE", binding, null);
        PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> pkc;
        try {
            pkc = PublicKeyCredential.parseAssertionResponseJson(mapper.writeValueAsString(credential));
        } catch (IOException | IllegalArgumentException | NullPointerException e) {
            throw invalid("The browser sent a passkey response this server can't read.");
        }
        record Stored(long id, long userId, Instant flaggedAt) {}
        Stored stored = jdbc.sql("SELECT id, user_id, flagged_at FROM user_passkeys WHERE credential_id = ?")
                .param(pkc.getId().getBytes())
                .query((rs, n) -> new Stored(rs.getLong(1), rs.getLong(2),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "PASSKEY_UNKNOWN",
                        "This passkey isn't registered with Howlers. It may have been removed; sign in another way."));
        if (stored.flaggedAt() != null) throw flagged();
        AssertionResult result;
        try {
            result = rp.finishAssertion(FinishAssertionOptions.builder()
                    .request(AssertionRequest.fromJson(request))
                    .response(pkc)
                    .build());
        } catch (InvalidSignatureCountException e) {
            jdbc.sql("UPDATE user_passkeys SET flagged_at = now() WHERE id = ?").param(stored.id()).update();
            log.warn("Passkey {} of user {} sent a signature counter that did not increase (expected >= {}, got {}); flagged",
                    stored.id(), stored.userId(), e.getExpectedMinimum(), e.getReceived());
            throw flagged();
        } catch (AssertionFailedException e) {
            log.info("Passkey assertion refused: {}", e.getMessage());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PASSKEY_INVALID", "The passkey couldn't be verified. Try again.");
        } catch (IOException | IllegalArgumentException e) {
            throw invalid("The passkey response couldn't be checked.");
        }
        if (!result.isSuccess()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PASSKEY_INVALID", "The passkey couldn't be verified. Try again.");
        }
        jdbc.sql("UPDATE user_passkeys SET sign_count = ?, backed_up = ?, last_used_at = now() WHERE id = ?")
                .params(result.getSignatureCount(), result.isBackedUp(), stored.id()).update();
        return stored.userId();
    }

    // ---------------------------------------------------------------- management

    @Transactional(readOnly = true)
    public List<PasskeyDto> list(long userId) {
        return jdbc.sql("""
                        SELECT id, name, created_at, last_used_at, backed_up, flagged_at, transports
                        FROM user_passkeys WHERE user_id = ? ORDER BY created_at""")
                .param(userId)
                .query((rs, n) -> new PasskeyDto(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant(), rs.getBoolean(5),
                        rs.getTimestamp(6) != null,
                        rs.getString(7) == null || rs.getString(7).isBlank() ? List.of() : List.of(rs.getString(7).split(","))))
                .list();
    }

    @Transactional(readOnly = true)
    public int count(long userId) {
        return jdbc.sql("SELECT count(*) FROM user_passkeys WHERE user_id = ?").param(userId).query(Integer.class).single();
    }

    @Transactional
    public void rename(long userId, long passkeyId, String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 60) throw ApiException.field("name", "Use 1-60 characters");
        if (jdbc.sql("UPDATE user_passkeys SET name = ? WHERE id = ? AND user_id = ?").params(n, passkeyId, userId).update() == 0) {
            throw ApiException.notFound("Passkey");
        }
    }

    @Transactional
    public void delete(long userId, long passkeyId) {
        if (jdbc.sql("DELETE FROM user_passkeys WHERE id = ? AND user_id = ?").params(passkeyId, userId).update() == 0) {
            throw ApiException.notFound("Passkey");
        }
        log.info("Passkey {} removed by user {}", passkeyId, userId);
    }

    @Scheduled(cron = "0 29 3 * * *")
    @Transactional
    public void purgeChallenges() {
        jdbc.sql("DELETE FROM webauthn_challenges WHERE expires_at < now() - interval '1 hour'").update();
    }

    // ---------------------------------------------------------------- internals

    private ByteArray userHandle(long userId) {
        byte[] fresh = new byte[32];
        RANDOM.nextBytes(fresh);
        jdbc.sql("""
                        INSERT INTO account_security (user_id, webauthn_user_handle) VALUES (?, ?)
                        ON CONFLICT (user_id) DO UPDATE
                          SET webauthn_user_handle = COALESCE(account_security.webauthn_user_handle, EXCLUDED.webauthn_user_handle)""")
                .params(userId, fresh).update();
        return new ByteArray(jdbc.sql("SELECT webauthn_user_handle FROM account_security WHERE user_id = ?")
                .param(userId).query((rs, n) -> rs.getBytes(1)).single());
    }

    private String saveChallenge(String kind, Long userId, String binding, String requestJson) {
        String requestId = Tokens.random(24);
        jdbc.sql("""
                        INSERT INTO webauthn_challenges (request_id, kind, user_id, binding_hash, request_json, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?)""")
                .params(requestId, kind, userId, Tokens.sha256(binding), requestJson,
                        Timestamp.from(Instant.now().plus(CHALLENGE_TTL)))
                .update();
        return requestId;
    }

    /** Single use: the row is marked used before verification, so a failed or replayed finish can't retry it. */
    private String consumeChallenge(String requestId, String kind, String binding, Long userId) {
        record Ch(long id, String kind, Long userId, String bindingHash, String json, Instant expiresAt, Instant usedAt) {}
        Ch ch = requestId == null ? null : jdbc.sql("""
                        SELECT id, kind, user_id, binding_hash, request_json, expires_at, used_at
                        FROM webauthn_challenges WHERE request_id = ? FOR UPDATE""")
                .param(requestId)
                .query((rs, n) -> new Ch(rs.getLong(1), rs.getString(2), (Long) rs.getObject(3), rs.getString(4),
                        rs.getString(5), rs.getTimestamp(6).toInstant(),
                        rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()))
                .optional().orElse(null);
        ApiException expired = new ApiException(HttpStatus.BAD_REQUEST, "CEREMONY_EXPIRED",
                "That passkey prompt expired or was already used. Try again.");
        if (ch == null || ch.usedAt() != null) throw expired;
        jdbc.sql("UPDATE webauthn_challenges SET used_at = now() WHERE id = ?").param(ch.id()).update();
        if (!ch.kind().equals(kind) || !ch.expiresAt().isAfter(Instant.now())) throw expired;
        if (binding == null || !java.security.MessageDigest.isEqual(Tokens.sha256(binding).getBytes(), ch.bindingHash().getBytes())) {
            throw expired;
        }
        if (userId != null && !userId.equals(ch.userId())) throw expired;
        return ch.json();
    }

    private static String cleanName(String name, String userAgent) {
        String n = name == null ? "" : name.strip();
        if (n.length() > 60) n = n.substring(0, 60);
        if (n.isEmpty()) n = "Passkey on " + UserAgentFamily.parse(userAgent).describe();
        return n.length() > 60 ? n.substring(0, 60) : n;
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "PASSKEY_INVALID", message);
    }

    private static ApiException flagged() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "PASSKEY_FLAGGED",
                "This passkey reported an unexpected signature counter, which can mean it was copied, so it's blocked. "
                        + "Sign in another way, then remove it in Settings and add it again.");
    }
}
