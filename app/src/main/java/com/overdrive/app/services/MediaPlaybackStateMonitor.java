package com.overdrive.app.services;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.overdrive.app.util.DaemonHttpClient;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App-process media-playback watcher. The daemon (UID 2000, synthetic Context) can't read
 * {@link AudioManager#isMusicActive()}, so this runs in the real app process and RELAYS each
 * on/off transition to the daemon via {@code POST /api/automations/event} as the whitelisted
 * {@code mediaPlaying} event — the same pattern as {@link CallStateMonitor}. Enables
 * "open YouTube Music, then loop Play until mediaPlaying = on".
 *
 * <p>Polls every {@link #POLL_MS} (cheap, one binder call) rather than registering a playback
 * callback, so it behaves identically on old head-unit builds. The relay dedupes consecutive
 * identical values; a failed POST does not latch the dedup, so it is retried next tick.
 * Singleton + idempotent {@link #start}.
 */
public final class MediaPlaybackStateMonitor {

    private static final String TAG = "MediaPlaybackMonitor";
    private static final long POLL_MS = 1500L;
    private static MediaPlaybackStateMonitor instance;

    private final Context appContext;
    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile String lastRelayed;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "media-state-relay");
        t.setDaemon(true);
        return t;
    });

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                relay(audio.isMusicActive() ? "on" : "off");
            } catch (Throwable t) {
                Log.w(TAG, "poll failed: " + t.getMessage());
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    private MediaPlaybackStateMonitor(Context ctx, AudioManager audio) {
        this.appContext = ctx.getApplicationContext();
        this.audio = audio;
    }

    /** Start the monitor (idempotent). */
    public static synchronized void start(Context ctx) {
        if (instance != null || ctx == null) return;
        AudioManager am = (AudioManager) ctx.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            Log.w(TAG, "no AudioManager");
            return;
        }
        MediaPlaybackStateMonitor m = new MediaPlaybackStateMonitor(ctx, am);
        instance = m;
        m.handler.post(m.tick);
        Log.i(TAG, "media-playback monitor started");
    }

    private void relay(String state) {
        if (state.equals(lastRelayed)) return;
        final String body = "{\"event\":\"mediaPlaying\",\"value\":\"" + state + "\"}";
        io.submit(() -> {
            HttpURLConnection conn = null;
            try {
                conn = DaemonHttpClient.open("/api/automations/event", "POST", 2000, 3000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) { os.write(body.getBytes()); }
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) lastRelayed = state; // latch only on success
                Log.i(TAG, "relayed mediaPlaying=" + state + " -> HTTP " + code);
            } catch (Throwable t) {
                Log.w(TAG, "relay failed: " + t.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }
}
