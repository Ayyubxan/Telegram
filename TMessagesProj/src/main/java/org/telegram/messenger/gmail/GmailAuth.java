package org.telegram.messenger.gmail;

import android.content.Context;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * FORK MOD (gmail): OAuth 2.0 authorization-code flow with PKCE, done in the system browser.
 * <p>
 * The app is signed with the keystore that ships in the public Telegram repository, so an Android-type OAuth client
 * (package + signing SHA-1) cannot be registered: that pair is already taken by someone else. A Desktop-type client
 * is not tied to the signature: Google's sign-in page opens in Chrome, redirects to a loopback address this class
 * listens on for a moment, and the refresh token is kept in the app's private preferences. With the Cloud project
 * "In production" the refresh token does not expire.
 */
public final class GmailAuth {

    public static final String SCOPE_MODIFY = "https://www.googleapis.com/auth/gmail.modify";
    public static final String SCOPE_SEND = "https://www.googleapis.com/auth/gmail.send";

    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final int WAIT_FOR_BROWSER_MS = 5 * 60 * 1000;

    private static volatile String cachedToken;
    private static volatile long cachedUntil;
    private static volatile ServerSocket pending;

    private GmailAuth() {}

    /** Trace shown on the settings page (release builds have FileLog off and R8 may drop Log); never tokens or codes */
    static void log(String msg) {
        android.util.Log.i("GmailAuth", msg);
        try {
            String prev = GmailStore.prefs().getString("auth_trace", "");
            String line = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + " " + msg;
            String next = prev.isEmpty() ? line : prev + "\n" + line;
            if (next.length() > 4000) {
                next = next.substring(next.length() - 4000);
            }
            GmailStore.prefs().edit().putString("auth_trace", next).apply();
        } catch (Throwable ignore) {}
    }

    public static String trace() {
        return GmailStore.prefs().getString("auth_trace", "");
    }

    public static void clearTrace() {
        GmailStore.prefs().edit().remove("auth_trace").apply();
    }

    /** False when the build has no client id (local.properties without GMAIL_OAUTH_CLIENT_ID) */
    public static boolean isConfigured() {
        return !TextUtils.isEmpty(BuildConfig.GMAIL_OAUTH_CLIENT_ID);
    }

    public interface Callback {
        /** On the main thread; exactly one of the two is non-null */
        void done(String accessToken, String error);
    }

    /** Opens Google's sign-in page in the browser and waits for it to come back to 127.0.0.1 */
    public static void connectViaBrowser(Context ctx, Callback cb) {
        if (!isConfigured()) {
            cb.done(null, "This build has no OAuth client id (GMAIL_OAUTH_CLIENT_ID in local.properties)");
            return;
        }
        Thread t = new Thread(() -> {
            String access = null, error = null;
            try {
                access = runBrowserFlow(ctx);
                log("flow finished OK");
            } catch (Throwable e) {
                FileLog.e(e);
                log("flow failed: " + e);
                error = describe(e);
            }
            final String a = access, err = error;
            AndroidUtilities.runOnUIThread(() -> cb.done(a, err));
        }, "gmail-oauth");
        t.setDaemon(true);
        t.start();
    }

