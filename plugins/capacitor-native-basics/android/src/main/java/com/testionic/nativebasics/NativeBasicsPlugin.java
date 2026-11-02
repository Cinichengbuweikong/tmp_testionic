package com.testionic.nativebasics;

import android.os.Build;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "NativeBasics")
public class NativeBasicsPlugin extends Plugin {

    @PluginMethod
    public void echo(PluginCall call) {
        String value = call.getString("value");
        if (value == null) {
            call.reject("value is required");
            return;
        }

        JSObject ret = new JSObject();
        ret.put("value", value);
        call.resolve(ret);
    }

    @PluginMethod
    public void showToast(PluginCall call) {
        String text = call.getString("text");
        if (text == null) {
            call.reject("text is required");
            return;
        }

        String duration = call.getString("duration", "short");
        final int length = "long".equals(duration) ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT;

        if (getActivity() == null) {
            call.reject("No activity available to show a toast");
            return;
        }

        getActivity()
            .runOnUiThread(() -> Toast.makeText(getContext(), text, length).show());

        call.resolve();
    }

    @PluginMethod
    public void getDeviceInfo(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("manufacturer", safe(Build.MANUFACTURER));
        ret.put("model", safe(Build.MODEL));
        ret.put("osVersion", safe(Build.VERSION.RELEASE));
        ret.put("platform", "android");
        ret.put("sdkInt", Build.VERSION.SDK_INT);
        ret.put("isEmulator", isEmulator());
        call.resolve(ret);
    }

    @NonNull
    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.contains("emulator")
            || Build.MODEL.contains("google_sdk")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for x86")
            || "google_sdk".equals(Build.PRODUCT)
            || Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu");
    }
}
