package za.schoolstreet.cameras;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Lets one chosen remote button open the app from anywhere. Every other button passes straight through. */
public class RemoteButtonService extends AccessibilityService {

    private static final String PREFS = "remote_button";
    private static final String KEY = "key";
    private static final String SCAN = "scan";

    private static volatile boolean running;
    private static volatile Runnable learning;
    private static volatile Runnable inFront;

    private final Handler handler = new Handler(Looper.getMainLooper());

    static boolean isOn() {
        return running;
    }

    /** The next usable button pressed becomes the chosen one, then this runs. */
    static void learn(Runnable done) {
        learning = done;
    }

    static void stopLearning() {
        learning = null;
    }

    /** What the chosen button does while the app is already open. Null means it opens the app. */
    static void whenInFront(Runnable action) {
        inFront = action;
    }

    static boolean isSet(Context context) {
        return prefs(context).contains(KEY);
    }

    static void clear(Context context) {
        prefs(context).edit().clear().apply();
    }

    /** A readable name for the chosen button, or null if there isn't one. */
    static String chosenName(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.contains(KEY)) {
            return null;
        }
        int key = prefs.getInt(KEY, KeyEvent.KEYCODE_UNKNOWN);
        if (key == KeyEvent.KEYCODE_UNKNOWN) {
            return "TV button " + prefs.getInt(SCAN, 0);
        }
        return KeyEvent.keyCodeToString(key).replace("KEYCODE_", "").replace('_', ' ');
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override
    protected void onServiceConnected() {
        running = true;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = false;
        learning = null;
        return super.onUnbind(intent);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        int scan = event.getScanCode();
        boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;

        Runnable waiting = learning;
        if (waiting != null && pressed && RemoteButton.canUse(key, scan)) {
            learning = null;
            prefs(this).edit().putInt(KEY, key).putInt(SCAN, scan).apply();
            handler.post(waiting);
            return true;
        }

        SharedPreferences prefs = prefs(this);
        if (!prefs.contains(KEY)
                || !RemoteButton.matches(prefs.getInt(KEY, KeyEvent.KEYCODE_UNKNOWN), prefs.getInt(SCAN, 0), key, scan)) {
            return false;
        }
        if (pressed && event.getRepeatCount() == 0) {
            Runnable action = inFront;
            if (action != null) {
                handler.post(action);
            } else {
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Only button presses are used.
    }

    @Override
    public void onInterrupt() {
        // Nothing to stop.
    }
}
