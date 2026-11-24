package app.howlers.auth;

import app.howlers.auth.device.KnownDeviceService;
import app.howlers.auth.mfa.TwoFactorService;
import app.howlers.auth.passkey.PasskeyService;
import app.howlers.auth.passkey.PasskeyService.CeremonyStart;
import app.howlers.auth.passkey.PasskeyService.PasskeyDto;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Signed-in account security: passkeys, two-factor authentication and the new-sign-in email (D-041, D-042). */
@Tag(name = "Account security")
@RestController
@RequestMapping("/api/v1/auth")
public class AccountSecurityController {
    private final TwoFactorService twoFactor;
    private final PasskeyService passkeys;
    private final KnownDeviceService devices;
    private final AuthCookies cookies;
    private final RateLimiter limiter;

    public AccountSecurityController(TwoFactorService twoFactor, PasskeyService passkeys, KnownDeviceService devices,
                                     AuthCookies cookies, RateLimiter limiter) {
        this.twoFactor = twoFactor;
        this.passkeys = passkeys;
        this.devices = devices;
        this.cookies = cookies;
        this.limiter = limiter;
    }

    public record TotpStatus(boolean enabled, Instant enabledAt, int recoveryCodesRemaining) {}

    public record SecuritySummary(TotpStatus totp, int passkeys, boolean newSignInAlerts, String passkeyRpId) {}

    public record SecuritySettings(@NotNull Boolean newSignInAlerts) {}

    public record PasswordBody(@NotBlank @Size(max = 128) String password) {}

    public record CodeBody(@NotBlank @Size(max = 40) String code) {}

    public record DisableBody(@NotBlank @Size(max = 128) String password, @NotBlank @Size(max = 40) String code) {}

    public record RecoveryCodes(List<String> recoveryCodes) {}

    public record RegisterPasskeyBody(@NotBlank @Size(max = 100) String requestId, @NotNull JsonNode credential,
                                      @Size(max = 60) String name) {}

    public record RenameBody(@NotBlank @Size(max = 60) String name) {}

    @GetMapping("/security")
    public SecuritySummary summary(@AuthenticationPrincipal AuthUser user) {
        TwoFactorService.Status s = twoFactor.status(user.id());
        return new SecuritySummary(new TotpStatus(s.enabled(), s.enabledAt(), s.recoveryCodesRemaining()),
                passkeys.count(user.id()), devices.userWantsAlerts(user.id()), passkeys.rpId());
    }

    @PatchMapping("/security")
    public SecuritySummary update(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody SecuritySettings body) {
        devices.setAlerts(user.id(), body.newSignInAlerts());
        return summary(user);
    }

    // ---------------------------------------------------------------- TOTP

    @PostMapping("/2fa/totp/setup")
    public TwoFactorService.SetupResult setup(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody PasswordBody body) {
        limit(user);
        return twoFactor.beginSetup(user.id(), body.password());
    }

    @PostMapping("/2fa/totp/confirm")
    public RecoveryCodes confirm(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CodeBody body) {
        limit(user);
        return new RecoveryCodes(twoFactor.confirmSetup(user.id(), body.code()));
    }

    @PostMapping("/2fa/totp/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody DisableBody body) {
        limit(user);
        twoFactor.disable(user.id(), body.password(), body.code());
    }

    @PostMapping("/2fa/recovery-codes")
    public RecoveryCodes regenerate(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CodeBody body) {
        limit(user);
        return new RecoveryCodes(twoFactor.regenerateRecoveryCodes(user.id(), body.code()));
    }

    // ---------------------------------------------------------------- passkeys

    @GetMapping("/passkeys")
    public List<PasskeyDto> listPasskeys(@AuthenticationPrincipal AuthUser user) {
        return passkeys.list(user.id());
    }

    @PostMapping("/passkeys/options")
    public CeremonyStart passkeyOptions(@AuthenticationPrincipal AuthUser user, HttpServletRequest http,
                                        HttpServletResponse res) {
        limit(user);
        return passkeys.startRegistration(user.id(), cookies.ceremonyBinding(http, res));
    }

    @PostMapping("/passkeys")
    @ResponseStatus(HttpStatus.CREATED)
    public PasskeyDto registerPasskey(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody RegisterPasskeyBody body,
                                      HttpServletRequest http) {
        limit(user);
        return passkeys.finishRegistration(user.id(), body.requestId(), cookies.existingCeremonyBinding(http),
                body.credential(), body.name(), http.getHeader(HttpHeaders.USER_AGENT));
    }

    @PatchMapping("/passkeys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void renamePasskey(@AuthenticationPrincipal AuthUser user, @PathVariable long id, @Valid @RequestBody RenameBody body) {
        passkeys.rename(user.id(), id, body.name());
    }

    @DeleteMapping("/passkeys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePasskey(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        passkeys.delete(user.id(), id);
    }

    private void limit(AuthUser user) {
        limiter.check("acctsec:" + user.id(), 30, Duration.ofMinutes(10));
    }
}
