package app.howlers.mail;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Queues transactional email off the request thread (D-038).
 * <ul>
 *   <li>Inside a transaction the message is handed over only after commit, so the token it links to exists; a
 *       rolled-back transaction sends nothing.</li>
 *   <li>Delivery runs on a small dedicated executor; the HTTP request never waits for the provider.</li>
 *   <li>429, 5xx and network failures are retried with {@link RetryPolicy} backoff; anything else (bad sender,
 *       rejected key) is logged once as an error and dropped.</li>
 *   <li>Outside {@code log} mode only the tag, a masked recipient and the provider message id are logged.</li>
 * </ul>
 */
public class MailDispatcher {
    private static final Logger log = LoggerFactory.getLogger(MailDispatcher.class);

    public enum Outcome { SENT, FAILED, GAVE_UP }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private volatile MailTransport transport;
    private final RetryPolicy policy;
    private final Executor executor;
    private final Sleeper sleeper;

    public MailDispatcher(MailTransport transport, RetryPolicy policy, Executor executor, Sleeper sleeper) {
        this.transport = transport;
        this.policy = policy;
        this.executor = executor;
        this.sleeper = sleeper;
    }

    public MailTransport transport() { return transport; }

    /** On shutdown, give queued mail up to 10 seconds to go out. */
    public void close() {
        if (executor instanceof ExecutorService es) {
            es.shutdown();
            try {
                if (!es.awaitTermination(10, TimeUnit.SECONDS)) log.warn("Mail queue not drained at shutdown");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Replaces the transport and returns the previous one. Integration tests use it to record mail; nothing else should. */
    public MailTransport swapTransport(MailTransport replacement) {
        MailTransport old = transport;
        transport = replacement;
        return old;
    }

    /** Sends after the current transaction commits, or right away (still asynchronously) when there is none. */
    public void submit(OutgoingEmail email) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue(email);
                }
            });
        } else {
            enqueue(email);
        }
    }

    private void enqueue(OutgoingEmail email) {
        try {
            executor.execute(() -> deliver(email));
        } catch (RuntimeException e) { // RejectedExecutionException: queue full or shutting down
            log.error("Mail queue full; dropped {} to {}", email.tag(), OutgoingEmail.mask(email.to()));
        }
    }

    /** Synchronous delivery with retries. Runs on the mail executor; public for tests. */
    public Outcome deliver(OutgoingEmail email) {
        MailTransport t = transport;
        String who = OutgoingEmail.mask(email.to());
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                String id = t.send(email);
                if (!"log".equals(t.name())) {
                    log.info("Mail sent via {}: {} to {}{}{}", t.name(), email.tag(), who,
                            id == null ? "" : " id=" + id, attempt > 1 ? " after " + attempt + " attempts" : "");
                }
                return Outcome.SENT;
            } catch (MailDeliveryException e) {
                if (!e.retryable()) {
                    log.error("Mail not sent via {}: {} to {}: {}", t.name(), email.tag(), who, e.getMessage());
                    return Outcome.FAILED;
                }
                if (!policy.canRetry(attempt)) {
                    log.error("Mail not sent via {} after {} attempts: {} to {}: {}", t.name(), attempt, email.tag(), who,
                            e.getMessage());
                    return Outcome.GAVE_UP;
                }
                Duration wait = policy.delayBefore(attempt + 1, e.retryAfter());
                log.warn("Mail attempt {} via {} failed ({}); retrying in {} ms", attempt, t.name(), e.getMessage(),
                        wait.toMillis());
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("Mail not sent (interrupted during shutdown): {} to {}", email.tag(), who);
                    return Outcome.GAVE_UP;
                }
            } catch (RuntimeException e) {
                log.error("Mail not sent via {}: {} to {}: unexpected {}", t.name(), email.tag(), who,
                        e.getClass().getSimpleName());
                return Outcome.FAILED;
            }
        }
    }
}
