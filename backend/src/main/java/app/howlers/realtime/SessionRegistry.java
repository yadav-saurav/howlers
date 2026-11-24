package app.howlers.realtime;

import app.howlers.common.security.AccountAccessRevoked;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * Knows every open WebSocket on this node and which user each authenticated STOMP session belongs to, so an account
 * that loses access (suspension, deactivation, deletion, role change) is disconnected at once instead of keeping its
 * live feed until it reconnects (D-030). The STOMP session id is the WebSocket session id.
 *
 * <p>Per node, like the broker (D-017): with several nodes, each node closes the sessions it holds when it handles
 * the change; sessions on other nodes are refused at their next CONNECT and their REST calls fail within the status
 * cache TTL.
 */
@Component
public class SessionRegistry {
    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);
    /** 1008 policy violation: the client must not retry blindly; ours signs out on the next REST 401. */
    static final CloseStatus ACCESS_REVOKED = CloseStatus.POLICY_VIOLATION.withReason("Account access revoked");

    private final Map<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> byUser = new ConcurrentHashMap<>();
    private final Map<String, Long> userOf = new ConcurrentHashMap<>();

    /** Wraps the STOMP handler so every socket is tracked from open to close. */
    WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sockets.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                forget(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    /** Called on STOMP CONNECT, before the account status is checked (so a concurrent revoke can't be missed). */
    void bind(String sessionId, long userId) {
        if (sessionId == null) return;
        userOf.put(sessionId, userId);
        byUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
    }

    void unbind(String sessionId) {
        Long user = sessionId == null ? null : userOf.remove(sessionId);
        if (user != null) {
            byUser.computeIfPresent(user, (k, set) -> {
                set.remove(sessionId);
                return set.isEmpty() ? null : set;
            });
        }
    }

    private void forget(String sessionId) {
        sockets.remove(sessionId);
        unbind(sessionId);
    }

    /** Number of open sockets bound to the user on this node. */
    public int openSessions(long userId) {
        Set<String> ids = byUser.get(userId);
        return ids == null ? 0 : ids.size();
    }

    /** Closes every socket of the user on this node; returns how many were closed. */
    public int closeAll(long userId) {
        Set<String> ids = byUser.get(userId);
        if (ids == null) return 0;
        int closed = 0;
        for (String id : Set.copyOf(ids)) {
            WebSocketSession s = sockets.get(id);
            unbind(id);
            if (s == null) continue;
            try {
                s.close(ACCESS_REVOKED);
                closed++;
            } catch (IOException | RuntimeException e) {
                log.debug("Closing WebSocket {} failed: {}", id, e.toString());
            }
        }
        return closed;
    }

    @Order(10)
    @TransactionalEventListener(fallbackExecution = true)
    public void onAccessRevoked(AccountAccessRevoked e) {
        int n = closeAll(e.userId());
        if (n > 0) log.info("Closed {} WebSocket session(s) of user {} ({})", n, e.userId(), e.reason());
    }
}
