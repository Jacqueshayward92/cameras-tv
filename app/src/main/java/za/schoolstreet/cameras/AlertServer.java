package za.schoolstreet.cameras;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** A tiny web listener that accepts alarm messages from the recorder and nobody else. */
final class AlertServer extends Thread implements Closeable {

    interface Listener {
        void onMessage(String body);
    }

    private static final int READ_TIMEOUT_MS = 3000;
    private static final int MAX_MESSAGE_BYTES = 4 * 1024 * 1024;
    private static final byte[] OK = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
            + "Content-Length: 2\r\nConnection: close\r\n\r\n{}").getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private final String allowedHost;
    private final Listener listener;
    private final ServerSocket server;
    private volatile boolean closed;

    AlertServer(int port, String allowedHost, Listener listener) throws IOException {
        super("alert-server");
        this.allowedHost = allowedHost;
        this.listener = listener;
        this.server = new ServerSocket(port);
        setDaemon(true);
    }

    @Override
    public void run() {
        while (!closed) {
            try {
                Socket client = server.accept();
                InetAddress from = client.getInetAddress();
                if (!from.getHostAddress().equals(allowedHost)) {
                    client.close();
                    continue;
                }
                Thread worker = new Thread(() -> handle(client), "alert-message");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                if (closed) {
                    return;
                }
            }
        }
    }

    private void handle(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String headers = readHeaders(in);
            String lower = headers.toLowerCase(Locale.ROOT);
            if (lower.contains("expect: 100-continue")) {
                out.write(CONTINUE);
                out.flush();
            }
            String body = readBody(in, lower);
            out.write(OK);
            out.flush();
            listener.onMessage(body);
        } catch (IOException ignored) {
            // A dropped connection only loses that one message.
        }
    }

    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream headers = new ByteArrayOutputStream(1024);
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while (matched < end.length) {
            int value = in.read();
            if (value < 0) {
                break;
            }
            headers.write(value);
            matched = value == end[matched] ? matched + 1 : (value == end[0] ? 1 : 0);
            if (headers.size() > 64 * 1024) {
                throw new IOException("Headers too long");
            }
        }
        return new String(headers.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static String readBody(InputStream in, String lowerHeaders) throws IOException {
        int length = -1;
        for (String line : lowerHeaders.split("\r\n")) {
            if (line.startsWith("content-length:")) {
                try {
                    length = Integer.parseInt(line.substring(15).trim());
                } catch (NumberFormatException ignored) {
                    length = -1;
                }
            }
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream(Math.max(length, 1024));
        byte[] buffer = new byte[8192];
        int total = 0;
        try {
            while (length < 0 || total < length) {
                int wanted = length < 0 ? buffer.length : Math.min(buffer.length, length - total);
                int read = in.read(buffer, 0, wanted);
                if (read < 0) {
                    break;
                }
                total += read;
                if (total > MAX_MESSAGE_BYTES) {
                    throw new IOException("Message too large");
                }
                body.write(buffer, 0, read);
            }
        } catch (SocketTimeoutException e) {
            if (length >= 0 && total == 0) {
                throw e;
            }
            // No declared length: the sender has simply finished.
        }
        return new String(body.toByteArray(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // Already closing.
        }
    }
}
