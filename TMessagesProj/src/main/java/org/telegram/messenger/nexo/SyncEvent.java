package org.telegram.messenger.nexo;

/** FORK MOD (Nexo): the last sync event shown in the UI (sent or received clip / file) */
public class SyncEvent {
    public final boolean incoming;
    /** null — sent nowhere yet (queued until a connection appears) */
    public final Channel channel;
    public final String peerName;
    public final String preview;
    public final long time;

    public SyncEvent(boolean incoming, Channel channel, String peerName, String preview, long time) {
        this.incoming = incoming;
        this.channel = channel;
        this.peerName = peerName;
        this.preview = preview;
        this.time = time;
    }
}
