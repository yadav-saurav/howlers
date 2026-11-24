package app.howlers.realtime;

import app.howlers.common.error.ApiException;
import app.howlers.common.security.AccountStatusCache;
import app.howlers.common.security.AuthUser;
import app.howlers.common.security.JwtService;
import app.howlers.community.Community;
import app.howlers.community.CommunityAccess;
import app.howlers.community.CommunityRepository;
import app.howlers.post.PostService;
import app.howlers.pulse.PulseService;
import java.security.Principal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Authenticates STOMP sessions and authorises every subscription.
 *
 * <ul>
 *   <li>CONNECT must carry a valid access token ({@code Authorization: Bearer ...}) of an ACTIVE account. Browsers
 *       can't set headers on the WebSocket handshake itself, so auth happens at the STOMP layer. Suspended,
 *       deactivated and deleted accounts are refused, and their open sessions are closed when the change commits
 *       ({@link SessionRegistry}, D-030).</li>
 *   <li>SUBSCRIBE is allowed only to your own user queue, the public new-posts topic, communities, posts and pulses
 *       you can view, and mod queues you moderate.
 *       Anything else is refused with an ERROR frame.</li>
 *   <li>SEND is allowed only to application destinations ({@code /app/**}); clients can't publish to topics directly.</li>
 * </ul>
 */
@Component
public class StompSecurityInterceptor implements ChannelInterceptor {
    private static final Pattern PULSE = Pattern.compile("^/topic/pulse\\.(\\d+)$");
    private static final Pattern MODQUEUE = Pattern.compile("^/topic/modqueue\\.(\\d+)$");
    private static final Pattern COMMUNITY = Pattern.compile("^/topic/community\\.(\\d+)$");
    private static final Pattern POST = Pattern.compile("^/topic/post\\.(\\d+)$");

    private final JwtService jwt;
    private final PulseService pulses;
    private final CommunityRepository communities;
    private final CommunityAccess access;
    private final PostService posts;
    private final AccountStatusCache accounts;
    private final SessionRegistry sessions;

    public StompSecurityInterceptor(JwtService jwt, @Lazy PulseService pulses, CommunityRepository communities, CommunityAccess access,
                                    @Lazy PostService posts, AccountStatusCache accounts, SessionRegistry sessions) {
        this.jwt = jwt;
        this.accounts = accounts;
        this.sessions = sessions;
        this.posts = posts;
        this.pulses = pulses;
        this.communities = communities;
        this.access = access;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (acc == null || acc.getCommand() == null) return message;
        StompCommand cmd = acc.getCommand();
        if (cmd == StompCommand.CONNECT) {
            String header = acc.getFirstNativeHeader("Authorization");
            String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
            AuthUser user = token == null ? null : jwt.verify(token).orElse(null);
            if (user == null) throw new MessageDeliveryException("Authentication required");
            // Bind first, then check the account: a suspension that commits in between closes this session too.
            sessions.bind(acc.getSessionId(), user.id());
            Optional<AccountStatusCache.State> state = accounts.state(user.id());
            if (state.isEmpty() || !state.get().active()) {
                sessions.unbind(acc.getSessionId());
                throw new MessageDeliveryException("This account can't connect");
            }
            // The role comes from the account, not the token, like REST (D-021).
            acc.setUser(new StompPrincipal(new AuthUser(user.id(), user.username(), state.get().role())));
            return message;
        }
        AuthUser user = userOf(acc.getUser());
        if (cmd == StompCommand.SUBSCRIBE) {
            if (user == null) throw new MessageDeliveryException("Authentication required");
            String dest = Optional.ofNullable(acc.getDestination()).orElse("");
            if (!allowed(dest, user)) throw new MessageDeliveryException("Not allowed to subscribe to " + dest);
        } else if (cmd == StompCommand.SEND) {
            if (user == null) throw new MessageDeliveryException("Authentication required");
            String dest = Optional.ofNullable(acc.getDestination()).orElse("");
            if (!dest.startsWith("/app/")) throw new MessageDeliveryException("Clients may only send to /app");
        }
        return message;
    }

    private boolean allowed(String dest, AuthUser user) {
        if (dest.equals("/user/queue/events") || dest.equals("/topic/posts")) return true;
        Matcher cm = COMMUNITY.matcher(dest);
        if (cm.matches()) {
            Optional<Community> c = communities.findById(Long.parseLong(cm.group(1)));
            return c.isPresent() && access.canView(c.get(), access.roleOf(c.get(), user), user);
        }
        Matcher pm = POST.matcher(dest);
        if (pm.matches()) {
            try {
                posts.context(Long.parseLong(pm.group(1)), user);
                return true;
            } catch (ApiException e) {
                return false;
            }
        }
        Matcher m = PULSE.matcher(dest);
        if (m.matches()) {
            try {
                pulses.get(Long.parseLong(m.group(1)), user);
                return true;
            } catch (ApiException e) {
                return false;
            }
        }
        m = MODQUEUE.matcher(dest);
        if (m.matches()) {
            Optional<Community> c = communities.findById(Long.parseLong(m.group(1)));
            return c.isPresent() && access.canModerate(access.roleOf(c.get(), user), user);
        }
        return false;
    }

    static AuthUser userOf(Principal p) {
        return p instanceof StompPrincipal sp ? sp.user() : null;
    }
}
