package app.howlers.funding;

import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.funding.ConnectDtos.MyPayouts;
import app.howlers.funding.ConnectDtos.OnboardingLink;
import app.howlers.funding.ConnectDtos.PayoutRun;
import app.howlers.funding.ConnectDtos.ResolveReviewRequest;
import app.howlers.funding.ConnectDtos.ReviewFlag;
import app.howlers.funding.ConnectDtos.StripeStatus;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Stripe Connect payouts (D-046, D-047): the moderator's own account and payouts, and the admin Stripe panel. */
@Tag(name = "Moderator payouts")
@RestController
@RequestMapping("/api/v1")
public class ConnectController {
    private final ConnectService connect;
    private final StripeAdminService admin;

    public ConnectController(ConnectService connect, StripeAdminService admin) {
        this.connect = connect;
        this.admin = admin;
    }

    private static AuthUser require(AuthUser user) {
        if (user == null) throw ApiException.unauthorized("Authentication required");
        return user;
    }

    @GetMapping("/me/funding/payouts")
    public MyPayouts mine(@AuthenticationPrincipal AuthUser user) {
        return connect.my(require(user));
    }

    /** Starts or resumes Stripe onboarding for the signed-in moderator. Returns a single-use Stripe-hosted URL. */
    @PostMapping("/me/funding/connect/onboarding")
    public OnboardingLink onboarding(@AuthenticationPrincipal AuthUser user) {
        return connect.startOnboarding(require(user));
    }

    /** Re-reads the account from Stripe (after returning from onboarding) and sends held shares if it is now enabled. */
    @PostMapping("/me/funding/connect/refresh")
    public MyPayouts refresh(@AuthenticationPrincipal AuthUser user) {
        return connect.refresh(require(user));
    }

    // ------------------------------------------------------------------ admin (also guarded by SecurityConfig: /api/v1/admin/**)

    @GetMapping("/admin/funding/stripe")
    public StripeStatus stripeStatus(@AuthenticationPrincipal AuthUser user) {
        return admin.status(require(user));
    }

    @GetMapping("/admin/funding/reviews")
    public List<ReviewFlag> reviews(@AuthenticationPrincipal AuthUser user, @RequestParam(defaultValue = "true") boolean open) {
        return admin.reviews(require(user), open);
    }

    @PostMapping("/admin/funding/reviews/{id}/resolve")
    public ReviewFlag resolve(@PathVariable long id, @AuthenticationPrincipal AuthUser user, @Valid @RequestBody ResolveReviewRequest req) {
        return admin.resolve(require(user), id, req.note());
    }

    @PostMapping("/admin/funding/payouts/run")
    public PayoutRun run(@AuthenticationPrincipal AuthUser user) {
        return admin.runPayouts(require(user));
    }
}
