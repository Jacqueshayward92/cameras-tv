package za.schoolstreet.cameras;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal RTSP client: video only, RTP over TCP, H.264 or H.265. No Android classes, so it can be tested on a PC. */
final class RtspSession implements Closeable {

    interface Listener {
        void onStep(String step);

        void onVideoConfig(String mime, byte[] csd0, byte[] csd1) throws IOException;

        void onAccessUnit(byte[] data, int length, long ptsUs, boolean keyFrame) throws IOException;
    }

    /** The recorder refused the username or password. Retrying could lock the account. */
    static final class LoginRejected extends IOException {
        LoginRejected() {
            super("The recorder rejected the username or password.");
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 6000;
    private static final long KEEPALIVE_MS = 20000;
    private static final byte[] START_CODE = {0, 0, 0, 1};
    private static final Pattern AUTH_PARAM = Pattern.compile("(\\w+)=\"?([^\",]*)\"?");
    private static final Pattern INTERLEAVED = Pattern.compile("interleaved=(\\d+)");

    private final String host;
    private final int port;
    private final String uri;
    private final String user;
    private final String password;
    private final Listener listener;

    private Socket socket;
    private OutputStream out;
    private DataInputStream in;
    private int cseq;
    private String session;

    private String realm;
    private String nonce;
    private String qop;
    private String opaque;
    private int nonceCount;

    private boolean hevc;
    private String mime;
    private byte[] vps;
    private byte[] sps;
    private byte[] pps;
    private boolean configSent;
    private boolean sawKeyFrame;

    private final ByteArrayOutputStream unit = new ByteArrayOutputStream(256 * 1024);
    private boolean unitHasKey;
    private boolean unitStarted;
    private long unitTimestamp;
    private long firstTimestamp = -1;
    private boolean fragmentOpen;

    RtspSession(String host, int port, String path, String user, String password, Listener listener) {
        this.host = host;
        this.port = port;
        this.uri = "rtsp://" + host + ":" + port + path;
        this.user = user;
        this.password = password;
        this.listener = listener;
    }

    private static final class Response {
        int status;
        String reason = "";
        final Map<String, List<String>> headers = new HashMap<>();
        byte[] body = new byte[0];

        String header(String name) {
            List<String> values = headers.get(name);
            return values == null ? null : values.get(0);
        }
    }

    /** Connects and streams until closed or something fails. */
    void run() throws IOException {
        listener.onStep("Connecting to the recorder");
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.setReceiveBufferSize(512 * 1024);
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        out = socket.getOutputStream();
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 256 * 1024));

        listener.onStep("Logging in");
        Response describe = request("DESCRIBE", uri, "Accept: application/sdp\r\n");
        requireOk("DESCRIBE", describe);
        String sdp = new String(describe.body, StandardCharsets.ISO_8859_1);

        String base = describe.header("content-base");
        if (base == null) {
            base = describe.header("content-location");
        }
        if (base == null) {
            base = uri;
        }

        listener.onStep("Opening the video");
        String control = parseSdp(sdp, base);
        Response setup = request("SETUP", control, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
        requireOk("SETUP", setup);
        String sessionHeader = setup.header("session");
        if (sessionHeader == null) {
            throw new IOException("SETUP gave no session.");
        }
        session = sessionHeader.split(";")[0].trim();

        int videoChannel = 0;
        String transport = setup.header("transport");
        if (transport != null) {
            Matcher matcher = INTERLEAVED.matcher(transport);
            if (matcher.find()) {
                videoChannel = Integer.parseInt(matcher.group(1));
            }
        }

        if (sps != null && pps != null && (!hevc || vps != null)) {
            sendConfigIfReady();
        }

        Response play = request("PLAY", stripTrailingSlash(base), "Range: npt=0.000-\r\n");
        requireOk("PLAY", play);

        listener.onStep("Waiting for video");
        stream(videoChannel, base);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static void requireOk(String step, Response response) throws IOException {
        if (response.status != 200) {
            throw new IOException(step + " failed: " + response.status + " " + response.reason);
        }
    }

    private void stream(int videoChannel, String base) throws IOException {
        long nextKeepalive = System.currentTimeMillis() + KEEPALIVE_MS;
        while (true) {
            int first;
            try {
                first = in.read();
            } catch (SocketTimeoutException e) {
                throw new IOException("The recorder stopped sending video.");
            }
            if (first < 0) {
                throw new EOFException("The recorder closed the connection.");
            }
            if (first == '$') {
                int channel = in.readUnsignedByte();
                int length = in.readUnsignedShort();
                byte[] packet = new byte[length];
                in.readFully(packet);
                if (channel == videoChannel) {
                    handleRtp(packet, length);
                }
            } else {
                readResponse(first);
            }
            long now = System.currentTimeMillis();
            if (now >= nextKeepalive) {
                send("GET_PARAMETER", stripTrailingSlash(base), "");
                nextKeepalive = now + KEEPALIVE_MS;
            }
        }
    }

    private Response request(String method, String target, String extra) throws IOException {
        boolean sentWithFreshChallenge = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            int sent = send(method, target, extra);
            Response response = awaitResponse(sent);
            if (response.status != 401) {
                return response;
            }
            if (user.isEmpty()) {
                throw new LoginRejected();
            }
            boolean stale = applyChallenge(response);
            if (sentWithFreshChallenge && !stale) {
                throw new LoginRejected();
            }
            sentWithFreshChallenge = true;
        }
        throw new LoginRejected();
    }

