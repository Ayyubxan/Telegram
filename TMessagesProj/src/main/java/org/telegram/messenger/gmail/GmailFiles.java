package org.telegram.messenger.gmail;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FORK MOD (gmail): files that Telegram's FileLoader cannot fetch itself.
 * <ul>
 * <li>Email attachments are real {@code TL_document}s (file bubbles, open like any Telegram file); their id is the
 *     message id, {@link #DOC_HASH} marks them, and {@code FileLoader.loadFileInternal} hands them here, where the
 *     bytes come from Gmail's attachments.get and land exactly where FileLoader would have put them.</li>
 * <li>Sender avatars: Gmail has no sender pictures, so a company sender gets its domain's icon (Google's favicon
 *     service); people on free-mail domains keep their initials. The avatar is a normal {@code TL_userProfilePhoto}
 *     whose photo_id is the sender's dialog id, written straight into the image cache.</li>
 * </ul>
 */
public final class GmailFiles {

    /** access_hash of every attachment document */
    public static final long DOC_HASH = 0x474D41494C444F43L; // "GMAILDOC"
    /** access_hash of every sender user (ImageLocation needs it non-zero to build an avatar location) */
    public static final long USER_HASH = 0x474D41494C555352L; // "GMAILUSR"
    /** a dc that does not exist: FileLoader never reaches the network for these */
    public static final int DOC_DC = 7;

