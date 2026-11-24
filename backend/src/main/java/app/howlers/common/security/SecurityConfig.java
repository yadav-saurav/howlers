package app.howlers.common.security;

import app.howlers.common.config.AppProperties;
import app.howlers.common.error.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless API security. CSRF protection is not needed for Bearer-authenticated calls; the only
 * cookie-authenticated endpoints (/auth/refresh, /auth/logout) use a SameSite=Strict, path-scoped
 * cookie and an Origin allow-list check (see AuthController).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] AUTHENTICATED_GETS = {
            "/api/v1/users/me", "/api/v1/users/me/**", "/api/v1/me/**", "/api/v1/notifications/**",
            "/api/v1/auth/sessions", "/api/v1/onboarding/**",
            "/api/v1/podcasts/episodes/*/audio-generation"
    };

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtService jwt, ObjectMapper mapper, AppProperties props,
                            AccountStatusCache accounts) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(cors(props)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .headers(h -> h
                        .contentTypeOptions(c -> {})
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        // Sent only on HTTPS requests (X-Forwarded-Proto from the TLS proxy counts), never on plain HTTP dev.
                        .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31_536_000).includeSubDomains(true)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/info", "/livez", "/readyz").permitAll()
                        // Only exposed in prod, and only on the unpublished management port (D-026).
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // WebSocket handshake; the STOMP CONNECT frame carries and checks the access token.
                        .requestMatchers("/ws", "/ws/**").permitAll()
                        // Before the public-GET rule below, or admin GETs would be open to everyone (D-021).
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, AUTHENTICATED_GETS).authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/resend-verification").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/auth/sessions/**").authenticated()
                        // Account security (D-041, D-042); passkey sign-in itself is /auth/passkey-login/**, public.
                        .requestMatchers("/api/v1/auth/security", "/api/v1/auth/2fa/**", "/api/v1/auth/passkeys",
                                "/api/v1/auth/passkeys/**").authenticated()
                        // Payment provider webhooks authenticate with a signed body (FundingService.webhook), not a JWT.
                        .requestMatchers(HttpMethod.POST, "/api/v1/funding/webhooks/*").permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // Public share links: the password check happens in ShareService (rate-limited, logged).
                        .requestMatchers(HttpMethod.POST, "/api/v1/share/*/unlock").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                write(res, mapper, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required"))
                        .accessDeniedHandler((req, res, ex) ->
                                write(res, mapper, HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to do that")))
                .addFilterBefore(new JwtAuthFilter(jwt, mapper, accounts), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    private CorsConfigurationSource cors(AppProperties props) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(props.cors().allowedOrigins());
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Trace-Id"));
        c.setExposedHeaders(List.of("X-Trace-Id"));
        c.setAllowCredentials(true);
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", c);
        return src;
    }

    private static void write(HttpServletResponse res, ObjectMapper mapper, HttpStatus status, String code, String msg)
            throws java.io.IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), GlobalExceptionHandler.body(status, code, msg));
    }
}
