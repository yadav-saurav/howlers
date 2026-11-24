package app.howlers.common.security;

import app.howlers.common.error.ApiException;

/** Small helpers for the nullable principal passed into services ("viewer" = who is looking). */
public final class Viewer {
    private Viewer() {}

    public static Long idOrNull(AuthUser user) {
        return user == null ? null : user.id();
    }

    public static AuthUser require(AuthUser user) {
        if (user == null) throw ApiException.unauthorized("Authentication required");
        return user;
    }
}
