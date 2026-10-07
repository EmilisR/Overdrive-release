package com.overdrive.app.automation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.condition.Conditions;

import org.junit.Test;

/** The {@code mediaPlaying} signal: registered, and derived from dumpsys media_session output. */
public class MediaPlayingSignalTest {

    private static final String PAUSED =
            "Sessions Stack - have 1 sessions:\n"
          + "    com.google.android.apps.youtube.music/YouTube Music media session (userId=0)\n"
          + "      package=com.google.android.apps.youtube.music\n"
          + "      active=true\n"
          + "      state=PlaybackState {state=2, position=0, buffered position=0, speed=1.0}\n";

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

    private static boolean isPlaying(String dump) throws Exception {
        java.lang.reflect.Method m = Class.forName("com.overdrive.app.automation.condition.MediaEvent")
                .getDeclaredMethod("isAnySessionPlaying", String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, dump);
    }
}
