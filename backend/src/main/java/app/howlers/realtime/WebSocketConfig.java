package app.howlers.realtime;

import app.howlers.common.config.AppProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over plain WebSocket at {@code /ws}. Uses Spring's in-memory simple broker (single node); swapping to a
 * broker relay (e.g. RabbitMQ STOMP) is a configuration change here if the app is ever scaled horizontally.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final StompSecurityInterceptor security;
    private final AppProperties props;
    private final SessionRegistry sessions;

    public WebSocketConfig(StompSecurityInterceptor security, AppProperties props, SessionRegistry sessions) {
        this.security = security;
        this.props = props;
        this.sessions = sessions;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(props.cors().allowedOrigins().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        ThreadPoolTaskScheduler heartbeat = new ThreadPoolTaskScheduler();
        heartbeat.setPoolSize(1);
        heartbeat.setThreadNamePrefix("ws-heartbeat-");
        heartbeat.initialize();
        // Heartbeats let both sides notice dead connections (and keep proxies from idling the socket out).
        registry.enableSimpleBroker("/queue", "/topic").setHeartbeatValue(new long[]{20_000, 20_000}).setTaskScheduler(heartbeat);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(security);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(16 * 1024).setSendBufferSizeLimit(512 * 1024).setSendTimeLimit(15_000);
        // Track every socket so a suspended or deleted account can be disconnected at once (D-030).
        registration.addDecoratorFactory(sessions::decorate);
    }
}
