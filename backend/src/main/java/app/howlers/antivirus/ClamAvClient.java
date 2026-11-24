package app.howlers.antivirus;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Minimal clamd client over TCP using the INSTREAM command (D-029).
 *
 * <p>Protocol: send {@code zINSTREAM\0}, then the content as chunks, each a 4-byte big-endian length followed by that
 * many bytes, then a zero-length chunk. clamd answers one NUL-terminated line: {@code stream: OK},
 * {@code stream: <Signature> FOUND}, or {@code ... ERROR} (for example {@code INSTREAM size limit exceeded. ERROR}
 * when the stream is larger than clamd's {@code StreamMaxLength}). The {@code z} prefix selects NUL-terminated
 * replies. Every socket operation has a timeout, so a hung clamd can't hold an upload thread forever.
 */
public final class ClamAvClient {
    /** A clean or infected answer. Protocol errors and size-limit refusals throw {@link ClamAvException}. */
    public record Reply(boolean infected, String signature, String raw) {}

    /** clamd answered with an error, or the connection failed or timed out. */
    public static final class ClamAvException extends IOException {
        public ClamAvException(String message) {
            super(message);
        }

        public ClamAvException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final int MAX_REPLY = 4096;

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int chunkSize;

    public ClamAvClient(String host, int port, Duration connectTimeout, Duration readTimeout, int chunkSize) {
        if (chunkSize < 1) throw new IllegalArgumentException("chunkSize must be positive");
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = (int) Math.max(1, connectTimeout.toMillis());
        this.readTimeoutMs = (int) Math.max(1, readTimeout.toMillis());
        this.chunkSize = chunkSize;
    }

    /** Streams {@code in} to clamd. The caller closes {@code in}. */
    public Reply scan(InputStream in) throws ClamAvException {
        try (Socket socket = open()) {
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), chunkSize + 4);
            try {
                out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                byte[] buf = new byte[chunkSize];
                int n;
                while ((n = in.readNBytes(buf, 0, buf.length)) > 0) {
                    writeLength(out, n);
                    out.write(buf, 0, n);
                }
                writeLength(out, 0);
                out.flush();
            } catch (IOException writeFailed) {
                // clamd closes the stream early when it refuses it (size limit); its reply explains why.
                String reply = tryRead(socket);
                if (reply != null && !reply.isEmpty()) throw new ClamAvException("clamd: " + reply, writeFailed);
                throw new ClamAvException("Could not send the file to clamd: " + writeFailed.getMessage(), writeFailed);
            }
            return parse(readReply(socket.getInputStream()));
        } catch (ClamAvException e) {
            throw e;
        } catch (IOException e) {
            throw new ClamAvException("clamd is unreachable at " + host + ":" + port + " (" + e.getMessage() + ")", e);
        }
    }

    /** {@code true} when clamd answers PING with PONG. */
    public boolean ping() {
        try (Socket socket = open()) {
            OutputStream out = socket.getOutputStream();
            out.write("zPING\0".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return "PONG".equals(readReply(socket.getInputStream()));
        } catch (IOException e) {
            return false;
        }
    }

    /** clamd's version line ({@code ClamAV 1.4.x/<db version>/<db date>}), or null when unreachable. */
    public String version() {
        try (Socket socket = open()) {
            OutputStream out = socket.getOutputStream();
            out.write("zVERSION\0".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return readReply(socket.getInputStream());
        } catch (IOException e) {
            return null;
        }
    }

    private Socket open() throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    static Reply parse(String reply) throws ClamAvException {
        String r = reply.strip();
        if (r.endsWith("ERROR")) throw new ClamAvException("clamd: " + r);
        String body = r.startsWith("stream:") ? r.substring("stream:".length()).strip() : r;
        if (body.equals("OK")) return new Reply(false, null, r);
        if (body.endsWith(" FOUND")) {
            String sig = body.substring(0, body.length() - " FOUND".length()).strip();
            return new Reply(true, sig.isEmpty() ? "unknown" : sig, r);
        }
        throw new ClamAvException("clamd sent an unexpected reply: " + (r.length() > 200 ? r.substring(0, 200) : r));
    }

    private static void writeLength(OutputStream out, int n) throws IOException {
        out.write((n >>> 24) & 0xFF);
        out.write((n >>> 16) & 0xFF);
        out.write((n >>> 8) & 0xFF);
        out.write(n & 0xFF);
    }

    private static String readReply(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != 0) {
            buf.write(c);
            if (buf.size() > MAX_REPLY) throw new ClamAvException("clamd reply is too long");
        }
        if (c == -1 && buf.size() == 0) throw new ClamAvException("clamd closed the connection without a reply");
        return buf.toString(StandardCharsets.US_ASCII);
    }

    private static String tryRead(Socket socket) {
        try {
            return readReply(socket.getInputStream());
        } catch (IOException e) {
            return null;
        }
    }
}
