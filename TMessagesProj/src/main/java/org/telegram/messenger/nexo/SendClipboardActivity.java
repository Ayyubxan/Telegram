package org.telegram.messenger.nexo;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * FORK MOD (Nexo): invisible window. Android 10+ does not let a background app read the clipboard,
 * so it takes focus for a moment, reads it and closes right away.
 *
 * Sources: the Share menu (text and files), the text-selection menu, the Quick Settings tile,
 * the notification button and auto mode.
 */
public class SendClipboardActivity extends Activity {
    public static final String EXTRA_AUTO = "auto";

    private boolean handled = false;

    private boolean auto() {
        Intent i = getIntent();
        return i != null && i.getBooleanExtra(EXTRA_AUTO, false);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            handled = true;
            List<Uri> streams = streams(intent);
            if (!streams.isEmpty()) {
                sendFiles(streams, false);
            } else {
                String text = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (text != null && !text.isEmpty()) send(text, true);
                finish();
            }
        } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            handled = true;
            CharSequence cs = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            String text = cs == null ? null : cs.toString();
            if (text != null && !text.isEmpty()) send(text, true);
            finish();
        }
    }

    @SuppressWarnings("deprecation")
    private static List<Uri> streams(Intent intent) {
        try {
            if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
                ArrayList<Uri> list;
                if (Build.VERSION.SDK_INT >= 33) {
                    list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
                } else {
                    list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                }
                return list != null ? list : Collections.<Uri>emptyList();
            }
            Uri uri;
            if (Build.VERSION.SDK_INT >= 33) {
                uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            }
            return uri != null ? Collections.singletonList(uri) : Collections.<Uri>emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || handled || isFinishing()) return;
        handled = true;
        boolean auto = auto();
        SyncEngine engine = Nexo.engine();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = null;
        try {
            clip = cm == null ? null : cm.getPrimaryClip();
        } catch (Exception ignore) {
        }
        ClipData.Item item = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0) : null;
        ClipDescription desc = clip == null ? null : clip.getDescription();
        boolean sensitive = false;
        if (Build.VERSION.SDK_INT >= 33 && desc != null && desc.getExtras() != null) {
            sensitive = desc.getExtras().getBoolean("android.content.extra.IS_SENSITIVE", false);
        }

        // Image (e.g. "Copy image" in a browser or a screenshot)
        Uri uri = item == null ? null : item.getUri();
        boolean isImage = false;
        if (uri != null) {
            String mime = desc != null && desc.getMimeTypeCount() > 0 ? desc.getMimeType(0) : null;
            if (mime != null && mime.startsWith("image/")) {
                isImage = true;
            } else {
                String resolved = null;
                try {
                    resolved = getContentResolver().getType(uri);
                } catch (Exception ignore) {
                }
                isImage = resolved != null && resolved.startsWith("image/");
            }
        }
        if (isImage) {
            boolean skip = auto && (sensitive || !engine.prefs.isImagesEnabled()
                || uri.equals(engine.getLastAppliedUri()) || uri.equals(engine.getLastSentUri()));
            if (skip) finish();
            else sendFiles(Collections.singletonList(uri), true);
            return;
        }

        CharSequence cs = item == null ? null : item.coerceToText(this);
        String text = cs == null ? null : cs.toString();
        if (text == null || text.isEmpty()) {
            if (!auto) toast("Clipboard is empty");
        } else if (auto && (sensitive || text.equals(engine.getLastAppliedText()) || text.equals(engine.getLastSentText()))) {
            // Auto mode: do not re-send passwords, text that came from the Mac or text already sent
        } else {
            send(text, !auto);
        }
        finish();
    }

    private boolean ensureReady() {
        if (!Nexo.engine().isPaired()) {
            toast("Pair with your Mac first");
            return false;
        }
        if (!SyncService.isRunning()) SyncService.start(this);
        return true;
    }

    private void send(String text, boolean showToast) {
        if (!ensureReady()) return;
        Nexo.engine().sendLocalText(text);
        if (showToast) toast("Sent to Mac");
    }

    /** The read permission lasts until this window closes — read first, then finish. */
    private void sendFiles(final List<Uri> uris, final boolean asClip) {
        if (!ensureReady()) {
            finish();
            return;
        }
        final boolean auto = auto();
        NexoFiles.readAsync(this, uris, (files, skipped) -> {
            for (LocalFile f : files) {
                Nexo.engine().sendLocalFile(f, asClip, asClip ? uris.get(0) : null);
            }
            if (!auto) {
                if (!skipped.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (String s : skipped) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(s);
                    }
                    toast("Not sent (larger than " + NexoFiles.sizeLabel(NexoFiles.MAX_BYTES) + " or unreadable): " + sb);
                } else if (asClip) {
                    toast("Image sent to Mac");
                } else {
                    toast(files.size() == 1 ? "Sent to Mac" : files.size() + " files sent to Mac");
                }
            }
            finish();
        });
    }

    private void toast(String s) {
        Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
