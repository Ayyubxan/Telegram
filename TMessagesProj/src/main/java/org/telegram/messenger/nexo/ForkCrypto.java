package org.telegram.messenger.nexo;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * FORK MOD: backup encryption — AES-256-GCM with a key derived from the user's passphrase (PBKDF2-HMAC-SHA256, 120k rounds).
 * File layout: "TGFORK1" + salt(16) + iv(12) + ciphertext+tag. Without the passphrase the file is just noise.
 */
public class ForkCrypto {
    private static final byte[] MAGIC = "TGFORK1".getBytes(StandardCharsets.US_ASCII);
    private static final int ITERATIONS = 120_000;
    public static final String EXT = ".tgbackup";

    public static boolean isEncrypted(byte[] data) {
        if (data == null || data.length < MAGIC.length + 16 + 12 + 16) return false;
        for (int i = 0; i < MAGIC.length; i++) if (data[i] != MAGIC[i]) return false;
        return true;
    }

    private static SecretKeySpec key(String passphrase, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 256);
        byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        return new SecretKeySpec(k, "AES");
    }

    public static byte[] encrypt(byte[] plain, String passphrase) throws Exception {
        SecureRandom rnd = new SecureRandom();
        byte[] salt = new byte[16], iv = new byte[12];
        rnd.nextBytes(salt);
        rnd.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key(passphrase, salt), new GCMParameterSpec(128, iv));
        c.updateAAD(MAGIC);
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[MAGIC.length + 16 + 12 + ct.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        System.arraycopy(salt, 0, out, MAGIC.length, 16);
        System.arraycopy(iv, 0, out, MAGIC.length + 16, 12);
        System.arraycopy(ct, 0, out, MAGIC.length + 28, ct.length);
        return out;
    }

    /** null if the passphrase is wrong or the data is damaged */
    public static byte[] decrypt(byte[] data, String passphrase) {
        if (!isEncrypted(data) || passphrase == null) return null;
        try {
            byte[] salt = Arrays.copyOfRange(data, MAGIC.length, MAGIC.length + 16);
            byte[] iv = Arrays.copyOfRange(data, MAGIC.length + 16, MAGIC.length + 28);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(passphrase, salt), new GCMParameterSpec(128, iv));
            c.updateAAD(MAGIC);
            return c.doFinal(data, MAGIC.length + 28, data.length - MAGIC.length - 28);
        } catch (Exception e) {
            return null;
        }
    }
}
