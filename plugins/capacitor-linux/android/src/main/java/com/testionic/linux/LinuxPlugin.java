package com.testionic.linux;

import android.os.Build;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Capacitor bridge for the Alpine Linux VM.
 *
 * <p>All work that can block (booting, extracting the rootfs, running a
 * command) happens on {@link LinuxVm}'s own worker thread, never on Capacitor's
 * serial plugin thread: a TCG boot takes 10-20 s and would otherwise stall every
 * other plugin call in the app.
 */
@CapacitorPlugin(name = "Linux")
public class LinuxPlugin extends Plugin implements LinuxVm.Listener {

    static final String TAG = "CapacitorLinux";

    /**
     * Process-wide VM. Keeping it static means an Activity recreation (rotation,
     * theme change) re-attaches to the running VM instead of killing it.
     */
    private static LinuxVm sharedVm;
    private static final Object LOCK = new Object();

    private LinuxVm vm;

    @Override
    public void load() {
        synchronized (LOCK) {
            if (sharedVm == null) {
                sharedVm = new LinuxVm(getContext(), this);
            } else {
                sharedVm.attachListener(this);
            }
            vm = sharedVm;
        }
    }

    @Override
    protected void handleOnDestroy() {
        // Detach only: the VM keeps running if the app is still alive, so a
        // configuration change does not lose the guest's state.
        synchronized (LOCK) {
            if (sharedVm != null) {
                sharedVm.detachListener(this);
            }
        }
        super.handleOnDestroy();
    }

    // ------------------------------------------------------------------ events

    @Override
    public void onOutput(boolean stderr, String data) {
        JSObject ev = new JSObject();
        ev.put("data", data);
        ev.put("stream", stderr ? "stderr" : "stdout");
        notifyListeners("consoleOutput", ev);
    }

    @Override
    public void onEngineLog(String line) {
        // QEMU's own stderr is the first place to look when the VM fails to boot.
        Log.i(TAG, "qemu: " + line);
        JSObject ev = new JSObject();
        ev.put("line", line);
        notifyListeners("engineLog", ev);
    }

    @Override
    public void onStateChanged(LinuxVm.State state, String detail) {
        JSObject ev = new JSObject();
        ev.put("state", state.name().toLowerCase(Locale.ROOT));
        ev.put("detail", detail);
        notifyListeners("vmState", ev);
    }

    @Override
    public void onExit(int code, long uptimeMs, boolean requested, String reason) {
        JSObject ev = new JSObject();
        ev.put("code", code);
        ev.put("uptimeMs", uptimeMs);
        ev.put("requested", requested);
        ev.put("reason", reason);
        notifyListeners("vmExited", ev);
    }

    // --------------------------------------------------------------- utilities

