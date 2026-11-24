package app.howlers.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.howlers.auth.AuthHttp.Session;
import app.howlers.auth.dto.AuthDtos.RegisterRequest;
import app.howlers.mail.EmailTemplates;
import app.howlers.mail.MailDispatcher;
import app.howlers.mail.MailTransport;
import app.howlers.mail.OutgoingEmail;
import app.howlers.support.IntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** New-device sign-in emails (D-041) and the sessions page additions, asserted on the recorded mail transport. */
@AutoConfigureMockMvc
class NewDeviceAlertIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MailDispatcher dispatcher;
    @Autowired AuthService auth;
    @Autowired JdbcClient jdbc;
    private AuthHttp http;
    private final List<OutgoingEmail> outbox = new CopyOnWriteArrayList<>();
    private MailTransport original;

    @BeforeEach
    void setUp() {
        http = new AuthHttp(mvc, json);
        original = dispatcher.swapTransport(new MailTransport() {
            @Override public String name() { return "log"; }
            @Override public String send(OutgoingEmail email) {
                outbox.add(email);
                return null;
            }
        });
    }

    @AfterEach
    void restore() {
        dispatcher.swapTransport(original);
    }

    private List<OutgoingEmail> alertsTo(String email) {
        return outbox.stream().filter(m -> m.to().equals(email) && m.tag().equals(EmailTemplates.TAG_NEW_SIGNIN)).toList();
    }

    /** Waits for {@code n} alerts, then a little longer to be sure no extra one follows. */
    private List<OutgoingEmail> settle(String email, int n) throws InterruptedException {
        for (int i = 0; i < 100 && alertsTo(email).size() < n; i++) Thread.sleep(50);
        Thread.sleep(250);
        return alertsTo(email);
    }

    @Test
    void oneEmailPerNewDeviceAndNoneForTheRegistrationSignIn() throws Exception {
        Session s = http.register(AuthHttp.unique("nd"), AuthHttp.FIREFOX_WINDOWS);
        assertThat(s.device()).isNotNull();
        assertThat(s.device().isHttpOnly()).isTrue();
        // Same browser again: known.
        http.login(s.username(), s.device(), AuthHttp.FIREFOX_WINDOWS);
        assertThat(settle(s.email(), 0)).isEmpty();

        // A new browser (no device cookie): one alert.
        Session other = http.login(s.username(), null, AuthHttp.CHROME_MAC);
        List<OutgoingEmail> alerts = settle(s.email(), 1);
        assertThat(alerts).hasSize(1);
        OutgoingEmail m = alerts.get(0);
        assertThat(m.subject()).isEqualTo("New sign-in to your Howlers account");
        assertThat(m.text()).contains("Chrome on macOS", "UTC", "Signed in with: password", "/settings?tab=account#sessions")
                .doesNotContain("127.0.0.1");
        assertThat(m.html()).contains("Chrome on macOS", "Review sessions");

        // That browser is known now; the first one still is.
        http.login(s.username(), other.device(), AuthHttp.CHROME_MAC);
        http.login(s.username(), s.device(), AuthHttp.FIREFOX_WINDOWS);
        assertThat(settle(s.email(), 1)).hasSize(1);

        // Same cookie, different browser family: a different device.
        http.login(s.username(), s.device(), AuthHttp.EDGE_WINDOWS);
        assertThat(settle(s.email(), 2)).hasSize(2);

        // Only a keyed hash of the network prefix is stored with the device.
        List<String> nets = jdbc.sql("SELECT d.network_hash FROM known_devices d JOIN users u ON u.id = d.user_id WHERE u.username = ?")
                .param(s.username()).query(String.class).list();
        assertThat(nets).hasSize(3).allMatch(h -> h.matches("[0-9a-f]{12}"));
    }

    @Test
    void noEmailWhenTheUserTurnedAlertsOff() throws Exception {
        Session s = http.register(AuthHttp.unique("ndo"), AuthHttp.FIREFOX_WINDOWS);
        mvc.perform(patch("/api/v1/auth/security").header("Authorization", s.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"newSignInAlerts\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.newSignInAlerts").value(false));
        http.login(s.username(), null, AuthHttp.CHROME_MAC);
        assertThat(settle(s.email(), 0)).isEmpty();
        mvc.perform(patch("/api/v1/auth/security").header("Authorization", s.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"newSignInAlerts\":true}")).andExpect(status().isOk());
        http.login(s.username(), null, AuthHttp.EDGE_WINDOWS);
        assertThat(settle(s.email(), 1)).hasSize(1);
    }

    @Test
    void accountsWithNoKnownDeviceYetDontGetAnEmailForTheirFirstSignIn() throws Exception {
        // Like an account that existed before this feature, or one registered without a device cookie.
        String name = AuthHttp.unique("ndp");
        auth.register(new RegisterRequest(name, name + "@sec.test", "password1", null), "t", "127.0.0.1");
        Session first = http.login(name, null, AuthHttp.CHROME_MAC);
        assertThat(settle(name + "@sec.test", 0)).isEmpty();
        http.login(name, first.device(), AuthHttp.CHROME_MAC);
        http.login(name, null, AuthHttp.FIREFOX_WINDOWS);
        assertThat(settle(name + "@sec.test", 1)).hasSize(1);
    }

    @Test
    void signOutEverywhereElseKeepsOnlyThisSession() throws Exception {
        Session a = http.register(AuthHttp.unique("so"), AuthHttp.FIREFOX_WINDOWS);
        Session b = http.login(a.username(), a.device(), AuthHttp.FIREFOX_WINDOWS);
        Session c = http.login(a.username(), null, AuthHttp.CHROME_MAC);

        mvc.perform(get("/api/v1/auth/sessions").header("Authorization", a.bearer()).cookie(a.refresh()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.current == true)].authMethod").value("PASSWORD"));
        // Needs this browser's refresh cookie to know which session to keep.
        mvc.perform(delete("/api/v1/auth/sessions/others").header("Authorization", a.bearer())).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/auth/sessions/others").header("Authorization", a.bearer()).cookie(a.refresh()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revoked").value(2));
        mvc.perform(get("/api/v1/auth/sessions").header("Authorization", a.bearer()).cookie(a.refresh()))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].current").value(true));

        mvc.perform(post("/api/v1/auth/refresh").cookie(b.refresh())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").cookie(c.refresh())).andExpect(status().isUnauthorized());
        MvcResult kept = mvc.perform(post("/api/v1/auth/refresh").cookie(a.refresh())).andExpect(status().isOk()).andReturn();
        // Rotation carries the sign-in method forward.
        Cookie rotated = kept.getResponse().getCookie("howlers_rt");
        mvc.perform(get("/api/v1/auth/sessions").header("Authorization", a.bearer()).cookie(rotated))
                .andExpect(jsonPath("$[0].authMethod").value("PASSWORD")).andExpect(jsonPath("$[0].current").value(true));
        // Someone else's cookie can't be used to pick the survivor.
        Session d = http.register(AuthHttp.unique("sx"), AuthHttp.FIREFOX_WINDOWS);
        mvc.perform(delete("/api/v1/auth/sessions/others").header("Authorization", d.bearer()).cookie(rotated))
                .andExpect(status().isBadRequest());
    }
}
