package com.overdrive.app.automation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.condition.Conditions;
import com.overdrive.app.automation.condition.EventData;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.BeforeClass;

import org.junit.Test;

import java.util.Map;
import java.util.UUID;

/** The {@code mediaPlaying} signal: registered, and derived from dumpsys media_session output. */
public class MediaPlayingSignalTest {

    private static final String PAUSED =
            "Sessions Stack - have 1 sessions:\n"
          + "    com.google.android.apps.youtube.music/YouTube Music media session (userId=0)\n"
          + "      package=com.google.android.apps.youtube.music\n"
          + "      active=true\n"
          + "      state=PlaybackState {state=2, position=0, buffered position=0, speed=1.0}\n";

    private String automationId;

    @BeforeClass
    public static void muteAndroidLog() {
        com.overdrive.app.logging.DaemonLogger.Config cfg =
                new com.overdrive.app.logging.DaemonLogger.Config();
        cfg.enableConsoleLog = false;
        cfg.enableFileLog = false;
        cfg.enableStdoutLog = true;
        com.overdrive.app.logging.DaemonLogger.configure(cfg);
    }

    @After
    public void cleanup() {
        if (automationId != null) Automations.deleteAutomation(automationId);
    }

    @Test
    public void rulesUsingSpecificAppsAreDiscoveredForThePoller() throws Exception {
        automationId = UUID.randomUUID().toString();
        JSONObject json = new JSONObject()
                .put("triggers", new JSONArray()
                        .put(new JSONObject().put("type", "mediaPlaying")
                                .put("variables", new JSONObject().put("app", "com.spotify.music"))))
                .put("conditions", new JSONArray())
                .put("delay", 0)
                .put("actions", new JSONArray()
                        .put(new JSONObject().put("type", "setVariable")
                                .put("variables", new JSONObject().put("name", "x").put("value", "${signal:mediaPlaying:app=app.rvx.android.apps.youtube.music}"))))
                .put("name", "media app probe")
                .put("disabled", false);
        assertTrue(Automations.updateAutomation(automationId, json));
        java.util.Set<EventData> found = Automations.referencedEventsOfType("mediaPlaying");
        assertTrue(found.contains(new EventData("mediaPlaying", Map.of("app", "com.spotify.music"))));
        assertTrue(found.contains(new EventData("mediaPlaying", Map.of("app", "app.rvx.android.apps.youtube.music"))));
    }

    @Test
    public void triggerWithoutAppAttributeStillLoadsAsAnyApp() throws Exception {
        // Saved before the app attribute existed (or with no app chosen): must not be dropped.
        JSONObject trigger = new JSONObject().put("type", "mediaPlaying").put("variables", new JSONObject());
        EventData e = new Conditions().getCondition("mediaPlaying").eventData(trigger);
        assertNotNull(e);
        assertTrue(e.getVariables().isEmpty());
    }

    @Test
    public void conditionIsRegistered() {
        assertNotNull(new Conditions().getCondition("mediaPlaying"));
    }

    @Test
    public void playingSessionReadsOn() throws Exception {
        assertTrue(isPlaying(PAUSED.replace("state=2", "state=3")));
    }

    @Test
    public void pausedBufferingOrEmptyReadOff() throws Exception {
        assertFalse(isPlaying(PAUSED));
        assertFalse(isPlaying(PAUSED.replace("state=2", "state=6")));
        assertFalse(isPlaying("Sessions Stack - have 0 sessions:\n"));
        assertFalse(isPlaying(null));
    }

    @Test
    public void inactiveSessionIsIgnoredAndLaterSessionStillCounts() throws Exception {
        String stale = PAUSED.replace("state=2", "state=3").replace("active=true", "active=false");
        assertFalse(isPlaying(stale));
        assertTrue(isPlaying(stale + PAUSED.replace("state=2", "state=3")));
    }

    @Test
    public void appSelectionMatchesOnlyThatPackage() throws Exception {
        String rvxPlaying = PAUSED.replace("com.google.android.apps.youtube.music", "app.rvx.android.apps.youtube.music")
                .replace("state=2", "state=3");
        String spotifyPaused = "    x\n      package=com.spotify.music\n      active=true\n"
                + "      state=PlaybackState {state=2, position=0}\n";
        String dump = rvxPlaying + spotifyPaused;
        assertTrue(appPlaying(dump, "app.rvx.android.apps.youtube.music")); // the selected installed app
        assertTrue(appPlaying(dump, "any"));
        assertTrue(appPlaying(dump, null));                                 // bare/legacy signal = any app
        assertFalse(appPlaying(dump, "com.spotify.music"));                 // paused
        assertFalse(appPlaying(dump, "com.google.android.apps.youtube.music")); // different build, not playing
        assertFalse(appPlaying(spotifyPaused + PAUSED, "app.rvx.android.apps.youtube.music"));
        assertTrue(appPlaying(dump, "youtubeMusic"));                       // id from an earlier build
        assertFalse(appPlaying(dump, "spotify"));
    }

    @Test
    public void sessionPackagesListsEverySessionRegardlessOfState() throws Exception {
        java.lang.reflect.Method m = Class.forName("com.overdrive.app.automation.condition.MediaEvent")
                .getDeclaredMethod("sessionPackages", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<String> pkgs = (java.util.Set<String>) m.invoke(null,
                PAUSED + "      package=com.spotify.music\n      active=false\n");
        assertTrue(pkgs.contains("com.google.android.apps.youtube.music"));
        assertTrue(pkgs.contains("com.spotify.music"));
    }

    @SuppressWarnings("unchecked")
    private static boolean appPlaying(String dump, String app) throws Exception {
        Class<?> c = Class.forName("com.overdrive.app.automation.condition.MediaEvent");
        java.lang.reflect.Method pp = c.getDeclaredMethod("playingPackages", String.class);
        pp.setAccessible(true);
        java.util.Set<String> playing = (java.util.Set<String>) pp.invoke(null, dump);
        java.lang.reflect.Method ap = c.getDeclaredMethod("appPlaying", java.util.Set.class, String.class);
        ap.setAccessible(true);
        return (Boolean) ap.invoke(null, playing, app);
    }

    private static boolean isPlaying(String dump) throws Exception {
        java.lang.reflect.Method m = Class.forName("com.overdrive.app.automation.condition.MediaEvent")
                .getDeclaredMethod("isAnySessionPlaying", String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, dump);
    }
}
