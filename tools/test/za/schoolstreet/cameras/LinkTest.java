package za.schoolstreet.cameras;

import java.io.IOException;

/** Runs the recorder-link code against the fake OpenAPI. */
public final class LinkTest {
    public static void main(String[] args) {
        int port = Integer.parseInt(args[0]);
        String user = args[1];
        String password = args[2];
        String address = args[3];
        String path = args[4];
        try {
            new RecorderLink("https://localhost:" + port, "localhost", user, password).register(address, 8787, path);
            System.out.println("LINKED");
        } catch (RecorderLink.LoginRejected e) {
            System.out.println("LOGIN_REJECTED");
        } catch (IOException e) {
            System.out.println("ERROR " + e);
        }
    }
}
