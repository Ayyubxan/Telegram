package org.telegram.messenger.nexo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * FORK MOD (Nexo): Bluetooth LE: the Mac is a GATT server (peripheral), the phone connects to it.
 * We write to RX and get notifications from TX. Frames are split into MTU-sized chunks.
 */
@SuppressLint("MissingPermission")
public class BleTransport implements LinkTransport {
    public static final UUID RX = UUID.fromString("5c1b0001-6a3e-4f3b-9d2a-c11b5ec0ffee");
    public static final UUID TX = UUID.fromString("5c1b0002-6a3e-4f3b-9d2a-c11b5ec0ffee");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static String[] permissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    public static boolean hasPermissions(Context ctx) {
        for (String p : permissions()) {
            if (ContextCompat.checkSelfPermission(ctx, p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private final Context ctx;
    private final SyncEngine engine;
    private final BluetoothManager manager;
    private final Handler h;

    private volatile boolean running = false;

    private BluetoothGatt gatt;
    private volatile String peerId;
    private BluetoothGattCharacteristic rxChar;
    private boolean ready = false;
    private boolean scanning = false;
    private int mtu = 23;
    private FrameParser parser = new FrameParser();
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private boolean writing = false;
    private long lastRx = 0L;
    private boolean receiverRegistered = false;

    public BleTransport(Context ctx, SyncEngine engine) {
        this.ctx = ctx;
        this.engine = engine;
        this.h = engine.handler;
        this.manager = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
    }

    private BluetoothAdapter adapter() {
        return manager == null ? null : manager.getAdapter();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public String status() {
        if (!running) return "Off";
        if (!hasPermissions(ctx)) return "Permission not granted";
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) return "Bluetooth is off";
        if (peerId != null) return "Connected";
        if (gatt != null) return "Connecting…";
        return "Looking for Mac…";
    }

    @Override
    public Set<String> connectedPeers() {
        String p = peerId;
        return p != null ? Collections.singleton(p) : Collections.<String>emptySet();
    }

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            h.post(() -> {
                if (state == BluetoothAdapter.STATE_ON) {
                    scheduleScan(1000);
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    resetGatt(null);
                }
                engine.changed();
            });
        }
    };

    @Override
    public void start() {
        if (running) return;
        running = true;
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                ctx.registerReceiver(stateReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
        scheduleScan(0);
        h.postDelayed(pingLoop, 30_000);
    }

    @Override
    public void stop() {
        if (!running) return;
        running = false;
        h.removeCallbacks(pingLoop);
        h.removeCallbacks(scanRunnable);
        stopScan();
        resetGatt(null);
        if (receiverRegistered) {
            try {
                ctx.unregisterReceiver(stateReceiver);
            } catch (Exception ignore) {
            }
            receiverRegistered = false;
        }
        engine.changed();
    }

    @Override
    public void send(byte[] sealed, Set<String> peers) {
        String p = peerId;
        if (p != null && peers.contains(p)) enqueue(sealed);
    }

    // region Scan

    private final Runnable scanRunnable = this::startScan;

    private void scheduleScan(long delay) {
        h.removeCallbacks(scanRunnable);
        if (running) h.postDelayed(scanRunnable, delay);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            final BluetoothDevice device = result.getDevice();
            h.post(() -> onFound(device));
        }

        @Override
        public void onScanFailed(int errorCode) {
            h.post(() -> {
                scanning = false;
                scheduleScan(30_000);
            });
        }
    };

    private void startScan() {
        if (!running || gatt != null) return;
        GroupKey key = engine.getKey();
        if (key == null) return;
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled() || !hasPermissions(ctx)) {
            // Permission may be granted later — check periodically
            scheduleScan(30_000);
            return;
        }
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            scheduleScan(30_000);
            return;
        }
        stopScan();
        try {
            List<ScanFilter> filters = Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(key.bleServiceUuid)).build());
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build();
            scanner.startScan(filters, settings, scanCallback);
            scanning = true;
        } catch (Exception e) {
            scheduleScan(30_000);
            return;
        }
        engine.changed();
        // Android throttles long-running scans — restart every 10 minutes
        scheduleScan(10 * 60_000);
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try {
            BluetoothAdapter a = adapter();
            BluetoothLeScanner scanner = a == null ? null : a.getBluetoothLeScanner();
            if (scanner != null) scanner.stopScan(scanCallback);
        } catch (Exception ignore) {
        }
    }

    private void onFound(BluetoothDevice device) {
        if (!running || gatt != null) return;
        stopScan();
        h.removeCallbacks(scanRunnable);
        try {
            // the transport argument is API 23+ (the fork's minSdk is 21)
            gatt = Build.VERSION.SDK_INT >= 23
                ? device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                : device.connectGatt(ctx, false, gattCallback);
        } catch (Exception e) {
            gatt = null;
        }
        if (gatt == null) {
            scheduleScan(10_000);
            return;
        }
        h.postDelayed(connectTimeout, 20_000);
        engine.changed();
    }

    private final Runnable connectTimeout = () -> {
        if (gatt != null && peerId == null) resetGatt(5_000L);
    };

    private void resetGatt(Long rescanDelay) {
        h.removeCallbacks(connectTimeout);
        BluetoothGatt g = gatt;
        if (g != null) {
            try {
                g.disconnect();
                g.close();
            } catch (Exception ignore) {
            }
        }
        boolean hadPeer = peerId != null;
        gatt = null;
        peerId = null;
        rxChar = null;
        ready = false;
        writing = false;
        queue.clear();
        parser = new FrameParser();
        mtu = 23;
        if (hadPeer) engine.changed();
        if (rescanDelay != null) scheduleScan(rescanDelay);
    }

    // endregion

    // region GATT

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt g, final int status, final int newState) {
            h.post(() -> {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (!g.requestMtu(517)) g.discoverServices();
                } else {
                    resetGatt(3_000L);
                }
            });
        }

        @Override
        public void onMtuChanged(final BluetoothGatt g, final int newMtu, final int status) {
            h.post(() -> {
                if (g != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu;
                g.discoverServices();
            });
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onServicesDiscovered(final BluetoothGatt g, int status) {
            h.post(() -> {
                if (g != gatt) return;
                GroupKey key = engine.getKey();
                android.bluetooth.BluetoothGattService service = key == null ? null : g.getService(key.bleServiceUuid);
                BluetoothGattCharacteristic tx = service == null ? null : service.getCharacteristic(TX);
                BluetoothGattCharacteristic rx = service == null ? null : service.getCharacteristic(RX);
                BluetoothGattDescriptor cccd = tx == null ? null : tx.getDescriptor(CCCD);
                if (rx == null || tx == null || cccd == null) {
                    resetGatt(15_000L);
                    return;
                }
                rxChar = rx;
                g.setCharacteristicNotification(tx, true);
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(cccd);
                }
            });
        }

        @Override
        public void onDescriptorWrite(final BluetoothGatt g, BluetoothGattDescriptor descriptor, final int status) {
            h.post(() -> {
                if (g != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    resetGatt(10_000L);
                    return;
                }
                ready = true;
                lastRx = SystemClock.elapsedRealtime();
                byte[] hello = engine.helloPayload();
                if (hello != null) enqueue(hello);
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            onData(g, value.clone());
        }

        /** Android 12 and older */
        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) {
                byte[] v = c.getValue();
                if (v != null) onData(g, v.clone());
            }
        }

        @Override
        public void onCharacteristicWrite(final BluetoothGatt g, BluetoothGattCharacteristic c, final int status) {
            h.post(() -> {
                if (g != gatt) return;
                writing = false;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    resetGatt(3_000L);
                    return;
                }
                pump();
            });
        }
    };

    private void onData(final BluetoothGatt g, final byte[] value) {
        h.post(() -> {
            if (g != gatt) return;
            lastRx = SystemClock.elapsedRealtime();
            List<byte[]> frames;
            try {
                frames = parser.push(value);
            } catch (Exception e) {
                resetGatt(3_000L);
                return;
            }
            for (byte[] frame : frames) {
                Msg msg = engine.receive(frame, Channel.BLE);
                if (msg == null) continue;
                if ("hello".equals(msg.t) && !msg.from.equals(peerId)) {
                    peerId = msg.from;
                    h.removeCallbacks(connectTimeout);
                    engine.changed();
                    engine.peerLinked();
                }
            }
        });
    }

    private void enqueue(byte[] payload) {
        byte[] data = Wire.frame(payload);
        int size = Math.max(20, mtu - 3);
        int offset = 0;
        while (offset < data.length) {
            int end = Math.min(offset + size, data.length);
            queue.addLast(Arrays.copyOfRange(data, offset, end));
            offset = end;
        }
        pump();
    }

    @SuppressWarnings("deprecation")
    private void pump() {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic rx = rxChar;
        if (g == null || rx == null) return;
        if (writing || !ready) return;
        byte[] chunk = queue.pollFirst();
        if (chunk == null) return;
        boolean ok;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ok = g.writeCharacteristic(rx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS;
            } else {
                rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                rx.setValue(chunk);
                ok = g.writeCharacteristic(rx);
            }
        } catch (Exception e) {
            ok = false;
        }
        if (ok) {
            writing = true;
        } else {
            queue.addFirst(chunk);
            h.postDelayed(this::pump, 50);
        }
    }

    private final Runnable pingLoop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (gatt != null && ready) {
                if (SystemClock.elapsedRealtime() - lastRx > 100_000) {
                    resetGatt(1_000L);
                } else if (peerId != null) {
                    byte[] ping = engine.pingPayload();
                    if (ping != null) enqueue(ping);
                }
            }
            h.postDelayed(this, 30_000);
        }
    };

    // endregion
}
