package za.schoolstreet.cameras;

import java.util.Locale;

/** Where each camera sits in the recorder's 5 by 5 Channel-Zero picture, read left to right from the top. */
final class FrameGrid {

    static final int SIDE = 5;

    // Read from the live grid on 3 November 2026. The recorder's order is not channel order.
    // The names decide, because they are what the grid itself shows. Numbers are only a fallback.
    // Street: Right, 12: Front Yard Main Bedroom and Chantel: Hek were black then, so their places are matched by group.
    private static final String[] NAMES = {
            "PTZ: Front Street", "Street: Left", "Street: Below Camera Pole", "Street: Right", "12: Front Gate",
            "12: Front Yard / Driveway", "12: Backyard Gate", "12: Front Door", "12: Backyard", "12: JoJo Tank",
            "12: Borehole", "12: Front Yard / Borehole", "12: Front Yard Main Bedroom", "14: Solar / Roof", "14: Front Yard",
            "14: Back Yard", "14: Front Gate", "14: Front Gate/Alley", "14: Alley Front", "14: Alley Back",
            "14: Courtyard/Alley", "14: Garage", "12: Driveway/Backyard Gate", "Chantel: Hek", "Chantel: Voordeur",
    };

    private static final int[] CHANNELS = {
            19, 1, 2, 3, 4,
            5, 10, 11, 20, 14,
            17, 13, 18, 16, 21,
            15, 7, 22, 12, 9,
            23, 24, 25, 6, 8,
    };

    private FrameGrid() {
    }

    /** The label normally sits top right, so a person in the top row's right half moves it across. */
    static boolean labelGoesLeft(int cell) {
        return cell >= SIDE / 2 && cell < SIDE;
    }

    /** The cell for a camera, by name if the name is known and otherwise by channel, or -1 if neither is. */
    static int cellOf(int channel, String name) {
        String wanted = comparable(name);
        for (int cell = 0; cell < NAMES.length; cell++) {
            if (comparable(NAMES[cell]).equals(wanted)) {
                return cell;
            }
        }
        for (int cell = 0; cell < CHANNELS.length; cell++) {
            if (CHANNELS[cell] == channel) {
                return cell;
            }
        }
        return -1;
    }

    private static String comparable(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
