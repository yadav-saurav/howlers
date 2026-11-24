package app.howlers.realtime;

import app.howlers.common.metrics.HowlersMetrics;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** Feeds the {@code howlers_websocket_sessions} gauge (D-026): open STOMP sessions on this node. */
@Component
public class WebSocketMetrics {
    private final HowlersMetrics metrics;

    public WebSocketMetrics(HowlersMetrics metrics) {
        this.metrics = metrics;
    }

    @EventListener
    public void onConnected(SessionConnectedEvent e) {
        metrics.websocketOpened();
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent e) {
        metrics.websocketClosed();
    }
}
