package com.tango.phone;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings entry point works without an always-listening microphone. */
public final class TangoTileService extends TileService {
    @Override public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel("Tango");
            if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle("Speak a task");
            tile.updateTile();
        }
    }

    // API 30-33 require the Intent overload; the PendingIntent overload starts at API 34.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Override public void onClick() {
        super.onClick();
        Runnable launch = () -> {
            Intent intent = PhoneController.listenIntent(this);
            if (Build.VERSION.SDK_INT >= 34) {
                android.os.Bundle options = null;
                if (Build.VERSION.SDK_INT >= 35) {
                    options = ActivityOptions.makeBasic().setPendingIntentCreatorBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
                }
                PendingIntent pending = PendingIntent.getActivity(this, 91, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, options);
                startActivityAndCollapse(pending);
            } else startActivityAndCollapse(intent);
        };
        if (isLocked()) unlockAndRun(launch);
        else launch.run();
    }
}
