package za.schoolstreet.cameras;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds human-detection events in the recorder's alarm messages. TP-Link documents several
 * layouts (plain and "enhanced" field names, JSON or multipart), so this reads the fields
 * wherever they appear rather than expecting one exact shape.
 */
final class AlertParser {

    private static final int CHANNEL_EVENT = 1;
    private static final int SUBTYPE_HUMAN = 21;
    private static final int SUBTYPE_PERSON = 101;

    private static final Pattern TYPE = Pattern.compile("\"(?:type|Event Type)\"\\s*:\\s*\"?(\\d+)");
    private static final Pattern SUB_TYPE = Pattern.compile(
            "\"(?:sub_type|Event Sub Type)\"\\s*:\\s*(\\[[^\\]]*\\]|\"[^\"]*\"|\\d+)");
    private static final Pattern CHANNEL = Pattern.compile("\"(?:channel|Camera)\"\\s*:\\s*\"?(\\d+)");
    private static final Pattern NAME = Pattern.compile("\"(?:channel_name|Camera Name)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    static final class Alert {
        final int channel;
        final String name;

        Alert(int channel, String name) {
            this.channel = channel;
            this.name = name;
        }
    }

    private AlertParser() {
    }

    static List<Alert> humanAlerts(String message) {
        List<Alert> alerts = new ArrayList<>();
        if (message == null) {
            return alerts;
        }
        String body = decodeIfEncoded(message);
        for (String object : body.split("\\{")) {
            if (isHuman(object)) {
                int channel = channelOf(object);
                String name = nameOf(object);
                if (!isStreetCamera(channel, name)) {
                    alerts.add(new Alert(channel, name));
                }
            }
        }
        return alerts;
    }

    /** People on the public street aren't a reason to alarm: channels 1, 2, 3 and 19, or "street" in the name. */
    private static boolean isStreetCamera(int channel, String name) {
        return channel == 1 || channel == 2 || channel == 3 || channel == 19
                || name.toLowerCase(Locale.ROOT).contains("street");
    }

    private static String decodeIfEncoded(String message) {
        if (!message.contains("%7B") && !message.contains("%22")) {
            return message;
        }
        try {
            return URLDecoder.decode(message, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return message;
        }
    }

    private static boolean isHuman(String object) {
        Matcher subType = SUB_TYPE.matcher(object);
        if (!subType.find()) {
            return false;
        }
        Matcher type = TYPE.matcher(object);
        if (type.find() && Integer.parseInt(type.group(1)) != CHANNEL_EVENT) {
            return false;
        }
        String value = subType.group(1);
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("human") || lower.contains("person")) {
            return true;
        }
        Matcher number = NUMBER.matcher(value);
        while (number.find()) {
            String digits = number.group();
            if (digits.length() <= 4) {
                int code = Integer.parseInt(digits);
                if (code == SUBTYPE_HUMAN || code == SUBTYPE_PERSON) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int channelOf(String object) {
        Matcher channel = CHANNEL.matcher(object);
        if (channel.find() && channel.group(1).length() <= 4) {
            return Integer.parseInt(channel.group(1));
        }
        return 0;
    }

    private static String nameOf(String object) {
        Matcher name = NAME.matcher(object);
        return name.find() ? name.group(1) : "";
    }
}
