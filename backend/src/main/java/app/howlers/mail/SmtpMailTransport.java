package app.howlers.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Any SMTP relay (spring.mail.* / SMTP_*), including Brevo's ({@code smtp-relay.brevo.com:587} with the SMTP login and
 * SMTP key from the Brevo account). Sends multipart/alternative: plain text plus HTML.
 */
public class SmtpMailTransport implements MailTransport {
    private final JavaMailSender sender;
    private final String fromAddress;
    private final String fromName;
    private final String replyTo;

    public SmtpMailTransport(JavaMailSender sender, String fromAddress, String fromName, String replyTo) {
        this.sender = sender;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        this.replyTo = replyTo;
    }

    @Override
    public String name() { return "smtp"; }

    @Override
    public String send(OutgoingEmail email) throws MailDeliveryException {
        MimeMessage message = sender.createMimeMessage();
        try {
            MimeMessageHelper h = new MimeMessageHelper(message, true, "UTF-8");
            if (fromName == null || fromName.isBlank()) h.setFrom(fromAddress);
            else h.setFrom(fromAddress, fromName);
            if (replyTo != null && !replyTo.isBlank()) h.setReplyTo(replyTo);
            h.setTo(email.to());
            h.setSubject(email.subject());
            h.setText(email.text(), email.html());
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw MailDeliveryException.permanent("Could not build the message: " + e.getClass().getSimpleName(), 0);
        }
        try {
            sender.send(message);
            return null;
        } catch (MailAuthenticationException e) {
            throw MailDeliveryException.permanent("SMTP authentication failed (check SMTP_USER / SMTP_PASSWORD)", 0);
        } catch (MailParseException | MailPreparationException e) {
            throw MailDeliveryException.permanent("SMTP rejected the message: " + e.getClass().getSimpleName(), 0);
        } catch (MailException e) {
            // Connection problems and 4xx/5xx replies look alike at this level; retrying a few times is harmless.
            throw MailDeliveryException.retryable("SMTP send failed: " + e.getClass().getSimpleName(), 0, null);
        }
    }
}
