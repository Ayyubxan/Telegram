package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;

// FORK MOD (love effect): a celebratory overlay shown when a "Sevaman" message is opened.
// Balloons and hearts float up the screen, fireworks burst, and a short jingle plays —
// for ~10 seconds. Self-contained: feed it the chat's FireworksOverlay to reuse the bursts.
public class LoveEffectOverlay extends View {

    private static final String[] EMOJIS = {"❤️", "💕", "💖", "💗", "💝", "🎈", "🎈", "💞"};

    private final ArrayList<Particle> particles = new ArrayList<>();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private long lastUpdateTime;
    private long endTime;
    private boolean running;

    private FireworksOverlay fireworksOverlay;
    private long lastFireworks;

    public LoveEffectOverlay(Context context) {
        super(context);
        textPaint.setTextAlign(Paint.Align.CENTER);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private class Particle {
        String emoji;
        float x, y;
        float speed;      // upward px per 16ms
        float size;       // dp
        float swayAmp;
        float swayPhase;
        float swaySpeed;
        float baseX;
        int alpha = 255;

        void draw(Canvas canvas) {
            textPaint.setTextSize(dp(size));
            textPaint.setAlpha(alpha);
            canvas.drawText(emoji, x, y, textPaint);
        }

        boolean update(int dt) {
            float c = dt / 16.0f;
            y -= speed * c;
            swayPhase += swaySpeed * c;
            x = baseX + (float) Math.sin(swayPhase) * swayAmp;
            // fade out in the top fifth of the screen
            float fadeStart = getH() * 0.2f;
            if (y < fadeStart) {
                alpha = (int) Math.max(0, 255 * (y / fadeStart));
            }
            return y < -dp(size) || alpha <= 0;
        }
    }

    private int getH() {
        int h = getMeasuredHeight();
        if (h == 0 && getParent() instanceof View) {
            h = ((View) getParent()).getHeight();
        }
        return h == 0 ? dp(600) : h;
    }

    private int getW() {
        int w = getMeasuredWidth();
        if (w == 0 && getParent() instanceof View) {
            w = ((View) getParent()).getWidth();
        }
        return w == 0 ? dp(360) : w;
    }

    private Particle createParticle() {
        Particle p = new Particle();
        p.emoji = EMOJIS[Utilities.random.nextInt(EMOJIS.length)];
        p.size = 18 + Utilities.random.nextFloat() * 18;
        p.baseX = dp(20) + Utilities.random.nextFloat() * Math.max(1, getW() - dp(40));
        p.x = p.baseX;
        p.y = getH() + dp(20) + Utilities.random.nextFloat() * dp(120);
        p.speed = dp(2.2f) + Utilities.random.nextFloat() * dp(2.8f);
        p.swayAmp = dp(8) + Utilities.random.nextFloat() * dp(22);
        p.swaySpeed = 0.04f + Utilities.random.nextFloat() * 0.06f;
        p.swayPhase = Utilities.random.nextFloat() * 6.28f;
        return p;
    }

    public boolean isRunning() {
        return running;
    }

    public void start(FireworksOverlay fireworksOverlay, long durationMs) {
        this.fireworksOverlay = fireworksOverlay;
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        running = true;
        endTime = SystemClock.elapsedRealtime() + durationMs;
        lastUpdateTime = SystemClock.elapsedRealtime();
        lastFireworks = 0;
        for (int i = 0; i < 14; i++) {
            particles.add(createParticle());
        }
        try {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Exception ignore) {
        }
        playTwinkles(durationMs);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long newTime = SystemClock.elapsedRealtime();
        int dt = (int) (newTime - lastUpdateTime);
        lastUpdateTime = newTime;
        if (dt > 18) {
            dt = 16;
        }
        boolean emitting = newTime < endTime;

        // keep firing heart bursts every ~1.3s while emitting
        if (emitting && fireworksOverlay != null && newTime - lastFireworks > 1300) {
            lastFireworks = newTime;
            if (!fireworksOverlay.isStarted()) {
                fireworksOverlay.startHearts();
            }
        }

        // top up particles while emitting
        if (emitting) {
            int target = 18;
            while (particles.size() < target) {
                particles.add(createParticle());
            }
        }

        for (int a = 0; a < particles.size(); a++) {
            Particle p = particles.get(a);
            p.draw(canvas);
            if (p.update(dt)) {
                particles.remove(a);
                a--;
            }
        }

        if (!particles.isEmpty() || emitting) {
            invalidate();
        } else {
            running = false;
            setLayerType(View.LAYER_TYPE_NONE, null);
        }
    }

    private static final int SAMPLE_RATE = 44100;

    // A gentle bell-like twinkle, played a few times across the effect (with silence between),
    // synthesized as soft sine arpeggios — pleasant, no audio asset needed.
    private void playTwinkles(long durationMs) {
        final short[] pcm = buildTwinkle();
        if (pcm == null) {
            return;
        }
        // play at the start, then twice more spaced out within the duration
        long[] offsets = {0, 3300, 6600};
        for (long off : offsets) {
            if (off >= durationMs) {
                continue;
            }
            AndroidUtilities.runOnUIThread(() -> playPcm(pcm), off);
        }
    }

    private void playPcm(short[] pcm) {
        new Thread(() -> {
            AudioTrack track = null;
            try {
                int bytes = pcm.length * 2;
                track = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        bytes, AudioTrack.MODE_STATIC);
                track.write(pcm, 0, pcm.length);
                track.play();
                // let it finish before releasing
                Thread.sleep((long) (1000L * pcm.length / SAMPLE_RATE) + 200);
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (track != null) {
                    try {
                        track.release();
                    } catch (Exception ignore) {
                    }
                }
            }
        }).start();
    }

    // Build a cheerful ascending major arpeggio of overlapping bell notes (~1.4s).
    private short[] buildTwinkle() {
        try {
            // C5 E5 G5 C6 E6 G6 — a bright major arpeggio
            double[] freqs = {523.25, 659.25, 783.99, 1046.50, 1318.51, 1567.98};
            double spacing = 0.13;  // seconds between note onsets
            double ring = 0.65;     // seconds each note rings out
            int total = (int) (SAMPLE_RATE * (spacing * freqs.length + ring));
            float[] mix = new float[total];
            int noteLen = (int) (SAMPLE_RATE * ring);
            for (int k = 0; k < freqs.length; k++) {
                int start = (int) (SAMPLE_RATE * spacing * k);
                double f = freqs[k];
                for (int i = 0; i < noteLen && start + i < total; i++) {
                    double t = (double) i / SAMPLE_RATE;
                    double env = Math.exp(-4.0 * t);                 // bell-like decay
                    double attack = Math.min(1.0, t / 0.006);        // short fade-in (no click)
                    double wave = Math.sin(2 * Math.PI * f * t)
                            + 0.45 * Math.sin(2 * Math.PI * 2 * f * t)
                            + 0.20 * Math.sin(2 * Math.PI * 3 * f * t);
                    mix[start + i] += (float) (env * attack * wave / 1.65);
                }
            }
            short[] out = new short[total];
            for (int i = 0; i < total; i++) {
                float s = mix[i] * 0.5f; // headroom
                if (s > 1f) s = 1f;
                if (s < -1f) s = -1f;
                out[i] = (short) (s * 32767);
            }
            return out;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }
}
