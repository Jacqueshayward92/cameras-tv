package za.schoolstreet.cameras;

/** Where each channel sits in the recorder's 5 by 5 Channel-Zero picture, read left to right from the top. */
final class FrameGrid {

    static final int SIDE = 5;

    // Read from the live grid on 3 November 2026. The recorder's order is not channel order.
    // Channels 3, 6 and 18 were black then, so their places are the empty cells, matched by group.
    private static final int[] CHANNELS = {
            19, 1, 2, 3, 4,
            5, 10, 11, 20, 14,
            17, 13, 18, 16, 21,
            15, 7, 22, 12, 9,
            23, 24, 25, 6, 8,
    };

    private FrameGrid() {
    }

    /** The cell number for a channel, or -1 if the channel isn't in the grid. */
    static int cellOf(int channel) {
        for (int cell = 0; cell < CHANNELS.length; cell++) {
            if (CHANNELS[cell] == channel) {
                return cell;
            }
        }
        return -1;
    }
}
