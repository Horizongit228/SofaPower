package com.sofa.power;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureStore {
    public static final String KEY_MAC = "pc_mac";
    public static final String KEY_IP = "pc_ip";
    public static final String KEY_NAME = "pc_name";

    private static final String PREFS = "sofapower_secure_v1";
    private static final String LEGACY_PREFS = "sofa_power_prefs";
    private static final String KEY_ALIAS = "SofaPowerLocalKey_v1";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private final SharedPreferences prefs;

    public SecureStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrateLegacy(context.getApplicationContext());
    }

    public synchronized void putString(String key, String value) {
        if (value == null || value.isEmpty()) {
            prefs.edit().remove(key).apply();
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            prefs.edit().putString(key, Base64.encodeToString(packed, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            throw new IllegalStateException("Secure storage is unavailable", e);
        }
    }

    public synchronized String getString(String key, String fallback) {
        String encoded = prefs.getString(key, null);
        if (encoded == null) return fallback;
        try {
            byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
            if (packed.length <= IV_BYTES) return fallback;
            byte[] iv = Arrays.copyOfRange(packed, 0, IV_BYTES);
            byte[] encrypted = Arrays.copyOfRange(packed, IV_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return fallback;
        }
    }

    public synchronized void clearPc() {
        prefs.edit().remove(KEY_MAC).remove(KEY_IP).remove(KEY_NAME).apply();
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
            return entry.getSecretKey();
        }

        KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build();
        keyGenerator.init(spec);
        return keyGenerator.generateKey();
    }

    private void migrateLegacy(Context context) {
        if (prefs.contains(KEY_MAC)) return;
        SharedPreferences legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
        String mac = legacy.getString(KEY_MAC, null);
        if (mac == null || mac.trim().isEmpty()) return;
        try {
            putString(KEY_MAC, mac);
            legacy.edit().remove(KEY_MAC).apply();
        } catch (Exception ignored) {
            // Keep legacy value if secure migration ever fails, so the user does not lose configuration.
        }
    }
}
