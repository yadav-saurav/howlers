package app.howlers.auth.dto;

import app.howlers.user.dto.UserDtos.Me;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class AuthDtos {
    private AuthDtos() {}

    public static final String USERNAME_REGEX = "^[A-Za-z0-9_]{3,21}$";
    public static final String PASSWORD_REGEX = "^(?=.*[A-Za-z])(?=.*\\d).+$";

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = USERNAME_REGEX, message = "3-21 letters, digits or underscores") String username,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 128, message = "must be 8-128 characters")
            @Pattern(regexp = PASSWORD_REGEX, message = "must contain a letter and a digit") String password,
            @Size(max = 50) String displayName) {}

    public record LoginRequest(@NotBlank @Size(max = 254) String login, @NotBlank @Size(max = 128) String password) {}

    public record AuthResponse(String accessToken, long expiresIn, Me user) {}

    public record UsernameAvailability(boolean available, String reason) {}

    public record TokenRequest(@NotBlank @Size(max = 200) String token) {}

    /** status: VERIFIED (this link confirmed the address) or ALREADY_VERIFIED (it was confirmed earlier). */
    public record VerifyEmailResult(String status) {}

    public record ForgotPasswordRequest(@NotBlank @Email String email) {}

    public record ResetPasswordRequest(@NotBlank @Size(max = 200) String token,
                                       @NotBlank @Size(min = 8, max = 128, message = "must be 8-128 characters")
                                       @Pattern(regexp = PASSWORD_REGEX, message = "must contain a letter and a digit")
                                       String newPassword) {}

    /** authMethod: PASSWORD, PASSWORD_TOTP, PASSWORD_RECOVERY, PASSKEY, or null for sessions from before D-041. */
    public record SessionDto(long id, String userAgent, String ip, Instant createdAt, Instant lastUsedAt, boolean current,
                             String authMethod) {}

    /**
     * Returned by /auth/login instead of a session when the account has two-factor authentication on (D-042). The token
     * only works on /auth/login/2fa, expires in {@code expiresIn} seconds and dies after a few wrong codes.
     */
    public record MfaRequired(boolean mfaRequired, String mfaToken, long expiresIn, List<String> methods) {}

    public record MfaLoginRequest(@NotBlank @Size(max = 200) String mfaToken, @NotBlank @Size(max = 40) String code) {}

    /** Internal: either a session or the second-step challenge. */
    public sealed interface LoginOutcome permits Issued, SecondStep {}

    public record Issued(IssuedSession session) implements LoginOutcome {}

    public record SecondStep(MfaRequired challenge) implements LoginOutcome {}

    /** Internal result: what the controller needs to set the cookie. */
    public record IssuedSession(AuthResponse response, String refreshToken) {}
}
