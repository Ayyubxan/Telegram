package org.telegram.messenger.nexo;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import org.telegram.messenger.FileLog;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FORK MOD (Nexo): routing, duplicate suppression and clipboard writing. State changes only on the {@link #handler} thread.
 */
public class SyncEngine {
    public static final int MAX_TEXT_BYTES = 1_000_000;
    public static final int BLE_MAX_BYTES = 256 * 1024;

    private final Context ctx;
    public final NexoPrefs prefs;
    public final Handler handler;
    private final Handler main = new Handler(Looper.getMainLooper());

    public final String deviceId;
    public final String deviceName;

    private volatile GroupKey key;

    public final LanTransport lan;
    public final BleTransport ble;
    public final InternetTransport internet;

    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private long lastClipTs = 0L;
    private Pending pending;
    private boolean active = false;
    private ConnectivityManager.NetworkCallback networkCallback;
    /** For writing received files to disk */
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private volatile SyncEvent lastEvent;

    /** The last text that came from the Mac and was written to the clipboard — so auto mode does not send it back */
    private volatile String lastAppliedText;
    private volatile long lastAppliedAt = 0L;
    private volatile String lastSentText;
    /** Same for images: the last clipboard URI received from the Mac and the last one sent from the phone */
    private volatile Uri lastAppliedUri;
    private volatile Uri lastSentUri;

    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private static class Pending {
        final byte[] sealed;
        final long ts;

        Pending(byte[] sealed, long ts) {
            this.sealed = sealed;
            this.ts = ts;
        }
    }

    public SyncEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        prefs = new NexoPrefs(this.ctx);
        HandlerThread thread = new HandlerThread("nexo-engine");
        thread.start();
        handler = new Handler(thread.getLooper());
        deviceId = prefs.deviceId;
        deviceName = Build.MODEL != null ? Build.MODEL : "Android";
        String b64 = prefs.getKeyB64();
        key = b64 == null ? null : GroupKey.fromBase64url(b64);
        lan = new LanTransport(this.ctx, this);
        ble = new BleTransport(this.ctx, this);
        internet = new InternetTransport(this);
    }

    public GroupKey getKey() {
        return key;
    }

    public boolean isPaired() {
        return key != null;
    }

    public SyncEvent getLastEvent() {
        return lastEvent;
    }

    public String getLastAppliedText() {
        return lastAppliedText;
    }

    public long getLastAppliedAt() {
        return lastAppliedAt;
    }

    public String getLastSentText() {
        return lastSentText;
    }

    public Uri getLastAppliedUri() {
        return lastAppliedUri;
    }

    public Uri getLastSentUri() {
        return lastSentUri;
    }

    public void addListener(Runnable l) {
        listeners.add(l);
    }

    public void removeListener(Runnable l) {
        listeners.remove(l);
    }

    public void changed() {
        main.post(() -> {
            for (Runnable l : listeners) {
                try {
                    l.run();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        });
    }

    // region Lifecycle (driven by SyncService)

    public void activate() {
        handler.post(() -> {
            active = true;
            registerNetworkCallback();
            applySettings();
        });
    }

    public void deactivate() {
        handler.post(() -> {
            active = false;
            unregisterNetworkCallback();
            lan.stop();
            ble.stop();
            internet.stop();
            changed();
        });
    }

    /** Engine thread */
    public void applySettings() {
        boolean on = active && key != null && prefs.isSyncEnabled();
        toggle(lan, on && prefs.isLanEnabled());
        toggle(ble, on && prefs.isBleEnabled());
        if (on && prefs.isInternetEnabled()) internet.start();
        else internet.stop();
        changed();
    }

    public void postApplySettings() {
        handler.post(this::applySettings);
    }

    private void toggle(LinkTransport t, boolean on) {
        if (on) t.start();
        else t.stop();
    }

    public void pair(final GroupKey newKey, final String macName, final List<String> hints, final String relay) {
        handler.post(() -> {
            lan.stop();
            ble.stop();
            internet.stop();
            prefs.setKeyB64(newKey.base64url());
            prefs.setMacName(macName);
            prefs.setLanHints(hints);
            prefs.setKnownPeers(new LinkedHashMap<>());
            if (relay != null && !relay.trim().isEmpty()) prefs.setRelayServer(relay);
            key = newKey;
            seen.clear();
            applySettings();
        });
    }

    public void unpair() {
        handler.post(() -> {
            lan.stop();
            ble.stop();
            internet.stop();
            prefs.setKeyB64(null);
            prefs.setMacName(null);
            prefs.setKnownPeers(new LinkedHashMap<>());
            key = null;
            lastEvent = null;
            changed();
        });
    }

    public void restartInternet() {
        handler.post(() -> {
            internet.stop();
            applySettings();
        });
    }

    private void registerNetworkCallback() {
        if (networkCallback != null) return;
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                handler.post(() -> {
                    internet.reconnectNow();
                    lan.networkChanged();
                });
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                cm.registerDefaultNetworkCallback(cb, handler);
            } else if (Build.VERSION.SDK_INT >= 24) {
                cm.registerDefaultNetworkCallback(cb);
            } else {
                cm.registerNetworkCallback(new NetworkRequest.Builder().build(), cb);
            }
            networkCallback = cb;
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void unregisterNetworkCallback() {
        ConnectivityManager.NetworkCallback cb = networkCallback;
        if (cb == null) return;
        networkCallback = null;
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(cb);
        } catch (Exception ignore) {
        }
    }

    // endregion

    // region Payloads

    private byte[] seal(Msg m) {
        GroupKey k = key;
        return k == null ? null : k.seal(m.toJson());
    }

    public byte[] helloPayload() {
        return seal(new Msg("hello", deviceId, deviceName, "android", null, 0, null, null, null, null));
    }

    public byte[] pingPayload() {
        return seal(new Msg("ping", deviceId));
    }

    // endregion

    // region Outgoing

    /** Sends text copied on the phone. May be called from any thread. */
    public void sendLocalText(final String text) {
        handler.post(() -> {
            if (key == null || !prefs.isSyncEnabled() || text == null || text.isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) return;
            long ts = System.currentTimeMillis();
            lastClipTs = ts;
            lastSentText = text;
            broadcast(new Msg("clip", deviceId, deviceName, null, newMid(), ts, text, null, null, null), preview(text));
        });
    }

    /**
     * Sends an image or a file. {@code asClip} — an image written to the Mac clipboard,
     * otherwise it is saved to Downloads on the Mac. {@code source} — the clipboard URI (to avoid re-sending it).
     */
    public void sendLocalFile(final LocalFile file, final boolean asClip, final Uri source) {
        handler.post(() -> {
            if (key == null || !prefs.isSyncEnabled() || file.bytes.length > NexoFiles.MAX_BYTES) return;
            long ts = System.currentTimeMillis();
            if (asClip) {
                lastClipTs = ts;
                lastSentUri = source;
            }
            Msg msg = new Msg(asClip ? "clip" : "file", deviceId, deviceName, null, newMid(), ts, null, file.name, file.mime, file.bytes);
            broadcast(msg, asClip ? imagePreview(file.bytes) : filePreview(file.name, file.bytes));
        });
    }

    private String newMid() {
        String mid = UUID.randomUUID().toString();
        remember(mid);
        return mid;
    }

    private void broadcast(Msg msg, String preview) {
        byte[] sealed = seal(msg);
        if (sealed == null) return;
        Channel used = route(sealed, null, false);
        if (used == null) pending = new Pending(sealed, msg.ts);
        String macName = prefs.getMacName();
        lastEvent = new SyncEvent(false, used, macName != null ? macName : "Mac", preview, msg.ts);
        changed();
    }

    /** Wi-Fi → Bluetooth → Internet: to each device over the best available channel. */
    private Channel route(byte[] sealed, String origin, boolean cameViaInternet) {
        HashSet<String> reached = new HashSet<>();
        if (origin != null) reached.add(origin);
        Channel used = null;
        LinkTransport[] transports = {lan, ble};
        Channel[] channels = {Channel.LAN, Channel.BLE};
        for (int i = 0; i < transports.length; i++) {
            LinkTransport transport = transports[i];
            Channel channel = channels[i];
            if (!transport.isRunning()) continue;
            // Bluetooth is slow — big images/files go over Wi-Fi or Internet only
            if (channel == Channel.BLE && sealed.length > BLE_MAX_BYTES) continue;
            HashSet<String> peers = new HashSet<>(transport.connectedPeers());
            peers.removeAll(reached);
            if (peers.isEmpty()) continue;
            transport.send(sealed, peers);
            reached.addAll(peers);
            if (used == null) used = channel;
        }
        Set<String> known = prefs.getKnownPeers().keySet();
        HashSet<String> unreached = new HashSet<>(known);
        unreached.removeAll(reached);
        boolean needInternet = !unreached.isEmpty() || (known.isEmpty() && origin == null);
        if (internet.isRunning() && !cameViaInternet && needInternet) {
            internet.publish(sealed);
            if (used == null) used = Channel.INTERNET;
        }
        return used;
    }

    /** When a new connection appears, sends the last clip that went nowhere. Engine thread. */
    public void peerLinked() {
        Pending p = pending;
        if (p == null) return;
        pending = null;
        if (System.currentTimeMillis() - p.ts < 120_000) route(p.sealed, null, false);
    }

    /** Internet connected. Nothing to replay — habits are no longer synced. Engine thread. */
    public void internetLinked() {
    }

    // endregion

    // region Incoming

    /** Called on the engine thread. null when the key does not match. */
    public Msg receive(byte[] sealed, Channel channel) {
        GroupKey k = key;
        if (k == null) return null;
        byte[] plain = k.open(sealed);
        if (plain == null) return null;
        Msg msg = Msg.parse(plain);
        if (msg == null) return null;
        if (deviceId.equals(msg.from)) return msg;
        if (msg.name != null) {
            Map<String, String> peers = prefs.getKnownPeers();
            if (!msg.name.equals(peers.get(msg.from))) {
                LinkedHashMap<String, String> updated = new LinkedHashMap<>(peers);
                updated.put(msg.from, msg.name);
                prefs.setKnownPeers(updated);
                if ("macos".equals(msg.os) || prefs.getMacName() == null) prefs.setMacName(msg.name);
                changed();
            }
        }
        if ("clip".equals(msg.t)) handleClip(msg, sealed, channel);
        else if ("file".equals(msg.t)) handleFile(msg, sealed, channel);
        // "habits" messages from old Nexo versions are ignored — habits stay on the phone
        return msg;
    }

    private void handleClip(Msg msg, byte[] sealed, Channel channel) {
        String mid = msg.mid;
        if (mid == null) return;
        String text = msg.text;
        byte[] data = msg.data;
        if (text == null && data == null) return;
        if (seen.contains(mid)) return;
        remember(mid);
        if (!prefs.isSyncEnabled()) return;
        route(sealed, msg.from, channel == Channel.INTERNET);
        if (msg.ts < lastClipTs) return;
        final ClipData clip;
        String shown;
        if (text != null) {
            lastAppliedText = text;
            clip = ClipData.newPlainText("Nexo", text);
            shown = preview(text);
        } else {
            if (!prefs.isImagesEnabled()) return;
            Uri uri = NexoFiles.saveClipImage(ctx, msg.mime, data);
            if (uri == null) return;
            lastAppliedUri = uri;
            clip = ClipData.newUri(ctx.getContentResolver(), "Nexo", uri);
            shown = imagePreview(data);
        }
        lastClipTs = msg.ts;
        lastAppliedAt = SystemClock.elapsedRealtime();
        main.post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(clip);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
        lastEvent = new SyncEvent(true, channel, msg.name != null ? msg.name : "Mac", shown, System.currentTimeMillis());
        changed();
    }

    private void handleFile(Msg msg, byte[] sealed, Channel channel) {
        String mid = msg.mid;
        if (mid == null) return;
        final byte[] data = msg.data;
        if (data == null) return;
        if (seen.contains(mid)) return;
        remember(mid);
        if (!prefs.isSyncEnabled()) return;
        route(sealed, msg.from, channel == Channel.INTERNET);
        final String name = NexoFiles.safeName(msg.file);
        final String mime = msg.mime != null ? msg.mime : NexoFiles.mimeFor(name);
        final String from = msg.name != null ? msg.name : "Mac";
        io.execute(() -> {
            Uri uri = NexoFiles.saveDownload(ctx, name, mime, data);
            if (uri == null) return;
            NexoFiles.notifyReceived(ctx, name, mime, uri, from);
        });
        lastEvent = new SyncEvent(true, channel, from, filePreview(name, data), System.currentTimeMillis());
        changed();
    }

    private void remember(String mid) {
        seen.add(mid);
        if (seen.size() > 500) seen.remove(seen.iterator().next());
    }

    private static String imagePreview(byte[] data) {
        return "🖼 Image · " + NexoFiles.sizeLabel(data.length);
    }

    private static String filePreview(String name, byte[] data) {
        return "📄 " + name + " · " + NexoFiles.sizeLabel(data.length);
    }

    private static String preview(String text) {
        String line = text.replaceAll("\\r\\n|\\r|\\n", " ").trim();
        return line.length() > 80 ? line.substring(0, 80) + "…" : line;
    }

    // endregion
}
