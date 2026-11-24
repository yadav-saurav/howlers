package app.howlers.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.howlers.common.config.AppProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Retry policy, after-commit hand-off and mode selection, all without network or Spring context. */
class MailDispatcherTest {

    /** Fails with the queued exceptions, then succeeds. */
    static class ScriptedTransport implements MailTransport {
        final Deque<MailDeliveryException> failures = new ConcurrentLinkedDeque<>();
        final List<OutgoingEmail> sent = new ArrayList<>();
        int attempts;

        @Override
        public String name() { return "scripted"; }

        @Override
        public String send(OutgoingEmail email) throws MailDeliveryException {
            attempts++;
            MailDeliveryException f = failures.poll();
            if (f != null) throw f;
            sent.add(email);
            return "id-" + attempts;
        }
    }

    private static OutgoingEmail email() {
        return new OutgoingEmail("ann@example.com", "ann", "s", "<p>h</p>", "t", "verify-email");
    }

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void retriesServerErrorsWithExponentialBackoff() {
        ScriptedTransport t = new ScriptedTransport();
        t.failures.add(MailDeliveryException.retryable("503", 503, null));
        t.failures.add(MailDeliveryException.retryable("500", 500, null));
        List<Duration> sleeps = new ArrayList<>();
        MailDispatcher d = new MailDispatcher(t, RetryPolicy.DEFAULT, Runnable::run, sleeps::add);
        assertThat(d.deliver(email())).isEqualTo(MailDispatcher.Outcome.SENT);
        assertThat(t.attempts).isEqualTo(3);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void givesUpAfterMaxAttempts() {
        ScriptedTransport t = new ScriptedTransport();
        for (int i = 0; i < 10; i++) t.failures.add(MailDeliveryException.retryable("500", 500, null));
        List<Duration> sleeps = new ArrayList<>();
        MailDispatcher d = new MailDispatcher(t, RetryPolicy.DEFAULT, Runnable::run, sleeps::add);
        assertThat(d.deliver(email())).isEqualTo(MailDispatcher.Outcome.GAVE_UP);
        assertThat(t.attempts).isEqualTo(4);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8));
    }

    @Test
    void permanentErrorsAreNotRetried() {
        ScriptedTransport t = new ScriptedTransport();
        t.failures.add(MailDeliveryException.permanent("bad sender", 400));
        List<Duration> sleeps = new ArrayList<>();
        MailDispatcher d = new MailDispatcher(t, RetryPolicy.DEFAULT, Runnable::run, sleeps::add);
        assertThat(d.deliver(email())).isEqualTo(MailDispatcher.Outcome.FAILED);
        assertThat(t.attempts).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void retryAfterHintIsHonouredButCapped() {
        RetryPolicy p = RetryPolicy.DEFAULT;
        assertThat(p.delayBefore(1, null)).isEqualTo(Duration.ZERO);
        assertThat(p.delayBefore(2, Duration.ofSeconds(9))).isEqualTo(Duration.ofSeconds(9));
        assertThat(p.delayBefore(2, Duration.ofSeconds(1))).isEqualTo(Duration.ofSeconds(2));
        assertThat(p.delayBefore(3, Duration.ofHours(1))).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.delayBefore(10, null)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void insideATransactionNothingIsSentUntilCommit() {
        ScriptedTransport t = new ScriptedTransport();
        MailDispatcher d = new MailDispatcher(t, RetryPolicy.DEFAULT, Runnable::run, x -> {});
        TransactionSynchronizationManager.initSynchronization();
        d.submit(email());
        assertThat(t.attempts).isZero();
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        syncs.forEach(TransactionSynchronization::afterCommit);
        assertThat(t.sent).hasSize(1);
    }

    @Test
    void rolledBackTransactionSendsNothing() {
        ScriptedTransport t = new ScriptedTransport();
        MailDispatcher d = new MailDispatcher(t, RetryPolicy.DEFAULT, Runnable::run, x -> {});
        TransactionSynchronizationManager.initSynchronization();
        d.submit(email());
        // On rollback Spring calls only afterCompletion, never afterCommit.
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(t.attempts).isZero();
    }

    @Test
    void withoutATransactionItIsHandedToTheExecutorImmediately() {
        ScriptedTransport t = new ScriptedTransport();
        List<Runnable> queued = new ArrayList<>();
        MailDispatcher d = new MailDispatcher(t, RetryPolicy.DEFAULT, queued::add, x -> {});
        d.submit(email());
        assertThat(t.attempts).isZero(); // not on the caller's thread
        assertThat(queued).hasSize(1);
        queued.get(0).run();
        assertThat(t.sent).hasSize(1);
    }

    @Test
    void fullQueueDropsWithoutThrowingIntoTheRequest() {
        MailDispatcher d = new MailDispatcher(new ScriptedTransport(), RetryPolicy.DEFAULT,
                r -> { throw new java.util.concurrent.RejectedExecutionException("full"); }, x -> {});
        d.submit(email()); // no exception
    }

    // --- mode selection ---

    private static AppProperties.Mail mail(String mode, String key) {
        return new AppProperties.Mail(mode, "no-reply@howlers.example.com", "Howlers", "",
                new AppProperties.Brevo(key, "https://api.brevo.com", false, Duration.ofSeconds(10)));
    }

    @Test
    void selectsTransportByMode() {
        HttpClient http = HttpClient.newHttpClient();
        JavaMailSenderImpl smtp = new JavaMailSenderImpl();
        assertThat(MailConfig.select(mail("log", null), smtp, http)).isInstanceOf(LogMailTransport.class);
        assertThat(MailConfig.select(mail(null, null), smtp, http)).isInstanceOf(LogMailTransport.class);
        assertThat(MailConfig.select(mail("smtp", null), smtp, http)).isInstanceOf(SmtpMailTransport.class);
        assertThat(MailConfig.select(mail(" Brevo ", "xkeysib-1"), smtp, http)).isInstanceOf(BrevoMailTransport.class);
    }

    @Test
    void misconfiguredModesFailStartup() {
        HttpClient http = HttpClient.newHttpClient();
        assertThatThrownBy(() -> MailConfig.select(mail("brevo", ""), null, http)).hasMessageContaining("BREVO_API_KEY");
        assertThatThrownBy(() -> MailConfig.select(mail("smtp", null), null, http)).hasMessageContaining("SMTP_HOST");
        assertThatThrownBy(() -> MailConfig.select(mail("sendgrid", null), null, http)).hasMessageContaining("Unknown MAIL_MODE");
    }
}
