package za.schoolstreet.cameras;

import android.view.KeyEvent;

/** Which remote buttons may open the app, and whether a press is the chosen button. */
final class RemoteButton {

    private RemoteButton() {
    }

    /** Keeps the buttons needed to move around the TV, so the remote can never be locked out of its settings. */
    static boolean canUse(int keyCode, int scanCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_TV_POWER:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_SOFT_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                return false;
            case KeyEvent.KEYCODE_UNKNOWN:
                return scanCode > 0;
            default:
                return true;
        }
    }

    /** Some TV brand buttons report no key name, only the remote's own number for the button. */
    static boolean matches(int savedKey, int savedScan, int keyCode, int scanCode) {
        if (savedKey != KeyEvent.KEYCODE_UNKNOWN) {
            return keyCode == savedKey;
        }
        return savedScan > 0 && keyCode == KeyEvent.KEYCODE_UNKNOWN && scanCode == savedScan;
    }
}
