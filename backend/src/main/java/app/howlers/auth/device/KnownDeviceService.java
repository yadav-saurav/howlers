package app.howlers.auth.device;

import app.howlers.auth.AccountSecurityProperties;
import app.howlers.auth.MailService;
import app.howlers.auth.mfa.AccountSecretsCipher;
import app.howlers.user.User;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Remembers the devices each account signs in from and emails the owner about a new one (D-041).
 *
 * <p>Fingerprint = keyed hash of (the {@code howlers_did} device cookie id, browser family, OS family). A sign-in from
 * a fingerprint not seen before for that user is "new". The alert is not sent when the account has no known device
 * yet: that covers the sign-in that comes with registration and the first sign-in of accounts that existed before
 * this feature. Refreshing a session is not a sign-in. Users can turn the alert off; it is on by default.
 *
 * <p>Only a 12-hex-character keyed hash of the IP's network prefix (/24 for IPv4, /48 for IPv6) is stored with the
 * device, like the files access log: enough to tell "same network as before", not to locate anyone.
 */
@Service
public class KnownDeviceService {
    private static final Logger log = LoggerFactory.getLogger(KnownDeviceService.class);

    public enum Outcome { KNOWN, NEW_FIRST_DEVICE, NEW_ALERTED, NEW_ALERTS_OFF }

    private final JdbcClient jdbc;
    private final AccountSecretsCipher cipher;
    private final MailService mail;
    private final boolean alertsEnabled;

    public KnownDeviceService(JdbcClient jdbc, AccountSecretsCipher cipher, MailService mail, AccountSecurityProperties props) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.mail = mail;
        this.alertsEnabled = props.newSigninAlerts();
    }

    @Transactional
    public Outcome recordSignIn(User user, String deviceId, String userAgent, String ip, String method) {
        UserAgentFamily fam = UserAgentFamily.parse(userAgent);
        String hash = cipher.keyedHash("device|" + deviceId + "|" + fam.browser() + "|" + fam.os(), 64);
        int before = jdbc.sql("SELECT count(*) FROM known_devices WHERE user_id = ?").param(user.getId())
                .query(Integer.class).single();
        boolean inserted = jdbc.sql("""
                        INSERT INTO known_devices (user_id, device_hash, browser_family, os_family, network_hash)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (user_id, device_hash) DO UPDATE
                          SET last_seen_at = now(), network_hash = EXCLUDED.network_hash
                        RETURNING (xmax = 0)""")
                .params(user.getId(), hash, fam.browser(), fam.os(), networkHash(ip))
                .query(Boolean.class).single();
        if (!inserted) return Outcome.KNOWN;
        if (before == 0) return Outcome.NEW_FIRST_DEVICE;
        if (!alertsEnabled || !userWantsAlerts(user.getId())) return Outcome.NEW_ALERTS_OFF;
        mail.sendNewSignIn(user.getEmail(), user.getUsername(), Instant.now(), fam, method);
        log.info("New-device sign-in alert queued for user {}", user.getId());
        return Outcome.NEW_ALERTED;
    }

    public boolean userWantsAlerts(long userId) {
        return jdbc.sql("SELECT new_signin_alerts FROM account_security WHERE user_id = ?").param(userId)
                .query(Boolean.class).optional().orElse(true);
    }

    @Transactional
    public void setAlerts(long userId, boolean on) {
        jdbc.sql("""
                        INSERT INTO account_security (user_id, new_signin_alerts) VALUES (?, ?)
                        ON CONFLICT (user_id) DO UPDATE SET new_signin_alerts = EXCLUDED.new_signin_alerts, updated_at = now()""")
                .params(userId, on).update();
    }

    /** Keyed hash of the network prefix; null for a missing or unparseable address. */
    String networkHash(String ip) {
        String prefix = networkPrefix(ip);
        return prefix == null ? null : cipher.keyedHash("net|" + prefix, 12);
    }

    static String networkPrefix(String ip) {
        if (ip == null || ip.isBlank() || !ip.matches("[0-9A-Fa-f:.%]+")) return null; // literals only: never a DNS lookup
        try {
            byte[] a = InetAddress.getByName(ip.replaceAll("%.*$", "")).getAddress();
            if (a.length == 4) return (a[0] & 0xff) + "." + (a[1] & 0xff) + "." + (a[2] & 0xff) + ".0/24";
            if (a.length == 16) {
                return String.format("%02x%02x:%02x%02x:%02x%02x::/48", a[0], a[1], a[2], a[3], a[4], a[5]);
            }
        } catch (UnknownHostException e) {
            return null;
        }
        return null;
    }
}
