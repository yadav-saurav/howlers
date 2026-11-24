package app.howlers.mail;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.web.util.HtmlUtils;

/**
 * The transactional emails the app sends (D-038): HTML plus a plain-text alternative each. Every interpolated value
 * (app name, username, link) is HTML-escaped; tokens are URL-encoded into the link. No images, no tracking pixels, no
 * remote CSS: one inline-styled table that reads well in clients that strip styles and in screen readers.
 */
public final class EmailTemplates {
    public static final String TAG_VERIFY = "verify-email";
    public static final String TAG_RESET = "reset-password";
    public static final String TAG_NEW_SIGNIN = "new-signin";
    /** Product name and tagline used in every email (the app is being renamed; config names still say howlers). */
    public static final String BRAND = "Howlers";
    public static final String TAGLINE = "Find your pack & howl together";

    private final String appName;
    private final String publicUrl;

    public EmailTemplates(String appName, String publicUrl) {
        this.appName = appName == null || appName.isBlank() ? BRAND : appName;
        String u = publicUrl == null ? "" : publicUrl.trim();
        this.publicUrl = u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    public OutgoingEmail verification(String to, String username, String token, Duration expiresIn) {
        String link = link("/verify-email", token);
        String subject = "Verify your " + appName + " email";
        String expiry = humanize(expiresIn);
        String text = "Hi " + username + ",\n\n"
                + "Confirm the email address for your " + appName + " account by opening this link:\n"
                + link + "\n\n"
                + "The link expires in " + expiry + ". After that, request a new one from Settings > Account.\n\n"
                + "Didn't create a " + appName + " account? You can ignore this email; nothing happens unless the link is opened.\n\n"
                + "- " + appName + ": " + TAGLINE + "\n";
        String html = layout(subject,
                "Confirm your email address",
                "Hi " + esc(username) + ", confirm the email address for your " + esc(appName) + " account.",
                "Verify email", link,
                "The link expires in " + esc(expiry) + ". After that, request a new one from Settings &rsaquo; Account.",
                "Didn't create a " + esc(appName) + " account? You can ignore this email; nothing happens unless the link is opened.");
        return new OutgoingEmail(to, username, subject, html, text, TAG_VERIFY);
    }

    public OutgoingEmail passwordReset(String to, String username, String token, Duration expiresIn) {
        String link = link("/reset-password", token);
        String subject = "Reset your " + appName + " password";
        String expiry = humanize(expiresIn);
        String text = "Hi " + username + ",\n\n"
                + "Someone asked to reset the password for your " + appName + " account. To choose a new password, open:\n"
                + link + "\n\n"
                + "The link expires in " + expiry + " and works once. Resetting signs you out on every device.\n\n"
                + "Didn't request this? Ignore this email; your password stays the same.\n\n"
                + "- " + appName + ": " + TAGLINE + "\n";
        String html = layout(subject,
                "Reset your password",
                "Hi " + esc(username) + ", someone asked to reset the password for your " + esc(appName) + " account.",
                "Choose a new password", link,
                "The link expires in " + esc(expiry) + " and works once. Resetting signs you out on every device.",
                "Didn't request this? Ignore this email; your password stays the same.");
        return new OutgoingEmail(to, username, subject, html, text, TAG_RESET);
    }

    /**
     * "New sign-in to your Howlers account" (D-041): when, which browser family on which OS, and how (password,
     * password plus 2FA, passkey), with a link to the sessions list. No IP address or location is included.
     */
    public OutgoingEmail newSignIn(String to, String username, String when, String device, String method) {
        String link = publicUrl + "/settings?tab=account#sessions";
        String subject = "New sign-in to your " + appName + " account";
        String text = "Hi " + username + ",\n\n"
                + "Your " + appName + " account was just signed in to from a device or browser it hasn't seen before.\n\n"
                + "When: " + when + "\n"
                + "Device: " + device + "\n"
                + "Signed in with: " + method + "\n\n"
                + "If this was you, there's nothing to do.\n"
                + "If it wasn't, review your sessions, sign out the ones you don't recognise and change your password:\n"
                + link + "\n\n"
                + "You can turn these emails off in Settings > Account & security.\n\n"
                + "- " + appName + ": " + TAGLINE + "\n";
        String html = layout(subject,
                "New sign-in to your account",
                "Hi " + esc(username) + ", your " + esc(appName) + " account was just signed in to from a device or browser it hasn't seen before."
                        + "<br><br><strong>When:</strong> " + esc(when)
                        + "<br><strong>Device:</strong> " + esc(device)
                        + "<br><strong>Signed in with:</strong> " + esc(method),
                "Review sessions", link,
                "If this was you, there's nothing to do. You can turn these emails off in Settings &rsaquo; Account &amp; security.",
                "Not you? Sign out the sessions you don't recognise and change your password.");
        return new OutgoingEmail(to, username, subject, html, text, TAG_NEW_SIGNIN);
    }

    String link(String path, String token) {
        return publicUrl + path + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    /** "24 hours", "1 hour", "30 minutes". */
    static String humanize(Duration d) {
        long hours = d.toHours();
        if (hours >= 1 && d.toMinutesPart() == 0) return hours + (hours == 1 ? " hour" : " hours");
        long minutes = Math.max(1, d.toMinutes());
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    static String esc(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s, "UTF-8");
    }

    /** Arguments other than {@code title} and {@code link} are already-escaped HTML fragments. */
    private String layout(String title, String heading, String intro, String button, String link, String expiry,
                          String notYou) {
        String href = esc(link);
        String name = esc(appName);
        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="color-scheme" content="light dark">
                <title>%s</title>
                </head>
                <body style="margin:0;padding:0;background:#f4f4f5;color:#18181b;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f4f4f5;">
                <tr><td align="center" style="padding:24px 12px;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:520px;background:#ffffff;border-radius:8px;border:1px solid #e4e4e7;">
                <tr><td style="padding:24px 28px 0;font-size:18px;font-weight:700;color:#18181b;">%s<div style="font-size:13px;font-weight:400;color:#52525b;">%s</div></td></tr>
                <tr><td style="padding:16px 28px 0;">
                <h1 style="margin:0 0 12px;font-size:20px;line-height:1.3;color:#18181b;">%s</h1>
                <p style="margin:0 0 20px;font-size:15px;line-height:1.6;color:#3f3f46;">%s</p>
                <p style="margin:0 0 20px;"><a href="%s" style="display:inline-block;background:#1d4ed8;color:#ffffff;text-decoration:none;font-weight:600;font-size:15px;padding:12px 20px;border-radius:6px;">%s</a></p>
                <p style="margin:0 0 8px;font-size:13px;line-height:1.6;color:#52525b;">If the button doesn't work, copy this address into your browser:</p>
                <p style="margin:0 0 20px;font-size:13px;line-height:1.5;word-break:break-all;"><a href="%s" style="color:#1d4ed8;">%s</a></p>
                <p style="margin:0 0 12px;font-size:13px;line-height:1.6;color:#52525b;">%s</p>
                <p style="margin:0 0 24px;font-size:13px;line-height:1.6;color:#52525b;">%s</p>
                </td></tr>
                <tr><td style="padding:16px 28px 24px;border-top:1px solid #e4e4e7;font-size:12px;line-height:1.5;color:#71717a;">You're receiving this because of an action on your %s account. This is an automated message.</td></tr>
                </table>
                </td></tr>
                </table>
                </body>
                </html>
                """.formatted(esc(title), name, esc(TAGLINE), heading, intro, href, esc(button), href, href, expiry, notYou, name);
    }
}
