package com.testionic.permissions;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限插件模板（Android 侧）。
 *
 * ============================ 这是一个「模板」============================
 * 现在这张表（{@link #DEFINITIONS}）已经覆盖了常见权限，可以直接用；但它**不是最终形态**。
 * 后续的人或 AI 应该把它当作起点，按业务需要增删权限、改名（例如把 `photos` 拆成
 * `photosReadOnly` / `photosFull`）、或者补上 Android 14 的「部分照片」细分状态。
 *
 * 添加一个新权限只需要两步（详见插件目录的 TEMPLATE.md）：
 * 1. 在 {@link #DEFINITIONS} 里加**一行** {@code PermissionDef}；
 * 2. 在 {@code @CapacitorPlugin(permissions = {...})} 里加对应的 {@code @Permission} 别名
 *    （Capacitor 的别名是**编译期注解**，无法从运行时表里生成，这是唯一绕不开的重复），
 *    以及（如有需要）在 `android/src/main/AndroidManifest.xml` 里声明权限。
 * 之后 `check() / request() / getSupported()` 都**自动**支持它，不需要改这三个方法。
 *
 * 设计约定（改代码时请保持）：
 * - 统一 id 是「业务语义」（camera），别名是「平台实现」（camera → CAMERA）；
 * - 同一个 id 允许按系统版本解析到不同别名（例如 photos → 13+ 用 READ_MEDIA_IMAGES、
 *   12 及以下用 READ_EXTERNAL_STORAGE），这是 {@link AliasResolver} 的唯一职责；
 * - 某个 id 在当前版本上**没有**对应运行时权限时返回 {@code stateWhenNoAlias}
 *   （"granted" = 安装即授予；"unsupported" = 平台没有这个概念），不要返回 null 让调用方猜；
 * - 别名必须与 `@Permission` 注解里的 alias 完全一致，否则 `getPermissionState()` 返回 null。
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
                // ⬇️ 新增权限：在这里加 @Permission(alias = "...", strings = { Manifest.permission.XXX })
        }
)
public class PermissionsPlugin extends Plugin {

    /** 把权限 id 映射到 `@Permission` 别名；返回 null 表示当前系统版本上没有运行时权限。 */
    private interface AliasResolver {
        String aliasFor(int sdkInt);
    }

    /** 一条权限定义：模板里「加一行就能用」的最小单元。 */
    private static final class PermissionDef {
        final String id;
        final AliasResolver alias;
        /** 当 alias 为 null 时 `check()` 返回的状态："granted"（安装即授予）或 "unsupported"。 */
        final String stateWhenNoAlias;
        /** 用于 `shouldShowRequestPermissionRationale()` 的代表性权限（可为 null）。 */
        final String primaryPermission;
        /** `getSupported()` 里展示给 JS 的宿主配置要求。 */
        final String[] requires;
        /** `getSupported()` 里展示的平台差异说明（可为 null）。 */
        final String note;
        /** 该权限概念存在的最低/最高系统版本（maxSdkExclusive = -1 表示不限制）。 */
        final int minSdk;
        final int maxSdkExclusive;

        PermissionDef(String id, AliasResolver alias, String stateWhenNoAlias,
                      String primaryPermission, String[] requires, String note,
                      int minSdk, int maxSdkExclusive) {
            this.id = id;
            this.alias = alias;
            this.stateWhenNoAlias = stateWhenNoAlias;
            this.primaryPermission = primaryPermission;
            this.requires = requires;
            this.note = note;
            this.minSdk = minSdk;
            this.maxSdkExclusive = maxSdkExclusive;
        }
    }

    /**
     * ==================== 权限注册表（模板的核心） ====================
     *
     * 每行一个权限，字段含义见 {@link PermissionDef}；
     * 顺序 = `getSupported()` 的输出顺序（LinkedHashMap 保持插入顺序）。
     */
    private static final Map<String, PermissionDef> DEFINITIONS = buildDefinitions();

