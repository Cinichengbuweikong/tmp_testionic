package com.testionic.securestorage;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 安全加密存储（Android）：AndroidKeyStore 中的 AES-256-GCM 密钥 + SharedPreferences 密文。
 *
 * - 普通条目用 `capacitor_secure_storage_v1` 密钥（无需认证）；
 * - `requireBiometric: true` 的条目用 `capacitor_secure_storage_bio_v1` 密钥，该密钥由
 *   `setUserAuthenticationRequired(true)` + `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`
 *   保护 —— 每次加解密都必须先通过 BiometricPrompt（CryptoObject 绑定），否则 doFinal 抛
 *   UserNotAuthenticatedException。
 *
 * 生物识别门控要求 Android 10 (API 29) 及以上（API 28 平台没有 BiometricManager）。
 *
 * 指纹/面容重新录入会让 Keystore 永久失效该密钥（setInvalidatedByBiometricEnrollment），
 * 旧条目将无法解密 → 插件返回 `BIOMETRIC_KEY_INVALIDATED`，业务层应清理并重新写入。
 */
@CapacitorPlugin(name = "SecureStorage")
public class SecureStoragePlugin extends Plugin {

    private static final String PREFS_NAME = "capacitor_secure_storage";
    private static final String ALIAS_PLAIN = "capacitor_secure_storage_v1";
    private static final String ALIAS_BIO = "capacitor_secure_storage_bio_v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    /** 生物识别门控的最低系统版本：Android 10。 */
    private static final int MIN_BIOMETRIC_SDK = Build.VERSION_CODES.Q;

