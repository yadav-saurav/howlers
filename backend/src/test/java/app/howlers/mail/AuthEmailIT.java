package app.howlers.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.howlers.auth.AuthService;
import app.howlers.auth.dto.AuthDtos.RegisterRequest;
import app.howlers.common.error.ApiException;
import app.howlers.support.IntegrationTest;
import app.howlers.user.UserRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Auth emails through the real dispatcher and database: after-commit sending and the verify-link states (D-038). */
class AuthEmailIT extends IntegrationTest {
    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_%-]+)");

    @Autowired MailDispatcher dispatcher;
    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcClient jdbc;

    private final List<OutgoingEmail> outbox = new CopyOnWriteArrayList<>();
    private MailTransport original;

    @BeforeEach
    void record() {
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

    private static String unique() {
        return "m" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private OutgoingEmail awaitMail(String to, String tag) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            for (OutgoingEmail m : outbox) if (m.to().equals(to) && m.tag().equals(tag)) return m;
            Thread.sleep(50);
        }
        throw new AssertionError("no " + tag + " mail to " + to);
    }

    private static String token(OutgoingEmail m) {
        Matcher x = TOKEN.matcher(m.text());
        assertThat(x.find()).isTrue();
        return x.group(1);
    }

    private static String code(Throwable t) {
        return ((ApiException) t).code();
    }

    @Test
    void registrationSendsAVerificationLinkThatWorksOnce() throws Exception {
        String name = unique();
        String email = name + "@mail.test";
        long id = auth.register(new RegisterRequest(name, email, "password1", null), "t", "127.0.0.1").response().user().id();
        OutgoingEmail m = awaitMail(email, EmailTemplates.TAG_VERIFY);
        assertThat(m.text()).contains("/verify-email?token=", "24 hours");
        assertThat(m.html()).contains("/verify-email?token=");

        String token = token(m);
        assertThat(auth.verifyEmail(token).status()).isEqualTo("VERIFIED");
        assertThat(users.findById(id).orElseThrow().isEmailVerified()).isTrue();
        assertThat(auth.verifyEmail(token).status()).isEqualTo("ALREADY_VERIFIED");
        assertThatThrownBy(() -> auth.resendVerification(id)).isInstanceOf(ApiException.class);
    }

    @Test
    void rolledBackRegistrationSendsNoEmail() throws Exception {
        String name = unique();
        String email = name + "@mail.test";
        tx.executeWithoutResult(status -> {
            auth.register(new RegisterRequest(name, email, "password1", null), "t", "127.0.0.1");
            status.setRollbackOnly();
        });
        assertThat(users.existsByEmailIgnoreCase(email)).isFalse();
        // A committed registration afterwards is delivered; the rolled-back one never is.
        String other = unique();
        auth.register(new RegisterRequest(other, other + "@mail.test", "password1", null), "t", "127.0.0.1");
        awaitMail(other + "@mail.test", EmailTemplates.TAG_VERIFY);
        Thread.sleep(200);
        assertThat(outbox).noneMatch(m -> m.to().equals(email));
    }

    @Test
    void resendReplacesTheOldLinkAndOldLinksReportExpired() throws Exception {
        String name = unique();
        String email = name + "@mail.test";
        long id = auth.register(new RegisterRequest(name, email, "password1", null), "t", "127.0.0.1").response().user().id();
        String first = token(awaitMail(email, EmailTemplates.TAG_VERIFY));
        outbox.clear();
        auth.resendVerification(id);
        String second = token(awaitMail(email, EmailTemplates.TAG_VERIFY));
        assertThat(second).isNotEqualTo(first);

        assertThatThrownBy(() -> auth.verifyEmail(first)).satisfies(e -> assertThat(code(e)).isEqualTo("LINK_EXPIRED"));
        assertThat(auth.verifyEmail(second).status()).isEqualTo("VERIFIED");
        // Once verified, even the superseded link just says so.
        assertThat(auth.verifyEmail(first).status()).isEqualTo("ALREADY_VERIFIED");
    }

    @Test
    void expiredAndUnknownLinks() throws Exception {
        String name = unique();
        String email = name + "@mail.test";
        auth.register(new RegisterRequest(name, email, "password1", null), "t", "127.0.0.1");
        String token = token(awaitMail(email, EmailTemplates.TAG_VERIFY));
        jdbc.sql("UPDATE email_tokens SET expires_at = now() - interval '1 minute' WHERE token_hash = ?")
                .param(app.howlers.common.text.Tokens.sha256(token)).update();
        assertThatThrownBy(() -> auth.verifyEmail(token)).satisfies(e -> assertThat(code(e)).isEqualTo("LINK_EXPIRED"));
        assertThatThrownBy(() -> auth.verifyEmail("nope-not-a-token")).satisfies(e -> assertThat(code(e)).isEqualTo("LINK_INVALID"));
    }

    @Test
    void forgotPasswordMailsOnlyExistingAccounts() throws Exception {
        String name = unique();
        String email = name + "@mail.test";
        auth.register(new RegisterRequest(name, email, "password1", null), "t", "127.0.0.1");
        auth.forgotPassword(email.toUpperCase());
        OutgoingEmail m = awaitMail(email, EmailTemplates.TAG_RESET);
        assertThat(m.text()).contains("/reset-password?token=", "1 hour", "Didn't request this?");
        auth.resetPassword(token(m), "newpassword2");

        String ghost = unique() + "@mail.test";
        auth.forgotPassword(ghost);
        Thread.sleep(300);
        assertThat(outbox).noneMatch(x -> x.to().equals(ghost));
    }

    @Test
    void defaultTransportInTestsIsLogMode() {
        // application-test.yml pins MAIL_MODE=log, so a developer's .env.local can't make the suite send real mail.
        assertThat(original.name()).isEqualTo("log");
    }
}
