package org.telegram.messenger;

import android.content.Context;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;

// FORK MOD (nearby offline chat): peer-to-peer messaging over Google Nearby Connections
// (Bluetooth + Wi-Fi Direct, fully offline). Independent of Telegram servers/accounts — only
// works between nearby devices running this same fork. The link is authenticated + encrypted
// by Nearby Connections itself.
public class NearbyController {

    // shared service id so only this fork's users discover each other
    public static final String SERVICE_ID = "org.telegram.messenger.fork.nearby";

    private static volatile NearbyController instance;

    public static NearbyController getInstance() {
        if (instance == null) {
            synchronized (NearbyController.class) {
                if (instance == null) {
                    instance = new NearbyController();
                }
            }
        }
        return instance;
    }

    public interface Listener {
        void onNearbyStateChanged();
        void onNearbyMessage(String endpointId);
    }

    public static class Peer {
        public final String endpointId;
        public String name;
        public boolean connected;
        public boolean connecting;
        public final ArrayList<Message> messages = new ArrayList<>();

        Peer(String endpointId, String name) {
            this.endpointId = endpointId;
            this.name = name;
        }
    }

    public static class Message {
        public final String text;
        public final boolean outgoing;
        public final long time;

        Message(String text, boolean outgoing) {
            this.text = text;
            this.outgoing = outgoing;
            this.time = System.currentTimeMillis();
        }
    }

    private ConnectionsClient client;
    private String localName = "Telegram user";
    private boolean running;
    private final LinkedHashMap<String, Peer> peers = new LinkedHashMap<>();
    private final ArrayList<Listener> listeners = new ArrayList<>();

    public void addListener(Listener l) {
        if (!listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyState() {
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).onNearbyStateChanged();
        }
    }

    private void notifyMessage(String endpointId) {
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).onNearbyMessage(endpointId);
        }
    }

    public boolean isRunning() {
        return running;
    }

    public String getLocalName() {
        return localName;
    }

    public ArrayList<Peer> getPeers() {
        return new ArrayList<>(peers.values());
    }

    public Peer getPeer(String endpointId) {
        return peers.get(endpointId);
    }

    public void start(Context context, String localName) {
        if (running) {
            return;
        }
        if (localName != null && localName.trim().length() > 0) {
            this.localName = localName.trim();
        }
        client = Nearby.getConnectionsClient(context.getApplicationContext());
        running = true;
        try {
            client.startAdvertising(this.localName, SERVICE_ID, connectionLifecycleCallback,
                    new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build());
            client.startDiscovery(SERVICE_ID, endpointDiscoveryCallback,
                    new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build());
        } catch (Exception e) {
            FileLog.e(e);
        }
        notifyState();
    }

    public void stop() {
        running = false;
        if (client != null) {
            try { client.stopAdvertising(); } catch (Exception ignore) {}
            try { client.stopDiscovery(); } catch (Exception ignore) {}
            try { client.stopAllEndpoints(); } catch (Exception ignore) {}
        }
        peers.clear();
        notifyState();
    }

    public void connect(String endpointId) {
        Peer peer = peers.get(endpointId);
        if (client == null || peer == null || peer.connected || peer.connecting) {
            return;
        }
        peer.connecting = true;
        client.requestConnection(localName, endpointId, connectionLifecycleCallback);
        notifyState();
    }

    public void sendText(String endpointId, String text) {
        Peer peer = peers.get(endpointId);
        if (client == null || peer == null || !peer.connected || text == null || text.length() == 0) {
            return;
        }
        client.sendPayload(endpointId, Payload.fromBytes(text.getBytes(StandardCharsets.UTF_8)));
        peer.messages.add(new Message(text, true));
        notifyMessage(endpointId);
    }

    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
            if (!SERVICE_ID.equals(info.getServiceId())) {
                return;
            }
            Peer peer = peers.get(endpointId);
            if (peer == null) {
                peers.put(endpointId, new Peer(endpointId, info.getEndpointName()));
            } else {
                peer.name = info.getEndpointName();
            }
            notifyState();
        }

        @Override
        public void onEndpointLost(String endpointId) {
            Peer peer = peers.get(endpointId);
            if (peer != null && !peer.connected && !peer.connecting) {
                peers.remove(endpointId);
                notifyState();
            }
        }
    };

    private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(String endpointId, ConnectionInfo info) {
            Peer peer = peers.get(endpointId);
            if (peer == null) {
                peers.put(endpointId, new Peer(endpointId, info.getEndpointName()));
            } else {
                peer.name = info.getEndpointName();
            }
            // MVP: auto-accept; Nearby Connections authenticates + encrypts the link.
            if (client != null) {
                client.acceptConnection(endpointId, payloadCallback);
            }
            notifyState();
        }

        @Override
        public void onConnectionResult(String endpointId, ConnectionResolution result) {
            Peer peer = peers.get(endpointId);
            if (peer == null) {
                return;
            }
            peer.connecting = false;
            peer.connected = result.getStatus().isSuccess();
            notifyState();
        }

        @Override
        public void onDisconnected(String endpointId) {
            Peer peer = peers.get(endpointId);
            if (peer != null) {
                peer.connected = false;
                peer.connecting = false;
                notifyState();
            }
        }
    };

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(String endpointId, Payload payload) {
            if (payload.getType() == Payload.Type.BYTES && payload.asBytes() != null) {
                String text = new String(payload.asBytes(), StandardCharsets.UTF_8);
                Peer peer = peers.get(endpointId);
                if (peer == null) {
                    peer = new Peer(endpointId, "?");
                    peers.put(endpointId, peer);
                }
                peer.messages.add(new Message(text, false));
                notifyMessage(endpointId);
            }
        }

        @Override
        public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update) {
        }
    };
}
