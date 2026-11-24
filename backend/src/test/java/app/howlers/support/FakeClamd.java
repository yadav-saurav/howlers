package app.howlers.support;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny clamd stand-in speaking the INSTREAM protocol on a random local port. It answers {@code stream: OK}, or
 * {@code stream: Eicar-Signature FOUND} when the payload contains {@link #MARKER}. (The real EICAR string is not used
 * in tests, so no antivirus on a developer machine quarantines the test sources.)
 */
public final class FakeClamd implements AutoCloseable {
    public static final String MARKER = "HOWLERS-FAKE-MALWARE-MARKER";

    public enum Mode { NORMAL, CLOSE_IMMEDIATELY, NEVER_REPLY, SIZE_LIMIT }

    private final ServerSocket server;
    private final Thread thread;
    private volatile Mode mode = Mode.NORMAL;
    private final List<byte[]> payloads = new CopyOnWriteArrayList<>();
    private final AtomicInteger maxChunk = new AtomicInteger();
    private final AtomicInteger chunks = new AtomicInteger();

    public FakeClamd() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        thread = new Thread(this::serve, "fake-clamd");
        thread.setDaemon(true);
        thread.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    public void mode(Mode m) {
        mode = m;
    }

    public List<byte[]> payloads() {
        return payloads;
    }

    public int maxChunk() {
        return maxChunk.get();
    }

    public int chunks() {
        return chunks.get();
    }

    private void serve() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> handle(s), "fake-clamd-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket s) {
        try (s) {
            Mode m = mode;
            if (m == Mode.CLOSE_IMMEDIATELY) return;
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String command = readCommand(in);
            switch (command) {
                case "zPING" -> reply(out, "PONG");
                case "zVERSION" -> reply(out, "ClamAV 0.0.0-fake/1/Thu Jan  1 00:00:00 1970");
                case "zINSTREAM" -> {
                    ByteArrayOutputStream data = new ByteArrayOutputStream();
                    while (true) {
                        int len = in.readInt();
                        if (len == 0) break;
                        chunks.incrementAndGet();
                        maxChunk.accumulateAndGet(len, Math::max);
                        data.write(in.readNBytes(len));
                    }
                    if (m == Mode.SIZE_LIMIT) { // real clamd answers this as soon as the limit is passed
                        reply(out, "INSTREAM size limit exceeded. ERROR");
                        return;
                    }
                    payloads.add(data.toByteArray());
                    if (m == Mode.NEVER_REPLY) {
                        Thread.sleep(30_000);
                        return;
                    }
                    boolean infected = data.toString(StandardCharsets.ISO_8859_1).contains(MARKER);
                    reply(out, infected ? "stream: Eicar-Signature FOUND" : "stream: OK");
                }
                default -> reply(out, "UNKNOWN COMMAND");
            }
        } catch (IOException | InterruptedException ignored) {
            // client went away
        }
    }

    private static String readCommand(DataInputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) > 0) b.write(c);
        return b.toString(StandardCharsets.US_ASCII);
    }

    private static void reply(OutputStream out, String text) throws IOException {
        out.write((text + "\0").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