    private static String runBrowserFlow(Context ctx) throws Exception {
        clearTrace();
        cancelPending();
        // explicitly IPv4: on Android getLoopbackAddress() is ::1, and the browser redirects to 127.0.0.1
        ServerSocket server = new ServerSocket(0, 50, loopback4());
        server.setSoTimeout(WAIT_FOR_BROWSER_MS);
        pending = server;
        String code;
        String redirect = "http://127.0.0.1:" + server.getLocalPort();
        log("listening on " + redirect + " bound=" + server.isBound() + " closed=" + server.isClosed());
        String verifier = randomToken(48);
        String state = randomToken(16);
        try {
            String challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            String url = AUTH_URL + "?client_id=" + enc(BuildConfig.GMAIL_OAUTH_CLIENT_ID)
                    + "&redirect_uri=" + enc(redirect)
                    + "&response_type=code"
                    + "&scope=" + enc(SCOPE_MODIFY + " " + SCOPE_SEND)
                    + "&access_type=offline&prompt=consent&include_granted_scopes=true"
                    + "&code_challenge=" + challenge + "&code_challenge_method=S256"
                    + "&state=" + state;
            AndroidUtilities.runOnUIThread(() -> {
                log("opening browser");
                Browser.openUrlInSystemBrowser(ctx, url);
            });
            code = waitForRedirect(server, state);
            log("got authorization code");
        } finally {
            try {
                server.close();
            } catch (Exception ignore) {}
            if (pending == server) {
                pending = null;
            }
            log("listener closed");
        }
        JSONObject t = tokenRequest("grant_type=authorization_code&code=" + enc(code)
                + "&client_id=" + enc(BuildConfig.GMAIL_OAUTH_CLIENT_ID)
                + "&client_secret=" + enc(BuildConfig.GMAIL_OAUTH_CLIENT_SECRET)
                + "&redirect_uri=" + enc(redirect)
                + "&code_verifier=" + verifier);
        String refresh = t.optString("refresh_token", null);
        String access = t.optString("access_token", null);
        if (TextUtils.isEmpty(refresh) || TextUtils.isEmpty(access)) {
            throw new IOException("Google returned no refresh token (remove the app at myaccount.google.com/permissions and retry)");
        }
        GmailStore.setRefreshToken(refresh);
        GmailStore.setNeedsAuth(false);
        remember(access, t.optLong("expires_in", 3600));
        return access;
    }

    /**
     * Serves connections until the redirect with the code arrives. Browsers open speculative "preconnect" sockets
     * (no request, closed much later) and fetch favicons, so every connection is handled on its own thread and only
     * Google's answer (code or error in the query) ends the wait; the ServerSocket timeout is the overall deadline.
     */
    private static String waitForRedirect(ServerSocket server, String expectedState) throws IOException {
        final String[] result = new String[2]; // [code, error]
        final Object lock = new Object();
        while (true) {
            Socket s;
            try {
                s = server.accept();
            } catch (java.net.SocketTimeoutException e) {
                throw new java.net.SocketTimeoutException("Timed out waiting for the browser");
            } catch (IOException e) {
                synchronized (lock) {
                    if (result[0] != null || result[1] != null) {
                        break;
                    }
                }
                throw e;
            }
            log("connection " + s.getPort());
            final Socket sock = s;
            Thread t = new Thread(() -> handle(sock, expectedState, result, lock), "gmail-oauth-conn");
            t.setDaemon(true);
            t.start();
            synchronized (lock) {
                if (result[0] != null || result[1] != null) {
                    break;
                }
            }
        }
        synchronized (lock) {
            if (result[0] != null) {
                return result[0];
            }
            throw new IOException(result[1] == null ? "No authorization code in the redirect" : result[1]);
        }
    }

    private static void handle(Socket sock, String expectedState, String[] result, Object lock) {
        try (Socket s = sock) {
            s.setSoTimeout(60000);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = r.readLine();
            log("request " + s.getPort() + ": " + (requestLine == null ? "(none)" : requestLine.replaceAll("code=[^&\\s]+", "code=***")));
            if (requestLine == null) {
                return;
            }
            String h;
            while ((h = r.readLine()) != null && !h.isEmpty()) {
                // headers are not needed
            }
            String[] parts = requestLine.split(" ");
            if (!requestLine.startsWith("GET ") || parts.length < 2) {
                respond(s, 404, "");
                return;
            }
            Map<String, String> q = query(parts[1]);
            if (!q.containsKey("code") && !q.containsKey("error")) {
                respond(s, 404, ""); // favicon, prefetch, anything but Google's answer
                return;
            }
            String code = null, err = null;
            if (q.containsKey("code") && expectedState.equals(q.get("state"))) {
                code = q.get("code");
            } else {
                err = q.containsKey("error") ? q.get("error") : "State mismatch";
            }
            respond(s, 200, "<!doctype html><html><head><meta name=viewport content='width=device-width'><title>Telegram</title></head>"
                    + "<body style='font-family:sans-serif;text-align:center;padding:48px 24px;color:#222'>"
                    + "<h2>" + (code != null ? "Gmail connected" : "Sign-in failed") + "</h2>"
                    + "<p>" + (code != null ? "You can close this tab and go back to Telegram." : err) + "</p></body></html>");
            synchronized (lock) {
                if (result[0] == null && result[1] == null) {
                    result[0] = code;
                    result[1] = err;
                }
            }
            // wake the acceptor: it is blocked in accept(); a throw-away connection does it
            try (Socket wake = new Socket(loopback4(), sock.getLocalPort())) {
                wake.getOutputStream().write(0);
            } catch (IOException ignore) {}
        } catch (IOException e) {
            log("connection " + sock.getPort() + " error: " + e);
        }
    }

