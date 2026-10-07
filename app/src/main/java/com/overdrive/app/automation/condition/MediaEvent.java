package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.logging.DaemonLogger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Publishes {@code mediaPlaying} (on/off) from the real media-session state.
 *
 * <p><b>Why not AudioManager.isMusicActive().</b> On this head unit some system component keeps
 * STREAM_MUSIC active permanently, so isMusicActive() read "on" all the time and a Loop
 * "until mediaPlaying = on" never retried. {@code dumpsys media_session} reports each app's own
 * PlaybackState (the same data the Play key is routed by), so a paused/never-started player reads
 * "off" regardless of unrelated audio streams. The daemon runs as the shell user, which may dump it.
 *
 * <p>Zero cost unless an enabled rule references the signal (see {@link ConditionalPoller}).
 * A failed or empty dump publishes nothing, so a transient error can't flip the state.
 */
public final class MediaEvent {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    static final long POLL_MS = 2000L;
    private static final long DUMP_TIMEOUT_MS = 3000L;
    /** android.media.session.PlaybackState.STATE_PLAYING */
    private static final int STATE_PLAYING = 3;

    private static final Pattern STATE = Pattern.compile("state=PlaybackState \\{state=(\\d+)");

    private static final ConditionalPoller poller = new ConditionalPoller(
            "media playing",
            POLL_MS,
            MediaEvent::referenced,
            MediaEvent::poll);

    private MediaEvent() {}

    private static boolean referenced() {
        return Automations.isEventReferenced(BydEvent.MEDIA_PLAYING);
    }

    public static void refresh() {
        poller.refresh();
    }

    private static void poll() {
        String dump = dumpMediaSessions();
        if (dump == null) return;
        Automations.update(BydEvent.MEDIA_PLAYING, isAnySessionPlaying(dump) ? "on" : "off");
    }

    /** True if any ACTIVE media session reports PlaybackState STATE_PLAYING. Pure; unit-tested. */
    static boolean isAnySessionPlaying(String dump) {
        if (dump == null) return false;
        boolean active = true; // sessions without an explicit flag are treated as active
        for (String line : dump.split("\n")) {
            String t = line.trim();
            if (t.startsWith("package=")) {
                active = true; // a new session block starts
            } else if (t.startsWith("active=")) {
                active = t.startsWith("active=true");
            } else if (active) {
                Matcher m = STATE.matcher(t);
                if (m.find() && Integer.parseInt(m.group(1)) == STATE_PLAYING) return true;
            }
        }
        return false;
    }

    /** Output of {@code dumpsys media_session}, or null on failure/timeout. */
    private static String dumpMediaSessions() {
        Process p = null;
        try {
            p = new ProcessBuilder("dumpsys", "media_session").redirectErrorStream(true).start();
            final InputStream in = p.getInputStream();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return null;
                }
            });
            String s = out.get(DUMP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return (s == null || s.isEmpty()) ? null : s;
        } catch (Exception e) {
            logger.warn("dumpsys media_session failed: " + e.getMessage());
            return null;
        } finally {
            if (p != null) p.destroyForcibly();
        }
    }
}
