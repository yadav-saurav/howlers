package app.howlers.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local development: writes the plain-text body (including the one-time link) to the log under the MAIL-DEV marker so
 * verification and reset links can be followed by hand. Never used when a real transport is configured.
 */
public class LogMailTransport implements MailTransport {
    private static final Logger log = LoggerFactory.getLogger(LogMailTransport.class);

    @Override
    public String name() { return "log"; }

    @Override
    public String send(OutgoingEmail email) {
        log.info("MAIL-DEV to=<{}> subject=\"{}\"\n{}", email.to(), email.subject(), email.text());
        return null;
    }
}
