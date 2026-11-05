package com.testionic.appstate;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * AppState 插件（Android 侧，jsName = "AppState"）。
 *
 * 通过 Capacitor Plugin 的生命周期钩子判断前后台：
 *  - `handleOnResume` → active（App 回到前台）
 *  - `handleOnPause`  → background（App 退到后台）
 *
 * 说明：Android 没有 iOS 那样独立的 `inactive` 状态通知，因此本插件只用 active/background
 * 两个状态（`inactive` 仅在 iOS 上出现）。
 *
 * 事件：`stateChanged`，载荷为 AppState（isActive / state / changedAt /
 * lastBackgroundAt / backgroundDuration）。
 */
@CapacitorPlugin(name = "AppState")
public class AppStatePlugin extends Plugin {

    private static final String EVENT_STATE_CHANGED = "stateChanged";

    private String state = "active";
    private long changedAt = System.currentTimeMillis();
    private long lastBackgroundAt = 0;
    private long backgroundDuration = 0;

    // ---------------------------------------------------------------- API

    @PluginMethod
    public void getState(PluginCall call) {
        call.resolve(stateJson());
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        long now = System.currentTimeMillis();
        if (!"active".equals(state)) {
            backgroundDuration = lastBackgroundAt > 0 ? now - lastBackgroundAt : 0;
            state = "active";
            changedAt = now;
            notifyListeners(EVENT_STATE_CHANGED, stateJson());
        }
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        long now = System.currentTimeMillis();
        if (!"background".equals(state)) {
            state = "background";
            changedAt = now;
            lastBackgroundAt = now;
            backgroundDuration = 0;
            notifyListeners(EVENT_STATE_CHANGED, stateJson());
        }
    }

    // ---------------------------------------------------------------- 内部

    private JSObject stateJson() {
        JSObject ret = new JSObject();
        ret.put("isActive", "active".equals(state));
        ret.put("state", state);
        ret.put("changedAt", changedAt);
        ret.put("lastBackgroundAt", lastBackgroundAt);
        ret.put("backgroundDuration", backgroundDuration);
        return ret;
    }
}