    private Response awaitResponse(int expected) throws IOException {
        while (true) {
            int first;
            try {
                first = in.read();
            } catch (SocketTimeoutException e) {
                throw new IOException("The recorder stopped answering.");
            }
            if (first < 0) {
                throw new EOFException("The recorder closed the connection.");
            }
            if (first == '$') {
                in.readUnsignedByte();
                in.skipBytes(in.readUnsignedShort());
                continue;
            }
            Response response = readResponse(first);
            String seq = response.header("cseq");
            if (seq == null || seq.trim().equals(Integer.toString(expected))) {
                return response;
            }
        }
    }

    private int send(String method, String target, String extra) throws IOException {
        cseq++;
        StringBuilder message = new StringBuilder();
        message.append(method).append(' ').append(target).append(" RTSP/1.0\r\n");
        message.append("CSeq: ").append(cseq).append("\r\n");
        message.append("User-Agent: Cameras\r\n");
        if (nonce != null) {
            message.append("Authorization: ").append(authorization(method, target)).append("\r\n");
        }
        if (session != null) {
            message.append("Session: ").append(session).append("\r\n");
        }
        message.append(extra).append("\r\n");
        out.write(message.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        return cseq;
    }

    private Response readResponse(int firstByte) throws IOException {
        String statusLine = (char) firstByte + readLine();
        Response response = new Response();
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("RTSP")) {
            throw new IOException("Unexpected reply: " + clip(statusLine));
        }
        response.status = Integer.parseInt(parts[1].trim());
        if (parts.length > 2) {
            response.reason = parts[2].trim();
        }
        String line;
        while (!(line = readLine()).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                List<String> values = response.headers.get(name);
                if (values == null) {
                    values = new ArrayList<>();
                    response.headers.put(name, values);
                }
                values.add(line.substring(colon + 1).trim());
            }
        }
        String contentLength = response.header("content-length");
        if (contentLength != null) {
            int length = Integer.parseInt(contentLength.trim());
            if (length > 0) {
                response.body = new byte[length];
                in.readFully(response.body);
            }
        }
        return response;
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        while (true) {
            int value = in.read();
            if (value < 0) {
                throw new EOFException("The recorder closed the connection.");
            }
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                line.write(value);
            }
        }
        return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    /** Takes the Digest challenge from a 401 and returns whether it says the old nonce merely expired. */
    private boolean applyChallenge(Response response) throws IOException {
        List<String> challenges = response.headers.get("www-authenticate");
        if (challenges != null) {
            for (String challenge : challenges) {
                if (challenge.regionMatches(true, 0, "Digest", 0, 6)) {
                    String algorithm = null;
                    String staleValue = null;
                    String newRealm = null;
                    String newNonce = null;
                    String newQop = null;
                    String newOpaque = null;
                    Matcher matcher = AUTH_PARAM.matcher(challenge.substring(6));
                    while (matcher.find()) {
                        String key = matcher.group(1).toLowerCase(Locale.ROOT);
                        String value = matcher.group(2);
                        switch (key) {
                            case "realm": newRealm = value; break;
                            case "nonce": newNonce = value; break;
                            case "qop": newQop = value; break;
                            case "opaque": newOpaque = value; break;
                            case "stale": staleValue = value; break;
                            case "algorithm": algorithm = value; break;
                            default: break;
                        }
                    }
                    if (algorithm != null && !algorithm.equalsIgnoreCase("MD5")) {
                        continue;
                    }
                    realm = newRealm;
                    nonce = newNonce;
                    qop = newQop;
                    opaque = newOpaque;
                    nonceCount = 0;
                    return "true".equalsIgnoreCase(staleValue);
                }
            }
        }
        throw new IOException("The recorder asked for an unsupported login type: " + clip(String.valueOf(challenges)));
    }

