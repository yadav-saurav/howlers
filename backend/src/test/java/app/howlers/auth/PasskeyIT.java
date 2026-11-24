package app.howlers.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.howlers.auth.AuthHttp.Session;
import app.howlers.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Passkey registration and usernameless sign-in through the real HTTP endpoints, with a software authenticator (D-041). */
@AutoConfigureMockMvc
class PasskeyIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    private AuthHttp http;

    private record Started(String requestId, JsonNode publicKey, Cookie binding) {}

    @BeforeEach
    void setUp() {
        http = new AuthHttp(mvc, json);
    }

    private Started registrationOptions(Session s, Cookie binding) throws Exception {
        var req = post("/api/v1/auth/passkeys/options").header("Authorization", s.bearer());
        if (binding != null) req.cookie(binding);
        MvcResult r = mvc.perform(req).andExpect(status().isOk()).andReturn();
        JsonNode b = http.read(r);
        Cookie c = r.getResponse().getCookie("howlers_wac");
        return new Started(b.get("requestId").asText(), b.get("publicKey"), c);
    }

    private MvcResult finishRegistration(Session s, Started st, ObjectNode credential, String name) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("requestId", st.requestId());
        body.set("credential", credential);
        if (name != null) body.put("name", name);
        var req = post("/api/v1/auth/passkeys").header("Authorization", s.bearer()).header("User-Agent", AuthHttp.CHROME_MAC)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (st.binding() != null) req.cookie(st.binding());
        return mvc.perform(req).andReturn();
    }

    private SoftwareAuthenticator enrol(Session s) throws Exception {
        SoftwareAuthenticator a = new SoftwareAuthenticator();
        Started st = registrationOptions(s, null);
        MvcResult r = finishRegistration(s, st, a.register(st.publicKey(), AuthHttp.ORIGIN), "My laptop");
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return a;
    }

    private Started loginOptions(Cookie binding) throws Exception {
        var req = post("/api/v1/auth/passkey-login/options");
        if (binding != null) req.cookie(binding);
        MvcResult r = mvc.perform(req).andExpect(status().isOk()).andReturn();
        JsonNode b = http.read(r);
        Cookie c = r.getResponse().getCookie("howlers_wac");
        return new Started(b.get("requestId").asText(), b.get("publicKey"), c);
    }

    private MvcResult loginVerify(Started st, ObjectNode credential, Cookie binding) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("requestId", st.requestId());
        body.set("credential", credential);
        var req = post("/api/v1/auth/passkey-login/verify").header("User-Agent", AuthHttp.CHROME_MAC)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (binding != null) req.cookie(binding);
        return mvc.perform(req).andReturn();
    }

    @Test
    void registerListRenameAndSignInWithoutAUsername() throws Exception {
        Session s = http.register(AuthHttp.unique("pk"), AuthHttp.FIREFOX_WINDOWS);
        Started st = registrationOptions(s, null);
        // Discoverable credential with user verification required, for this relying party.
        assertThat(st.publicKey().path("rp").path("id").asText()).isEqualTo("localhost");
        assertThat(st.publicKey().path("authenticatorSelection").path("residentKey").asText()).isEqualTo("required");
        assertThat(st.publicKey().path("authenticatorSelection").path("userVerification").asText()).isEqualTo("required");
        assertThat(st.binding()).isNotNull();
        assertThat(st.binding().isHttpOnly()).isTrue();

        SoftwareAuthenticator a = new SoftwareAuthenticator();
        MvcResult reg = finishRegistration(s, st, a.register(st.publicKey(), AuthHttp.ORIGIN), "  ");
        assertThat(reg.getResponse().getStatus()).as(reg.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode pk = http.read(reg);
        assertThat(pk.get("name").asText()).isEqualTo("Passkey on Chrome on macOS");
        assertThat(pk.get("flagged").asBoolean()).isFalse();

        mvc.perform(patch("/api/v1/auth/passkeys/" + pk.get("id").asLong()).header("Authorization", s.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Work laptop\"}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/passkeys").header("Authorization", s.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Work laptop"));

        // A fresh browser: no cookies, no username. Only the public key, counter and handle are stored.
        Started login = loginOptions(null);
        assertThat(login.publicKey().path("allowCredentials").size()).isZero();
        assertThat(login.publicKey().path("userVerification").asText()).isEqualTo("required");
        MvcResult ok = loginVerify(login, a.authenticate(login.publicKey(), AuthHttp.ORIGIN), login.binding());
        assertThat(ok.getResponse().getStatus()).as(ok.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode session = http.read(ok);
        assertThat(session.path("user").path("username").asText()).isEqualTo(s.username());
        Cookie refresh = ok.getResponse().getCookie("howlers_rt");
        assertThat(refresh).isNotNull();
        assertThat(refresh.getValue()).isNotBlank();

        // Same session machinery as a password login: refresh works, and the session says it used a passkey.
        mvc.perform(post("/api/v1/auth/refresh").cookie(refresh)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/sessions").header("Authorization", "Bearer " + session.get("accessToken").asText()))
                .andExpect(jsonPath("$[?(@.authMethod == 'PASSKEY')]").exists())
                .andExpect(jsonPath("$[?(@.authMethod == 'PASSWORD')]").exists());
        Long counter = jdbc.sql("SELECT sign_count FROM user_passkeys WHERE id = ?").param(pk.get("id").asLong()).query(Long.class).single();
        assertThat(counter).isEqualTo(a.counter());
    }

    @Test
    void challengesAreSingleUseAndBoundToTheBrowserThatStartedThem() throws Exception {
        Session s = http.register(AuthHttp.unique("pkb"), AuthHttp.FIREFOX_WINDOWS);
        SoftwareAuthenticator a = enrol(s);

        Started st = loginOptions(null);
        ObjectNode assertion = a.authenticate(st.publicKey(), AuthHttp.ORIGIN);
        // Another browser's binding cookie: refused, and the challenge is now spent.
        Cookie stranger = new Cookie("howlers_wac", "someone-elses-binding-value-123");
        mvc.perform(post("/api/v1/auth/passkey-login/verify").cookie(stranger).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(json.createObjectNode().put("requestId", st.requestId()).set("credential", assertion))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CEREMONY_EXPIRED"));
        assertThat(loginVerify(st, assertion, st.binding()).getResponse().getStatus()).isEqualTo(400);

        // No cookie at all.
        Started st2 = loginOptions(null);
        assertThat(loginVerify(st2, a.authenticate(st2.publicKey(), AuthHttp.ORIGIN), null).getResponse().getStatus()).isEqualTo(400);

        // Replay of a successful assertion.
        Started st3 = loginOptions(null);
        ObjectNode good = a.authenticate(st3.publicKey(), AuthHttp.ORIGIN);
        assertThat(loginVerify(st3, good, st3.binding()).getResponse().getStatus()).isEqualTo(200);
        MvcResult replay = loginVerify(st3, good, st3.binding());
        assertThat(replay.getResponse().getStatus()).isEqualTo(400);
        assertThat(http.read(replay).get("code").asText()).isEqualTo("CEREMONY_EXPIRED");

        // Expired challenge.
        Started st4 = loginOptions(null);
        jdbc.sql("UPDATE webauthn_challenges SET expires_at = now() - interval '1 second' WHERE request_id = ?")
                .param(st4.requestId()).update();
        assertThat(loginVerify(st4, a.authenticate(st4.publicKey(), AuthHttp.ORIGIN), st4.binding()).getResponse().getStatus()).isEqualTo(400);

        // A registration challenge belongs to the user who started it.
        Session other = http.register(AuthHttp.unique("pkc"), AuthHttp.FIREFOX_WINDOWS);
        Started mine = registrationOptions(s, null);
        SoftwareAuthenticator b = new SoftwareAuthenticator();
        MvcResult hijack = finishRegistration(other, mine, b.register(mine.publicKey(), AuthHttp.ORIGIN), null);
        assertThat(hijack.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void wrongOriginMissingUserVerificationAndUnknownCredentialsAreRefused() throws Exception {
        Session s = http.register(AuthHttp.unique("pko"), AuthHttp.FIREFOX_WINDOWS);
        SoftwareAuthenticator a = enrol(s);

        Started st = loginOptions(null);
        MvcResult phish = loginVerify(st, a.authenticate(st.publicKey(), "https://howlers.example.evil"), st.binding());
        assertThat(phish.getResponse().getStatus()).isEqualTo(401);
        assertThat(http.read(phish).get("code").asText()).isEqualTo("PASSKEY_INVALID");

        a.userVerified = false;
        Started st2 = loginOptions(null);
        assertThat(loginVerify(st2, a.authenticate(st2.publicKey(), AuthHttp.ORIGIN), st2.binding()).getResponse().getStatus()).isEqualTo(401);
        a.userVerified = true;

        // Registration without user verification is refused too.
        SoftwareAuthenticator noUv = new SoftwareAuthenticator();
        noUv.userVerified = false;
        Started reg = registrationOptions(s, null);
        assertThat(finishRegistration(s, reg, noUv.register(reg.publicKey(), AuthHttp.ORIGIN), null).getResponse().getStatus()).isEqualTo(400);

        SoftwareAuthenticator stranger = new SoftwareAuthenticator();
        Started reg2 = registrationOptions(http.register(AuthHttp.unique("pkx"), AuthHttp.FIREFOX_WINDOWS), null);
        stranger.register(reg2.publicKey(), AuthHttp.ORIGIN); // never sent to the server
        Started st3 = loginOptions(null);
        MvcResult unknown = loginVerify(st3, stranger.authenticate(st3.publicKey(), AuthHttp.ORIGIN), st3.binding());
        assertThat(http.read(unknown).get("code").asText()).isEqualTo("PASSKEY_UNKNOWN");
    }

    @Test
    void aSignatureCounterRegressionRefusesAndFlagsThePasskey() throws Exception {
        Session s = http.register(AuthHttp.unique("pkn"), AuthHttp.FIREFOX_WINDOWS);
        SoftwareAuthenticator a = enrol(s);
        Started st = loginOptions(null);
        assertThat(loginVerify(st, a.authenticate(st.publicKey(), AuthHttp.ORIGIN), st.binding()).getResponse().getStatus()).isEqualTo(200);

        a.setCounter(1); // a clone with an older counter
        Started st2 = loginOptions(null);
        MvcResult cloned = loginVerify(st2, a.authenticate(st2.publicKey(), AuthHttp.ORIGIN), st2.binding());
        assertThat(cloned.getResponse().getStatus()).isEqualTo(401);
        assertThat(http.read(cloned).get("code").asText()).isEqualTo("PASSKEY_FLAGGED");

        // Flagged: refused even with a counter that moves forward again, and shown in settings.
        a.setCounter(1000);
        Started st3 = loginOptions(null);
        assertThat(http.read(loginVerify(st3, a.authenticate(st3.publicKey(), AuthHttp.ORIGIN), st3.binding())).get("code").asText())
                .isEqualTo("PASSKEY_FLAGGED");
        mvc.perform(get("/api/v1/auth/passkeys").header("Authorization", s.bearer())).andExpect(jsonPath("$[0].flagged").value(true));
    }

    @Test
    void authenticatorsWithoutACounterStillWork() throws Exception {
        Session s = http.register(AuthHttp.unique("pkz"), AuthHttp.FIREFOX_WINDOWS);
        SoftwareAuthenticator a = new SoftwareAuthenticator();
        a.setCounter(0);
        a.counterStep = 0;
        Started reg = registrationOptions(s, null);
        assertThat(finishRegistration(s, reg, a.register(reg.publicKey(), AuthHttp.ORIGIN), null).getResponse().getStatus()).isEqualTo(201);
        for (int i = 0; i < 2; i++) {
            Started st = loginOptions(null);
            assertThat(loginVerify(st, a.authenticate(st.publicKey(), AuthHttp.ORIGIN), st.binding()).getResponse().getStatus()).isEqualTo(200);
        }
    }

    @Test
    void removedPasskeysStopWorkingAndManagementNeedsSignIn() throws Exception {
        Session s = http.register(AuthHttp.unique("pkr"), AuthHttp.FIREFOX_WINDOWS);
        SoftwareAuthenticator a = enrol(s);
        long id = http.read(mvc.perform(get("/api/v1/auth/passkeys").header("Authorization", s.bearer())).andReturn()).get(0).get("id").asLong();

        Session other = http.register(AuthHttp.unique("pks"), AuthHttp.FIREFOX_WINDOWS);
        mvc.perform(delete("/api/v1/auth/passkeys/" + id).header("Authorization", other.bearer())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/auth/passkeys")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/passkeys/options")).andExpect(status().isUnauthorized());

        mvc.perform(delete("/api/v1/auth/passkeys/" + id).header("Authorization", s.bearer())).andExpect(status().isNoContent());
        Started st = loginOptions(null);
        assertThat(http.read(loginVerify(st, a.authenticate(st.publicKey(), AuthHttp.ORIGIN), st.binding())).get("code").asText())
                .isEqualTo("PASSKEY_UNKNOWN");
    }

    @Test
    void aSuspendedAccountCantSignInWithAPasskey() throws Exception {
        Session s = http.register(AuthHttp.unique("pku"), AuthHttp.FIREFOX_WINDOWS);
        SoftwareAuthenticator a = enrol(s);
        jdbc.sql("UPDATE users SET status = 'SUSPENDED' WHERE username = ?").param(s.username()).update();
        Started st = loginOptions(null);
        assertThat(loginVerify(st, a.authenticate(st.publicKey(), AuthHttp.ORIGIN), st.binding()).getResponse().getStatus()).isEqualTo(403);
    }
}