    // ---------------------------------------------------------------- 存储

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private SecretKey getOrCreateKey(String alias, boolean requireAuth) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        java.security.Key existing = null;
        try {
            existing = keyStore.getKey(alias, null);
        } catch (UnrecoverableKeyException e) {
            // 密钥被系统失效：删掉重新生成（旧密文将无法解密，由调用方报 VAULT_INVALIDATED）
            keyStore.deleteEntry(alias);
        }
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }

        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);
        if (requireAuth) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // timeout=0 = 每次操作都要认证；此时只允许 AUTH_BIOMETRIC_STRONG
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
            } else {
                // API 29：-1 = 每次操作都要认证（该方法在 API 30 起废弃，但仍然有效）
                builder.setUserAuthenticationValidityDurationSeconds(-1);
            }
            builder.setInvalidatedByBiometricEnrollment(true);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(builder.build());
        return generator.generateKey();
    }

    @PluginMethod
    public void set(PluginCall call) {
        String key = call.getString("key");
        String value = call.getString("value");
        if (key == null || key.isEmpty()) {
            call.reject("key 不能为空", "INVALID_KEY");
            return;
        }
        if (value == null) {
            call.reject("value 不能为 null（删除请用 remove）", "INVALID_VALUE");
            return;
        }
        boolean requireBiometric = Boolean.TRUE.equals(call.getBoolean("requireBiometric", false));
        if (requireBiometric && Build.VERSION.SDK_INT < MIN_BIOMETRIC_SDK) {
            call.reject("BIOMETRY_UNSUPPORTED: 生物识别存储需要 Android 10 (API 29) 及以上", "BIOMETRY_UNSUPPORTED");
            return;
        }

        try {
            SecretKey secretKey = getOrCreateKey(requireBiometric ? ALIAS_BIO : ALIAS_PLAIN, requireBiometric);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] plainBytes = value.getBytes(StandardCharsets.UTF_8);

            if (!requireBiometric) {
                store(key, cipher, plainBytes, false);
                resolveStored(call, key, false);
                return;
            }

            if (BiometricGate.canAuthenticate(getContext()) != BiometricGate.STATUS_OK) {
                call.reject("BIOMETRY_NOT_ENROLLED: 设备未录入指纹/面容，无法使用生物识别存储", "BIOMETRY_NOT_ENROLLED");
                return;
            }
            final String fKey = key;
            BiometricGate.prompt(
                    getActivity(),
                    call.getString("title", "保存加密数据"),
                    call.getString("reason", "请验证身份以保存该项"),
                    call.getString("cancelText", "取消"),
                    cipher,
                    new BiometricGate.Callback() {
                        @Override
                        public void onSuccess(Cipher authorized) {
                            try {
                                store(fKey, authorized, plainBytes, true);
                                resolveStored(call, fKey, true);
                            } catch (Exception e) {
                                call.reject("ENCRYPT_FAILED: " + e.getMessage(), e);
                            }
                        }

                        @Override
                        public void onError(String code, String message) {
                            call.reject(message, code);
                        }
                    });
        } catch (Exception e) {
            call.reject(describeCryptoError(e), e);
        }
    }

    private void store(String key, Cipher cipher, byte[] plainBytes, boolean requireBiometric) throws Exception {
        byte[] cipherBytes = cipher.doFinal(plainBytes);
        JSONObject record = new JSONObject();
        record.put("v", 1);
        record.put("alg", "A256GCM");
        record.put("bio", requireBiometric);
        record.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        record.put("ct", Base64.encodeToString(cipherBytes, Base64.NO_WRAP));
        prefs().edit().putString(key, record.toString()).apply();
    }

    private void resolveStored(PluginCall call, String key, boolean requireBiometric) {
        JSObject ret = new JSObject();
        ret.put("key", key);
        ret.put("requireBiometric", requireBiometric);
        call.resolve(ret);
    }

    @PluginMethod
    public void get(PluginCall call) {
        String key = call.getString("key");
        if (key == null || key.isEmpty()) {
            call.reject("key 不能为空", "INVALID_KEY");
            return;
        }
        String raw = prefs().getString(key, null);
        if (raw == null) {
            call.resolve(notFound(key));
            return;
        }

        JSONObject record;
        try {
            record = new JSONObject(raw);
        } catch (JSONException e) {
            call.reject("CORRUPTED_DATA: 记录无法解析", "CORRUPTED_DATA");
            return;
        }
        boolean bio = record.optBoolean("bio", false);
        byte[] iv = Base64.decode(record.optString("iv"), Base64.NO_WRAP);
        byte[] cipherBytes = Base64.decode(record.optString("ct"), Base64.NO_WRAP);

        if (bio && Build.VERSION.SDK_INT < MIN_BIOMETRIC_SDK) {
            call.reject("BIOMETRY_UNSUPPORTED: 该条目需要 Android 10 (API 29) 及以上", "BIOMETRY_UNSUPPORTED");
            return;
        }

        try {
            SecretKey secretKey = getOrCreateKey(bio ? ALIAS_BIO : ALIAS_PLAIN, bio);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));

            if (!bio) {
                resolveValue(call, key, new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8), false);
                return;
            }
            if (BiometricGate.canAuthenticate(getContext()) != BiometricGate.STATUS_OK) {
                call.reject("BIOMETRY_NOT_ENROLLED: 设备未录入指纹/面容，无法读取该项", "BIOMETRY_NOT_ENROLLED");
                return;
            }
            final String fKey = key;
            BiometricGate.prompt(
                    getActivity(),
                    call.getString("title", "读取加密数据"),
                    call.getString("reason", "请验证身份以读取该项"),
                    call.getString("cancelText", "取消"),
                    cipher,
                    new BiometricGate.Callback() {
                        @Override
                        public void onSuccess(Cipher authorized) {
                            try {
                                String value = new String(authorized.doFinal(cipherBytes), StandardCharsets.UTF_8);
                                resolveValue(call, fKey, value, true);
                            } catch (Exception e) {
                                call.reject(describeCryptoError(e), e);
                            }
                        }

                        @Override
                        public void onError(String code, String message) {
                            call.reject(message, code);
                        }
                    });
        } catch (Exception e) {
            call.reject(describeCryptoError(e), e);
        }
    }

    private void resolveValue(PluginCall call, String key, String value, boolean bio) {
        JSObject ret = new JSObject();
        ret.put("key", key);
        ret.put("value", value);
        ret.put("requireBiometric", bio);
        ret.put("notFound", false);
        call.resolve(ret);
    }

    private JSObject notFound(String key) {
        JSObject ret = new JSObject();
        ret.put("key", key);
        // JSObject.put(key, null) 会把 key 删掉，必须用 JSONObject.NULL 才能让 JS 拿到 null
        ret.put("value", JSONObject.NULL);
        ret.put("requireBiometric", false);
        ret.put("notFound", true);
        return ret;
    }

    /** 把各类加密异常翻译成稳定的错误码（业务层可以据此清理数据）。 */
    private String describeCryptoError(Exception e) {
        if (e instanceof KeyPermanentlyInvalidatedException) {
            return "BIOMETRIC_KEY_INVALIDATED: 生物识别信息已变更，该条目无法再解密，请重新写入";
        }
        if (e instanceof AEADBadTagException) {
            return "VAULT_INVALIDATED: 密文与当前密钥不匹配（App 数据被备份恢复或密钥被清除），请重新写入";
        }
        if (e instanceof android.security.keystore.UserNotAuthenticatedException) {
            return "BIOMETRY_REQUIRED: 需要先通过生物识别验证";
        }
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (msg.contains("invalidated") || msg.contains("permanently")) {
            return "BIOMETRIC_KEY_INVALIDATED: 生物识别信息已变更，该条目无法再解密，请重新写入";
        }
        if (msg.contains("unrecoverable") || msg.contains("bad tag")) {
            return "VAULT_INVALIDATED: 密钥与密文不匹配，请重新写入";
        }
        return "CRYPTO_FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    @PluginMethod
    public void remove(PluginCall call) {
        String key = call.getString("key");
        if (key == null || key.isEmpty()) {
            call.reject("key 不能为空", "INVALID_KEY");
            return;
        }
        boolean existed = prefs().contains(key);
        prefs().edit().remove(key).apply();
        JSObject ret = new JSObject();
        ret.put("key", key);
        ret.put("deleted", existed);
        call.resolve(ret);
    }

    @PluginMethod
    public void keys(PluginCall call) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
            keys.add(entry.getKey());
        }
        JSObject ret = new JSObject();
        ret.put("keys", keys);
        call.resolve(ret);
    }

    @PluginMethod
    public void clear(PluginCall call) {
        int count = prefs().getAll().size();
        prefs().edit().clear().apply();
        JSObject ret = new JSObject();
        ret.put("cleared", count);
        call.resolve(ret);
    }

    @PluginMethod
    public void isBiometryAvailable(PluginCall call) {
        JSObject ret = new JSObject();
        if (Build.VERSION.SDK_INT < MIN_BIOMETRIC_SDK) {
            ret.put("available", false);
            ret.put("type", "none");
            ret.put("enrolled", false);
            ret.put("reason", "REQUIRES_ANDROID_10_API_29");
            call.resolve(ret);
            return;
        }
        int status = BiometricGate.canAuthenticate(getContext());
        boolean available = status == BiometricGate.STATUS_OK;
        boolean enrolled = status != BiometricGate.STATUS_ERROR_NO_HARDWARE
                && status != BiometricGate.STATUS_ERROR_HW_UNAVAILABLE;
        ret.put("available", available);
        ret.put("type", available ? BiometricGate.biometryType() : "none");
        ret.put("enrolled", enrolled);
        if (!available) {
            ret.put("reason", BiometricGate.describe(status));
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void authenticate(PluginCall call) {
        if (Build.VERSION.SDK_INT < MIN_BIOMETRIC_SDK) {
            call.reject("BIOMETRY_UNSUPPORTED: 需要 Android 10 (API 29) 及以上", "BIOMETRY_UNSUPPORTED");
            return;
        }
        if (BiometricGate.canAuthenticate(getContext()) != BiometricGate.STATUS_OK) {
            call.reject("BIOMETRY_NOT_ENROLLED: 设备未录入指纹/面容", "BIOMETRY_NOT_ENROLLED");
            return;
        }
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("当前没有可用的 Activity", "NO_ACTIVITY");
            return;
        }
        BiometricGate.prompt(
                activity,
                call.getString("title", "身份验证"),
                call.getString("reason", "请验证身份"),
                call.getString("cancelText", "取消"),
                null,
                new BiometricGate.Callback() {
                    @Override
                    public void onSuccess(Cipher authorized) {
                        JSObject ret = new JSObject();
                        ret.put("success", true);
                        call.resolve(ret);
                    }

                    @Override
                    public void onError(String code, String message) {
                        if ("AUTH_CANCELED".equals(code)) {
                            JSObject ret = new JSObject();
                            ret.put("success", false);
                            ret.put("reason", "USER_CANCELED");
                            call.resolve(ret);
                        } else {
                            call.reject(message, code);
                        }
                    }
                });
    }

    @PluginMethod
    public void getInfo(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("platform", "android");
        ret.put("backend", "AndroidKeyStore AES-256-GCM + SharedPreferences");
        ret.put("itemCount", prefs().getAll().size());
        JSObject bio = new JSObject();
        if (Build.VERSION.SDK_INT >= MIN_BIOMETRIC_SDK) {
            int status = BiometricGate.canAuthenticate(getContext());
            bio.put("available", status == BiometricGate.STATUS_OK);
            bio.put("type", status == BiometricGate.STATUS_OK ? BiometricGate.biometryType() : "none");
            bio.put("enrolled", status != BiometricGate.STATUS_ERROR_NO_HARDWARE
                    && status != BiometricGate.STATUS_ERROR_HW_UNAVAILABLE);
        } else {
            bio.put("available", false);
            bio.put("type", "none");
            bio.put("enrolled", false);
        }
        ret.put("biometry", bio);
        call.resolve(ret);
    }
}
