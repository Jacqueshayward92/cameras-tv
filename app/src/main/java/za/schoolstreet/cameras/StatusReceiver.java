package za.schoolstreet.cameras;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Lets the approved laptop ask for the recorder's alert setup. It only reads, and the file it writes holds
 * the recorder's answers, never the login. The manifest keeps other apps from calling this.
 */
public class StatusReceiver extends BroadcastReceiver {

    private static final int CHANNELS = 32;

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        new Thread(() -> writeStatus(app), "recorder-status").start();
    }

    private static void writeStatus(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("login", Context.MODE_PRIVATE);
        File dir = context.getExternalFilesDir(null);
        if (dir == null || !prefs.contains("password")) {
            return;
        }
        List<String> paths = new ArrayList<>();
        paths.add("/openapi/module_list");
        paths.add("/openapi/added_devices");
        paths.add("/openapi/event_server");
        paths.add("/openapi/alarm_device/alarm_mode?device_id=1");
        paths.add("/openapi/alarm_output/nvr_alarm");
        paths.add("/openapi/alarm_output/batch_get_ipc_alarm");
        for (int channel = 1; channel <= CHANNELS; channel++) {
            paths.add("/openapi/active_defense/arm_status?channel=" + channel);
        }
        String result;
        try {
            result = new RecorderLink("https://" + MainActivity.RECORDER_HOST + ":" + MainActivity.OPENAPI_PORT,
                    MainActivity.RECORDER_HOST, prefs.getString("user", ""), prefs.getString("password", ""))
                    .readSettings(paths);
        } catch (IOException e) {
            result = "FAILED: " + e.getMessage() + "\n";
        }
        try (FileWriter writer = new FileWriter(new File(dir, "recorder_status.txt"))) {
            writer.write(result);
        } catch (IOException ignored) {
            // Nothing else to do: the laptop will see no file.
        }
    }
}
