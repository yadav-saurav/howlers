package app.howlers.realtime;

import app.howlers.common.security.AuthUser;
import java.security.Principal;

/** WebSocket session identity. {@code getName()} is the user id, which is what user destinations are keyed by. */
public record StompPrincipal(AuthUser user) implements Principal {
    @Override
    public String getName() {
        return Long.toString(user.id());
    }
}
