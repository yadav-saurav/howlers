package app.howlers.auth;

import app.howlers.auth.dto.AuthDtos.AuthResponse;
import app.howlers.auth.dto.AuthDtos.IssuedSession;
import app.howlers.common.config.AppProperties;
import app.howlers.common.text.Tokens;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The auth cookies. All are HttpOnly, SameSite=Strict and scoped to {@code /api/v1/auth}, so page scripts can't read
 * them and other API calls don't carry them.
 * <ul>
 *   <li>{@code howlers_rt}: the refresh token.</li>
 *   <li>{@code howlers_did}: a random device id (D-041). Part of the new-device fingerprint; it identifies a browser
 *       profile to this site only and is never shown or logged.</li>
 *   <li>{@code howlers_wac}: binds passkey ceremonies to the browser that started them (D-041).</li>
 * </ul>
 */
@Component
public class AuthCookies {
    public static final String REFRESH = AuthController.COOKIE;
    public static final String DEVICE = "howlers_did";
    public static final String CEREMONY = "howlers_wac";
    static final String PATH = "/api/v1/auth";
    private static final Duration DEVICE_TTL = Duration.ofDays(400); // the longest cookie lifetime browsers keep
    private static final Duration CEREMONY_TTL = Duration.ofMinutes(10);

    private final AppProperties props;

    public AuthCookies(AppProperties props) {
        this.props = props;
    }

    public AuthResponse withSession(IssuedSession s, HttpServletResponse res) {
        add(res, REFRESH, s.refreshToken(), props.jwt().refreshTtl());
        return s.response();
    }

    public void clearSession(HttpServletResponse res) {
        add(res, REFRESH, "", Duration.ZERO);
    }

    /** The browser's device id, minting (and setting) one when it has none or an unusable one. */
    public String deviceId(HttpServletRequest req, HttpServletResponse res) {
        String v = read(req, DEVICE);
        if (v == null || !v.matches("[A-Za-z0-9_-]{20,64}")) v = Tokens.random(24);
        add(res, DEVICE, v, DEVICE_TTL); // refreshed on every sign-in so an active browser keeps its id
        return v;
    }

    /** Ceremony binding secret: reused while it lives, so two prompts in the same browser don't cancel each other. */
    public String ceremonyBinding(HttpServletRequest req, HttpServletResponse res) {
        String v = read(req, CEREMONY);
        if (v == null || !v.matches("[A-Za-z0-9_-]{20,64}")) v = Tokens.random(24);
        add(res, CEREMONY, v, CEREMONY_TTL);
        return v;
    }

    public String existingCeremonyBinding(HttpServletRequest req) {
        return read(req, CEREMONY);
    }

    static String read(HttpServletRequest req, String name) {
        Cookie[] cs = req.getCookies();
        if (cs == null) return null;
        for (Cookie c : cs) if (name.equals(c.getName())) return c.getValue();
        return null;
    }

    private void add(HttpServletResponse res, String name, String value, Duration maxAge) {
        res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, value)
                .httpOnly(true).secure(props.cookie().secure()).sameSite("Strict").path(PATH)
                .maxAge(maxAge).build().toString());
    }
}
