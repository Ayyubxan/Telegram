package org.telegram.messenger.gmail;

import android.accounts.Account;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.text.TextUtils;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * FORK MOD (gmail): OAuth through Google Play Services' Authorization API. No client secret and no refresh token
 * live in the app: Play Services keeps the grant for the chosen Google account and hands out short-lived access
 * tokens; the Cloud project only needs an Android OAuth client with this app's package name and signing SHA-1.
 */
public final class GmailAuth {

    public static final String SCOPE_MODIFY = "https://www.googleapis.com/auth/gmail.modify";
    public static final String SCOPE_SEND = "https://www.googleapis.com/auth/gmail.send";

    private static volatile String cachedToken;
    private static volatile long cachedAt;
    private static final long TOKEN_TTL = 45 * 60 * 1000L; // access tokens live ~60 minutes

    private GmailAuth() {}

    private static AuthorizationRequest request(String email) {
        List<Scope> scopes = Arrays.asList(new Scope(SCOPE_MODIFY), new Scope(SCOPE_SEND));
        AuthorizationRequest.Builder b = AuthorizationRequest.builder().setRequestedScopes(scopes);
        if (!TextUtils.isEmpty(email)) {
            b.setAccount(new Account(email, "com.google"));
        }
        return b.build();
    }

    /** Result of an interactive connect attempt */
    public interface ConnectCallback {
        /** Play Services needs the user: the fragment must launch this intent and feed the result to {@link #fromConsentResult} */
        void needsConsent(PendingIntent intent);
        void token(String accessToken);
        void failed(String error);
    }

    /** Interactive: may require the consent / account picker screen. Callbacks arrive on the main thread. */
    public static void connect(Activity activity, String email, ConnectCallback cb) {
        try {
            Identity.getAuthorizationClient(activity).authorize(request(email))
                    .addOnSuccessListener(result -> {
                        if (result.hasResolution()) {
                            cb.needsConsent(result.getPendingIntent());
                        } else {
                            String t = result.getAccessToken();
                            if (TextUtils.isEmpty(t)) {
                                cb.failed("No access token");
                            } else {
                                remember(t);
                                cb.token(t);
                            }
                        }
                    })
                    .addOnFailureListener(e -> cb.failed(describe(e)));
        } catch (Throwable e) {
            cb.failed(describe(e));
        }
    }

    /** After the consent activity returned RESULT_OK */
    public static String fromConsentResult(Context ctx, Intent data) {
        try {
            AuthorizationResult r = Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data);
            String t = r.getAccessToken();
            if (!TextUtils.isEmpty(t)) {
                remember(t);
            }
            return t;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Silent token for background work; blocks the calling (background) thread. Returns null and flags
     * {@link GmailStore#setNeedsAuth} when the grant is gone and the user has to reconnect from settings.
     */
    public static String tokenBlocking() {
        String t = cachedToken;
        if (t != null && SystemClock.elapsedRealtime() - cachedAt < TOKEN_TTL) {
            return t;
        }
        return refreshBlocking();
    }

    /** Forces a fresh token (after a 401) */
    public static String refreshBlocking() {
        cachedToken = null;
        String email = GmailStore.email();
        if (TextUtils.isEmpty(email)) {
            return null;
        }
        try {
            AuthorizationResult r = Tasks.await(Identity.getAuthorizationClient(ApplicationLoader.applicationContext).authorize(request(email)), 30, TimeUnit.SECONDS);
            if (r.hasResolution() || TextUtils.isEmpty(r.getAccessToken())) {
                GmailStore.setNeedsAuth(true);
                return null;
            }
            GmailStore.setNeedsAuth(false);
            remember(r.getAccessToken());
            return r.getAccessToken();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    public static void forget() {
        cachedToken = null;
    }

    private static void remember(String token) {
        cachedToken = token;
        cachedAt = SystemClock.elapsedRealtime();
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        if (m != null && m.contains("10:")) {
            return "DEVELOPER_ERROR (10): package name / SHA-1 not registered in Google Cloud";
        }
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
