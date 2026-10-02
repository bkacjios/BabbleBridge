package com.bkacjios.babblebridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Brings the bridge back after a headset reboot if it was left on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (BridgeService.isEnabled(context)) {
            context.startForegroundService(new Intent(context, BridgeService.class));
        }
    }
}
