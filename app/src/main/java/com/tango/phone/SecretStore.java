package com.tango.phone;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Credentials never appear in the task journal or Android backup. */
public final class SecretStore {
    private static final String ALIAS = "tango.settings.v1";
    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
    public static synchronized void put(Context context, String name, String value) throws Exception {
        if (value.isEmpty()) { context.getSharedPreferences("secrets", 0).edit().remove(name).apply(); return; }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        String encrypted = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." +
                Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        context.getSharedPreferences("secrets", 0).edit().putString(name, encrypted).apply();
    }
    /** A rotating OAuth token set is one encrypted record, durably replaced before it is used. */
    public static synchronized void putAtomic(Context context, String name, String value) throws Exception {
        android.content.SharedPreferences.Editor edit = context.getSharedPreferences("secrets", 0).edit();
        if (value.isEmpty()) edit.remove(name);
        else {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            String encrypted = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." +
                    Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
            edit.putString(name, encrypted);
        }
        if (!edit.commit()) throw new Exception("Could not securely save the ChatGPT session.");
    }
    public static synchronized String get(Context context, String name) {
        try {
            String saved = context.getSharedPreferences("secrets", 0).getString(name, "");
            if (saved.isEmpty()) return "";
            String[] parts = saved.split("\\.");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }
    private SecretStore() {}
}
