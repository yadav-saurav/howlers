package app.howlers.push;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;

/**
 * One-off: prints a fresh VAPID key pair in the environment-variable format Howlers reads. Runs offline.
 *
 * <pre>
 *   cd backend && ./mvnw -q compile
 *   java -cp target/classes app.howlers.push.VapidKeyGenerator
 * </pre>
 *
 * The output is the same base64url format as {@code npx web-push generate-vapid-keys}, so either tool works. Put the
 * values in the server environment (deploy/.env), never in the repository. Keep the pair stable: changing it
 * invalidates every existing browser subscription.
 */
public final class VapidKeyGenerator {
    private VapidKeyGenerator() {}

    public static void main(String[] args) {
        KeyPair kp = WebPushCrypto.generateKeyPair();
        System.out.println("VAPID_PUBLIC_KEY=" + WebPushCrypto.b64(WebPushCrypto.encode((ECPublicKey) kp.getPublic())));
        System.out.println("VAPID_PRIVATE_KEY=" + WebPushCrypto.b64(WebPushCrypto.encode((ECPrivateKey) kp.getPrivate())));
        System.out.println("VAPID_SUBJECT=mailto:you@example.com");
    }
}
