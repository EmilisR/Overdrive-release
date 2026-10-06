package com.overdrive.app.automation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.condition.BydEvent;
import com.overdrive.app.automation.condition.Conditions;

import org.junit.BeforeClass;
import org.junit.Test;

/** The relayed {@code mediaPlaying} signal: accepts only on/off, is stored, and is registered. */
public class MediaPlayingSignalTest {

    @BeforeClass
    public static void muteAndroidLog() {
        com.overdrive.app.logging.DaemonLogger.Config cfg =
                new com.overdrive.app.logging.DaemonLogger.Config();
        cfg.enableConsoleLog = false;
        cfg.enableFileLog = false;
        cfg.enableStdoutLog = true;
        com.overdrive.app.logging.DaemonLogger.configure(cfg);
    }

    @Test
    public void publishAcceptsOnOffAndStoresEvenWhileDisabled() {
        assertTrue(Automations.publishExternalEvent("mediaPlaying", "off"));
        assertNotNull("forceStore must seed the state map", Automations.getStateValue(BydEvent.MEDIA_PLAYING));
        assertTrue(Automations.publishExternalEvent("mediaPlaying", "on"));
    }

    @Test
    public void publishRejectsGarbage() {
        assertFalse(Automations.publishExternalEvent("mediaPlaying", "paused"));
        assertFalse(Automations.publishExternalEvent("mediaPlaying", null));
        assertFalse(Automations.publishExternalEvent("mediaPlaying", ""));
    }

    @Test
    public void conditionIsRegistered() {
        assertNotNull(new Conditions().getCondition("mediaPlaying"));
    }
}
