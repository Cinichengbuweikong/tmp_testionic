package com.testionic.permissions;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限插件（Android）：统一 id → 运行时权限，检查 + 申请。
 *
 * 权限别名用 Capacitor 的 `@Permission` 机制声明，这样 `requestPermissionForAliases()`
 * 会走标准的 `ActivityCompat.requestPermissions` 流程，并在 `@PermissionCallback` 里回调。
 *
 * ⚠️ 这些 `<uses-permission>` 会被 manifest merger 合并进宿主 App，所以插件里只声明了
 * 常用权限；宿主如果还需要别的（短信、电话等），自己往 AndroidManifest 里加并扩展本插件。
 */
@CapacitorPlugin(
        name = "Permissions",
        permissions = {
                @Permission(alias = "camera", strings = { Manifest.permission.CAMERA }),
                @Permission(alias = "microphone", strings = { Manifest.permission.RECORD_AUDIO }),
                @Permission(alias = "location", strings = {
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }),
                @Permission(alias = "locationCoarse", strings = { Manifest.permission.ACCESS_COARSE_LOCATION }),
                @Permission(alias = "notifications", strings = { "android.permission.POST_NOTIFICATIONS" }),
                @Permission(alias = "photos", strings = {
                        "android.permission.READ_MEDIA_IMAGES",
                        "android.permission.READ_MEDIA_VIDEO"
                }),
                @Permission(alias = "storage", strings = {
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }),
                @Permission(alias = "contacts", strings = { Manifest.permission.READ_CONTACTS }),
                @Permission(alias = "calendar", strings = { Manifest.permission.READ_CALENDAR }),
                @Permission(alias = "activityRecognition", strings = { Manifest.permission.ACTIVITY_RECOGNITION }),
                @Permission(alias = "bodySensors", strings = { Manifest.permission.BODY_SENSORS }),
                @Permission(alias = "bluetooth", strings = {
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                })
        }
)
public class PermissionsPlugin extends Plugin {

    private static final String[] ANDROID_IDS = {
            "camera", "microphone", "location", "locationCoarse", "notifications", "photos",
            "storage", "contacts", "calendar", "activityRecognition", "bodySensors", "bluetooth"
    };

    // ------------------------------------------------------------ API

    @PluginMethod
    public void check(PluginCall call) {
        call.resolve(resultsFor(requestedIds(call)));
    }

    @PluginMethod
    public void request(PluginCall call) {
        List<String> ids = requestedIds(call);
        Set<String> aliases = new LinkedHashSet<>();
        for (String id : ids) {
            String alias = effectiveAlias(id);
            if (alias == null) {
                continue;
            }
            PermissionState state = getPermissionState(alias);
            if (state != PermissionState.GRANTED) {
                aliases.add(alias);
            }
        }
        if (aliases.isEmpty()) {
            call.resolve(resultsFor(ids));
            return;
        }
        requestPermissionForAliases(aliases.toArray(new String[0]), call, "permissionsCallback");
    }

    @PermissionCallback
    private void permissionsCallback(PluginCall call) {
        call.resolve(resultsFor(requestedIds(call)));
    }