    private static boolean isSupportedAbi() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                return true;
            }
        }
        return false;
    }

    private JSObject capabilities() {
        boolean abi = isSupportedAbi();
        JSObject ret = new JSObject();
        ret.put("platform", "android");
        ret.put("supported", abi);
        ret.put("engine", "qemu-tcg");
        ret.put("architecture", "aarch64");
        ret.put("nativeExecution", false);
        ret.put("isolation", "process");
        ret.put("apkAvailable", true);
        ret.put("persistence", "disk");
        JSObject minimum = new JSObject();
        minimum.put("androidSdk", 24);
        minimum.put("abis", new String[]{"arm64-v8a"});
        minimum.put("deviceRamMb", 2048);
        ret.put("minimum", minimum);
        if (!abi) {
            ret.put("reasons", new String[]{
                    "this device has no arm64-v8a ABI; the bundled QEMU emulator is arm64 only"
            });
        } else {
            ret.put("reasons", new String[0]);
        }
        return ret;
    }

    private boolean checkRunning(PluginCall call) {
        if (!vm.isRunning()) {
            call.reject("VM_NOT_RUNNING: call start() first", "VM_NOT_RUNNING");
            return false;
        }
        return true;
    }

    // ----------------------------------------------------------------- methods

    @PluginMethod
    public void getCapabilities(PluginCall call) {
        call.resolve(capabilities());
    }

    @PluginMethod
    public void getInfo(PluginCall call) {
        JSObject ret = vm.info();
        JSObject caps = capabilities();
        ret.put("supported", caps.getBool("supported"));
        ret.put("state", vm.getState().name().toLowerCase(Locale.ROOT));
        call.resolve(ret);
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        call.resolve(vm.status());
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (!isSupportedAbi()) {
            call.reject("UNSUPPORTED_DEVICE: arm64-v8a is required", "UNSUPPORTED_DEVICE");
            return;
        }
        if (vm.isRunning()) {
            call.reject("ALREADY_RUNNING", "ALREADY_RUNNING");
            return;
        }
        final int memoryMb = call.getInt("memoryMb", 1024);
        final int cpus = call.getInt("cpus", 2);
        final String kernelArgs = call.getString("kernelCmdline");
        final long bootTimeoutMs = call.getInt("bootTimeoutMs", 180000);
        // rootfsMode: "ram"（默认，root 在内存）或 "disk"（整个 root 落盘，持久化）
        // persistent:true 是旧写法，等价于 rootfsMode:"disk"。
        String mode = call.getString("rootfsMode");
        if (mode == null) {
            mode = call.getBoolean("persistent", false) ? "disk" : "ram";
        }
        final String rootfsMode = mode;
        final boolean persistent = "disk".equalsIgnoreCase(rootfsMode) || call.getBoolean("persistent", false);
        final int diskSizeMb = call.getInt("diskSizeMb", "disk".equalsIgnoreCase(rootfsMode) ? 2048 : 1024);
        if (memoryMb < 256 || memoryMb > 4096) {
            call.reject("INVALID_OPTION: memoryMb must be 256..4096", "INVALID_OPTION");
            return;
        }
        if (cpus < 1 || cpus > 8) {
            call.reject("INVALID_OPTION: cpus must be 1..8", "INVALID_OPTION");
            return;
        }
        if (persistent && (diskSizeMb < 64 || diskSizeMb > 8192)) {
            call.reject("INVALID_OPTION: diskSizeMb must be 64..8192", "INVALID_OPTION");
            return;
        }
        vm.worker().execute(() -> {
            try {
                vm.start(memoryMb, cpus, kernelArgs, bootTimeoutMs, persistent, diskSizeMb, rootfsMode);
                JSObject ret = vm.status();
                ret.put("booted", true);
                call.resolve(ret);
            } catch (Exception e) {
                Log.e(TAG, "start failed", e);
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                call.reject(msg, msg.contains(":") ? msg.substring(0, msg.indexOf(':')) : "START_FAILED");
            }
        });
    }

    @PluginMethod
    public void stop(PluginCall call) {
        final boolean force = call.getBoolean("force", false);
        if (!vm.isRunning()) {
            call.resolve(vm.status());
            return;
        }
        vm.ioWorker().execute(() -> {
            try {
                vm.stop(force);
                // Give the waiter thread a moment to publish the final state.
                for (int i = 0; i < 100 && vm.isRunning(); i++) {
                    Thread.sleep(50);
                }
                call.resolve(vm.status());
            } catch (Exception e) {
                call.reject(e.getMessage() == null ? e.toString() : e.getMessage(), "STOP_FAILED");
            }
        });
    }

    @PluginMethod
    public void restart(PluginCall call) {
        final int memoryMb = call.getInt("memoryMb", 1024);
        final int cpus = call.getInt("cpus", 2);
        final String kernelArgs = call.getString("kernelCmdline");
        final long bootTimeoutMs = call.getInt("bootTimeoutMs", 180000);
        // rootfsMode: "ram"（默认，root 在内存）或 "disk"（整个 root 落盘，持久化）
        // persistent:true 是旧写法，等价于 rootfsMode:"disk"。
        String mode = call.getString("rootfsMode");
        if (mode == null) {
            mode = call.getBoolean("persistent", false) ? "disk" : "ram";
        }
        final String rootfsMode = mode;
        final boolean persistent = "disk".equalsIgnoreCase(rootfsMode) || call.getBoolean("persistent", false);
        final int diskSizeMb = call.getInt("diskSizeMb", "disk".equalsIgnoreCase(rootfsMode) ? 2048 : 1024);
        vm.worker().execute(() -> {
            try {
                if (vm.isRunning()) {
                    vm.stop(false);
                    for (int i = 0; i < 200 && vm.isRunning(); i++) {
                        Thread.sleep(50);
                    }
                }
                vm.start(memoryMb, cpus, kernelArgs, bootTimeoutMs, persistent, diskSizeMb, rootfsMode);
                call.resolve(vm.status());
            } catch (Exception e) {
                call.reject(e.getMessage() == null ? e.toString() : e.getMessage(), "RESTART_FAILED");
            }
        });
    }

    @PluginMethod
    public void write(PluginCall call) {
        String data = call.getString("data");
        if (data == null) {
            call.reject("INVALID_ARGUMENT: data is required", "INVALID_ARGUMENT");
            return;
        }
        if (!checkRunning(call)) {
            return;
        }
        String encoding = call.getString("encoding", "utf8");
        final byte[] bytes;
        if ("base64".equals(encoding)) {
            bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT);
        } else {
            bytes = data.getBytes(StandardCharsets.UTF_8);
        }
        vm.ioWorker().execute(() -> {
            try {
                vm.write(bytes);
                call.resolve();
            } catch (Exception e) {
                call.reject(e.getMessage() == null ? e.toString() : e.getMessage(), "WRITE_FAILED");
            }
        });
    }

    @PluginMethod
    public void getShareDir(PluginCall call) {
        java.io.File dir = vm.shareDir();
        JSObject ret = new JSObject();
        ret.put("hostPath", dir.getAbsolutePath());
        ret.put("guestPath", LinuxVm.GUEST_SHARE_DIR);
        ret.put("exists", dir.isDirectory());
        ret.put("available", vm.isRunning());
        try {
            String[] names = dir.list();
            ret.put("hostEntries", names == null ? 0 : names.length);
        } catch (Exception ignored) {
            ret.put("hostEntries", 0);
        }
        // 9p is mounted by /init at boot; report where to look if it is missing.
        ret.put("note", "files placed in hostPath appear in the guest as "
                + LinuxVm.GUEST_SHARE_DIR + " (9p over virtio). Verify inside the VM with "
                + "`mount | grep 9p`; if missing, the 9p kernel modules failed to load.");
        call.resolve(ret);
    }

    @PluginMethod
    public void sendKey(PluginCall call) {
        final String key = call.getString("key");
        if (key == null || key.trim().isEmpty()) {
            call.reject("INVALID_ARGUMENT: key is required", "INVALID_ARGUMENT");
            return;
        }
        if (!checkRunning(call)) {
            return;
        }
        final byte[] bytes = LinuxVm.keyBytes(key);
        if (bytes == null) {
            call.reject("INVALID_ARGUMENT: unknown key '" + key + "'", "INVALID_ARGUMENT");
            return;
        }
        final int repeat = Math.max(1, Math.min(100, call.getInt("repeat", 1)));
        vm.ioWorker().execute(() -> {
            try {
                for (int i = 0; i < repeat; i++) {
                    vm.write(bytes);
                    if (repeat > 1) {
                        // Give the guest time to react (e.g. arrow keys in a TUI);
                        // otherwise every repeat lands as one burst.
                        Thread.sleep(30);
                    }
                }
                call.resolve();
            } catch (Exception e) {
                call.reject(e.getMessage() == null ? e.toString() : e.getMessage(), "SEND_KEY_FAILED");
            }
        });
    }

    @PluginMethod
    public void exec(PluginCall call) {
        final String command = call.getString("command");
        if (command == null || command.trim().isEmpty()) {
            call.reject("INVALID_ARGUMENT: command is required", "INVALID_ARGUMENT");
            return;
        }
        if (!checkRunning(call)) {
            return;
        }
        final long timeoutMs = call.getInt("timeoutMs", 30000);
        vm.worker().execute(() -> {
            try {
                call.resolve(vm.exec(command, timeoutMs));
            } catch (Exception e) {
                call.reject(e.getMessage() == null ? e.toString() : e.getMessage(), "EXEC_FAILED");
            }
        });
    }
}