    private static Map<String, PermissionDef> buildDefinitions() {
        Map<String, PermissionDef> map = new LinkedHashMap<>();

        map.put("camera", new PermissionDef(
                "camera",
                sdk -> "camera",
                "unsupported",
                Manifest.permission.CAMERA,
                new String[]{ Manifest.permission.CAMERA },
                null, 24, -1));

        map.put("microphone", new PermissionDef(
                "microphone",
                sdk -> "microphone",
                "unsupported",
                Manifest.permission.RECORD_AUDIO,
                new String[]{ Manifest.permission.RECORD_AUDIO },
                null, 24, -1));

        map.put("location", new PermissionDef(
                "location",
                sdk -> "location",
                "unsupported",
                Manifest.permission.ACCESS_FINE_LOCATION,
                new String[]{ Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION },
                "Android 12+ 用户可能只给「大致位置」，此时 location 也是 granted（精度由系统决定）",
                24, -1));

        map.put("locationCoarse", new PermissionDef(
                "locationCoarse",
                sdk -> "locationCoarse",
                "unsupported",
                Manifest.permission.ACCESS_COARSE_LOCATION,
                new String[]{ Manifest.permission.ACCESS_COARSE_LOCATION },
                null, 24, -1));

        map.put("notifications", new PermissionDef(
                "notifications",
                sdk -> sdk >= 33 ? "notifications" : null,
                "granted", // Android 13 以下没有这个运行时权限，通知默认可见
                "android.permission.POST_NOTIFICATIONS",
                new String[]{ "android.permission.POST_NOTIFICATIONS" },
                "Android 13 以下不需要通知权限",
                33, -1));

        // 同一个 id 两套后缀：13+ 用 READ_MEDIA_*，12 及以下用 READ_EXTERNAL_STORAGE
        map.put("photos", new PermissionDef(
                "photos",
                sdk -> sdk >= 33 ? "photos" : "storage",
                "unsupported",
                Manifest.permission.READ_EXTERNAL_STORAGE,
                new String[]{ "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO" },
                "Android 14+ 支持「仅选中部分照片」，本模板按 granted/denied 二元处理",
                24, -1));

        map.put("storage", new PermissionDef(
                "storage",
                sdk -> "storage",
                "unsupported",
                Manifest.permission.READ_EXTERNAL_STORAGE,
                new String[]{ Manifest.permission.READ_EXTERNAL_STORAGE },
                "Android 13+ 请用 photos（分区存储已废弃 READ_EXTERNAL_STORAGE）；Android 10+ 公共目录受分区存储限制",
                24, 33));

        map.put("contacts", new PermissionDef(
                "contacts",
                sdk -> "contacts",
                "unsupported",
                Manifest.permission.READ_CONTACTS,
                new String[]{ Manifest.permission.READ_CONTACTS },
                null, 24, -1));

        map.put("calendar", new PermissionDef(
                "calendar",
                sdk -> "calendar",
                "unsupported",
                Manifest.permission.READ_CALENDAR,
                new String[]{ Manifest.permission.READ_CALENDAR },
                null, 24, -1));

        map.put("activityRecognition", new PermissionDef(
                "activityRecognition",
                sdk -> sdk >= 29 ? "activityRecognition" : null,
                "granted",
                Manifest.permission.ACTIVITY_RECOGNITION,
                new String[]{ Manifest.permission.ACTIVITY_RECOGNITION },
                null, 29, -1));

        map.put("bodySensors", new PermissionDef(
                "bodySensors",
                sdk -> "bodySensors",
                "unsupported",
                Manifest.permission.BODY_SENSORS,
                new String[]{ Manifest.permission.BODY_SENSORS },
                null, 24, -1));

        map.put("bluetooth", new PermissionDef(
                "bluetooth",
                sdk -> sdk >= 31 ? "bluetooth" : null,
                "granted", // Android 12 以下蓝牙是普通权限，安装即授予
                Manifest.permission.BLUETOOTH_SCAN,
                new String[]{ Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT },
                "Android 12 以下蓝牙是普通权限，安装即授予",
                31, -1));

        // ⬇️ 新增权限：照上面的格式加一行即可（再补 @Permission 注解 + Manifest）
        return map;
    }

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
        List<JSObject> items = new ArrayList<>();
        for (PermissionDef def : DEFINITIONS.values()) {
            items.add(info(def));
        }
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

    /** 解析 id 在当前系统版本上应使用的权限别名（null = 没有运行时权限）。 */
    private String effectiveAlias(String id) {
        PermissionDef def = DEFINITIONS.get(id);
        if (def == null) {
            return null;
        }
        return def.alias.aliasFor(Build.VERSION.SDK_INT);
    }

    private String stateWhenNoAlias(String id) {
        PermissionDef def = DEFINITIONS.get(id);
        return def == null ? "unsupported" : def.stateWhenNoAlias;
    }

    private boolean isSupported(PermissionDef def) {
        int sdk = Build.VERSION.SDK_INT;
        if (sdk < def.minSdk) {
            return false;
        }
        return def.maxSdkExclusive < 0 || sdk < def.maxSdkExclusive;
    }

    private JSObject resultsFor(List<String> ids) {
        JSObject results = new JSObject();
        JSObject details = new JSObject();
        for (String id : ids) {
            String alias = effectiveAlias(id);
            String state;
            if (alias == null) {
                state = stateWhenNoAlias(id);
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
        PermissionDef def = DEFINITIONS.get(id);
        if (def == null) {
            return "未知权限 id：" + id + "（检查 DEFINITIONS 表，或参考 TEMPLATE.md 添加）";
        }
        if (alias == null) {
            return "当前系统版本不需要该权限（" + state + "）";
        }
        if ("denied".equals(state) && def.primaryPermission != null && getActivity() != null) {
            boolean canAsk = ActivityCompat.shouldShowRequestPermissionRationale(
                    getActivity(), def.primaryPermission);
            return canAsk
                    ? "被拒绝过一次；再次 request() 会弹窗（建议先解释用途）"
                    : "已被拒绝；系统可能不再弹窗，需要去设置页手动开启";
        }
        return "Android 运行时权限状态：" + state;
    }

    private JSObject info(PermissionDef def) {
        JSObject item = new JSObject();
        item.put("id", def.id);
        item.put("supported", isSupported(def));
        List<String> requires = new ArrayList<>();
        for (String require : def.requires) {
            requires.add(require);
        }
        item.put("requires", new com.getcapacitor.JSArray(new ArrayList<String>(requires)));
        if (def.note != null) {
            item.put("note", def.note);
        }
        return item;
    }
}
