package com.testionic.securestorage;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.fragment.app.FragmentActivity;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;

/**
 * 生物识别门控（Android），基于 **androidx.biometric**。
 *
 * 为什么用 androidx 而不是平台的 `android.hardware.biometrics.BiometricPrompt`：
 * 平台 API 的构造器是包级私有的，只能用 `BiometricPrompt.Builder`，且没有 `PromptInfo`，
 * 取消按钮还必须传 `DialogInterface.OnClickListener`；androidx 版本兼容 API 24+、
 * 统一了 `CryptoObject` 与回调错误码，也是 Google 目前推荐的做法。
 *
 * 注意：biometric 的 `BiometricPrompt` 要求宿主 `Activity` 是 `FragmentActivity`
 * （Capacitor 的 `BridgeActivity extends AppCompatActivity`，满足）。
 */
public final class BiometricGate {

    /** 所有弹窗共用一个守护线程（只用来跑 BiometricPrompt 的回调，不做重活）。 */
    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "capacitor-secure-storage-biometric");
        thread.setDaemon(true);
        return thread;
    });

    // 与 BiometricManager 常量等值（androidx 与平台数值一致）
    public static final int STATUS_OK = 0;
    public static final int STATUS_ERROR_HW_UNAVAILABLE = 1;
    public static final int STATUS_ERROR_NONE_ENROLLED = 11;
    public static final int STATUS_ERROR_NO_HARDWARE = 12;
    public static final int STATUS_ERROR_SECURITY_UPDATE_REQUIRED = 15;

    private BiometricGate() {
    }

    public interface Callback {
        void onSuccess(Cipher authorized);

        void onError(String code, String message);
    }

    public static int canAuthenticate(android.content.Context context) {
        if (context == null) {
            return STATUS_ERROR_NO_HARDWARE;
        }
        BiometricManager manager = BiometricManager.from(context.getApplicationContext());
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
    }

    public static String biometryType() {
        // androidx 的 BiometricManager 不暴露具体类型（指纹/面容），统一报 biometric
        return "biometric";
    }

    public static String describe(int status) {
        switch (status) {
            case STATUS_OK:
                return "OK";
            case STATUS_ERROR_HW_UNAVAILABLE:
                return "HARDWARE_UNAVAILABLE";
            case STATUS_ERROR_NONE_ENROLLED:
                return "NONE_ENROLLED";
            case STATUS_ERROR_NO_HARDWARE:
                return "NO_HARDWARE";
            case STATUS_ERROR_SECURITY_UPDATE_REQUIRED:
                return "SECURITY_UPDATE_REQUIRED";
            default:
                return "UNKNOWN(" + status + ")";
        }
    }

    public static void prompt(Activity activity, String title, String subtitle, String cancelText,
                              Cipher cipher, Callback callback) {
        if (activity == null) {
            callback.onError("NO_ACTIVITY", "NO_ACTIVITY: 当前没有可用的 Activity");
            return;
        }
        if (!(activity instanceof FragmentActivity)) {
            callback.onError("NO_FRAGMENT_ACTIVITY",
                    "生物识别需要 FragmentActivity（Capacitor 的 BridgeActivity 满足；自定义 Activity 请继承 AppCompatActivity）");
            return;
        }
        FragmentActivity fragmentActivity = (FragmentActivity) activity;
        // 复用同一个后台线程：旧实现每次弹窗都 new 一个 SingleThreadExecutor 且从不 shutdown，
        // 用户多试几次就会泄漏线程（在低端机上表现为线程数持续增长）
        Executor executor = EXECUTOR;

        BiometricPrompt.AuthenticationCallback authCallback = new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                switch (errorCode) {
                    case BiometricPrompt.ERROR_USER_CANCELED:
                    case BiometricPrompt.ERROR_NEGATIVE_BUTTON:
                    case BiometricPrompt.ERROR_CANCELED:
                        callback.onError("AUTH_CANCELED", "用户取消了生物识别");
                        break;
                    case BiometricPrompt.ERROR_LOCKOUT:
                    case BiometricPrompt.ERROR_LOCKOUT_PERMANENT:
                        callback.onError("AUTH_LOCKOUT", "尝试次数过多，已被锁定：" + errString);
                        break;
                    case BiometricPrompt.ERROR_NO_BIOMETRICS:
                    case BiometricPrompt.ERROR_HW_NOT_PRESENT:
                        callback.onError("BIOMETRY_UNAVAILABLE", "设备没有可用的生物识别：" + errString);
                        break;
                    default:
                        callback.onError("AUTH_ERROR", "生物识别失败(" + errorCode + ")：" + errString);
                        break;
                }
            }

            @Override
            public void onAuthenticationFailed() {
                // 单次不匹配：不结束流程，系统会继续提示，用户可重试
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                Cipher authorized = result.getCryptoObject() != null
                        ? result.getCryptoObject().getCipher()
                        : cipher;
                callback.onSuccess(authorized);
            }
        };

        // 必须在主线程创建
        activity.runOnUiThread(() -> {
            BiometricPrompt prompt = new BiometricPrompt(fragmentActivity, executor, authCallback);
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButtonText(cancelText != null ? cancelText : "取消")
                    .build();
            if (cipher != null) {
                prompt.authenticate(info, new BiometricPrompt.CryptoObject(cipher));
            } else {
                prompt.authenticate(info);
            }
        });
    }
}
