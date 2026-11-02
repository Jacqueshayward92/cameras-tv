package za.schoolstreet.cameras;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** Runs RtspSession against the fake recorder and writes what it receives to a video file. */
public final class ClientTest {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String user = args[1];
        String password = args[2];
        String output = args[3];
        int wanted = Integer.parseInt(args[4]);

        try (FileOutputStream file = new FileOutputStream(output)) {
            final int[] units = {0};
            final int[] keys = {0};
            final RtspSession[] holder = new RtspSession[1];
            RtspSession.Listener listener = new RtspSession.Listener() {
                @Override
                public void onStep(String step) {
                    System.out.println("STEP " + step);
                }

                @Override
                public void onVideoConfig(String mime, byte[] csd0, byte[] csd1) throws IOException {
                    System.out.println("CONFIG " + mime + " csd0=" + csd0.length
                            + " csd1=" + (csd1 == null ? "none" : csd1.length)
                            + " startsWith=" + Arrays.toString(Arrays.copyOf(csd0, 5)));
                    file.write(csd0);
                    if (csd1 != null) {
                        file.write(csd1);
                    }
                }

                @Override
                public void onAccessUnit(byte[] data, int length, long ptsUs, boolean keyFrame) throws IOException {
                    if (units[0] == 0 && !keyFrame) {
                        throw new IOException("First picture was not a key frame");
                    }
                    file.write(data, 0, length);
                    units[0]++;
                    if (keyFrame) {
                        keys[0]++;
                    }
                    if (units[0] == wanted) {
                        System.out.println("UNITS " + units[0] + " keys=" + keys[0] + " lastPtsUs=" + ptsUs);
                        holder[0].close();
                    }
                }
            };
            holder[0] = new RtspSession("127.0.0.1", port, "/live/0/1/avm", user, password, listener);
            try {
                holder[0].run();
            } catch (RtspSession.LoginRejected e) {
                System.out.println("LOGIN_REJECTED " + e.getMessage());
            } catch (IOException e) {
                System.out.println(units[0] >= wanted ? "CLOSED_AFTER_TARGET" : "ERROR " + e);
            }
        }
    }
}
