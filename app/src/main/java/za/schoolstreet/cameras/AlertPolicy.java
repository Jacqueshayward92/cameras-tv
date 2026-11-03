package za.schoolstreet.cameras;

import java.util.HashMap;
import java.util.Map;

/** Decides what an alert does, so one person walking past doesn't set the TV off repeatedly. */
final class AlertPolicy {

    enum Action { NONE, BANNER, SOUND }

    static final long CAMERA_REPEAT_MS = 30000;
    static final long SOUND_REPEAT_MS = 10000;
    static final long MUTE_MS = 30 * 60000;
    private static final int SOUND_FROM_HOUR = 19;
    private static final int SOUND_UNTIL_HOUR = 7;

    private final Map<Integer, Long> lastByCamera = new HashMap<>();
    private long lastSoundAt = Long.MIN_VALUE / 2;
    private long mutedUntil = Long.MIN_VALUE / 2;

    synchronized Action decide(int channel, long nowMs, int hourOfDay) {
        Long last = lastByCamera.get(channel);
        if (last != null && nowMs - last < CAMERA_REPEAT_MS) {
            return Action.NONE;
        }
        lastByCamera.put(channel, nowMs);
        boolean night = hourOfDay >= SOUND_FROM_HOUR || hourOfDay < SOUND_UNTIL_HOUR;
        if (night && nowMs >= mutedUntil && nowMs - lastSoundAt >= SOUND_REPEAT_MS) {
            lastSoundAt = nowMs;
            return Action.SOUND;
        }
        return Action.BANNER;
    }

    synchronized void mute(long nowMs) {
        mutedUntil = nowMs + MUTE_MS;
    }
}
