package com.fongmi.android.tv.smb;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 可选的 SMB 登录信息存储。只在用户勾选“保存登录信息”时写入应用私有
 * SharedPreferences，密码用 Android Keystore 的 AES/GCM 加密，不落明文。
 *
 * <p>按主机名保存用户名、密码和域；清除时只删对应主机。Keystore 不可用
 * （例如部分精简电视固件）时保存会失败，页面会提示，但不会影响本次登录。</p>
 */
public final class SmbCredentialStore {

    private static final String PREF = "smb_credentials";
    private static final String ALIAS = "webhtv_smb_credentials";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private SmbCredentialStore() {
    }

    public static final class Stored {
        public final String username;
        public final String password;
        public final String domain;

        Stored(String username, String password, String domain) {
            this.username = username == null ? "" : username;
            this.password = password == null ? "" : password;
            this.domain = domain == null ? "" : domain;
        }

        public boolean isEmpty() {
            return username.isEmpty() && password.isEmpty() && domain.isEmpty();
        }
    }

    public static void save(Context context, String host, String username, String password, String domain) throws Exception {
        if (host == null || host.trim().isEmpty()) return;
        JSONObject object = new JSONObject();
        object.put("u", username == null ? "" : username);
        object.put("p", password == null ? "" : password);
        object.put("d", domain == null ? "" : domain);
        prefs(context).edit().putString(key(host), encrypt(object.toString())).apply();
    }

    public static Stored load(Context context, String host) {
        if (host == null || host.trim().isEmpty()) return null;
        String value = prefs(context).getString(key(host), "");
        if (value.isEmpty()) return null;
        try {
            JSONObject object = new JSONObject(decrypt(value));
            return new Stored(object.optString("u", ""), object.optString("p", ""), object.optString("d", ""));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean has(Context context, String host) {
        return !prefs(context).getString(key(host), "").isEmpty();
    }

    public static void clear(Context context, String host) {
        if (host == null || host.trim().isEmpty()) return;
        prefs(context).edit().remove(key(host)).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String key(String host) {
        return host.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String encrypt(String text) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] body = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + body.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(body, 0, out, iv.length, body.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private static String decrypt(String value) throws Exception {
        byte[] input = Base64.decode(value, Base64.DEFAULT);
        if (input.length <= IV_BYTES) return "";
        byte[] iv = new byte[IV_BYTES];
        byte[] body = new byte[input.length - IV_BYTES];
        System.arraycopy(input, 0, iv, 0, IV_BYTES);
        System.arraycopy(input, IV_BYTES, body, 0, body.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
        return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance(ANDROID_KEYSTORE);
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
