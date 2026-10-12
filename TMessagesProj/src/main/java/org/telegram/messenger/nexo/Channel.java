package org.telegram.messenger.nexo;

/** FORK MOD (Nexo): the transport a message travelled over */
public enum Channel {
    LAN("Wi-Fi"),
    BLE("Bluetooth"),
    INTERNET("Internet");

    public final String label;

    Channel(String label) {
        this.label = label;
    }
}
