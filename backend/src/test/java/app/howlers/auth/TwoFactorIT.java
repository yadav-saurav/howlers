package app.howlers.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.howlers.auth.AuthHttp.Session;
import app.howlers.auth.mfa.Totp;
import app.howlers.auth.mfa.TwoFactorService;
import app.howlers.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** TOTP two-factor authentication end to end (D-042): enrolment, the second login step, recovery codes, lockout. */
@AutoConfigureMockMvc
class TwoFactorIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired TwoFactorService twoFactor;
    private AuthHttp http;
    private final TestClock clock = new TestClock();

    /** Starts at a step boundary + 5 s, so "the next step" is always 30 s away. */
    static final class TestClock extends Clock {
        Instant now = Instant.ofEpochSecond((Instant.now().getEpochSecond() / 30) * 30 + 5);

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() {
        http = new AuthHttp(mvc, json);
        twoFactor.useClock(clock);
    }

    @AfterEach
    void restore() {
        twoFactor.useClock(Clock.systemUTC());
    }

    private String code(String secret) {
        return Totp.code(secret, Totp.stepAt(clock.now.getEpochSecond()));
    }

    private MvcResult postJson(String path, Session s, String body) throws Exception {
        var req = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (s != null) req.header("Authorization", s.bearer());
        return mvc.perform(req).andReturn();
    }

    private record Enrolled(Session session, String secret, List<String> recoveryCodes) {}

    private Enrolled enrol() throws Exception {
        Session s = http.register(AuthHttp.unique("tf"), AuthHttp.FIREFOX_WINDOWS);
        MvcResult setup = postJson("/api/v1/auth/2fa/totp/setup", s, "{\"password\":\"password1\"}");
        assertThat(setup.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = http.read(setup);
        String secret = body.get("secret").asText();
        MvcResult confirm = postJson("/api/v1/auth/2fa/totp/confirm", s, "{\"code\":\"" + code(secret) + "\"}");
        assertThat(confirm.getResponse().getStatus()).as(confirm.getResponse().getContentAsString()).isEqualTo(200);
        List<String> codes = new ArrayList<>();
        http.read(confirm).get("recoveryCodes").forEach(n -> codes.add(n.asText()));
        clock.advance(Duration.ofSeconds(30)); // the confirm code's step is spent
        return new Enrolled(s, secret, codes);
    }

    private String mfaToken(String username) throws Exception {
        MvcResult r = http.loginRaw(username, "password1", null, AuthHttp.FIREFOX_WINDOWS);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode b = http.read(r);
        assertThat(b.get("mfaRequired").asBoolean()).isTrue();
        assertThat(b.has("accessToken")).isFalse();
        assertThat(r.getResponse().getCookie("howlers_rt")).isNull(); // not a session yet
        return b.get("mfaToken").asText();
    }

    private MvcResult secondStep(String token, String code) throws Exception {
        return mvc.perform(post("/api/v1/auth/login/2fa").contentType(MediaType.APPLICATION_JSON)
                .header("User-Agent", AuthHttp.FIREFOX_WINDOWS)
                .content("{\"mfaToken\":\"" + token + "\",\"code\":\"" + code + "\"}")).andReturn();
    }

    @Test
    void enrolmentShowsAQrCodeNeedsTheRightCodeAndStoresTheSecretEncrypted() throws Exception {
        Session s = http.register(AuthHttp.unique("tfe"), AuthHttp.FIREFOX_WINDOWS);
        assertThat(postJson("/api/v1/auth/2fa/totp/setup", s, "{\"password\":\"wrong-pass1\"}").getResponse().getStatus()).isEqualTo(400);
        JsonNode setup = http.read(postJson("/api/v1/auth/2fa/totp/setup", s, "{\"password\":\"password1\"}"));
        String secret = setup.get("secret").asText();
        assertThat(setup.get("otpauthUri").asText()).startsWith("otpauth://totp/Howlers:" + s.username() + "?secret=" + secret);
        assertThat(setup.get("qrSvg").asText()).startsWith("<svg");

        String wrong = code(secret).equals("000000") ? "111111" : "000000";
        assertThat(postJson("/api/v1/auth/2fa/totp/confirm", s, "{\"code\":\"" + wrong + "\"}").getResponse().getStatus()).isEqualTo(401);
        mvc.perform(get("/api/v1/auth/security").header("Authorization", s.bearer())).andExpect(jsonPath("$.totp.enabled").value(false));

        JsonNode confirmed = http.read(postJson("/api/v1/auth/2fa/totp/confirm", s, "{\"code\":\"" + code(secret) + "\"}"));
        assertThat(confirmed.get("recoveryCodes")).hasSize(10);
        mvc.perform(get("/api/v1/auth/security").header("Authorization", s.bearer()))
                .andExpect(jsonPath("$.totp.enabled").value(true)).andExpect(jsonPath("$.totp.recoveryCodesRemaining").value(10))
                .andExpect(jsonPath("$.newSignInAlerts").value(true));

        String stored = jdbc.sql("SELECT s.totp_secret_enc FROM account_security s JOIN users u ON u.id = s.user_id WHERE u.username = ?")
                .param(s.username()).query(String.class).single();
        assertThat(stored).startsWith("v1:").doesNotContain(secret);
        List<String> hashes = jdbc.sql("SELECT c.code_hash FROM totp_recovery_codes c JOIN users u ON u.id = c.user_id WHERE u.username = ?")
                .param(s.username()).query(String.class).list();
        assertThat(hashes).hasSize(10).noneMatch(h -> confirmed.get("recoveryCodes").toString().contains(h));
        // Setting up again while on is refused.
        assertThat(postJson("/api/v1/auth/2fa/totp/setup", s, "{\"password\":\"password1\"}").getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void loginNeedsTheCodeAndTheSameCodeCantBeUsedTwice() throws Exception {
        Enrolled e = enrol();
        String token = mfaToken(e.session().username());
        // The second-step token is not an access token.
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());

        String code = code(e.secret());
        MvcResult ok = secondStep(token, code);
        assertThat(ok.getResponse().getStatus()).as(ok.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(ok.getResponse().getCookie("howlers_rt")).isNotNull();
        String access = http.read(ok).get("accessToken").asText();
        mvc.perform(get("/api/v1/auth/sessions").header("Authorization", "Bearer " + access))
                .andExpect(jsonPath("$[?(@.current == false && @.authMethod == 'PASSWORD_TOTP')]").exists());

        // The token is spent, and the code can't be replayed on a new attempt within its window.
        assertThat(http.read(secondStep(token, code)).get("code").asText()).isEqualTo("MFA_TOKEN_EXPIRED");
        String token2 = mfaToken(e.session().username());
        assertThat(http.read(secondStep(token2, code)).get("code").asText()).isEqualTo("INVALID_CODE");
        // One step of clock skew: the next step's code works now.
        String next = Totp.code(e.secret(), Totp.stepAt(clock.now.getEpochSecond()) + 1);
        assertThat(secondStep(token2, next).getResponse().getStatus()).isEqualTo(200);
        // Plain password login is refused for callers that can't do the second step.
        assertThat(http.loginRaw(e.session().username(), "wrong-pass1", null, AuthHttp.FIREFOX_WINDOWS).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void recoveryCodesWorkOnceAndRegeneratingReplacesThem() throws Exception {
        Enrolled e = enrol();
        String first = e.recoveryCodes().get(0);
        MvcResult ok = secondStep(mfaToken(e.session().username()), first.toUpperCase().replace("-", " "));
        assertThat(ok.getResponse().getStatus()).as(ok.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(http.read(secondStep(mfaToken(e.session().username()), first)).get("code").asText()).isEqualTo("INVALID_CODE");
        mvc.perform(get("/api/v1/auth/security").header("Authorization", e.session().bearer()))
                .andExpect(jsonPath("$.totp.recoveryCodesRemaining").value(9));

        // Regenerating needs an authenticator code (not a recovery code) and invalidates the old set.
        assertThat(postJson("/api/v1/auth/2fa/recovery-codes", e.session(), "{\"code\":\"" + e.recoveryCodes().get(1) + "\"}")
                .getResponse().getStatus()).isEqualTo(401);
        JsonNode fresh = http.read(postJson("/api/v1/auth/2fa/recovery-codes", e.session(), "{\"code\":\"" + code(e.secret()) + "\"}"));
        assertThat(fresh.get("recoveryCodes")).hasSize(10);
        assertThat(http.read(secondStep(mfaToken(e.session().username()), e.recoveryCodes().get(2))).get("code").asText()).isEqualTo("INVALID_CODE");
        assertThat(secondStep(mfaToken(e.session().username()), fresh.get("recoveryCodes").get(0).asText()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void repeatedWrongCodesLockTheSecondStepBriefly() throws Exception {
        Enrolled e = enrol();
        String token = mfaToken(e.session().username());
        String wrong = code(e.secret()).equals("123456") ? "654321" : "123456";
        for (int i = 0; i < 5; i++) {
            assertThat(http.read(secondStep(token, wrong)).get("code").asText()).isEqualTo("INVALID_CODE");
        }
        // Locked: even the right code is refused, on a fresh token too.
        String token2 = mfaToken(e.session().username());
        MvcResult locked = secondStep(token2, code(e.secret()));
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(http.read(locked).get("code").asText()).isEqualTo("MFA_LOCKED");
        // The first token died after its 5 wrong attempts.
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertThat(http.read(secondStep(token, code(e.secret()))).get("code").asText()).isEqualTo("MFA_TOKEN_EXPIRED");
        // After the lockout, a new attempt succeeds.
        String token3 = mfaToken(e.session().username());
        assertThat(secondStep(token3, code(e.secret())).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void secondStepTokensExpire() throws Exception {
        Enrolled e = enrol();
        String token = mfaToken(e.session().username());
        clock.advance(Duration.ofMinutes(6));
        assertThat(http.read(secondStep(token, code(e.secret()))).get("code").asText()).isEqualTo("MFA_TOKEN_EXPIRED");
        assertThat(http.read(secondStep("not-a-real-token", code(e.secret()))).get("code").asText()).isEqualTo("MFA_TOKEN_EXPIRED");
    }

    @Test
    void disablingNeedsThePasswordAndACode() throws Exception {
        Enrolled e = enrol();
        assertThat(postJson("/api/v1/auth/2fa/totp/disable", e.session(),
                "{\"password\":\"wrong-pass1\",\"code\":\"" + code(e.secret()) + "\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(postJson("/api/v1/auth/2fa/totp/disable", e.session(),
                "{\"password\":\"password1\",\"code\":\"000000\"}").getResponse().getStatus()).isIn(401);
        assertThat(postJson("/api/v1/auth/2fa/totp/disable", e.session(),
                "{\"password\":\"password1\",\"code\":\"" + e.recoveryCodes().get(3) + "\"}").getResponse().getStatus()).isEqualTo(204);
        mvc.perform(get("/api/v1/auth/security").header("Authorization", e.session().bearer()))
                .andExpect(jsonPath("$.totp.enabled").value(false)).andExpect(jsonPath("$.totp.recoveryCodesRemaining").value(0));
        // Back to one step.
        JsonNode b = http.login(e.session().username(), null, AuthHttp.FIREFOX_WINDOWS).body();
        assertThat(b.get("accessToken").asText()).isNotBlank();
        assertThat(b.has("mfaRequired")).isFalse();
    }

    @Test
    void accountSecurityEndpointsNeedSignIn() throws Exception {
        mvc.perform(get("/api/v1/auth/security")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/2fa/totp/setup").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        // Public auth endpoints still work with a stale token attached.
        mvc.perform(post("/api/v1/auth/login").header("Authorization", "Bearer expired.or.junk")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"login\":\"nobody_here\",\"password\":\"password1\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Incorrect username or password"));
    }
}
