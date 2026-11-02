package za.schoolstreet.cameras;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/** Plays a short two-tone alarm, generated in code so the app needs no sound files. */
final class AlarmTone {

    private static final int RATE = 22050;
    private static final int SECONDS = 4;
    private static final int LOW_HZ = 880;
    private static final int HIGH_HZ = 1320;
    private static final int TONE_MS = 300;

    private final AudioManager audio;
    private final short[] samples;
    private AudioTrack track;

    AlarmTone(Context context) {
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        samples = new short[RATE * SECONDS];
        double phase = 0;
        int toneLength = RATE * TONE_MS / 1000;
        for (int i = 0; i < samples.length; i++) {
            int frequency = (i / toneLength) % 2 == 0 ? LOW_HZ : HIGH_HZ;
            phase += 2 * Math.PI * frequency / RATE;
            samples[i] = (short) (Math.sin(phase) * Short.MAX_VALUE * 0.9);
        }
    }

    synchronized void play() {
        stop();
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(samples.length * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        track.write(samples, 0, samples.length);
        track.play();
    }

    synchronized void stop() {
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
                // It had already finished.
            }
            track.release();
            track = null;
        }
    }
}
