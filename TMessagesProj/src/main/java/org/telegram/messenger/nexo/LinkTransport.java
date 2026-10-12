package org.telegram.messenger.nexo;

import java.util.Set;

/** FORK MOD (Nexo): persistent-connection channels such as Wi-Fi and Bluetooth. All methods are called on the engine thread. */
public interface LinkTransport {
    boolean isRunning();
    String status();
    Set<String> connectedPeers();
    void start();
    void stop();
    void send(byte[] sealed, Set<String> peers);
}
