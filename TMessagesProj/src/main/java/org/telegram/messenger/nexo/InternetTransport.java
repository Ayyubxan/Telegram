package org.telegram.messenger.nexo;

import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FORK MOD (Nexo): Internet: ntfy pub/sub (default — https://ntfy.sh). The server only ever sees encrypted bytes.
 */
public class InternetTransport {
    /** ntfy message limit is 4096 bytes — larger clips go as attachments */
    private static final int INLINE_LIMIT = 3800;

    private final SyncEngine engine;

    private volatile boolean running = false;
    private volatile boolean connected = false;

    private volatile Thread thread;
    private volatile HttpURLConnection conn;
    private volatile byte[] pending;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Object wake = new Object();
    private boolean skipBackoff = false;

    public InternetTransport(SyncEngine engine) {
        this.engine = engine;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isConnected() {
        return connected;
    }

    public String status() {
        if (!running) return "Off";
        if (connected) return "Connected";
        return "Connecting…";
    }

    private String base() {
        String s = engine.prefs.getRelayServer().trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::loop, "nexo-relay");
        thread = t;
        t.start();
    }

    public void stop() {
        if (!running) return;
        running = false;
        Thread t = thread;
        thread = null;
        HttpURLConnection c = conn;
        if (c != null) c.disconnect();
        if (t != null) t.interrupt();
        connected = false;
        engine.changed();
    }

    public void reconnectNow() {
        if (!running) return;
        synchronized (wake) {
            skipBackoff = true;
            wake.notifyAll();
        }
        io.execute(() -> {
            HttpURLConnection c = conn;
            if (c != null) c.disconnect();
        });
    }

    private void loop() {
        Thread me = Thread.currentThread();
        long backoff = 1000L;
        while (running && thread == me) {
            GroupKey key = engine.getKey();
            if (key == null) break;
            try {
                String since = engine.prefs.relayLastId(key.topic);
                String query = since == null ? "" : "?since=" + URLEncoder.encode(since, "UTF-8");
                HttpURLConnection c = (HttpURLConnection) new URL(base() + "/" + key.topic + "/json" + query).openConnection();
                c.setConnectTimeout(15_000);
                c.setReadTimeout(120_000); // ntfy sends a keepalive every ~45 s
                conn = c;
                if (c.getResponseCode() == 200) {
                    connected = true;
                    backoff = 1000;
                    engine.changed();
                    engine.handler.post(engine::internetLinked);
                    byte[] p = pending;
                    if (p != null) {
                        pending = null;
                        publish(p);
                    }
                    BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                    try {
                        while (running && thread == me) {
                            String line = reader.readLine();
                            if (line == null) break;
                            handleLine(line, key);
                        }
                    } finally {
                        try {
                            reader.close();
                        } catch (Exception ignore) {
                        }
                    }
                }
            } catch (Exception ignore) {
            } finally {
                HttpURLConnection c = conn;
                if (c != null) c.disconnect();
                conn = null;
                if (connected) {
                    connected = false;
                    engine.changed();
                }
            }
            if (!running || thread != me) break;
            try {
                synchronized (wake) {
                    if (!skipBackoff) wake.wait(backoff);
                    skipBackoff = false;
                }
            } catch (InterruptedException e) {
                break;
            }
            backoff = Math.min(backoff * 2, 60_000L);
        }
    }

    private void handleLine(String line, GroupKey key) {
        JSONObject o;
        try {
            o = new JSONObject(line);
        } catch (Exception e) {
            return;
        }
        if (!"message".equals(o.optString("event"))) return;
        String id = o.optString("id");
        if (id != null && !id.isEmpty()) engine.prefs.setRelayLastId(key.topic, id);
        JSONObject attachment = o.optJSONObject("attachment");
        final byte[] sealed;
        if (attachment != null) {
            sealed = download(attachment.optString("url"));
            if (sealed == null) return;
        } else {
            byte[] decoded;
            try {
                decoded = Base64.decode(o.optString("message"), Base64.DEFAULT);
            } catch (Exception e) {
                return;
            }
            sealed = decoded;
        }
        engine.handler.post(() -> engine.receive(sealed, Channel.INTERNET));
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private byte[] download(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            try {
                if (c.getResponseCode() != 200) return null;
                InputStream in = c.getInputStream();
                try {
                    return readAll(in);
                } finally {
                    in.close();
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    public void publish(final byte[] sealed) {
        io.execute(() -> {
            // Not delivered — the latest clip is kept and sent once the connection is back
            if (!doPublish(sealed)) pending = sealed;
        });
    }

    private boolean doPublish(byte[] sealed) {
        GroupKey key = engine.getKey();
        if (key == null) return false;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(base() + "/" + key.topic).openConnection();
            c.setConnectTimeout(15_000);
            // The server answer may be delayed after a big upload
            c.setReadTimeout(30_000 + sealed.length / 50);
            c.setDoOutput(true);
            String b64 = Base64.encodeToString(sealed, Base64.NO_WRAP);
            byte[] body;
            if (b64.length() <= INLINE_LIMIT) {
                c.setRequestMethod("POST");
                body = b64.getBytes(StandardCharsets.UTF_8);
            } else {
                c.setRequestMethod("PUT");
                c.setRequestProperty("Filename", "clip.bin");
                body = sealed;
            }
            c.setFixedLengthStreamingMode(body.length);
            OutputStream out = c.getOutputStream();
            try {
                out.write(body);
            } finally {
                out.close();
            }
            int code = c.getResponseCode();
            boolean ok = code >= 200 && code <= 299;
            c.disconnect();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }
}