    private static final ExecutorService exec = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "gmail-files");
        t.setDaemon(true);
        return t;
    });

    private static final Set<String> FREE_MAIL = new HashSet<>(Arrays.asList(
            "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.uk", "ymail.com", "outlook.com", "hotmail.com", "live.com", "msn.com",
            "icloud.com", "me.com", "mac.com", "mail.ru", "bk.ru", "inbox.ru", "list.ru", "yandex.ru", "yandex.com", "ya.ru", "rambler.ru",
            "protonmail.com", "proton.me", "pm.me", "aol.com", "gmx.com", "gmx.de", "web.de", "zoho.com", "fastmail.com", "hey.com", "qq.com", "163.com", "126.com"));

    private GmailFiles() {}

    // ---- documents ------------------------------------------------------------------------------------------------

    public static boolean isGmailDocument(TLRPC.Document document) {
        return document != null && document.access_hash == DOC_HASH && GmailIds.isGmailMessageId((int) document.id);
    }

    /** The document for an attachment message with id {@code mid} */
    public static TLRPC.TL_document buildDocument(int mid, int date, String filename, String mime, long size) {
        TLRPC.TL_document d = new TLRPC.TL_document();
        d.id = mid;
        d.access_hash = DOC_HASH;
        d.file_reference = new byte[0];
        d.date = date;
        d.mime_type = TextUtils.isEmpty(mime) ? "application/octet-stream" : mime;
        d.size = size;
        d.dc_id = DOC_DC;
        TLRPC.TL_documentAttributeFilename fn = new TLRPC.TL_documentAttributeFilename();
        fn.file_name = TextUtils.isEmpty(filename) ? "attachment" : filename;
        d.attributes.add(fn);
        return d;
    }

    /** From FileLoader.loadFileInternal: fetch the attachment and finish the load like a FileLoadOperation would */
    public static void loadDocument(int account, TLRPC.Document document, Object parentObject, String fileName) {
        exec.execute(() -> {
            FileLoader fl = FileLoader.getInstance(account);
            File target = fl.getPathToAttach(document, null, false);
            boolean ok = false;
            try {
                if (target.exists() && target.length() > 0) {
                    ok = true;
                } else {
                    GmailStore.Attachment a = GmailStore.attachmentByMid((int) document.id);
                    if (a == null) {
                        throw new IllegalStateException("No attachment record for " + document.id);
                    }
                    String token = GmailAuth.tokenBlocking();
                    if (token == null) {
                        throw new IllegalStateException("Needs re-authorization");
                    }
                    byte[] data;
                    try {
                        data = GmailApi.getAttachment(token, a.gmailId, a.attachmentId);
                    } catch (GmailApi.HttpError e) {
                        if (e.code != 401) {
                            throw e;
                        }
                        token = GmailAuth.refreshBlocking();
                        if (token == null) {
                            throw new IllegalStateException("Needs re-authorization");
                        }
                        data = GmailApi.getAttachment(token, a.gmailId, a.attachmentId);
                    }
                    File dir = target.getParentFile();
                    if (dir != null && !dir.exists()) {
                        dir.mkdirs();
                    }
                    File tmp = new File(target.getPath() + ".gmailtmp");
                    try (FileOutputStream os = new FileOutputStream(tmp)) {
                        os.write(data);
                    }
                    if (!tmp.renameTo(target)) {
                        throw new IllegalStateException("Could not move the file into place");
                    }
                    ok = true;
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            fl.forkExternalLoadFinished(fileName, target, parentObject, FileLoader.MEDIA_DIR_DOCUMENT, !ok);
        });
    }

    // ---- avatars --------------------------------------------------------------------------------------------------

    public static boolean isGmailAvatar(TLRPC.TL_fileLocationToBeDeprecated location) {
        return location != null && GmailIds.isGmailDialog(-location.volume_id);
    }

    public static String domainOf(String email) {
        int at = email == null ? -1 : email.lastIndexOf('@');
        return at < 0 ? "" : email.substring(at + 1).toLowerCase(Locale.US);
    }

    /** Whether a sender on this address may get a domain icon at all */
    public static boolean wantsAvatar(String email) {
        String d = domainOf(email);
        return !d.isEmpty() && !FREE_MAIL.contains(d) && d.contains(".");
    }

    /** The profile photo of a sender whose icon is (or will be) in the cache */
    public static TLRPC.TL_userProfilePhoto avatarPhoto(long did) {
        TLRPC.TL_userProfilePhoto p = new TLRPC.TL_userProfilePhoto();
        p.photo_id = did;
        p.dc_id = DOC_DC;
        p.photo_small = new TLRPC.TL_fileLocationToBeDeprecated();
        p.photo_small.volume_id = -did;
        p.photo_small.local_id = 'a';
        p.photo_big = new TLRPC.TL_fileLocationToBeDeprecated();
        p.photo_big.volume_id = -did;
        p.photo_big.local_id = 'c';
        return p;
    }

    private static File avatarFile(long did, int localId) {
        return new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), (-did) + "_" + localId + ".jpg");
    }

    /** -1 = tried and no icon, 1 = icon stored, 0 = unknown */
    public static int avatarState(long did) {
        return GmailStore.prefs().getInt("avatar_" + did, 0);
    }

    /**
     * Fetches the domain icon for the sender and writes both avatar sizes into the image cache (blocking, background
     * thread). Returns true when an icon is now on disk.
     */
    public static boolean fetchAvatar(long did, String email) {
        if (avatarFile(did, 'a').exists()) {
            return true;
        }
        if (!wantsAvatar(email)) {
            return false;
        }
        String domain = domainOf(email);
        try {
            byte[] png = httpGet("https://www.google.com/s2/favicons?domain=" + domain + "&sz=128");
            if (png == null) {
                throw new IllegalStateException("no icon");
            }
            Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (bmp == null || bmp.getWidth() < 48) { // Google's generic globe is 16 px: no real icon for this domain
                GmailStore.prefs().edit().putInt("avatar_" + did, -1).apply();
                return false;
            }
            Bitmap square = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(square);
            canvas.drawColor(0xFFFFFFFF); // icons are transparent PNGs; avatars are opaque JPEGs
            canvas.drawBitmap(bmp, 0, 0, null);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            square.compress(Bitmap.CompressFormat.JPEG, 92, bo);
            byte[] jpg = bo.toByteArray();
            File dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            for (int localId : new int[]{'a', 'c'}) {
                try (FileOutputStream os = new FileOutputStream(avatarFile(did, localId))) {
                    os.write(jpg);
                }
            }
            GmailStore.prefs().edit().putInt("avatar_" + did, 1).apply();
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** From FileLoader.loadFileInternal when the cached avatar file is gone */
    public static void loadAvatar(int account, TLRPC.TL_fileLocationToBeDeprecated location, Object parentObject, String fileName) {
        final long did = -location.volume_id;
        exec.execute(() -> {
            String email = GmailStore.senderEmail(did);
            boolean ok = email != null && fetchAvatar(did, email);
            File f = avatarFile(did, location.local_id);
            FileLoader.getInstance(account).forkExternalLoadFinished(fileName, f, parentObject, FileLoader.MEDIA_DIR_CACHE, !ok || !f.exists());
            if (!ok) {
                dropAvatar(account, did);
            }
        });
    }

    /** No icon for this sender after all: take the photo off the user so the initials show without further loads */
    static void dropAvatar(int account, long did) {
        AndroidUtilities.runOnUIThread(() -> {
            MessagesController mc = MessagesController.getInstance(account);
            TLRPC.User u = mc.getUser(did);
            if (u == null || u.photo == null) {
                return;
            }
            TLRPC.TL_user copy = new TLRPC.TL_user();
            copy.id = u.id;
            copy.first_name = u.first_name;
            copy.last_name = u.last_name;
            copy.access_hash = u.access_hash;
            copy.flags = u.flags & ~32;
            ArrayList<TLRPC.User> list = new ArrayList<>();
            list.add(copy);
            mc.putUsers(list, false);
            MessagesStorage.getInstance(account).putUsersAndChats(list, null, true, true);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_AVATAR);
        });
    }

    private static byte[] httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) TelegramFork");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                }
                return bo.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }
}
