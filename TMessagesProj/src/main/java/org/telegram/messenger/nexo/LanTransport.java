package org.telegram.messenger.nexo;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * FORK MOD (Nexo): Wi-Fi: finds the Mac via Bonjour (NSD) and connects over TCP.
 * When NSD does not work, the IP addresses from the QR code and the ones found earlier are tried.
 */
public class LanTransport implements LinkTransport {
    private final SyncEngine engine;
    private final NsdManager nsd;
    private final Handler h;

    private volatile boolean running = false;
    private volatile Link link;
    private NsdManager.DiscoveryListener discovery;
    private boolean resolving = false;
    private int hintIndex = 0;

    private final Runnable connectLoop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (discovery == null) startDiscovery();
            tryHint();
            h.postDelayed(this, 15_000);
        }
    };

    private final Runnable pingLoop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            Link l = link;
            if (l != null && l.peerId != null) {
                byte[] ping = engine.pingPayload();
                if (ping != null) l.write(ping);
            }
            h.postDelayed(this, 25_000);
        }
    };

    public LanTransport(Context ctx, SyncEngine engine) {
        this.engine = engine;
        this.h = engine.handler;
        this.nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public String status() {
        Link l = link;
        if (!running) return "Off";
        if (l != null && l.peerId != null) return "Connected (" + l.host + ")";
        return "Looking for Mac…";
    }

    @Override
    public Set<String> connectedPeers() {
        Link l = link;
        return l != null && l.peerId != null ? Collections.singleton(l.peerId) : Collections.<String>emptySet();
    }

    @Override
    public void start() {
        if (running) return;
        running = true;
        startDiscovery();
        h.post(connectLoop);
        h.postDelayed(pingLoop, 25_000);
    }

    @Override
    public void stop() {
        if (!running) return;
        running = false;
        h.removeCallbacks(connectLoop);
        h.removeCallbacks(pingLoop);
        stopDiscovery();
        Link l = link;
        if (l != null) l.close();
        link = null;
        engine.changed();
    }

    public void networkChanged() {
        if (!running) return;
        // The old socket may be dead after a network switch — restart Bonjour
        stopDiscovery();
        startDiscovery();
        Link l = link;
        if (l == null || l.peerId == null) h.postDelayed(this::tryHint, 1500);
    }

    @Override
    public void send(byte[] sealed, Set<String> peers) {
        Link l = link;
        if (l == null) return;
        if (l.peerId != null && peers.contains(l.peerId)) l.write(sealed);
    }

    private void startDiscovery() {
        GroupKey key = engine.getKey();
        if (key == null) return;
        if (discovery != null || nsd == null) return;
        final String prefix = "Nexo-" + key.groupId;
        final NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onServiceFound(final NsdServiceInfo info) {
                String name = info.getServiceName();
                if (name != null && name.startsWith(prefix)) h.post(() -> resolve(info));
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {}

            @Override
            public void onDiscoveryStarted(String serviceType) {}

            @Override
            public void onDiscoveryStopped(String serviceType) {}

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                final NsdManager.DiscoveryListener me = this;
                h.post(() -> {
                    if (discovery == me) discovery = null;
                });
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
        };
        try {
            nsd.discoverServices("_nexo._tcp", NsdManager.PROTOCOL_DNS_SD, listener);
            discovery = listener;
        } catch (Exception ignore) {
        }
    }

    private void stopDiscovery() {
        NsdManager.DiscoveryListener d = discovery;
        if (d == null) return;
        discovery = null;
        resolving = false;
        try {
            nsd.stopServiceDiscovery(d);
        } catch (Exception ignore) {
        }
    }

    @SuppressWarnings("deprecation")
    private void resolve(NsdServiceInfo info) {
        Link l = link;
        if (!running || resolving || (l != null && l.peerId != null)) return;
        resolving = true;
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo i, int errorCode) {
                    h.post(() -> resolving = false);
                }

                @Override
                public void onServiceResolved(final NsdServiceInfo i) {
                    h.post(() -> {
                        resolving = false;
                        if (i.getHost() == null) return;
                        String host = i.getHost().getHostAddress();
                        if (host == null) return;
                        String hint = host.contains(":") ? "[" + host + "]:" + i.getPort() : host + ":" + i.getPort();
                        engine.prefs.addLanHint(hint);
                        connect(host, i.getPort(), true);
                    });
                }
            });
        } catch (Exception e) {
            resolving = false;
        }
    }

    private void tryHint() {
        if (!running || link != null) return;
        List<String> hints = engine.prefs.getLanHints();
        if (hints.isEmpty()) return;
        String hint = hints.get(hintIndex++ % hints.size());
        int sep = hint.lastIndexOf(':');
        if (sep <= 0) return;
        String host = hint.substring(0, sep);
        while (host.startsWith("[")) host = host.substring(1);
        while (host.endsWith("]")) host = host.substring(0, host.length() - 1);
        int port;
        try {
            port = Integer.parseInt(hint.substring(sep + 1));
        } catch (NumberFormatException e) {
            return;
        }
        connect(host, port, false);
    }

    private void connect(String host, int port, boolean force) {
        if (!running) return;
        Link current = link;
        if (current != null) {
            if (current.peerId != null) return;
            // Replace a connection that has not answered yet with the Bonjour result
            if (!force && !current.stale()) return;
            current.close();
        }
        Link l = new Link(host, port);
        link = l;
        l.start();
        engine.changed();
    }

    private class Link extends Thread {
        final String host;
        final int port;
        private final Socket socket = new Socket();
        private final long created = SystemClock.elapsedRealtime();
        volatile String peerId;
        private volatile DataOutputStream out;
        private volatile boolean closed = false;

        Link(String host, int port) {
            super("nexo-lan");
            this.host = host;
            this.port = port;
        }

        boolean stale() {
            return SystemClock.elapsedRealtime() - created > 10_000;
        }

        @Override
        public void run() {
            try {
                socket.connect(new InetSocketAddress(host, port), 3000);
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.setSoTimeout(70_000);
                DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                byte[] hello = engine.helloPayload();
                if (hello != null) write(hello);
                while (!closed) {
                    int len = input.readInt();
                    if (len <= 0 || len > Wire.MAX_FRAME) break;
                    final byte[] payload = new byte[len];
                    input.readFully(payload);
                    h.post(() -> onPayload(payload));
                }
            } catch (Exception ignore) {
            }
            close();
            h.post(() -> {
                if (link == Link.this) {
                    link = null;
                    engine.changed();
                    if (running) h.postDelayed(LanTransport.this::tryHint, 2000);
                }
            });
        }

        private void onPayload(byte[] payload) {
            if (link != this) return;
            Msg msg = engine.receive(payload, Channel.LAN);
            if (msg == null) return;
            if ("hello".equals(msg.t) && !msg.from.equals(peerId)) {
                peerId = msg.from;
                engine.prefs.addLanHint(host + ":" + port);
                engine.changed();
                engine.peerLinked();
            }
        }

        void write(byte[] payload) {
            DataOutputStream o = out;
            if (o == null) return;
            try {
                synchronized (o) {
                    o.writeInt(payload.length);
                    o.write(payload);
                    o.flush();
                }
            } catch (Exception e) {
                close();
            }
        }

        void close() {
            closed = true;
            try {
                socket.close();
            } catch (Exception ignore) {
            }
        }
    }
}
