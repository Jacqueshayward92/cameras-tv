package za.schoolstreet.cameras;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Calendar;
import java.util.List;

/** Opens straight into the recorder's Channel-Zero grid and keeps it running. */
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String RECORDER_HOST = "192.168.0.224";
    private static final int RECORDER_PORT = 554;
    private static final String GRID_PATH = "/live/0/1/avm";

    private static final int WATCHDOG_INTERVAL_MS = 1000;
    private static final int FROZEN_AFTER_MS = 5000;
    private static final int FIRST_PICTURE_TIMEOUT_MS = 10000;
    private static final int RETRY_DELAY_MS = 1000;
    private static final int QUIET_RECOVERY_MS = 15000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = this::checkFeed;
    private final Runnable retry = this::startFeed;

    private SharedPreferences prefs;
    private SurfaceView surface;
    private TextView status;
    private Surface videoSurface;
    private Feed feed;
    private AlertDialog loginDialog;
    private boolean started;
    private volatile long lastGoodAt;

    private static final int ALERT_PORT = 8787;
    private static final int OPENAPI_PORT = 20443;
    private static final int BANNER_MS = 15000;
    private static final int NOTICE_MS = 6000;

    private final AlertPolicy alertPolicy = new AlertPolicy();
    private TextView banner;
    private final Runnable hideBanner = () -> banner.setVisibility(View.GONE);
    private AlertServer alertServer;
    private AlarmTone alarmTone;
    private boolean linkLoginRejected;
    private volatile boolean recorderSeen;

    /** A short drop right after good video recovers on its own, so no message is needed. */
    private boolean recentlyPlaying() {
        return System.currentTimeMillis() - lastGoodAt < QUIET_RECOVERY_MS;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("login", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(20);
        status.setGravity(Gravity.CENTER);
        status.setPadding(80, 40, 80, 40);
        root.addView(status);
        banner = new TextView(this);
        banner.setTextColor(Color.WHITE);
        banner.setBackgroundColor(0xE0B00020);
        banner.setTextSize(26);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(40, 24, 40, 24);
        banner.setVisibility(View.GONE);
        root.addView(banner, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        setContentView(root);
    }

    private void startAlerts() {
        if (!BuildConfig.ALERTS || alertServer != null || !hasLogin()) {
            return;
        }
        recorderSeen = false;
        if (alarmTone == null) {
            alarmTone = new AlarmTone(this);
        }
        try {
            alertServer = new AlertServer(ALERT_PORT, RECORDER_HOST, this::onRecorderMessage);
            alertServer.start();
        } catch (IOException e) {
            showBanner("Alerts are off: " + e.getMessage(), BANNER_MS);
            return;
        }
        if (!linkLoginRejected) {
            String user = prefs.getString("user", "");
            String password = prefs.getString("password", "");
            String path = "/cameras-alert-" + installId();
            new Thread(() -> linkToRecorder(user, password, path), "recorder-link").start();
        }
    }

    private void stopAlerts() {
        if (alertServer != null) {
            alertServer.close();
            alertServer = null;
        }
        if (alarmTone != null) {
            alarmTone.stop();
        }
        handler.removeCallbacks(hideBanner);
        banner.setVisibility(View.GONE);
    }

    private String installId() {
        String id = prefs.getString("install", null);
        if (id == null) {
            id = Long.toHexString(System.nanoTime() ^ System.currentTimeMillis());
            prefs.edit().putString("install", id).apply();
        }
        return id;
    }

    /** Runs once each time the app opens. A refused login is not retried, to avoid locking the account. */
    private void linkToRecorder(String user, String password, String path) {
        String notice;
        try {
            String myAddress = RecorderLink.localAddressFacing(RECORDER_HOST, OPENAPI_PORT);
            new RecorderLink("https://" + RECORDER_HOST + ":" + OPENAPI_PORT, RECORDER_HOST, user, password)
                    .register(myAddress, ALERT_PORT, path);
            notice = "Human alerts are on";
        } catch (RecorderLink.LoginRejected e) {
            linkLoginRejected = true;
            notice = "Alerts are off: " + e.getMessage();
        } catch (IOException e) {
            notice = "Alerts are off: can't link to the recorder (" + e.getMessage() + ")";
        }
        String message = notice;
        handler.post(() -> {
            if (started) {
                showBanner(message, NOTICE_MS);
            }
        });
    }

    private void onRecorderMessage(String body) {
        List<AlertParser.Alert> alerts = AlertParser.humanAlerts(body);
        handler.post(() -> {
            if (!started) {
                return;
            }
            if (!recorderSeen) {
                recorderSeen = true;
                if (alerts.isEmpty()) {
                    showBanner("The recorder reached this TV", NOTICE_MS);
                }
            }
            for (AlertParser.Alert alert : alerts) {
                onHumanAlert(alert);
            }
        });
    }

    private void onHumanAlert(AlertParser.Alert alert) {
        AlertPolicy.Action action = alertPolicy.decide(alert.channel, System.currentTimeMillis(),
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
        if (action == AlertPolicy.Action.NONE) {
            return;
        }
        String where = alert.name.isEmpty() ? "camera " + alert.channel : alert.name;
        showBanner("Person detected: " + where, BANNER_MS);
        if (action == AlertPolicy.Action.SOUND) {
            alarmTone.play();
        }
    }

    private void showBanner(String message, int forMs) {
        banner.setText(message);
        banner.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideBanner);
        handler.postDelayed(hideBanner, forMs);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
        if (BuildConfig.ALERTS && ok && event.isTracking() && !event.isCanceled()) {
            alertPolicy.mute(System.currentTimeMillis());
            if (alarmTone != null) {
                alarmTone.stop();
            }
            showBanner("Alert sound off for 30 minutes", NOTICE_MS);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        hideSystemBars();
        if (hasLogin()) {
            startFeed();
            startAlerts();
        } else {
            showLogin();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        stopFeed();
        stopAlerts();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        videoSurface = holder.getSurface();
        if (started && hasLogin() && (loginDialog == null || !loginDialog.isShowing())) {
            startFeed();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // The decoder scales to whatever size the surface is.
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopFeed();
        videoSurface = null;
    }

    private boolean hasLogin() {
        return prefs.contains("user") && prefs.contains("password");
    }

    private void startFeed() {
        stopFeed();
        if (!started || videoSurface == null || !hasLogin()) {
            return;
        }
        if (!recentlyPlaying()) {
            showStatus("Connecting…");
        }
        feed = new Feed(videoSurface, prefs.getString("user", ""), prefs.getString("password", ""));
        feed.start();
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
    }

    private void stopFeed() {
        handler.removeCallbacks(watchdog);
        handler.removeCallbacks(retry);
        if (feed != null) {
            feed.shutdown();
            feed = null;
        }
    }

    /** Restarts the feed if the picture stops moving. */
    private void checkFeed() {
        if (feed == null) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean frozen = feed.lastPictureAt > 0
                ? now - feed.lastPictureAt > FROZEN_AFTER_MS
                : now - feed.startedAt > FIRST_PICTURE_TIMEOUT_MS;
        if (frozen) {
            feedFailed(feed, "No picture arrived. Last step: " + feed.step + ".", true);
            return;
        }
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
    }

    private void feedFailed(Feed failed, String detail, boolean tryAgain) {
        if (failed != feed) {
            return;
        }
        stopFeed();
        if (!tryAgain || !recentlyPlaying()) {
            showStatus(detail + "\n\n" + (tryAgain ? "Trying again. " : "") + "Hold OK to change the login.");
        }
        if (tryAgain) {
            handler.postDelayed(retry, RETRY_DELAY_MS);
        }
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
                    linkLoginRejected = false;
                    startFeed();
                    stopAlerts();
                    startAlerts();
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

    /** One connection: reads the stream and hands each frame straight to the hardware decoder. */
    private final class Feed extends Thread implements RtspSession.Listener {
        private static final long INPUT_WAIT_US = 10000;
        private static final long INPUT_GIVE_UP_MS = 200;

        final long startedAt = System.currentTimeMillis();
        volatile long lastPictureAt;
        volatile String step = "Starting";

        private final Surface target;
        private final String user;
        private final String password;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private volatile RtspSession session;
        private volatile boolean stopping;
        private MediaCodec codec;

        Feed(Surface target, String user, String password) {
            super("camera-feed");
            this.target = target;
            this.user = user;
            this.password = password;
        }

        @Override
        public void run() {
            try {
                session = new RtspSession(RECORDER_HOST, RECORDER_PORT, GRID_PATH, user, password, this);
                if (!stopping) {
                    session.run();
                }
            } catch (RtspSession.LoginRejected e) {
                report(e.getMessage(), false);
            } catch (Exception e) {
                report(e.getClass().getSimpleName() + " at \"" + step + "\": " + e.getMessage(), true);
            } finally {
                releaseCodec();
            }
        }

        private void report(String detail, boolean tryAgain) {
            if (!stopping) {
                handler.post(() -> feedFailed(this, detail, tryAgain));
            }
        }

        void shutdown() {
            stopping = true;
            RtspSession current = session;
            if (current != null) {
                current.close();
            }
            try {
                join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void onStep(String newStep) {
            step = newStep;
            if (lastPictureAt == 0 && !stopping) {
                handler.post(() -> {
                    if (feed == this && !recentlyPlaying()) {
                        showStatus(newStep + "…");
                    }
                });
            }
        }

        @Override
        public void onVideoConfig(String mime, byte[] csd0, byte[] csd1) throws IOException {
            step = "Starting the " + mime + " decoder";
            MediaFormat format = MediaFormat.createVideoFormat(mime, 1920, 1080);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));
            if (csd1 != null) {
                format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1));
            }
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            if (Build.VERSION.SDK_INT >= 30) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, target, null, 0);
            codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            codec.start();
        }

        @Override
        public void onAccessUnit(byte[] data, int length, long ptsUs, boolean keyFrame) {
            long giveUpAt = System.currentTimeMillis() + INPUT_GIVE_UP_MS;
            while (!stopping) {
                int index = codec.dequeueInputBuffer(INPUT_WAIT_US);
                drainPictures();
                if (index >= 0) {
                    ByteBuffer buffer = codec.getInputBuffer(index);
                    buffer.clear();
                    if (length <= buffer.remaining()) {
                        buffer.put(data, 0, length);
                        codec.queueInputBuffer(index, 0, length, ptsUs, 0);
                    } else {
                        codec.queueInputBuffer(index, 0, 0, ptsUs, 0);
                    }
                    break;
                }
                if (System.currentTimeMillis() > giveUpAt) {
                    break;
                }
            }
            drainPictures();
        }

        /** Shows every finished picture immediately, so nothing builds up into a delay. */
        private void drainPictures() {
            while (true) {
                int index = codec.dequeueOutputBuffer(info, 0);
                if (index >= 0) {
                    codec.releaseOutputBuffer(index, true);
                    if (lastPictureAt == 0) {
                        handler.post(() -> {
                            if (feed == this) {
                                showStatus(null);
                            }
                        });
                    }
                    lastPictureAt = System.currentTimeMillis();
                    lastGoodAt = lastPictureAt;
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    return;
                }
            }
        }

        private void releaseCodec() {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                    // The decoder may already have failed.
                }
                codec.release();
                codec = null;
            }
        }
    }
}