    private String authorization(String method, String target) {
        String ha1 = md5(user + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + target);
        StringBuilder header = new StringBuilder("Digest username=\"").append(user)
                .append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce)
                .append("\", uri=\"").append(target).append('"');
        String digest;
        if (qop != null && qop.toLowerCase(Locale.ROOT).contains("auth")) {
            nonceCount++;
            String nc = String.format(Locale.ROOT, "%08x", nonceCount);
            String cnonce = md5(Long.toString(System.nanoTime())).substring(0, 16);
            digest = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
            header.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"');
        } else {
            digest = md5(ha1 + ":" + nonce + ":" + ha2);
        }
        header.append(", response=\"").append(digest).append('"');
        if (opaque != null) {
            header.append(", opaque=\"").append(opaque).append('"');
        }
        return header.toString();
    }

    private static String md5(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (byte value : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads the codec and track address, returning the address to SETUP. */
    private String parseSdp(String sdp, String base) throws IOException {
        boolean inVideo = false;
        boolean foundVideo = false;
        String control = null;
        String payload = null;
        for (String raw : sdp.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("m=")) {
                if (foundVideo) {
                    break;
                }
                inVideo = line.startsWith("m=video");
                foundVideo = inVideo;
                if (inVideo) {
                    String[] parts = line.split(" ");
                    payload = parts.length > 3 ? parts[3] : null;
                }
            } else if (inVideo && line.startsWith("a=control:")) {
                control = line.substring(10).trim();
            } else if (inVideo && line.startsWith("a=rtpmap:")) {
                String upper = line.toUpperCase(Locale.ROOT);
                if (upper.contains("H265") || upper.contains("HEVC")) {
                    hevc = true;
                    mime = "video/hevc";
                } else if (upper.contains("H264")) {
                    hevc = false;
                    mime = "video/avc";
                }
            } else if (inVideo && line.startsWith("a=fmtp:")) {
                readFmtp(line);
            }
        }
        if (!foundVideo) {
            throw new IOException("No video in the stream description: " + clip(sdp));
        }
        if (mime == null) {
            if ("96".equals(payload) || payload == null) {
                mime = "video/avc";
            } else {
                throw new IOException("Unknown video type: " + clip(sdp));
            }
        }
        if (control == null || control.equals("*")) {
            return stripTrailingSlash(base);
        }
        if (control.toLowerCase(Locale.ROOT).startsWith("rtsp://")) {
            return control;
        }
        return (base.endsWith("/") ? base : base + "/") + control;
    }

    private void readFmtp(String line) {
        int space = line.indexOf(' ');
        if (space < 0) {
            return;
        }
        for (String item : line.substring(space + 1).split(";")) {
            String entry = item.trim();
            int equals = entry.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = entry.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = entry.substring(equals + 1).trim();
            try {
                switch (key) {
                    case "sprop-parameter-sets":
                        String[] sets = value.split(",");
                        sps = Base64.getDecoder().decode(sets[0].trim());
                        if (sets.length > 1) {
                            pps = Base64.getDecoder().decode(sets[1].trim());
                        }
                        break;
                    case "sprop-vps": vps = Base64.getDecoder().decode(value); break;
                    case "sprop-sps": sps = Base64.getDecoder().decode(value); break;
                    case "sprop-pps": pps = Base64.getDecoder().decode(value); break;
                    default: break;
                }
            } catch (IllegalArgumentException ignored) {
                // A bad description is not fatal: the same data also arrives inside the video.
            }
        }
    }

    private void sendConfigIfReady() throws IOException {
        if (configSent || sps == null || pps == null || (hevc && vps == null)) {
            return;
        }
        configSent = true;
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        if (hevc) {
            first.write(START_CODE);
            first.write(vps);
            first.write(START_CODE);
            first.write(sps);
            first.write(START_CODE);
            first.write(pps);
            listener.onVideoConfig(mime, first.toByteArray(), null);
        } else {
            first.write(START_CODE);
            first.write(sps);
            ByteArrayOutputStream second = new ByteArrayOutputStream();
            second.write(START_CODE);
            second.write(pps);
            listener.onVideoConfig(mime, first.toByteArray(), second.toByteArray());
        }
    }

    private void handleRtp(byte[] packet, int length) throws IOException {
        if (length < 12 || (packet[0] & 0xC0) != 0x80) {
            return;
        }
        int csrcCount = packet[0] & 0x0F;
        boolean hasExtension = (packet[0] & 0x10) != 0;
        boolean hasPadding = (packet[0] & 0x20) != 0;
        boolean marker = (packet[1] & 0x80) != 0;
        long timestamp = ((packet[4] & 0xFFL) << 24) | ((packet[5] & 0xFFL) << 16)
                | ((packet[6] & 0xFFL) << 8) | (packet[7] & 0xFFL);

        int offset = 12 + 4 * csrcCount;
        if (hasExtension) {
            if (offset + 4 > length) {
                return;
            }
            int words = ((packet[offset + 2] & 0xFF) << 8) | (packet[offset + 3] & 0xFF);
            offset += 4 + 4 * words;
        }
        int end = length;
        if (hasPadding) {
            end -= packet[length - 1] & 0xFF;
        }
        if (offset >= end) {
            return;
        }

        if (unitStarted && timestamp != unitTimestamp) {
            flushUnit();
        }
        if (!unitStarted) {
            unitStarted = true;
            unitTimestamp = timestamp;
        }
        if (hevc) {
            depacketizeH265(packet, offset, end);
        } else {
            depacketizeH264(packet, offset, end);
        }
        if (marker) {
            flushUnit();
        }
    }

    private void depacketizeH264(byte[] data, int offset, int end) throws IOException {
        int type = data[offset] & 0x1F;
        if (type >= 1 && type <= 23) {
            addNal(data, offset, end - offset);
        } else if (type == 24) {
            int position = offset + 1;
            while (position + 2 <= end) {
                int size = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
                position += 2;
                if (size == 0 || position + size > end) {
                    return;
                }
                addNal(data, position, size);
                position += size;
            }
        } else if (type == 28 && end - offset >= 2) {
            int fuHeader = data[offset + 1] & 0xFF;
            boolean start = (fuHeader & 0x80) != 0;
            int nalType = fuHeader & 0x1F;
            if (start) {
                unit.write(START_CODE);
                unit.write((data[offset] & 0xE0) | nalType);
                noteNalType(nalType);
                fragmentOpen = true;
            }
            if (fragmentOpen) {
                unit.write(data, offset + 2, end - offset - 2);
            }
            if ((fuHeader & 0x40) != 0) {
                fragmentOpen = false;
            }
        }
    }

    private void depacketizeH265(byte[] data, int offset, int end) throws IOException {
        if (end - offset < 2) {
            return;
        }
        int type = (data[offset] >> 1) & 0x3F;
        if (type == 48) {
            int position = offset + 2;
            while (position + 2 <= end) {
                int size = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
                position += 2;
                if (size == 0 || position + size > end) {
                    return;
                }
                addNal(data, position, size);
                position += size;
            }
        } else if (type == 49 && end - offset >= 3) {
            int fuHeader = data[offset + 2] & 0xFF;
            boolean start = (fuHeader & 0x80) != 0;
            int nalType = fuHeader & 0x3F;
            if (start) {
                unit.write(START_CODE);
                unit.write((data[offset] & 0x81) | (nalType << 1));
                unit.write(data[offset + 1]);
                noteNalType(nalType);
                fragmentOpen = true;
            }
            if (fragmentOpen) {
                unit.write(data, offset + 3, end - offset - 3);
            }
            if ((fuHeader & 0x40) != 0) {
                fragmentOpen = false;
            }
        } else if (type < 48) {
            addNal(data, offset, end - offset);
        }
    }

    private void addNal(byte[] data, int offset, int size) throws IOException {
        if (size < 1) {
            return;
        }
        int type = hevc ? (data[offset] >> 1) & 0x3F : data[offset] & 0x1F;
        byte[] copy = null;
        if (hevc ? (type >= 32 && type <= 34) : (type == 7 || type == 8)) {
            copy = new byte[size];
            System.arraycopy(data, offset, copy, 0, size);
        }
        if (hevc) {
            if (type == 32) vps = copy;
            if (type == 33) sps = copy;
            if (type == 34) pps = copy;
        } else {
            if (type == 7) sps = copy;
            if (type == 8) pps = copy;
        }
        unit.write(START_CODE);
        unit.write(data, offset, size);
        noteNalType(type);
    }

    private void noteNalType(int type) {
        if (hevc ? (type >= 16 && type <= 21) : type == 5) {
            unitHasKey = true;
        }
    }

    private void flushUnit() throws IOException {
        boolean key = unitHasKey;
        long timestamp = unitTimestamp;
        unitStarted = false;
        unitHasKey = false;
        fragmentOpen = false;
        if (unit.size() == 0) {
            return;
        }
        sendConfigIfReady();
        if (configSent && (sawKeyFrame || key)) {
            if (key && !sawKeyFrame) {
                sawKeyFrame = true;
                listener.onStep("Starting the picture");
            }
            if (firstTimestamp < 0) {
                firstTimestamp = timestamp;
            }
            long ticks = (timestamp - firstTimestamp) & 0xFFFFFFFFL;
            listener.onAccessUnit(unit.toByteArray(), unit.size(), ticks * 1000 / 90, key);
        }
        unit.reset();
    }

    private static String clip(String text) {
        String flat = text.replace("\r", " ").replace("\n", " ");
        return flat.length() > 220 ? flat.substring(0, 220) + "…" : flat;
    }

    @Override
    public void close() {
        Socket current = socket;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // Already closing.
            }
        }
    }
}