    private static void respond(Socket s, int status, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        OutputStream os = s.getOutputStream();
        os.write(("HTTP/1.1 " + status + (status == 200 ? " OK" : " Not Found") + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(body);
        os.flush();
    }

    private static Map<String, String> query(String path) {
        HashMap<String, String> out = new HashMap<>();
        int qm = path.indexOf('?');
        if (qm < 0) {
            return out;
        }
        for (String pair : path.substring(qm + 1).split("&")) {
            int eq = pair.indexOf('=');
            try {
                String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), "UTF-8");
                String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                out.put(k, v);
            } catch (Exception ignore) {}
        }
        return out;
    }

    /** Access token for background work (blocking). Null, with {@link GmailStore#setNeedsAuth}, when the user must reconnect. */
    public static String tokenBlocking() {
        String t = cachedToken;
        if (t != null && SystemClock.elapsedRealtime() < cachedUntil) {
            return t;
        }
        return refreshBlocking();
    }

    /** Forces a fresh access token from the refresh token (after a 401) */
    public static String refreshBlocking() {
        cachedToken = null;
        String refresh = GmailStore.refreshToken();
        if (TextUtils.isEmpty(refresh) || !isConfigured()) {
            GmailStore.setNeedsAuth(true);
            return null;
        }
        try {
            JSONObject t = tokenRequest("grant_type=refresh_token&refresh_token=" + enc(refresh)
                    + "&client_id=" + enc(BuildConfig.GMAIL_OAUTH_CLIENT_ID)
                    + "&client_secret=" + enc(BuildConfig.GMAIL_OAUTH_CLIENT_SECRET));
            String access = t.optString("access_token", null);
            if (TextUtils.isEmpty(access)) {
                throw new IOException("No access token in the refresh response");
            }
            GmailStore.setNeedsAuth(false);
            remember(access, t.optLong("expires_in", 3600));
            return access;
        } catch (OAuthError e) {
            FileLog.e(e);
            if ("invalid_grant".equals(e.code)) { // revoked / expired: the user has to sign in again
                GmailStore.setRefreshToken(null);
                GmailStore.setNeedsAuth(true);
            }
            return null;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    public static void forget() {
        cachedToken = null;
        GmailStore.setRefreshToken(null);
        cancelPending();
    }

    private static InetAddress loopback4() {
        try {
            return InetAddress.getByAddress("127.0.0.1", new byte[]{127, 0, 0, 1});
        } catch (java.net.UnknownHostException e) {
            return InetAddress.getLoopbackAddress();
        }
    }

    private static void cancelPending() {
        ServerSocket p = pending;
        pending = null;
        if (p != null) {
            try {
                p.close();
            } catch (Exception ignore) {}
        }
    }

    private static void remember(String token, long expiresInSeconds) {
        cachedToken = token;
        cachedUntil = SystemClock.elapsedRealtime() + Math.max(60, expiresInSeconds - 120) * 1000L;
    }

    static final class OAuthError extends IOException {
        final String code;

        OAuthError(String code, String description) {
            super(code + (TextUtils.isEmpty(description) ? "" : ": " + description));
            this.code = code;
        }
    }

    private static JSONObject tokenRequest(String form) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] body = form.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        sb.append(line);
                    }
                }
            }
            JSONObject json;
            try {
                json = new JSONObject(sb.toString());
            } catch (Exception e) {
                throw new IOException("Token endpoint returned HTTP " + code);
            }
            if (code >= 400 || json.has("error")) {
                throw new OAuthError(json.optString("error", "http_" + code), json.optString("error_description", null));
            }
            return json;
        } finally {
            conn.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        Utilities.random.nextBytes(b);
        return base64Url(b);
    }

    private static String base64Url(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        if (e instanceof java.net.SocketTimeoutException) {
            return "Timed out waiting for the browser";
        }
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
