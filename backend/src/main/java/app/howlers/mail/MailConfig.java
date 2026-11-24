package app.howlers.mail;

import app.howlers.common.config.AppProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/** Picks the mail transport from {@code MAIL_MODE} (log | smtp | brevo) and builds the async dispatcher (D-038). */
@Configuration(proxyBeanMethods = false)
public class MailConfig {
    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    MailTransport mailTransport(AppProperties props, ObjectProvider<JavaMailSender> javaMail) {
        MailTransport t = select(props.mail(), javaMail.getIfAvailable(), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
        AppProperties.Mail m = props.mail();
        switch (t.name()) {
            case "brevo" -> log.info("Mail: Brevo transactional API, from \"{}\" <{}>{}, API key configured. The sender must be"
                    + " verified in the Brevo account (Senders, domains & dedicated IPs) or Brevo returns 400.",
                    m.fromName(), m.fromAddress(), blank(m.replyTo()) ? "" : ", reply-to <" + m.replyTo() + ">");
            case "smtp" -> log.info("Mail: SMTP, from \"{}\" <{}>. The sender must be allowed by the relay.", m.fromName(), m.fromAddress());
            default -> log.info("Mail: log mode (MAIL-DEV); no email leaves this server");
        }
        return t;
    }

    /** Mode selection, separate from Spring so it can be unit-tested. Unknown modes and missing settings fail startup. */
    static MailTransport select(AppProperties.Mail m, JavaMailSender javaMail, HttpClient http) {
        String mode = m.mode() == null ? "log" : m.mode().trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "", "log" -> new LogMailTransport();
            case "smtp" -> {
                if (javaMail == null) throw new IllegalStateException("MAIL_MODE=smtp needs SMTP_HOST (spring.mail.host)");
                yield new SmtpMailTransport(javaMail, m.fromAddress(), m.fromName(), m.replyTo());
            }
            case "brevo" -> {
                AppProperties.Brevo b = m.brevo();
                if (b == null || blank(b.apiKey())) throw new IllegalStateException("MAIL_MODE=brevo needs BREVO_API_KEY");
                yield new BrevoMailTransport(http, b.baseUrl(), b.apiKey(), m.fromAddress(), m.fromName(), m.replyTo(),
                        b.removeTrackingPixel(), b.timeout());
            }
            default -> throw new IllegalStateException("Unknown MAIL_MODE '" + m.mode() + "' (use log, smtp or brevo)");
        };
    }

    @Bean(destroyMethod = "close")
    MailDispatcher mailDispatcher(MailTransport transport) {
        return new MailDispatcher(transport, RetryPolicy.DEFAULT, mailExecutor(), d -> Thread.sleep(d.toMillis()));
    }

    /** Two daemon threads and a bounded queue: a provider outage can't pile up unbounded work or block shutdown. */
    private static ExecutorService mailExecutor() {
        AtomicInteger n = new AtomicInteger();
        ThreadPoolExecutor ex = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1000), r -> {
            Thread th = new Thread(r, "mail-" + n.incrementAndGet());
            th.setDaemon(true);
            return th;
        });
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
