package org.telegram.messenger.nexo;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** FORK MOD (Nexo): Quick Settings tile: tapping it sends the clipboard to the Mac. */
public class ClipTileService extends TileService {
    @Override
    public void onStartListening() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(Nexo.engine().isPaired() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel("Send to Mac");
        tile.updateTile();
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @SuppressWarnings("deprecation")
    @Override
    public void onClick() {
        Intent intent = new Intent(this, SendClipboardActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(intent);
        }
    }
}
