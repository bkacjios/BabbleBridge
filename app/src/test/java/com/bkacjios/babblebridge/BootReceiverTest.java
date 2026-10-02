package com.bkacjios.babblebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Objects;

@RunWith(RobolectricTestRunner.class)
public class BootReceiverTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void startsBridgeAfterBootWhenEnabled() {
        BridgeService.setEnabled(app, true);
        new BootReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
        Intent started = shadowOf(app).getNextStartedService();
        assertEquals(BridgeService.class.getName(), Objects.requireNonNull(started.getComponent()).getClassName());
    }

    @Test
    public void staysOffAfterBootWhenDisabled() {
        BridgeService.setEnabled(app, false);
        new BootReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertNull(shadowOf(app).getNextStartedService());
    }

    @Test
    public void ignoresOtherBroadcasts() {
        BridgeService.setEnabled(app, true);
        new BootReceiver().onReceive(app, new Intent(Intent.ACTION_SCREEN_ON));
        assertNull(shadowOf(app).getNextStartedService());
    }

    @Test
    public void receiverIsRegisteredForBootCompleted() {
        Intent boot = new Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(app.getPackageName());
        assertEquals(1, app.getPackageManager().queryBroadcastReceivers(boot, 0).size());
    }
}