    @PluginMethod
    public void getSupported(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("platform", "android");
        com.getcapacitor.JSArray list = new com.getcapacitor.JSArray();
        List<JSObject> items = new ArrayList<>();
        items.add(info("camera", true, new String[]{Manifest.permission.CAMERA}, null));
        items.add(info("microphone", true, new String[]{Manifest.permission.RECORD_AUDIO}, null));
        items.add(info("location", true,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                "Android 12+ 用户可能只给「大致位置」，此时 location 也会是 granted（精度由系统决定）"));
        items.add(info("locationCoarse", true, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, null));
        items.add(info("notifications", Build.VERSION.SDK_INT >= 33, new String[]{"android.permission.POST_NOTIFICATIONS"},
                Build.VERSION.SDK_INT >= 33 ? null : "Android 13 以下不需要通知权限"));
        items.add(info("photos", true,
                new String[]{Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_IMAGES" : Manifest.permission.READ_EXTERNAL_STORAGE},
                "Android 14+ 支持「仅选中部分照片」，本插件按 granted/denied 二元处理"));
        items.add(info("storage", Build.VERSION.SDK_INT < 33, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                Build.VERSION.SDK_INT >= 33 ? "Android 13+ 请用 photos（分区存储已废弃 READ_EXTERNAL_STORAGE）"
                        : "Android 10+ 公共目录受分区存储限制"));
        items.add(info("contacts", true, new String[]{Manifest.permission.READ_CONTACTS}, null));
        items.add(info("calendar", true, new String[]{Manifest.permission.READ_CALENDAR}, null));
        items.add(info("activityRecognition", Build.VERSION.SDK_INT >= 29,
                new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, null));
        items.add(info("bodySensors", true, new String[]{Manifest.permission.BODY_SENSORS}, null));
        items.add(info("bluetooth", Build.VERSION.SDK_INT >= 31,
                new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT},
                Build.VERSION.SDK_INT >= 31 ? null : "Android 12 以下蓝牙是普通权限，安装即授予"));
        ret.put("permissions", new com.getcapacitor.JSArray(new ArrayList<JSObject>(items)));
        call.resolve(ret);
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        JSObject ret = new JSObject();
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getContext().getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            ret.put("opened", true);
        } catch (Exception e) {
            ret.put("opened", false);
        }
        call.resolve(ret);
    }

    // ------------------------------------------------------------ 内部

    private List<String> requestedIds(PluginCall call) {
        List<String> ids = new ArrayList<>();
        com.getcapacitor.JSArray array = call.getArray("permissions");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String id = array.optString(i, null);
                if (id != null && !id.isEmpty() && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /**
     * 把统一 id 映射到 Android 上的权限别名。
     *
     * 返回 null 表示"这个 id 在当前系统版本上没有运行时权限"（由 `autoState` 决定结果）。
     */
    private String effectiveAlias(String id) {
        switch (id) {
            case "camera":
            case "microphone":
            case "location":
            case "locationCoarse":
            case "contacts":
            case "calendar":
            case "bodySensors":
                return id;
            case "notifications":
                return Build.VERSION.SDK_INT >= 33 ? "notifications" : null;
            case "photos":
                return Build.VERSION.SDK_INT >= 33 ? "photos" : "storage";
            case "storage":
                return "storage";
            case "activityRecognition":
                return Build.VERSION.SDK_INT >= 29 ? "activityRecognition" : null;
            case "bluetooth":
                return Build.VERSION.SDK_INT >= 31 ? "bluetooth" : null;
            default:
                return null;
        }
    }

    private String autoState(String id) {
        switch (id) {
            case "notifications":
                // Android 13 以下没有通知权限（通知默认可见）
                return Build.VERSION.SDK_INT >= 33 ? "prompt" : "granted";
            case "activityRecognition":
                return Build.VERSION.SDK_INT >= 29 ? "prompt" : "granted";
            case "bluetooth":
                return Build.VERSION.SDK_INT >= 31 ? "prompt" : "granted";
            case "camera":
            case "microphone":
            case "location":
            case "locationCoarse":
            case "photos":
            case "storage":
            case "contacts":
            case "calendar":
            case "bodySensors":
                return "prompt";
            default:
                return "unsupported";
        }
    }

    private JSObject resultsFor(List<String> ids) {
        JSObject results = new JSObject();
        JSObject details = new JSObject();
        for (String id : ids) {
            String alias = effectiveAlias(id);
            String state;
            if (alias == null) {
                state = autoState(id);
            } else {
                PermissionState permissionState = getPermissionState(alias);
                state = permissionState == null ? "unsupported" : permissionState.toString();
            }
            results.put(id, state);
            details.put(id, describe(id, alias, state));
        }
        JSObject ret = new JSObject();
        ret.put("results", results);
        ret.put("details", details);
        return ret;
    }

    private String describe(String id, String alias, String state) {
        if (alias == null) {
            return "当前系统版本不需要该权限（" + state + "）";
        }
        if ("denied".equals(state)) {
            boolean canAsk = hasActivity() && androidx.appcompat.app.AppCompatActivity.class.isInstance(getActivity())
                    ? androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                            getActivity(), permissionStringFor(alias))
                    : false;
            return canAsk
                    ? "被拒绝过一次；再次 request() 会弹窗（建议先解释用途）"
                    : "已被拒绝；系统可能不再弹窗，需要去设置页手动开启";
        }
        return "Android 运行时权限状态：" + state;
    }

    private boolean hasActivity() {
        return getActivity() != null;
    }

    private String permissionStringFor(String alias) {
        switch (alias) {
            case "camera": return Manifest.permission.CAMERA;
            case "microphone": return Manifest.permission.RECORD_AUDIO;
            case "location": return Manifest.permission.ACCESS_FINE_LOCATION;
            case "locationCoarse": return Manifest.permission.ACCESS_COARSE_LOCATION;
            case "notifications": return "android.permission.POST_NOTIFICATIONS";
            case "photos": return "android.permission.READ_MEDIA_IMAGES";
            case "storage": return Manifest.permission.READ_EXTERNAL_STORAGE;
            case "contacts": return Manifest.permission.READ_CONTACTS;
            case "calendar": return Manifest.permission.READ_CALENDAR;
            case "activityRecognition": return Manifest.permission.ACTIVITY_RECOGNITION;
            case "bodySensors": return Manifest.permission.BODY_SENSORS;
            case "bluetooth": return Manifest.permission.BLUETOOTH_SCAN;
            default: return "";
        }
    }

    private JSObject info(String id, boolean supported, String[] requires, String note) {
        JSObject item = new JSObject();
        item.put("id", id);
        item.put("supported", supported);
        com.getcapacitor.JSArray list = new com.getcapacitor.JSArray();
        List<String> values = new ArrayList<>();
        for (String require : requires) {
            values.add(require);
        }
        item.put("requires", new com.getcapacitor.JSArray(new ArrayList<String>(values)));
        if (note != null) {
            item.put("note", note);
        }
        return item;
    }
}
