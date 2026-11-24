package app.howlers.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import app.howlers.admin.AdminService;
import app.howlers.auth.AuthService;
import app.howlers.auth.dto.AuthDtos.RegisterRequest;
import app.howlers.common.security.AuthUser;
import app.howlers.support.IntegrationTest;
import app.howlers.user.UserService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** D-030: suspending, deactivating or deleting an account closes its live sockets at once and refuses reconnects. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionRevocationIT extends IntegrationTest {
    @LocalServerPort int port;
    @Autowired AuthService auth;
    @Autowired AdminService admin;
    @Autowired UserService users;
    @Autowired SessionRegistry sessions;
    @Autowired JdbcClient jdbc;

    private final WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());

    {
        client.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void stop() {
        client.stop();
    }

    private record Registered(AuthUser user, String token) {}

    private Registered user(String prefix) {
        String name = prefix + (System.nanoTime() % 1_000_000);
        var res = auth.register(new RegisterRequest(name, name + "@rev.local", "password1", null), "t", "127.0.0.1").response();
        return new Registered(new AuthUser(res.user().id(), res.user().username(), res.user().role()), res.accessToken());
    }

    private static final class Events extends StompSessionHandlerAdapter {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            events.add("ERROR " + headers.getFirst("message"));
        }

        @Override
        public void handleException(StompSession s, StompCommand c, StompHeaders h, byte[] p, Throwable e) {
            events.add("EXCEPTION " + e);
        }

        @Override
        public void handleTransportError(StompSession session, Throwable e) {
            events.add("TRANSPORT " + e);
        }
    }

    private StompSession connect(String token, Events handler) throws Exception {
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + token);
        return client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connect, handler)
                .get(10, TimeUnit.SECONDS);
    }

    private static boolean closedWithin(StompSession s, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (!s.isConnected()) return true;
            Thread.sleep(50);
        }
        return !s.isConnected();
    }

    /** A reconnect with the still-unexpired access token must be refused (ERROR frame or a failed connect). */
    private boolean reconnectRefused(String token) throws Exception {
        Events h = new Events();
        try {
            StompSession s = connect(token, h);
            String ev = h.events.poll(10, TimeUnit.SECONDS);
            return ev != null && (ev.contains("can't connect") || !s.isConnected() || closedWithin(s, 3000));
        } catch (Exception e) {
            return true;
        }
    }

    private Registered admin() {
        Registered a = user("boss");
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = ?").param(a.user().id()).update();
        return new Registered(new AuthUser(a.user().id(), a.user().username(), "ADMIN"), a.token());
    }

    @Test
    void suspensionClosesOpenSocketsAndRefusesReconnect() throws Exception {
        Registered boss = admin(), mia = user("mia");
        StompSession tab1 = connect(mia.token(), new Events());
        StompSession tab2 = connect(mia.token(), new Events());
        tab1.subscribe("/user/queue/events", new Events());
        Thread.sleep(300);
        assertThat(sessions.openSessions(mia.user().id())).isEqualTo(2);
        assertThat(tab1.isConnected()).isTrue();

        admin.suspend(boss.user(), mia.user().id(), "spam");

        assertThat(closedWithin(tab1, 5000)).isTrue();
        assertThat(closedWithin(tab2, 5000)).isTrue();
        assertThat(sessions.openSessions(mia.user().id())).isZero();
        assertThat(reconnectRefused(mia.token())).isTrue();

        // Others are unaffected, and an unsuspended account can connect again with a valid token.
        StompSession bossSession = connect(boss.token(), new Events());
        assertThat(bossSession.isConnected()).isTrue();
        admin.unsuspend(boss.user(), mia.user().id(), "appeal accepted");
        StompSession back = connect(mia.token(), new Events());
        Thread.sleep(300);
        assertThat(back.isConnected()).isTrue();
    }

    @Test
    void deactivationAndDeletionCloseSocketsToo() throws Exception {
        Registered ivy = user("ivy"), kai = user("kai");
        StompSession ivySession = connect(ivy.token(), new Events());
        StompSession kaiSession = connect(kai.token(), new Events());
        Thread.sleep(300);

        users.deactivate(ivy.user(), "password1");
        assertThat(closedWithin(ivySession, 5000)).isTrue();
        assertThat(reconnectRefused(ivy.token())).isTrue();

        users.delete(kai.user(), "password1");
        assertThat(closedWithin(kaiSession, 5000)).isTrue();
        assertThat(reconnectRefused(kai.token())).isTrue();
    }
}
