package za.schoolstreet.cameras;

import android.view.KeyEvent;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Checks the alert logic: which messages count, the repeat limits, and the listener. */
public final class AlertTests {
    private static int failures;
    private static int passes;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (ok) {
            passes++;
        } else {
            failures++;
        }
    }

    private static boolean human(String body, int channel, String name) {
        List<AlertParser.Alert> alerts = AlertParser.humanAlerts(body);
        return alerts.size() == 1 && alerts.get(0).channel == channel && alerts.get(0).name.equals(name);
    }

    private static boolean none(String body) {
        return AlertParser.humanAlerts(body).isEmpty();
    }

    public static void main(String[] args) throws Exception {
        parser();
        policy();
        remoteButton();
        server();
        System.out.println("RESULT " + passes + " passed, " + failures + " failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void parser() {
        check("documented layout, human", human(
                "{\"device_id\":\"id\",\"device_name\":\"NVR\",\"messages\":[{\"type\":1,\"sub_type\":[21],"
                        + "\"localtime\":\"2026-11-02 21:01:39\",\"channel\":\"20\",\"channel_name\":\"12: Backyard\"}]}",
                20, "12: Backyard"));
        check("enhanced layout, human", human(
                "{\"device_id\":\"id\",\"messages\":[{\"Event Type\":1,\"Event Sub Type\":[21],"
                        + "\"EVENT TIME\":\"2026-11-02 21:01:39\",\"Camera\":\"4\",\"Camera Name\":\"12: Front Gate\",\"Extra Text\":\"None\"}]}",
                4, "12: Front Gate"));
        check("numbers given as text", human(
                "{\"messages\":[{\"type\":\"1\",\"sub_type\":\"21\",\"channel\":\"7\",\"channel_name\":\"14: Front Gate\"}]}",
                7, "14: Front Gate"));
        check("motion that the camera says is a person", human(
                "{\"messages\":[{\"type\":1,\"sub_type\":[2,101],\"channel\":\"9\",\"channel_name\":\"14: Alley Back\"}]}",
                9, "14: Alley Back"));
        check("line crossing by a person", human(
                "{\"messages\":[{\"type\":1,\"sub_type\":[4,101],\"channel\":\"10\",\"channel_name\":\"12: Backyard Gate\"}]}",
                10, "12: Backyard Gate"));
        check("spaces and new lines", human(
                "{\n \"messages\" : [ {\n  \"type\" : 1,\n  \"sub_type\" : [ 21 ],\n  \"channel\" : \"13\",\n  \"channel_name\" : \"12: Front Yard / Borehole\"\n } ]\n}",
                13, "12: Front Yard / Borehole"));
        for (String street : new String[] {"1|Street: Left", "2|Street: Below Camera Pole", "3|Street: Right", "19|PTZ: Front Street"}) {
            String[] parts = street.split("\\|");
            check("street camera is ignored: " + parts[1], none(
                    "{\"messages\":[{\"type\":1,\"sub_type\":[21],\"channel\":\"" + parts[0] + "\",\"channel_name\":\"" + parts[1] + "\"}]}"));
        }
        check("street channel is ignored even with no name", none("{\"messages\":[{\"type\":1,\"sub_type\":[21],\"channel\":\"19\"}]}"));
        check("street in the name is ignored on any channel", none("{\"messages\":[{\"type\":1,\"sub_type\":[21],\"channel\":\"30\",\"channel_name\":\"New street cam\"}]}"));
        for (String home : new String[] {"4|12: Front Gate", "5|12: Front Yard / Driveway", "6|Chantel: Hek", "7|14: Front Gate", "8|Chantel: Voordeur", "9|14: Alley Back",
                "10|12: Backyard Gate", "11|12: Front Door", "12|14: Alley Front", "14|12: JoJo Tank", "15|14: Back Yard", "16|14: Solar / Roof", "17|12: Borehole",
                "18|12: Front Yard Main Bedroom", "20|12: Backyard", "21|14: Front Yard", "22|14: Front Gate/Alley", "23|14: Courtyard/Alley", "24|14: Garage", "25|12: Driveway/Backyard Gate"}) {
            String[] parts = home.split("\\|");
            check("home camera alerts: " + parts[1], human(
                    "{\"messages\":[{\"type\":1,\"sub_type\":[21],\"channel\":\"" + parts[0] + "\",\"channel_name\":\"" + parts[1] + "\"}]}",
                    Integer.parseInt(parts[0]), parts[1]));
        }
        check("percent-encoded message", human(
                "%7B%22messages%22%3A%5B%7B%22type%22%3A1%2C%22sub_type%22%3A%5B21%5D%2C%22channel%22%3A%225%22%2C%22channel_name%22%3A%2212%3A%20Driveway%22%7D%5D%7D",
                5, "12: Driveway"));
        check("multipart wrapper with a picture", human(
                "--ReportEventBoundary\r\nContent-Disposition: form-data;name=\"event\"\r\nContent-Type: application/json\r\n\r\n"
                        + "{\"messages\":[{\"type\":1,\"sub_type\":[21],\"channel\":\"12\",\"channel_name\":\"14: Alley Front\"}]}"
                        + "\r\n--ReportEventBoundary\r\nContent-Disposition: form-data;name=\"snapshot\"\r\n\r\nÿØ{junk}\r\n--ReportEventBoundary--",
                12, "14: Alley Front"));
        check("two people in one message", AlertParser.humanAlerts(
                "{\"messages\":[{\"type\":1,\"sub_type\":[21],\"channel\":\"4\",\"channel_name\":\"A\"},"
                        + "{\"type\":1,\"sub_type\":[22],\"channel\":\"5\",\"channel_name\":\"B\"},"
                        + "{\"type\":1,\"sub_type\":[21],\"channel\":\"7\",\"channel_name\":\"C\"}]}").size() == 2);

        check("vehicle is ignored", none("{\"messages\":[{\"type\":1,\"sub_type\":[22],\"channel\":\"19\",\"channel_name\":\"PTZ\"}]}"));
        check("motion/vehicle is ignored", none("{\"messages\":[{\"type\":1,\"sub_type\":[2,102],\"channel\":\"19\"}]}"));
        check("plain motion is ignored", none("{\"messages\":[{\"type\":1,\"sub_type\":[2],\"channel\":\"19\"}]}"));
        check("video loss is ignored", none("{\"messages\":[{\"type\":1,\"sub_type\":[19],\"channel\":\"3\"}]}"));
        check("device fault with the same number is ignored", none("{\"messages\":[{\"type\":2,\"sub_type\":[21],\"port\":\"1\"}]}"));
        check("disk fault is ignored", none("{\"messages\":[{\"type\":7,\"sub_type\":[1]}]}"));
        check("a camera called channel 21 isn't mistaken", none("{\"messages\":[{\"type\":1,\"sub_type\":[22],\"channel\":\"21\",\"channel_name\":\"sub_type 21\"}]}"));
        check("empty message is ignored", none(""));
        check("garbage is ignored", none("not json at all { [ \" ::"));
    }

    private static void policy() {
        long start = 1_000_000_000L;
        int night = 22;
        int day = 13;

        AlertPolicy policy = new AlertPolicy();
        check("first alert at night sounds", policy.decide(1, start, night) == AlertPolicy.Action.SOUND);
        check("same camera seconds later is quiet", policy.decide(1, start + 5000, night) == AlertPolicy.Action.NONE);
        check("another camera right after shows but stays quiet", policy.decide(2, start + 6000, night) == AlertPolicy.Action.BANNER);
        check("another camera later sounds", policy.decide(3, start + 11000, night) == AlertPolicy.Action.SOUND);
        check("same camera after the repeat limit sounds", policy.decide(1, start + 31000, night) == AlertPolicy.Action.SOUND);

        AlertPolicy daytime = new AlertPolicy();
        check("daytime shows but stays quiet", daytime.decide(1, start, day) == AlertPolicy.Action.BANNER);
        check("18:00 sounds", new AlertPolicy().decide(1, start, 18) == AlertPolicy.Action.SOUND);
        check("06:00 sounds", new AlertPolicy().decide(1, start, 6) == AlertPolicy.Action.SOUND);
        check("07:00 stays quiet", new AlertPolicy().decide(1, start, 7) == AlertPolicy.Action.BANNER);

        AlertPolicy muted = new AlertPolicy();
        muted.mute(start);
        check("muted shows but stays quiet", muted.decide(1, start + 1000, night) == AlertPolicy.Action.BANNER);
        check("sound returns after mute ends", muted.decide(2, start + AlertPolicy.MUTE_MS + 1, night) == AlertPolicy.Action.SOUND);
    }

    private static void remoteButton() {
        int[] kept = {KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_POWER,
                KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN};
        for (int key : kept) {
            check("button " + key + " can't be taken over", !RemoteButton.canUse(key, 100));
        }
        check("a colour button can be chosen", RemoteButton.canUse(KeyEvent.KEYCODE_PROG_RED, 100));
        check("a number button can be chosen", RemoteButton.canUse(KeyEvent.KEYCODE_1, 100));
        check("an unnamed TV button can be chosen", RemoteButton.canUse(KeyEvent.KEYCODE_UNKNOWN, 700));
        check("a press with no name or number can't be chosen", !RemoteButton.canUse(KeyEvent.KEYCODE_UNKNOWN, 0));

        check("the chosen button matches", RemoteButton.matches(KeyEvent.KEYCODE_PROG_RED, 100, KeyEvent.KEYCODE_PROG_RED, 100));
        check("another button doesn't match", !RemoteButton.matches(KeyEvent.KEYCODE_PROG_RED, 100, KeyEvent.KEYCODE_PROG_BLUE, 100));
        check("an unnamed TV button matches by its number", RemoteButton.matches(KeyEvent.KEYCODE_UNKNOWN, 700, KeyEvent.KEYCODE_UNKNOWN, 700));
        check("another unnamed TV button doesn't match", !RemoteButton.matches(KeyEvent.KEYCODE_UNKNOWN, 700, KeyEvent.KEYCODE_UNKNOWN, 701));
        check("a named button doesn't match an unnamed choice", !RemoteButton.matches(KeyEvent.KEYCODE_UNKNOWN, 700, KeyEvent.KEYCODE_PROG_RED, 700));
    }

    private static String post(int port, String headersExtra, String body, boolean declareLength) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(6000);
            OutputStream out = socket.getOutputStream();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String head = "POST /cameras-alert-test HTTP/1.1\r\nHost: tv\r\nContent-Type: application/json\r\n"
                    + (declareLength ? "Content-Length: " + bytes.length + "\r\n" : "") + headersExtra + "\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(bytes);
            out.flush();
            StringBuilder reply = new StringBuilder();
            int value;
            try {
                while ((value = socket.getInputStream().read()) >= 0) {
                    reply.append((char) value);
                }
            } catch (IOException ignored) {
                // Reset once the reply is complete.
            }
            return reply.toString();
        }
    }

    private static void waitFor(List<String> received, int count) throws InterruptedException {
        for (int i = 0; i < 80 && received.size() < count; i++) {
            Thread.sleep(50);
        }
    }

    private static void server() throws Exception {
        String message = "{\"messages\":[{\"type\":1,\"sub_type\":[21],\"channel\":\"4\",\"channel_name\":\"Gate\"}]}";
        List<String> received = Collections.synchronizedList(new ArrayList<String>());

        try (AlertServer server = new AlertServer(18787, "127.0.0.1", received::add)) {
            server.start();
            String reply = post(18787, "", message, true);
            waitFor(received, 1);
            check("listener replies 200", reply.startsWith("HTTP/1.1 200"));
            check("listener delivers the message", received.size() == 1 && received.get(0).equals(message));

            received.clear();
            String continued = post(18787, "Expect: 100-continue\r\n", message, true);
            waitFor(received, 1);
            check("listener handles 100-continue", continued.startsWith("HTTP/1.1 100") && continued.contains("200 OK") && received.size() == 1);

            received.clear();
            post(18787, "", message, false);
            waitFor(received, 1);
            check("listener handles a message with no declared length", received.size() == 1 && received.get(0).equals(message));

            received.clear();
            StringBuilder big = new StringBuilder(message);
            for (int i = 0; i < 300000; i++) {
                big.append('x');
            }
            post(18787, "", big.toString(), true);
            waitFor(received, 1);
            check("listener handles a large message", received.size() == 1 && received.get(0).length() == big.length());

            received.clear();
            final int burst = 30;
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < burst; i++) {
                Thread thread = new Thread(() -> {
                    try {
                        post(18787, "", message, true);
                    } catch (IOException ignored) {
                        // Counted below.
                    }
                });
                threads.add(thread);
                thread.start();
            }
            for (Thread thread : threads) {
                thread.join();
            }
            waitFor(received, burst);
            check("listener survives 30 messages at once", received.size() == burst);

            received.clear();
            try (Socket idle = new Socket("127.0.0.1", 18787)) {
                idle.getOutputStream().write("GET".getBytes(StandardCharsets.ISO_8859_1));
                post(18787, "", message, true);
                waitFor(received, 1);
                check("a stuck connection doesn't block others", received.size() == 1);
            }
        }

        List<String> blocked = Collections.synchronizedList(new ArrayList<String>());
        try (AlertServer server = new AlertServer(18788, "192.168.0.224", blocked::add)) {
            server.start();
            try {
                post(18788, "", message, true);
            } catch (IOException ignored) {
                // The listener can drop the connection before the message is written.
            }
            Thread.sleep(300);
            check("messages from anything but the recorder are refused", blocked.isEmpty());
        }

        try (AlertServer server = new AlertServer(18789, "127.0.0.1", received::add)) {
            server.start();
            server.close();
            boolean refused;
            try (Socket socket = new Socket("127.0.0.1", 18789)) {
                refused = false;
            } catch (IOException e) {
                refused = true;
            }
            check("closing frees the port", refused);
        }
        try (AlertServer again = new AlertServer(18789, "127.0.0.1", received::add)) {
            check("the port can be reopened", true);
        }
    }
}
