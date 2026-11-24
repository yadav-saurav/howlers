package app.howlers.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Request building and response handling against a local stub of Brevo's API (JDK HttpServer); no network. */
class BrevoMailTransportTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "xkeysib-test-not-a-real-key";

    record Reply(int status, String body, Map<String, String> headers) {}
    record Received(String method, String path, Map<String, List<String>> headers, String body) {}

    private HttpServer server;
    private final Deque<Reply> replies = new ConcurrentLinkedDeque<>();
    private final List<Received> received = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            synchronized (received) {
                received.add(new Received(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                        Map.copyOf(ex.getRequestHeaders()), new String(body, StandardCharsets.UTF_8)));
            }
            Reply r = replies.isEmpty() ? new Reply(201, "{\"messageId\":\"<default@smtp-relay>\"}", Map.of()) : replies.poll();
            r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(r.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private BrevoMailTransport transport(boolean removePixel, String replyTo) {
        return new BrevoMailTransport(HttpClient.newHttpClient(), baseUrl(), KEY, "no-reply@howlers.example.com", "Howlers",
                replyTo, removePixel, Duration.ofSeconds(5));
    }

    private static OutgoingEmail email() {
        return new OutgoingEmail("ann@example.com", "ann \"the\" k", "Verify your Howlers email",
                "<p>Hi &lt;ann&gt;</p>\n<a href=\"https://x.test/verify-email?token=abc\">Verify</a>",
                "Hi <ann>\nhttps://x.test/verify-email?token=abc", "verify-email", "idem-1");
    }

    @Test
    void buildsTheDocumentedPayload() throws Exception {
        JsonNode n = JSON.readTree(transport(false, "help@howlers.example.com").payload(email()));
        assertThat(n.at("/sender/email").asText()).isEqualTo("no-reply@howlers.example.com");
        assertThat(n.at("/sender/name").asText()).isEqualTo("Howlers");
        assertThat(n.at("/to/0/email").asText()).isEqualTo("ann@example.com");
        assertThat(n.at("/to/0/name").asText()).isEqualTo("ann \"the\" k");
        assertThat(n.at("/to/0/contactPixelTrackingConsent").asBoolean(true)).isFalse();
        assertThat(n.at("/to/0").has("removeTrackingPixel")).isFalse();
        assertThat(n.at("/replyTo/email").asText()).isEqualTo("help@howlers.example.com");
        assertThat(n.get("subject").asText()).isEqualTo("Verify your Howlers email");
        assertThat(n.get("htmlContent").asText()).isEqualTo(email().html());
        assertThat(n.get("textContent").asText()).isEqualTo(email().text());
        assertThat(n.at("/tags/0").asText()).isEqualTo("verify-email");
        assertThat(n.at("/headers/IdempotencyKey").asText()).isEqualTo("idem-1");
    }

    @Test
    void jsonEscapesQuotesNewlinesAndAngleBrackets() {
        String raw = transport(false, null).payload(email());
        assertThat(raw).contains("ann \\\"the\\\" k").contains("\\n").doesNotContain("\"replyTo\"");
    }

    @Test
    void optionalPixelRemovalIsOptIn() throws Exception {
        JsonNode n = JSON.readTree(transport(true, null).payload(email()));
        assertThat(n.at("/to/0/removeTrackingPixel").asBoolean()).isTrue();
    }

    @Test
    void requestCarriesApiKeyHeaderAndPostsJson() {
        HttpRequest r = transport(false, null).request(email());
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.uri().getPath()).isEqualTo("/v3/smtp/email");
        assertThat(r.headers().firstValue("api-key")).contains(KEY);
        assertThat(r.headers().firstValue("content-type")).contains("application/json");
        assertThat(r.headers().firstValue("accept")).contains("application/json");
    }

    @Test
    void sendsEndToEndAndReturnsMessageId() throws Exception {
        replies.add(new Reply(201, "{\"messageId\":\"<abc@smtp-relay.mailin.fr>\"}", Map.of()));
        String id = transport(false, null).send(email());
        assertThat(id).isEqualTo("<abc@smtp-relay.mailin.fr>");
        Received got = received.get(0);
        assertThat(got.method()).isEqualTo("POST");
        assertThat(got.path()).isEqualTo("/v3/smtp/email");
        assertThat(header(got, "api-key")).isEqualTo(KEY);
        JsonNode body = JSON.readTree(got.body());
        assertThat(body.at("/to/0/email").asText()).isEqualTo("ann@example.com");
        assertThat(body.get("htmlContent").asText()).contains("<a href=\"https://x.test/verify-email?token=abc\">");
    }

    @Test
    void throttlingAndServerErrorsAreRetryable() {
        replies.add(new Reply(429, "{\"code\":\"too_many_requests\",\"message\":\"slow down\"}", Map.of("Retry-After", "7")));
        assertThatThrownBy(() -> transport(false, null).send(email()))
                .isInstanceOfSatisfying(MailDeliveryException.class, e -> {
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.status()).isEqualTo(429);
                    assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(7));
                });
        replies.add(new Reply(503, "", Map.of()));
        assertThatThrownBy(() -> transport(false, null).send(email()))
                .isInstanceOfSatisfying(MailDeliveryException.class, e -> assertThat(e.retryable()).isTrue());
    }

    @Test
    void badRequestIsPermanentAndExplainsTheSender() {
        replies.add(new Reply(400, "{\"code\":\"invalid_parameter\",\"message\":\"Sender is invalid / inactive\"}", Map.of()));
        assertThatThrownBy(() -> transport(false, null).send(email()))
                .isInstanceOfSatisfying(MailDeliveryException.class, e -> {
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).contains("invalid_parameter", "Sender is invalid", "verified sender")
                            .doesNotContain(KEY).doesNotContain("token=");
                });
    }

    @Test
    void rejectedKeyIsPermanentAndNeverEchoesTheKey() {
        replies.add(new Reply(401, "{\"code\":\"unauthorized\",\"message\":\"Key not found\"}", Map.of()));
        assertThatThrownBy(() -> transport(false, null).send(email()))
                .isInstanceOfSatisfying(MailDeliveryException.class, e -> {
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).contains("BREVO_API_KEY").doesNotContain(KEY);
                });
    }

    @Test
    void duplicateRequestMeansAnEarlierAttemptWasAccepted() throws Exception {
        replies.add(new Reply(400, "{\"code\":\"duplicate_request\",\"message\":\"Request already processed\"}", Map.of()));
        assertThat(transport(false, null).send(email())).isNull();
    }

    @Test
    void networkFailureIsRetryable() {
        int port = server.getAddress().getPort();
        server.stop(0);
        BrevoMailTransport t = new BrevoMailTransport(HttpClient.newHttpClient(), "http://127.0.0.1:" + port, KEY,
                "no-reply@howlers.example.com", null, null, false, Duration.ofSeconds(2));
        assertThatThrownBy(() -> t.send(email()))
                .isInstanceOfSatisfying(MailDeliveryException.class, e -> assertThat(e.retryable()).isTrue());
    }

    @Test
    void refusesToStartWithoutKeyOrSender() {
        assertThatThrownBy(() -> new BrevoMailTransport(HttpClient.newHttpClient(), null, " ", "a@b.c", null, null, false, null))
                .hasMessageContaining("BREVO_API_KEY");
        assertThatThrownBy(() -> new BrevoMailTransport(HttpClient.newHttpClient(), null, KEY, "", null, null, false, null))
                .hasMessageContaining("MAIL_FROM_ADDRESS");
    }

    @Test
    void dispatcherRetriesThrottledSendsAgainstTheStub() {
        replies.add(new Reply(429, "", Map.of("Retry-After", "1")));
        replies.add(new Reply(502, "", Map.of()));
        replies.add(new Reply(201, "{\"messageId\":\"<ok@relay>\"}", Map.of()));
        List<Duration> sleeps = new ArrayList<>();
        MailDispatcher d = new MailDispatcher(transport(false, null), RetryPolicy.DEFAULT, Runnable::run, sleeps::add);
        assertThat(d.deliver(email())).isEqualTo(MailDispatcher.Outcome.SENT);
        assertThat(received).hasSize(3);
        // Same idempotency key on every attempt, so Brevo can drop a duplicate.
        assertThat(received).extracting(r -> r.body().contains("\"IdempotencyKey\":\"idem-1\"")).containsOnly(true);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    private static String header(Received r, String name) {
        return r.headers().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(e -> e.getValue().get(0)).findFirst().orElse(null);
    }
}
