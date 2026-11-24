package app.howlers.auth;

import app.howlers.auth.dto.AuthDtos.AuthResponse;
import app.howlers.auth.dto.AuthDtos.ForgotPasswordRequest;
import app.howlers.auth.dto.AuthDtos.IssuedSession;
import app.howlers.auth.dto.AuthDtos.Issued;
import app.howlers.auth.dto.AuthDtos.LoginOutcome;
import app.howlers.auth.dto.AuthDtos.LoginRequest;
import app.howlers.auth.dto.AuthDtos.MfaLoginRequest;
import app.howlers.auth.dto.AuthDtos.SecondStep;
import app.howlers.auth.passkey.PasskeyService;
import app.howlers.auth.passkey.PasskeyService.CeremonyStart;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import app.howlers.auth.dto.AuthDtos.RegisterRequest;
import app.howlers.auth.dto.AuthDtos.ResetPasswordRequest;
import app.howlers.auth.dto.AuthDtos.SessionDto;
import app.howlers.auth.dto.AuthDtos.TokenRequest;
import app.howlers.auth.dto.AuthDtos.UsernameAvailability;
import app.howlers.auth.dto.AuthDtos.VerifyEmailResult;
import app.howlers.common.config.AppProperties;
import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.common.text.Tokens;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public static final String COOKIE = "howlers_rt";

    private final AuthService auth;
    private final RateLimiter limiter;
    private final AppProperties props;
    private final AuthCookies cookies;
    private final PasskeyService passkeys;

    public AuthController(AuthService auth, RateLimiter limiter, AppProperties props, AuthCookies cookies,
                          PasskeyService passkeys) {
        this.auth = auth;
        this.limiter = limiter;
        this.props = props;
        this.cookies = cookies;
        this.passkeys = passkeys;
    }

    public record PasskeyLoginRequest(@NotBlank @Size(max = 100) String requestId, @NotNull JsonNode credential) {}

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest req, HttpServletRequest http, HttpServletResponse res) {
        limiter.check("register:" + clientIp(http), 5, Duration.ofHours(1));
        String device = cookies.deviceId(http, res);
        return withCookie(auth.register(req, http.getHeader(HttpHeaders.USER_AGENT), clientIp(http), device), res);
    }

    /**
     * {@link AuthResponse} (and the refresh cookie) when the password is enough; {@code MfaRequired} with a
     * second-step token when the account has two-factor authentication on (D-042).
     */
    @PostMapping("/login")
    public Object login(@Valid @RequestBody LoginRequest req, HttpServletRequest http, HttpServletResponse res) {
        limiter.check("login:ip:" + clientIp(http), 30, Duration.ofMinutes(10));
        limiter.check("login:acct:" + req.login().toLowerCase(), 10, Duration.ofMinutes(10));
        String device = cookies.deviceId(http, res);
        LoginOutcome o = auth.passwordLogin(req.login(), req.password(), http.getHeader(HttpHeaders.USER_AGENT), clientIp(http), device);
        return switch (o) {
            case Issued i -> withCookie(i.session(), res);
            case SecondStep step -> step.challenge();
        };
    }

    /** Second step: the token from /login plus an authenticator code or a recovery code. */
    @PostMapping("/login/2fa")
    public AuthResponse loginSecondStep(@Valid @RequestBody MfaLoginRequest req, HttpServletRequest http, HttpServletResponse res) {
        limiter.check("login2fa:ip:" + clientIp(http), 30, Duration.ofMinutes(10));
        limiter.check("login2fa:tok:" + Tokens.sha256(req.mfaToken()), 10, Duration.ofMinutes(10));
        String device = cookies.deviceId(http, res);
        return withCookie(auth.completeMfaLogin(req.mfaToken(), req.code(), http.getHeader(HttpHeaders.USER_AGENT),
                clientIp(http), device), res);
    }

    /** Starts a usernameless passkey sign-in (D-041). The challenge is bound to this browser by a cookie. */
    @PostMapping("/passkey-login/options")
    public CeremonyStart passkeyLoginOptions(HttpServletRequest http, HttpServletResponse res) {
        requireAllowedOrigin(http);
        limiter.check("pklogin:opts:" + clientIp(http), 30, Duration.ofMinutes(5));
        return passkeys.startAuthentication(cookies.ceremonyBinding(http, res));
    }

    /** Finishes a passkey sign-in and issues the same session as a password login. Satisfies 2FA (D-041). */
    @PostMapping("/passkey-login/verify")
    public AuthResponse passkeyLogin(@Valid @RequestBody PasskeyLoginRequest req, HttpServletRequest http, HttpServletResponse res) {
        requireAllowedOrigin(http);
        limiter.check("pklogin:verify:" + clientIp(http), 30, Duration.ofMinutes(5));
        long userId = passkeys.finishAuthentication(req.requestId(), cookies.existingCeremonyBinding(http), req.credential());
        String device = cookies.deviceId(http, res);
        return withCookie(auth.passkeyLogin(userId, http.getHeader(HttpHeaders.USER_AGENT), clientIp(http), device), res);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@CookieValue(name = COOKIE, required = false) String token,
                                HttpServletRequest http, HttpServletResponse res) {
        requireAllowedOrigin(http);
        limiter.check("refresh:" + clientIp(http), 60, Duration.ofMinutes(1));
        try {
            return withCookie(auth.refresh(token, http.getHeader(HttpHeaders.USER_AGENT), clientIp(http)), res);
        } catch (ApiException e) {
            clearCookie(res);
            throw e;
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name = COOKIE, required = false) String token, HttpServletRequest http,
                       HttpServletResponse res) {
        requireAllowedOrigin(http);
        auth.logout(token);
        clearCookie(res);
    }

    @GetMapping("/username-available")
    public UsernameAvailability usernameAvailable(@RequestParam String username, HttpServletRequest http) {
        limiter.check("uname:" + clientIp(http), 120, Duration.ofMinutes(1));
        return auth.usernameAvailability(username);
    }

    @PostMapping("/verify-email")
    public VerifyEmailResult verifyEmail(@Valid @RequestBody TokenRequest req, HttpServletRequest http) {
        limiter.check("verify:" + clientIp(http), 30, Duration.ofHours(1));
        return auth.verifyEmail(req.token());
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resend(@AuthenticationPrincipal AuthUser user) {
        limiter.check("resend:" + user.id(), 3, Duration.ofHours(1));
        auth.resendVerification(user.id());
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgot(@Valid @RequestBody ForgotPasswordRequest req, HttpServletRequest http) {
        limiter.check("forgot:" + clientIp(http), 5, Duration.ofHours(1));
        // Per address too, so one mailbox can't be flooded from many IPs. Applies whether or not the account exists.
        limiter.check("forgot:addr:" + Tokens.sha256(req.email().trim().toLowerCase(Locale.ROOT)), 3, Duration.ofHours(1));
        auth.forgotPassword(req.email());
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@Valid @RequestBody ResetPasswordRequest req, HttpServletRequest http) {
        limiter.check("reset:" + clientIp(http), 10, Duration.ofHours(1));
        auth.resetPassword(req.token(), req.newPassword());
    }

    @GetMapping("/sessions")
    public List<SessionDto> sessions(@AuthenticationPrincipal AuthUser user,
                                     @CookieValue(name = COOKIE, required = false) String token) {
        return auth.sessions(user.id(), token);
    }

    /** "Sign out everywhere else": revokes every session except the one this browser's refresh cookie belongs to. */
    @DeleteMapping("/sessions/others")
    public Map<String, Integer> revokeOtherSessions(@AuthenticationPrincipal AuthUser user,
                                                    @CookieValue(name = COOKIE, required = false) String token) {
        return Map.of("revoked", auth.revokeOtherSessions(user.id(), token));
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSession(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        auth.revokeSession(user.id(), id);
    }

    private AuthResponse withCookie(IssuedSession s, HttpServletResponse res) {
        return cookies.withSession(s, res);
    }

    private void clearCookie(HttpServletResponse res) {
        cookies.clearSession(res);
    }

    /** Defence in depth for the cookie-authenticated endpoints, on top of SameSite=Strict. */
    private void requireAllowedOrigin(HttpServletRequest http) {
        String origin = http.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !props.cors().allowedOrigins().contains(origin)) {
            throw ApiException.forbidden("Origin not allowed");
        }
    }

    /** Behind the reverse proxy, server.forward-headers-strategy=native resolves X-Forwarded-For. */
    static String clientIp(HttpServletRequest http) {
        return http.getRemoteAddr();
    }
}
