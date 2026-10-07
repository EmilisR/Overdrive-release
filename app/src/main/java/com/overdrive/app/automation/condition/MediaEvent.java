package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.logging.DaemonLogger;

import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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

    /** Attribute value meaning "any app" (what the bare, attribute-less signal also means). */
    static final String ANY = "any";

    /**
     * Ids stored by an earlier build that offered a fixed app list (before the installed-app
     * picker). Kept so those saved rules keep working: id -> substring of the package.
     */
    private static final Map<String, String> LEGACY_IDS = new LinkedHashMap<>();
    static {
        LEGACY_IDS.put("youtubeMusic", "youtube.music");
        LEGACY_IDS.put("spotify", "com.spotify.");
        LEGACY_IDS.put("appleMusic", "com.apple.android.music");
        LEGACY_IDS.put("amazonMusic", "com.amazon.mp3");
        LEGACY_IDS.put("deezer", "deezer.android");
        LEGACY_IDS.put("tidal", "com.aspiro.tidal");
        LEGACY_IDS.put("soundcloud", "com.soundcloud.android");
        LEGACY_IDS.put("vlc", "org.videolan.vlc");
        LEGACY_IDS.put("poweramp", "com.maxmpz.audioplayer");
    }

    private static final Pattern STATE = Pattern.compile("state=PlaybackState \\{state=(\\d+)");

    private static final ConditionalPoller poller = new ConditionalPoller(
            "media playing",
            POLL_MS,
            MediaEvent::referenced,
            MediaEvent::poll);

    private MediaEvent() {}

    private static boolean referenced() {
        return Automations.isEventReferenced(BydEvent.MEDIA_PLAYING)
                || !Automations.referencedEventsOfType(BydEvent.MEDIA_PLAYING.getType()).isEmpty();
    }

    public static void refresh() {
        poller.refresh();
    }

    private static volatile String lastPublished;

    private static final long MIN_SAMPLE_GAP_MS = 500L;
    private static Set<String> lastPlaying = new HashSet<>();
    private static Set<String> lastSessionPackages = new HashSet<>();
    private static long lastSampleMs = 0L;
    private static boolean haveSample = false;

    private static void poll() {
        sampleAndPublish(null, false);
    }

    /**
     * Read the media sessions (at most once per {@link #MIN_SAMPLE_GAP_MS}) and publish the result.
     * Shared by the 2s poller and by {@link #sampleNow}, so a flow action that reads the signal
     * never depends on the poller's scheduling having caught up.
     *
     * @param extra     an additional key to publish (a rule's own address), or null
     * @param allApps   also publish every app that currently has a session (editor hints)
     */
    private static synchronized void sampleAndPublish(EventData extra, boolean allApps) {
        long now = System.currentTimeMillis();
        if (!haveSample || now - lastSampleMs >= MIN_SAMPLE_GAP_MS) {
            String dump = dumpMediaSessions();
            if (dump != null) {
                lastPlaying = playingPackages(dump);
                lastSessionPackages = sessionPackages(dump);
                haveSample = true;
                lastSampleMs = now;
                String value = lastPlaying.isEmpty() ? "off" : "on";
                // Log only on change so the daemon log shows what this poller saw, without 2s spam.
                String sig = value + lastPlaying;
                if (!sig.equals(lastPublished)) {
                    logger.info("mediaPlaying -> " + value + " playing=" + lastPlaying
                            + " (" + summarize(dump) + ")");
                    lastPublished = sig;
                }
            }
        }
        if (!haveSample) return;
        Set<String> playing = lastPlaying;
        // Bare key = any app (what a saved address without an app attribute resolves to).
        Automations.update(BydEvent.MEDIA_PLAYING, playing.isEmpty() ? "off" : "on");
        // One key per app instance that rules actually use (mediaPlaying:app=<package|any>),
        // published every cycle so an app with no session yet reads "off", not null.
        for (EventData e : Automations.referencedEventsOfType(BydEvent.MEDIA_PLAYING.getType())) {
            publishApp(e, playing);
        }
        if (extra != null && BydEvent.MEDIA_PLAYING.getType().equals(extra.getType())) {
            publishApp(extra, playing);
        }
        if (allApps) {
            for (String pkg : lastSessionPackages) {
                publishApp(new EventData(BydEvent.MEDIA_PLAYING.getType(), Map.of("app", pkg)), playing);
            }
            publishApp(new EventData(BydEvent.MEDIA_PLAYING.getType(), Map.of("app", ANY)), playing);
        }
    }

    private static void publishApp(EventData e, Set<String> playing) {
        String app = e.getVariables().get("app");
        Automations.update(e, appPlaying(playing, app) ? "on" : "off");
    }

    /**
     * Refresh the signal right now for a flow action (Loop / Wait Until / If) that is about to read
     * {@code key}. Makes those actions independent of the background poller: even if it has not
     * started, been parked, or is up to 2s behind, the value they compare is current. Rate-limited,
     * so a tight loop costs at most ~2 dumps/s.
     */
    public static void sampleNow(EventData key) {
        try {
            sampleAndPublish(key, false);
        } catch (Throwable t) {
            logger.warn("mediaPlaying sampleNow failed: " + t.getMessage());
        }
    }

    /**
     * Publish live values for the editor's "reads X right now" hint, including every app that has a
     * media session, even while no rule references the signal yet (the poller is parked then).
     */
    public static void seedForEditor() {
        try {
            sampleAndPublish(null, true);
        } catch (Throwable t) {
            logger.warn("mediaPlaying seedForEditor failed: " + t.getMessage());
        }
    }

    /** All packages that have a media session (any state). Pure; unit-tested. */
    static Set<String> sessionPackages(String dump) {
        Set<String> out = new HashSet<>();
        if (dump == null) return out;
        for (String line : dump.split("\n")) {
            String t = line.trim();
            if (t.startsWith("package=")) out.add(t.substring(8).trim());
        }
        return out;
    }

    /**
     * Whether {@code app} (an installed package, "any"/null for any app, or a legacy fixed-list
     * id) is among the packages currently playing.
     */
    static boolean appPlaying(Set<String> playingPackages, String app) {
        if (app == null || app.isEmpty() || ANY.equals(app)) return !playingPackages.isEmpty();
        String legacy = LEGACY_IDS.get(app);
        for (String pkg : playingPackages) {
            if (legacy != null ? pkg.contains(legacy) : pkg.equals(app)) return true;
        }
        return false;
    }

    /** "pkg:state, pkg:state" for each session, for the change log line. */
    static String summarize(String dump) {
        StringBuilder sb = new StringBuilder();
        String pkg = "?";
        for (String line : dump.split("\n")) {
            String t = line.trim();
            if (t.startsWith("package=")) {
                pkg = t.substring(8).trim();
            } else {
                Matcher m = STATE.matcher(t);
                if (m.find()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(pkg).append(':').append(m.group(1));
                }
            }
        }
        return sb.length() == 0 ? "no sessions" : sb.toString();
    }

    /** True if any ACTIVE media session reports PlaybackState STATE_PLAYING. Pure; unit-tested. */
    static boolean isAnySessionPlaying(String dump) {
        return !playingPackages(dump).isEmpty();
    }

    /** Packages of ACTIVE sessions whose PlaybackState is STATE_PLAYING. Pure; unit-tested. */
    static Set<String> playingPackages(String dump) {
        Set<String> out = new HashSet<>();
        if (dump == null) return out;
        String pkg = "?";
        boolean active = true; // sessions without an explicit flag are treated as active
        for (String line : dump.split("\n")) {
            String t = line.trim();
            if (t.startsWith("package=")) {
                pkg = t.substring(8).trim();
                active = true; // a new session block starts
            } else if (t.startsWith("active=")) {
                active = t.startsWith("active=true");
            } else if (active) {
                Matcher m = STATE.matcher(t);
                if (m.find() && Integer.parseInt(m.group(1)) == STATE_PLAYING) out.add(pkg);
            }
        }
        return out;
    }

    /** Output of {@code dumpsys media_session}, or null on failure/timeout. */
    private static String dumpMediaSessions() {
        Process p = null;
        try {
            // Via sh like the other daemon shell calls, so PATH/dumpsys resolution matches them.
            p = new ProcessBuilder("sh", "-c", "dumpsys media_session").redirectErrorStream(true).start();
            final InputStream in = p.getInputStream();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return null;
                }
            });
            String s = out.get(DUMP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            // A genuine dump starts "MEDIA SESSION SERVICE"; anything else (shell error text,
            // truncated output) must not be read as "nothing playing".
            return (s == null || !s.contains("MEDIA SESSION")) ? null : s;
        } catch (Exception e) {
            logger.warn("dumpsys media_session failed: " + e.getMessage());
            return null;
        } finally {
            if (p != null) p.destroyForcibly();
        }
    }
}
