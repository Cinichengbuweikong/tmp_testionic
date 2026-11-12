package com.testionic.bootautostart;

import android.content.Context;

/** 保存"开机自启"开关（SharedPreferences）。 */
final class BootAutostartStore {
    private static final String PREFS = "boot_autostart";
    private static final String KEY_ENABLED = "enabled";

    private BootAutostartStore() {}

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply();
    }
}
