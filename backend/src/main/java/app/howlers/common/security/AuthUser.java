package app.howlers.common.security;

/** Authenticated principal derived from a verified access token. Immutable; never holds secrets. */
public record AuthUser(long id, String username, String role) {
    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
