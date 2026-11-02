package za.schoolstreet.cameras;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

/** Opens straight into the recorder's Channel-Zero grid and keeps it running. */
@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends Activity {

    private static final String RECORDER = "192.168.0.224:554";
    private static final String GRID_PATH = "/live/0/1/avm";

    private static final int WATCHDOG_INTERVAL_MS = 2000;
    private static final int FROZEN_AFTER_MS = 8000;
    private static final int MAX_DELAY_MS = 4000;
    private static final int RETRY_DELAY_MS = 2000;
    private static final int FAILURES_BEFORE_HELP = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = this::checkFeed;
    private final Runnable retry = this::startFeed;

    private SharedPreferences prefs;
    private SurfaceView surface;
    private TextView status;
    private ExoPlayer player;
    private AlertDialog loginDialog;

    private long lastPosition;
    private long lastProgressAt;
    private int failures;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("login", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        root.addView(surface);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(22);
        status.setGravity(Gravity.CENTER);
        root.addView(status);
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        hideSystemBars();
        if (hasLogin()) {
            startFeed();
        } else {
            showLogin();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopFeed();
    }

    private boolean hasLogin() {
        return prefs.contains("user") && prefs.contains("password");
    }

    private void startFeed() {
        stopFeed();
        showStatus(failures >= FAILURES_BEFORE_HELP
                ? "Can't reach the recorder. Still trying.\nHold OK to change the login."
                : "Connecting…");

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(500, 2000, 250, 250)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setAllowedVideoJoiningTimeMs(0);

        player = new ExoPlayer.Builder(this, renderers)
                .setLoadControl(loadControl)
                .build();
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                failures = 0;
                showStatus(null);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                failures++;
                stopFeed();
                handler.postDelayed(retry, RETRY_DELAY_MS);
            }
        });

        player.setMediaSource(new RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(8000)
                .createMediaSource(MediaItem.fromUri(gridUri())));
        player.setPlayWhenReady(true);
        player.prepare();

        lastPosition = 0;
        lastProgressAt = System.currentTimeMillis();
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
    }

    private void stopFeed() {
        handler.removeCallbacks(watchdog);
        handler.removeCallbacks(retry);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    /** Restarts the feed if the picture stops moving or falls behind live. */
    private void checkFeed() {
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long position = player.getCurrentPosition();
        if (position != lastPosition) {
            lastPosition = position;
            lastProgressAt = now;
        }
        if (now - lastProgressAt > FROZEN_AFTER_MS) {
            failures++;
            startFeed();
            return;
        }
        if (player.getTotalBufferedDuration() > MAX_DELAY_MS) {
            startFeed();
            return;
        }
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
    }

    private Uri gridUri() {
        String login = Uri.encode(prefs.getString("user", "")) + ":"
                + Uri.encode(prefs.getString("password", ""));
        return new Uri.Builder()
                .scheme("rtsp")
                .encodedAuthority(login + "@" + RECORDER)
                .encodedPath(GRID_PATH)
                .build();
    }

    private void showStatus(String message) {
        status.setText(message);
        status.setVisibility(message == null ? View.GONE : View.VISIBLE);
    }

    private void showLogin() {
        if (loginDialog != null && loginDialog.isShowing()) {
            return;
        }
        stopFeed();

        EditText user = new EditText(this);
        user.setHint("Recorder username");
        user.setSingleLine(true);
        user.setText(prefs.getString("user", "admin"));
        EditText password = new EditText(this);
        password.setHint("Recorder password");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(48, 24, 48, 0);
        form.addView(user);
        form.addView(password);

        loginDialog = new AlertDialog.Builder(this)
                .setTitle("Recorder login")
                .setView(form)
                .setCancelable(false)
                .setPositiveButton("Save", (dialog, which) -> {
                    prefs.edit()
                            .putString("user", user.getText().toString().trim())
                            .putString("password", password.getText().toString())
                            .apply();
                    failures = 0;
                    startFeed();
                })
                .create();
        loginDialog.show();
        password.requestFocus();
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            showLogin();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
