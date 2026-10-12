package org.telegram.messenger.nexo;

import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * FORK MOD (Nexo): same as GroupKey on the Mac: the 32-byte key derives the cipher key,
 * the Bonjour name, the BLE service UUID and the internet (ntfy) topic.
 */
public class GroupKey {
    private static final SecureRandom random = new SecureRandom();

    private final byte[] raw;
    private final SecretKeySpec spec;

    public final String groupId;
    public final String topic;
    public final UUID bleServiceUuid;

    public GroupKey(byte[] raw) {
        if (raw == null || raw.length != 32) throw new IllegalArgumentException("GroupKey must be 32 bytes");
        this.raw = raw.clone();
        this.spec = new SecretKeySpec(this.raw, "AES");
        groupId = hex(derive("nexo-group")).substring(0, 16);
        topic = "nexo_" + hex(derive("nexo-topic")).substring(0, 32);
        ByteBuffer bb = ByteBuffer.wrap(derive("nexo-ble"));
        bleServiceUuid = new UUID(bb.getLong(), bb.getLong());
    }

    private byte[] derive(String label) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(label.getBytes(StandardCharsets.UTF_8));
            md.update(raw);
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String base64url() {
        return Base64.encodeToString(raw, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    /** nonce(12) + ciphertext + tag(16) — same as CryptoKit's `combined` format */
    public byte[] seal(byte[] plain) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, spec, new GCMParameterSpec(128, iv));
            byte[] enc = cipher.doFinal(plain);
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** null when the key does not match or the data is corrupt */
    public byte[] open(byte[] sealed) {
        if (sealed == null || sealed.length < 28) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, spec, new GCMParameterSpec(128, sealed, 0, 12));
            return cipher.doFinal(sealed, 12, sealed.length - 12);
        } catch (Exception e) {
            return null;
        }
    }

    public static GroupKey fromBase64url(String s) {
        if (s == null) return null;
        try {
            byte[] bytes = Base64.decode(s.trim(), Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            return bytes.length == 32 ? new GroupKey(bytes) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xff;
            if (v < 0x10) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }
}
