package za.schoolstreet.cameras;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Tells the recorder where to send alarm messages, using its OpenAPI, so the link survives the
 * TV getting a new address. The recorder's certificate is self-signed, so certificate checks
 * are skipped for this one local address only.
 */
final class RecorderLink {

    /** The recorder refused the login. Retrying could lock the account. */
    static final class LoginRejected extends IOException {
        LoginRejected() {
            super("The recorder rejected the username or password.");
        }
    }

    private static final int TIMEOUT_MS = 5000;
    private static final Pattern PARAM = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    private static final Pattern TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ERROR_CODE = Pattern.compile("\"error_code\"\\s*:\\s*(-?\\d+)");
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)");
    private static final Pattern URL_FIELD = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern HOST_FIELD = Pattern.compile("\"ip_or_domain\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern PORT_FIELD = Pattern.compile("\"port\"\\s*:\\s*\"?(\\d+)");

    private final String baseUrl;
    private final String host;
    private final String user;
    private final String password;
    private String token;

    RecorderLink(String baseUrl, String host, String user, String password) {
        this.baseUrl = baseUrl;
        this.host = host;
        this.user = user;
        this.password = password;
    }

    /** The address this device uses to reach the recorder, which is the one the recorder can call back. */
    static String localAddressFacing(String host, int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            return socket.getLocalAddress().getHostAddress();
        }
    }

    /** Points one alarm-server slot at this device, reusing its slot if it already has one. */
    void register(String myAddress, int myPort, String path) throws IOException {
        login();
        String servers = decode(call("GET", "/openapi/event_server", null));
        Set<Integer> used = new HashSet<>();
        int mine = -1;
        for (String object : servers.split("\\{")) {
            Matcher id = ID.matcher(object);
            if (!id.find()) {
                continue;
            }
            int slot = Integer.parseInt(id.group(1));
            used.add(slot);
            Matcher url = URL_FIELD.matcher(object);
            if (url.find() && url.group(1).equals(path)) {
                mine = slot;
                Matcher address = HOST_FIELD.matcher(object);
                Matcher port = PORT_FIELD.matcher(object);
                if (address.find() && address.group(1).equals(myAddress)
                        && port.find() && Integer.parseInt(port.group(1)) == myPort) {
                    return;
                }
            }
        }
        if (mine > 0) {
            requireOk(call("POST", "/openapi/event_server/delete_server", "{\"id\":" + mine + "}"), "remove the old link");
        } else {
            for (int slot = 1; slot <= 3 && mine < 0; slot++) {
                if (!used.contains(slot)) {
                    mine = slot;
                }
            }
            if (mine < 0) {
                throw new IOException("The recorder's 3 alarm-server slots are all in use.");
            }
        }
        String tail = ",\"ip_or_domain\":\"" + myAddress + "\",\"port\":" + myPort + ",\"url\":\"" + path
                + "\",\"protocol\":\"HTTP\",\"picture_switch\":\"off\"}";
        String added = call("POST", "/openapi/event_server", "{\"id\":" + mine + tail);
        if (!isOk(added)) {
            // TP-Link's documentation shows the id both as a number and as text.
            added = call("POST", "/openapi/event_server", "{\"id\":\"" + mine + "\"" + tail);
        }
        requireOk(added, "add the link");
    }

    private void login() throws IOException {
        HttpURLConnection first = open("/openapi/token");
        first.setRequestMethod("GET");
        int firstStatus = first.getResponseCode();
        String challenge = first.getHeaderField("WWW-Authenticate");
        drain(first);
        if (challenge == null) {
            throw new IOException("The recorder's OpenAPI gave no login challenge (status " + firstStatus + ").");
        }
        String realm = null;
        String nonce = null;
        String url = "/openapi/token";
        String algorithm = "SHA-256";
        Matcher matcher = PARAM.matcher(challenge);
        while (matcher.find()) {
            switch (matcher.group(1).toLowerCase(Locale.ROOT)) {
                case "realm": realm = matcher.group(2); break;
                case "nonce": nonce = matcher.group(2); break;
                case "url": case "uri": url = matcher.group(2); break;
                case "algorithm": algorithm = matcher.group(2); break;
                default: break;
            }
        }
        String hash = algorithm.toUpperCase(Locale.ROOT).contains("256") ? "SHA-256" : "MD5";
        String a1 = digest(hash, user + ":" + realm + ":" + password);
        String a2 = digest(hash, "GET:" + url);
        String response = digest(hash, a1 + ":" + nonce + ":" + a2);

        HttpURLConnection second = open("/openapi/token");
        second.setRequestMethod("GET");
        second.setRequestProperty("Authorization", "Digest username=\"" + user + "\", nonce=\"" + nonce
                + "\", realm=\"" + realm + "\", response=\"" + response + "\"");
        int status = second.getResponseCode();
        String body = drain(second);
        if (status == 401 || status == 403) {
            throw new LoginRejected();
        }
        Matcher tokenMatch = TOKEN.matcher(decode(body));
        if (!tokenMatch.find()) {
            throw new LoginRejected();
        }
        String value = tokenMatch.group(1);
        while (value.contains("%")) {
            String decoded = decode(value);
            if (decoded.equals(value)) {
                break;
            }
            value = decoded;
        }
        token = value;
    }

    private String call(String method, String path, String json) throws IOException {
        HttpURLConnection connection = open(path);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        if (json != null) {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
        }
        connection.getResponseCode();
        return drain(connection);
    }

    private static boolean isOk(String body) {
        Matcher code = ERROR_CODE.matcher(decode(body));
        return code.find() && code.group(1).equals("0");
    }

    private static void requireOk(String body, String step) throws IOException {
        if (!isOk(body)) {
            String flat = decode(body).replace('\n', ' ');
            throw new IOException("The recorder wouldn't " + step + ": "
                    + flat.substring(0, Math.min(flat.length(), 120)));
        }
    }

    private static String decode(String text) {
        if (!text.contains("%")) {
            return text;
        }
        try {
            return URLDecoder.decode(text, "UTF-8");
        } catch (IOException | IllegalArgumentException e) {
            return text;
        }
    }

    private static String digest(String algorithm, String text) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", value));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private HttpURLConnection open(String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setUseCaches(false);
        if (connection instanceof HttpsURLConnection) {
            HttpsURLConnection secure = (HttpsURLConnection) connection;
            secure.setSSLSocketFactory(localContext().getSocketFactory());
            secure.setHostnameVerifier((name, session) -> name.equals(host));
        }
        return connection;
    }

    private static SSLContext localContext() throws IOException {
        try {
            TrustManager trustLocalRecorder = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // Not used: this app never accepts secure connections.
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // The recorder's own self-signed certificate; used for this one address only.
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {trustLocalRecorder}, new SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static String drain(HttpURLConnection connection) throws IOException {
        InputStream stream;
        try {
            stream = connection.getInputStream();
        } catch (IOException e) {
            stream = connection.getErrorStream();
        }
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                body.write(buffer, 0, read);
            }
            return new String(body.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
