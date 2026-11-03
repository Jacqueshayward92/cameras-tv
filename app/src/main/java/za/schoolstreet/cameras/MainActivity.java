package za.schoolstreet.cameras;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
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
    private static final int SAFE_SIDE_DP = 48;
    private static final int SAFE_TOP_DP = 27;
    private static final int NOTICE_COLOR = 0xCC202020;
    private static final int PERSON_COLOR = 0xE6B00020;

    private final AlertPolicy alertPolicy = new AlertPolicy();
    private TextView banner;
    private boolean personAlertShowing;
    private final Runnable hideBanner = () -> {
        banner.setVisibility(View.GONE);
        personAlertShowing = false;
    };
    private AlertServer alertServer;
    private AlarmTone alarmTone;
    private boolean linkLoginRejected;
    private volatile boolean recorderSeen;
    private AlertDialog buttonDialog;
    private boolean backFromAccessibilitySettings;

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
        surface.setContentDescription("Camera grid");
        root.addView(surface);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(24), dp(16), dp(24), dp(16));
        status.setMaxWidth(dp(560));
        status.setBackground(panel(NOTICE_COLOR));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE);
        root.addView(status, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // A small corner label inside the area every TV is sure to show, covering as little video as possible.
        banner = new TextView(this);
        banner.setTextColor(Color.WHITE);
        banner.setTextSize(18);
        banner.setTypeface(Typeface.DEFAULT_BOLD);
        banner.setPadding(dp(16), dp(10), dp(16), dp(10));
        banner.setMaxLines(3);
        banner.setEllipsize(TextUtils.TruncateAt.END);
        banner.setMaxWidth(getResources().getDisplayMetrics().widthPixels * 45 / 100);
        banner.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        banner.setVisibility(View.GONE);
        FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        corner.setMargins(dp(SAFE_SIDE_DP), dp(SAFE_TOP_DP), dp(SAFE_SIDE_DP), dp(SAFE_TOP_DP));
        root.addView(banner, corner);
        setContentView(root);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable panel(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(8));
        return shape;
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
        hideBanner.run();
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
        String message = RemoteButtonService.isSet(this) ? notice : notice + "\nPress OK to set a remote button";
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
        showLabel("Person: " + where, PERSON_COLOR, BANNER_MS);
        personAlertShowing = true;
        if (action == AlertPolicy.Action.SOUND) {
            alarmTone.play();
        }
    }

    /** Grey for information. Red is kept for a person, so the two can't be confused at a glance. */
    private void showBanner(String message, int forMs) {
        showLabel(message, NOTICE_COLOR, forMs);
        personAlertShowing = false;
    }

    private void showLabel(String message, int color, int forMs) {
        banner.setBackground(panel(color));
        banner.setText(message);
        banner.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideBanner);
        handler.postDelayed(hideBanner, forMs);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
        boolean shortPress = event.isTracking() && !event.isCanceled()
                && (event.getFlags() & KeyEvent.FLAG_CANCELED_LONG_PRESS) == 0;
        if (BuildConfig.ALERTS && ok && shortPress) {
            if (personAlertShowing) {
                alertPolicy.mute(System.currentTimeMillis());
                if (alarmTone != null) {
                    alarmTone.stop();
                }
                showBanner("Alert sound off for 30 minutes", NOTICE_MS);
            } else {
                showMenu();
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void showMenu() {
        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setItems(new String[] {"Remote button", "Recorder login"}, (dialog, which) -> {
                    if (which == 0) {
                        showButtonSetup();
                    } else {
                        showLogin();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        hideSystemBars();
        if (hasLogin()) {
            startFeed();
            startAlerts();
            if (backFromAccessibilitySettings) {
                backFromAccessibilitySettings = false;
                if (RemoteButtonService.isOn()) {
                    showButtonSetup();
                } else {
                    showBanner("The remote button isn't switched on yet", NOTICE_MS);
                }
            }
        } else {
            showLogin();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (buttonDialog != null) {
            buttonDialog.dismiss();
        }
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
        user.setSingleLine(true);
        user.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        user.setText(prefs.getString("user", "admin"));
        EditText password = new EditText(this);
        password.setSingleLine(true);
        password.setImeOptions(EditorInfo.IME_ACTION_DONE);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), 0);
        form.addView(fieldLabel("Username"));
        form.addView(user);
        form.addView(fieldLabel("Password"));
        form.addView(password);

        boolean canGoBack = hasLogin();
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Recorder login")
                .setMessage("Saved on this TV, so you only enter it once.")
                .setView(form)
                .setCancelable(canGoBack)
                .setPositiveButton("Save", null);
        if (canGoBack) {
            builder.setNegativeButton("Cancel", (dialog, which) -> resumeAfterLogin())
                    .setOnCancelListener(dialog -> resumeAfterLogin());
        }
        loginDialog = builder.create();
        loginDialog.show();

        Button save = loginDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        save.setOnClickListener(view -> {
            if (user.getText().toString().trim().isEmpty()) {
                user.setError("Enter the username");
                user.requestFocus();
            } else if (password.getText().toString().isEmpty()) {
                password.setError("Enter the password");
                password.requestFocus();
            } else {
                prefs.edit()
                        .putString("user", user.getText().toString().trim())
                        .putString("password", password.getText().toString())
                        .apply();
                linkLoginRejected = false;
                loginDialog.dismiss();
                stopAlerts();
                resumeAfterLogin();
            }
        });
        password.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save.performClick();
                return true;
            }
            return false;
        });
        password.requestFocus();
    }

    private void resumeAfterLogin() {
        startFeed();
        startAlerts();
    }

    private void showButtonSetup() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("Remote button");
        boolean on = RemoteButtonService.isOn();
        if (on) {
            String chosen = RemoteButtonService.chosenName(this);
            builder.setMessage("Press the button that should open this app. It will stop doing its usual job."
                            + (chosen == null ? "" : "\n\nNow using: " + chosen))
                    .setNegativeButton("Cancel", null);
            if (chosen != null) {
                builder.setNeutralButton("Remove", (dialog, which) -> RemoteButtonService.clear(this));
            }
        } else {
            builder.setMessage("In the next screen, choose Cameras Alert remote button and switch it on."
                            + " The app only looks for the one button you choose.")
                    .setPositiveButton("Open settings", (dialog, which) -> openAccessibilitySettings())
                    .setNegativeButton("Cancel", null);
        }
        buttonDialog = builder.create();
        buttonDialog.setOnDismissListener(dialog -> {
            RemoteButtonService.stopLearning();
            buttonDialog = null;
        });
        buttonDialog.show();
        if (on) {
            RemoteButtonService.learn(() -> {
                if (buttonDialog != null) {
                    buttonDialog.dismiss();
                }
                showBanner(RemoteButtonService.chosenName(this) + " now opens this app", NOTICE_MS);
            });
        }
    }

    private void openAccessibilitySettings() {
        backFromAccessibilitySettings = true;
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private TextView fieldLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(14);
        label.setPadding(0, dp(12), 0, 0);
        return label;
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
                report("Can't show the cameras right now.\n(" + step + ": " + e.getMessage() + ")", true);
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
