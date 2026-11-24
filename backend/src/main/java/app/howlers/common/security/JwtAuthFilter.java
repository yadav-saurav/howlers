package app.howlers.common.security;

import app.howlers.common.error.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates the security context from a Bearer token. A request that presents an invalid or expired
 * token is rejected with 401 (even on public endpoints) so the client knows to refresh, instead of
 * silently being treated as anonymous.
 */
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final ObjectMapper mapper;
    private final AccountStatusCache accounts;

    public JwtAuthFilter(JwtService jwt, ObjectMapper mapper, AccountStatusCache accounts) {
        this.jwt = jwt;
        this.mapper = mapper;
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        // Under /auth/ a stale or invalid token must not block login, refresh or logout, so it is ignored there rather
        // than rejected; a valid one still authenticates, which the signed-in auth endpoints (sessions, passkeys,
        // two-factor settings) need.
        boolean authPath = req.getRequestURI().startsWith("/api/v1/auth/");
        if (header != null && header.startsWith("Bearer ")) {
            Optional<AuthUser> token = jwt.verify(header.substring(7));
            if (token.isEmpty()) {
                if (authPath) {
                    chain.doFilter(req, res);
                    return;
                }
                reject(res, "Access token expired or invalid");
                return;
            }
            // A valid token is not enough: suspended or deleted accounts lose access at once, and the role comes
            // from the account (a promoted or demoted admin doesn't wait for the token to expire). See D-021.
            Optional<AccountStatusCache.State> state = accounts.state(token.get().id());
            if (state.isEmpty() || !state.get().active()) {
                if (authPath) {
                    chain.doFilter(req, res);
                    return;
                }
                reject(res, state.isPresent() && "SUSPENDED".equals(state.get().status())
                        ? "This account is suspended" : "Account is not active");
                return;
            }
            AuthUser user = new AuthUser(token.get().id(), token.get().username(), state.get().role());
            var auth = new UsernamePasswordAuthenticationToken(user, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(req, res);
    }

    private void reject(HttpServletResponse res, String message) throws IOException {
        res.setStatus(HttpStatus.UNAUTHORIZED.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), GlobalExceptionHandler.body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message));
    }
}
