package com.testionic.linux;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.Os;
import android.util.Log;

import com.getcapacitor.JSObject;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One Alpine Linux VM running under QEMU's TCG emulator as a child process.
 *
 * <h3>Why QEMU and not a chroot/proot</h3>
 * Since Android 10 an app targeting API 29+ runs in {@code untrusted_app}, and
 * SELinux only grants {@code execute_no_trans} on {@code apk_data_file}
 * (i.e. {@code nativeLibraryDir}). Binaries unpacked into the app's data
 * directory cannot be {@code execve()}'d, so a userspace Linux ("proot") only
 * works for apps that keep {@code targetSdk <= 28}. QEMU sidesteps this
 * entirely: the guest never executes anything on the Android host, it is
 * interpreted, and only the QEMU binary itself is exec'd - from
 * {@code nativeLibraryDir}, which is allowed.
 *
 * <h3>Isolation</h3>
 * QEMU is a separate process. A guest kernel panic, a runaway program or an
 * OOM inside the VM kills that process, never the app. The plugin reports it
 * through {@code vmExited} and can start a fresh VM.
 *
 * <h3>I/O model</h3>
 * <ul>
 *   <li>{@code -serial stdio} -> the guest console on /dev/ttyAMA0, mapped to
 *       this process's stdin/stdout (raw bytes, streaming, unbuffered).</li>
 *   <li>A second emulated 16550 (/dev/ttyAMA1) is backed by a QEMU unix-socket
 *       chardev; the app reads it through a {@link LocalSocket}. {@link #exec}
 *       redirects a command's stderr there, which is how stdout and stderr can
 *       be delivered as separate streams.</li>
 * </ul>
 */
public class LinuxVm {

    public static final String TAG = "CapacitorLinux";

    /** Printed by /init right before the login shell is spawned. */
    public static final String READY_MARKER = "capinux: ready";

    /** ASCII unit separator: cannot collide with anything a terminal prints. */
    private static final char SEP = '\u001f';

    public enum State { STOPPED, STARTING, RUNNING, STOPPING, CRASHED, ERROR }

    public interface Listener {
        /** Console (ttyAMA0) bytes. {@code stderr} is the ttyAMA1 side-channel. */
        void onOutput(boolean stderr, String data);

        /** QEMU's own stderr (engine diagnostics, not guest output). */
        void onEngineLog(String line);

        void onStateChanged(State state, String detail);

        void onExit(int code, long uptimeMs, boolean requested, String reason);
    }

    private final Context context;
    private volatile Listener listener;
    /** Boot/stop/exec run here, serialised: the console has one foreground slot. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "caphinux-vm");
        t.setDaemon(true);
        return t;
    });
    /**
     * stdin writes and stop() must NOT queue behind a long-running exec(): if
     * they did, typing into an interactive program would block until that
     * program exits (or its exec() times out).
     */
    private final ExecutorService ioWorker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "caphinux-io");
        t.setDaemon(true);
        return t;
    });
    /** Guards interleaving of console writes (exec's command line vs user bytes). */
    private final Object writeLock = new Object();

    /** Used while no plugin is attached (bridge destroyed / app backgrounded). */
    private static final Listener NOOP = new Listener() {
        @Override
        public void onOutput(boolean stderr, String data) {
        }

        @Override
        public void onEngineLog(String line) {
        }

        @Override
        public void onStateChanged(State state, String detail) {
        }

        @Override
        public void onExit(int code, long uptimeMs, boolean requested, String reason) {
        }
    };

    private volatile Process process;
    private volatile State state = State.STOPPED;
    private volatile long startedAt = 0L;
    private volatile long stoppedAt = 0L;
    private volatile int exitCode = Integer.MIN_VALUE;
    private volatile boolean stopRequested = false;
    private volatile String lastError = null;
    private volatile String configSummary = "";
    private volatile int memoryMb = 1024;
    private volatile int cpus = 2;
    private volatile boolean persistent = false;
    private volatile int diskSizeMb = 1024;
    private volatile CountDownLatch readyLatch = new CountDownLatch(1);

    private LocalSocket stderrSocket;
    private LocalSocket consoleSocket;
    /** Write side of the console chardev (/dev/ttyAMA0). */
    private volatile OutputStream consoleOut;
    /** True once the /dev/ttyAMA1 socket is connected (exec needs it for stderr). */
    private volatile boolean stderrAvailable = false;
    private Thread stdoutPump;
    private Thread stderrPump;
    private Thread enginePump;
    private Thread waiter;

    /** Serialises exec(): the guest console is a single foreground process slot. */
    private final Object execLock = new Object();

    /** Set while an exec() is in flight; console chunks are mirrored into it. */
    private final Object captureLock = new Object();
    private ExecCapture outCapture;
    private ExecCapture errCapture;
    /**
     * Ctrl-C (or Ctrl-\) arrived while an exec() was running. A shell aborts the
     * rest of a command list when the foreground job dies from SIGINT, so the
     * exec's trailing end-marker is never printed: fail fast instead of waiting
     * for the timeout.
     */
    private volatile boolean execInterrupted = false;

    public LinuxVm(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** Re-points the event sink after an Activity/bridge recreation. */
    public void attachListener(Listener newListener) {
        if (newListener != null) {
            this.listener = newListener;
        }
    }

    public void detachListener(Listener oldListener) {
        if (this.listener == oldListener) {
            this.listener = NOOP;
        }
    }

    // ------------------------------------------------------------------ paths

    private File workDir() {
        return new File(context.getFilesDir(), "linuxvm");
    }

    private File rootfsDir() {
        return new File(workDir(), "alpine");
    }

    private File kernelFile() {
        return new File(rootfsDir(), "kernel");
    }

    private File rootfsFile() {
        return new File(rootfsDir(), "rootfs.cpio.gz");
    }

    private File stampFile() {
        return new File(rootfsDir(), "runtime.stamp");
    }

    /** Host-side directory shared with the guest (mounted at {@link #GUEST_SHARE_DIR}). */
    public File shareDir() {
        File dir = new File(workDir(), "share");
        if (!dir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Raw image backing the guest's persistent data disk (/dev/vda).
     *
     * <p>Created sparse (a full-size file of zeroes would cost real storage) and
     * formatted by the guest on first boot, which is why the rootfs carries
     * e2fsprogs.
     */
    public File diskFile() {
        return new File(workDir(), "disk.img");
    }

    /** Ensures the disk image exists with the requested (sparse) size. */
    private void ensureDisk(int sizeMb) throws IOException {
        File disk = diskFile();
        long want = (long) sizeMb * 1024 * 1024;
        if (disk.isFile() && disk.length() == want) {
            return;
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(disk, "rw")) {
            raf.setLength(want);   // sparse: no blocks are allocated until written
        }
    }

    /** Where the guest sees {@link #shareDir()} (9p mount, created by /init). */
    public static final String GUEST_SHARE_DIR = "/mnt/host";

    private File stderrSocketFile() {
        return new File(workDir(), "ttyAMA1.sock");
    }

    private File consoleSocketFile() {
        return new File(workDir(), "ttyAMA0.sock");
    }

    private String nativeLibraryDir() {
        return context.getApplicationInfo().nativeLibraryDir;
    }

    private File qemuBinary() {
        return new File(nativeLibraryDir(), "libqemu-system-aarch64.so");
    }

    // ----------------------------------------------------------------- status

    public State getState() {
        return state;
    }

    public boolean isRunning() {
        State s = state;
        return s == State.STARTING || s == State.RUNNING || s == State.STOPPING;
    }

    public JSObject status() {
        JSObject ret = new JSObject();
        ret.put("state", state.name().toLowerCase(Locale.ROOT));
        ret.put("running", isRunning());
        if (startedAt > 0) {
            ret.put("startedAt", startedAt);
            ret.put("uptimeMs", Math.max(0, (stoppedAt > 0 ? stoppedAt : System.currentTimeMillis()) - startedAt));
        }
        if (exitCode != Integer.MIN_VALUE) {
            ret.put("exitCode", exitCode);
        }
        ret.put("memoryMb", memoryMb);
        ret.put("cpus", cpus);
        ret.put("consoleTty", "/dev/ttyAMA0");
        ret.put("stderrTty", "/dev/ttyAMA1");
        ret.put("persistence", persistent ? "disk" : "none");
        ret.put("shareDir", shareDir().getAbsolutePath());
        ret.put("guestShareDir", GUEST_SHARE_DIR);
        if (persistent) {
            ret.put("diskBytes", diskFile().length());
        }
        if (lastError != null) {
            ret.put("lastError", lastError);
        }
        Process p = process;
        if (p != null) {
            ret.put("pid", pidOf(p));
        }
        return ret;
    }

    /** Reads the bundled manifest (versions/sizes) without touching the VM. */
    public JSObject info() {
        JSObject ret = new JSObject();
        ret.put("distribution", "alpine");
        ret.put("engine", "qemu-tcg");
        ret.put("architecture", "aarch64");
        ret.put("rootfsMode", "initramfs");
        ret.put("nativeExecution", false);
        ret.put("apkAvailable", true);
        ret.put("executable", qemuBinary().getAbsolutePath());
        ret.put("executablePresent", qemuBinary().isFile());
        ret.put("runtimeExtracted", rootfsFile().isFile() && kernelFile().isFile());
        try (InputStream in = context.getAssets().open("linux/manifest.json")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            JSONObject manifest = new JSONObject(bos.toString("UTF-8"));
            ret.put("distributionVersion", manifest.optString("distributionVersion"));
            ret.put("kernelRelease", manifest.optString("kernelRelease"));
            JSONObject qemu = manifest.optJSONObject("qemu");
            if (qemu != null) {
                ret.put("qemuVersion", qemu.optString("version"));
                ret.put("qemuBytes", qemu.optLong("bytes"));
            }
            JSONObject kernel = manifest.optJSONObject("kernel");
            if (kernel != null) {
                ret.put("kernelBytes", kernel.optLong("bytes"));
            }
            JSONObject rootfs = manifest.optJSONObject("rootfs");
            if (rootfs != null) {
                ret.put("rootfsBytes", rootfs.optLong("bytes"));
            }
            ret.put("runtimeBundled", true);
        } catch (Exception e) {
            ret.put("runtimeBundled", false);
            ret.put("runtimeError", "assets/linux/manifest.json missing: run `npm run fetch-runtime` "
                    + "in plugins/capacitor-linux, then `npm run build:plugins`");
        }
        ret.put("state", state.name().toLowerCase(Locale.ROOT));
        return ret;
    }

    // ------------------------------------------------------------------- start

    /** Boots the VM. Blocking; call from a worker thread. */
    public void start(int memoryMb, int cpus, String extraKernelArgs, long bootTimeoutMs,
                      boolean persistent, int diskSizeMb) throws Exception {
        if (isRunning()) {
            throw new IllegalStateException("ALREADY_RUNNING");
        }
        if (!qemuBinary().isFile()) {
            throw new IllegalStateException("ENGINE_MISSING: " + qemuBinary() + " not found. "
                    + "The host app must package native libraries with extractNativeLibs=true "
                    + "(android.packaging.jniLibs.useLegacyPackaging = true) and the plugin's "
                    + "runtime/ must have been generated with `npm run fetch-runtime`.");
        }
        ensureRuntimeExtracted();

        this.memoryMb = memoryMb;
        this.cpus = cpus;
        this.persistent = persistent;
        this.diskSizeMb = diskSizeMb;
        this.stopRequested = false;
        this.exitCode = Integer.MIN_VALUE;
        this.lastError = null;
        this.stoppedAt = 0L;
        this.readyLatch = new CountDownLatch(1);

        File dir = workDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        // Stale sockets from a previous run would make QEMU refuse to bind.
        for (File stale : new File[]{consoleSocketFile(), stderrSocketFile()}) {
            if (stale.exists() && !stale.delete()) {
                Log.w(TAG, "could not delete stale socket " + stale);
            }
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(qemuBinary().getAbsolutePath());
        cmd.add("-M");
        cmd.add("virt");
        cmd.add("-cpu");
        cmd.add("max");
        cmd.add("-smp");
        cmd.add(String.valueOf(cpus));
        cmd.add("-m");
        cmd.add(String.valueOf(memoryMb));
        cmd.add("-display");
        cmd.add("none");
        // Both serial ports are unix sockets owned by QEMU: a pipe on stdio
        // buffers guest input unpredictably (a write can sit until the next
        // write wakes it), a socket delivers immediately and keeps the two
        // channels symmetric.
        cmd.add("-chardev");
        cmd.add("socket,id=ttyS0,path=" + consoleSocketFile().getAbsolutePath() + ",server=on,wait=off");
        cmd.add("-serial");
        cmd.add("chardev:ttyS0");         // /dev/ttyAMA0: the console (stdin + stdout)
        cmd.add("-chardev");
        cmd.add("socket,id=ttyS1,path=" + stderrSocketFile().getAbsolutePath() + ",server=on,wait=off");
        cmd.add("-serial");
        cmd.add("chardev:ttyS1");         // /dev/ttyAMA1: exec()'s stderr side-channel
        cmd.add("-netdev");
        cmd.add("user,id=net0");
        cmd.add("-device");
        cmd.add("virtio-net-device,netdev=net0");
        // Host <-> guest file share. The guest mounts this at /mnt/host
        // (see scripts/vm/init.sh); security_model=none maps all guest access
        // to the app's uid, which is what we want inside our own sandbox.
        cmd.add("-virtfs");
        cmd.add("local,path=" + shareDir().getAbsolutePath()
                + ",mount_tag=hostshare,security_model=none,multidevs=remap");
        if (persistent) {
            ensureDisk(diskSizeMb);
            cmd.add("-drive");
            cmd.add("file=" + diskFile().getAbsolutePath()
                    + ",format=raw,if=virtio,cache=writeback");
        }
        cmd.add("-kernel");
        cmd.add(kernelFile().getAbsolutePath());
        cmd.add("-initrd");
        cmd.add(rootfsFile().getAbsolutePath());
        StringBuilder args = new StringBuilder("console=ttyAMA0 rdinit=/init loglevel=6 panic=1");
        if (extraKernelArgs != null && !extraKernelArgs.trim().isEmpty()) {
            args.append(' ').append(extraKernelArgs.trim());
        }
        cmd.add("-append");
        cmd.add(args.toString());
        cmd.add("-no-reboot");            // panic=1 reboots -> QEMU exits, app survives
        configSummary = "qemu " + String.join(" ", cmd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir);
        java.util.Map<String, String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", nativeLibraryDir());  // QEMU's bundled .so closure
        env.put("HOME", dir.getAbsolutePath());
        env.put("TMPDIR", new File(dir, "tmp").getAbsolutePath());
        env.put("TZ", "UTC");
        new File(dir, "tmp").mkdirs();

        setState(State.STARTING, null);
        Process p = pb.start();
        this.process = p;
        this.startedAt = System.currentTimeMillis();

        startPumps(p);

        // Wait for /init's readiness marker; a TCG boot takes ~10-20 s on a phone.
        boolean ready = readyLatch.await(bootTimeoutMs, TimeUnit.MILLISECONDS);
        if (!ready) {
            String detail = "VM did not become ready within " + (bootTimeoutMs / 1000) + "s";
            if (!isAlive(p)) {
                detail = "VM process exited before becoming ready (code " + exitValueOf(p) + ")";
            }
            lastError = detail;
            setState(State.ERROR, detail);
            killProcess();
            throw new IllegalStateException("BOOT_TIMEOUT: " + detail);
        }
        setState(State.RUNNING, null);
    }

    private void startPumps(Process p) {
        // QEMU creates the listening sockets during chardev setup; connect with
        // a short retry loop before reading from them.
        consoleSocket = connectSocket(consoleSocketFile(), "console");
        stderrSocket = connectSocket(stderrSocketFile(), "stderr");
        stderrAvailable = stderrSocket != null;
        if (consoleSocket == null) {
            Log.e(TAG, "console socket missing: cannot talk to the guest");
            listener.onEngineLog("error: /dev/ttyAMA0 socket unavailable");
        }

        stdoutPump = new Thread(() -> pump(consoleSocket, false), "caphinux-console");
        stderrPump = new Thread(() -> pump(stderrSocket, true), "caphinux-ttyS1");
        enginePump = new Thread(() -> pumpEngine(p.getErrorStream()), "caphinux-qemu-stderr");
        waiter = new Thread(() -> waitForExit(p), "caphinux-wait");
        for (Thread t : new Thread[]{stdoutPump, stderrPump, enginePump, waiter}) {
            t.setDaemon(true);
            t.start();
        }
        if (consoleSocket != null) {
            try {
                consoleOut = consoleSocket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "console output stream: " + e.getMessage());
            }
        }
    }

    /** Connects to one of QEMU's unix-socket chardevs, retrying until it exists. */
    private LocalSocket connectSocket(File path, String label) {
        for (int i = 0; i < 200; i++) {
            if (process == null || !isAlive(process)) {
                Log.w(TAG, "qemu exited before the " + label + " socket appeared");
                return null;
            }
            try {
                LocalSocket s = new LocalSocket();
                s.connect(new LocalSocketAddress(path.getAbsolutePath(),
                        LocalSocketAddress.Namespace.FILESYSTEM));
                return s;
            } catch (IOException e) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        Log.w(TAG, label + " socket unavailable: " + path);
        return null;
    }

    /** Reads one chardev socket. {@code socket == null} -> nothing to read. */
    private void pump(LocalSocket socket, boolean isStderrChannel) {
        if (socket == null) {
            return;
        }
        byte[] buf = new byte[8192];
        // The readiness marker can straddle two reads, so match against a
        // rolling tail instead of the current chunk alone.
        StringBuilder readyTail = new StringBuilder();
        try {
            InputStream src = socket.getInputStream();
            while (true) {
                int n = src.read(buf);
                if (n < 0) {
                    break;
                }
                if (n == 0) {
                    continue;
                }
                String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                if (!isStderrChannel && readyLatch.getCount() > 0) {
                    readyTail.append(chunk);
                    if (readyTail.length() > 4096) {
                        readyTail.delete(0, readyTail.length() - 4096);
                    }
                    if (readyTail.indexOf(READY_MARKER) >= 0) {
                        readyLatch.countDown();
                    }
                }
                synchronized (captureLock) {
                    ExecCapture cap = isStderrChannel ? errCapture : outCapture;
                    if (cap != null) {
                        cap.append(chunk);
                    }
                }
                listener.onOutput(isStderrChannel, chunk);
            }
        } catch (Exception e) {
            // Socket/stream closed when the VM stops; not an error.
            Log.d(TAG, "pump(" + isStderrChannel + ") ended: " + e.getMessage());
        }
    }

    private void pumpEngine(InputStream in) {
        byte[] buf = new byte[4096];
        StringBuilder line = new StringBuilder();
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                for (int i = 0; i < n; i++) {
                    char c = (char) (buf[i] & 0xff);
                    if (c == '\n') {
                        if (line.length() > 0) {
                            listener.onEngineLog(line.toString());
                            line.setLength(0);
                        }
                    } else if (c != '\r') {
                        line.append(c);
                    }
                }
            }
        } catch (IOException ignored) {
        }
        if (line.length() > 0) {
            listener.onEngineLog(line.toString());
        }
    }

    private void waitForExit(Process p) {
        int code = 0;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        exitCode = code;
        stoppedAt = System.currentTimeMillis();
        long uptime = startedAt > 0 ? stoppedAt - startedAt : 0;
        boolean requested = stopRequested;
        closeQuietly();
        String reason = requested ? "requested" : (code == 0 ? "guest-exit" : "engine-exit");
        if (!requested) {
            lastError = "VM exited unexpectedly (code " + code + ")";
            setState(State.CRASHED, lastError);
        } else {
            setState(State.STOPPED, null);
        }
        listener.onExit(code, uptime, requested, reason);
    }

    // --------------------------------------------------------------------- I/O

    /** Writes raw bytes to the guest console (what a user types in a terminal). */
    public void write(byte[] data) throws IOException {
        if (!isRunning()) {
            throw new IllegalStateException("VM_NOT_RUNNING");
        }
        for (byte b : data) {
            if (b == 0x03 || b == 0x1c) {   // Ctrl-C / Ctrl-\
                markInterrupt();
                break;
            }
        }
        synchronized (writeLock) {
            OutputStream out = consoleOut;
            if (out == null) {
                throw new IllegalStateException("CONSOLE_UNAVAILABLE");
            }
            out.write(data);
            out.flush();
        }
    }

    /** Notes that the user pressed an interrupt key (see {@link #execInterrupted}). */
    public void markInterrupt() {
        if (outCapture != null) {
            execInterrupted = true;
        }
    }

    /**
     * Runs {@code command} inside the guest and returns its separated output.
     *
     * <p>The command is base64-encoded so quotes/newlines survive the trip
     * through the console, written to a temp script and executed with
     * {@code sh}. stdout stays on ttyAMA0, stderr is redirected to ttyAMA1
     * (which the plugin reads from a socket), so the two streams never mix.
     */
    public JSObject exec(String command, long timeoutMs) throws Exception {
        if (state != State.RUNNING) {
            throw new IllegalStateException("VM_NOT_RUNNING");
        }
        synchronized (execLock) {
            String id = Long.toHexString(System.nanoTime());
            String b64 = android.util.Base64.encodeToString(
                    command.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
            String script = "/tmp/.capinux-exec-" + id + ".sh";
            String begin = SEP + "B" + id + SEP;

            ExecCapture out = new ExecCapture(begin, id);
            ExecCapture err = new ExecCapture(begin, id);
            execInterrupted = false;
            synchronized (captureLock) {
                outCapture = out;
                errCapture = err;
            }
            long t0 = System.currentTimeMillis();
            try {
                // One line: no interactive prompt can slip in between the steps.
                // The BEGIN markers carry no trailing newline so stdout/stderr
                // payloads start exactly at the first byte the command writes.
                String line = "printf '\\037B" + id + "\\037'; "
                        + "printf '\\037B" + id + "\\037' >/dev/ttyAMA1; "
                        + "echo " + b64 + " | base64 -d > " + script + " && sh " + script
                        + " >/dev/ttyAMA0 2>/dev/ttyAMA1; "
                        + "c=$?; "
                        + "printf '\\037E" + id + ":%d\\037\\n' \"$c\"; "
                        + "printf '\\037E" + id + ":%d\\037\\n' \"$c\" >/dev/ttyAMA1\n";
                write(line.getBytes(StandardCharsets.UTF_8));

                // The command line can be lost when the shell has just handled a
                // signal: the tty flushes pending input, and that flush can land
                // after our write. Retry while the BEGIN marker is still missing -
                // the marker proves the line only starts executing after the shell
                // read it, so a slow command is never run twice.
                for (int attempt = 0; attempt < 3; attempt++) {
                    long startDeadline = System.currentTimeMillis() + 2500;
                    while (System.currentTimeMillis() < startDeadline) {
                        if (out.hasBegun() || execInterrupted || !isRunning()) {
                            break;
                        }
                        Thread.sleep(20);
                    }
                    if (out.hasBegun() || execInterrupted || !isRunning()) {
                        break;
                    }
                    Log.w(TAG, "exec line was not picked up, resending (attempt " + (attempt + 1) + ")");
                    write(line.getBytes(StandardCharsets.UTF_8));
                }

                if (!out.hasBegun() && !execInterrupted && isRunning()) {
                    // Never started: the guest console is wedged (seen after a signal
                    // hit a foreground job in busybox ash). Fail fast with a hint
                    // instead of burning the caller's whole timeout.
                    throw new IllegalStateException("CONSOLE_UNRESPONSIVE: the guest shell did not "
                            + "pick up the command line; call restart() to get a clean console");
                }

                long deadline = t0 + Math.max(1000, timeoutMs);
                boolean done = false;
                while (System.currentTimeMillis() < deadline) {
                    if ((out.finished && (err.finished || !stderrAvailable)) || execInterrupted) {
                        done = true;
                        break;
                    }
                    Thread.sleep(20);
                }
                boolean interrupted = execInterrupted && !out.finished;
                boolean timedOut = !done;
                if (timedOut && !interrupted) {
                    // Ctrl-C the foreground process group; the end marker may never arrive.
                    write(new byte[]{0x03});
                    Thread.sleep(120);
                }
                JSObject ret = new JSObject();
                ret.put("code", interrupted ? 130 : out.exitCode);
                ret.put("stdout", out.text());
                ret.put("stderr", err.text());
                ret.put("durationMs", System.currentTimeMillis() - t0);
                ret.put("timedOut", timedOut);
                ret.put("interrupted", interrupted);
                return ret;
            } finally {
                synchronized (captureLock) {
                    outCapture = null;
                    errCapture = null;
                }
            }
        }
    }

    // -------------------------------------------------------------------- stop

    /** Asks the guest to power off, then falls back to SIGKILL. Blocking. */
    public void stop(boolean force) {
        if (!isRunning()) {
            return;
        }
        stopRequested = true;
        setState(State.STOPPING, null);
        Process p = process;
        if (force) {
            killProcess();
            return;
        }
        try {
            write("poweroff -f\n".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "poweroff write failed: " + e.getMessage());
        }
        try {
            if (p != null && !waitForTimeout(p, 8000)) {
                Log.w(TAG, "guest did not power off in 8s, killing qemu");
                killProcess();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killProcess();
        }
    }

    private void killProcess() {
        Process p = process;
        if (p == null) {
            return;
        }
        p.destroy();
        try {
            if (!waitForTimeout(p, 3000)) {
                destroyForcibly(p);
                waitForTimeout(p, 2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyForcibly(p);
        }
    }

    // --- Process helpers ------------------------------------------------
    // Process.isAlive()/waitFor(long,TimeUnit)/destroyForcibly() are API 26+.
    // This plugin targets Android 7.0 (API 24), so they are emulated here.

    /** {@code Process.isAlive()} without requiring API 26. */
    private static boolean isAlive(Process p) {
        if (p == null) {
            return false;
        }
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    /** Waits up to {@code timeoutMs}; returns true when the process has exited. */
    private static boolean waitForTimeout(Process p, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive(p)) {
                return true;
            }
            Thread.sleep(25);
        }
        return !isAlive(p);
    }

    /** {@code Process.destroyForcibly()} via reflection (API 26+), else destroy(). */
    private static void destroyForcibly(Process p) {
        try {
            Process.class.getMethod("destroyForcibly").invoke(p);
        } catch (Exception e) {
            p.destroy();
        }
    }

    private void closeQuietly() {
        for (LocalSocket s : new LocalSocket[]{consoleSocket, stderrSocket}) {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
        consoleSocket = null;
        stderrSocket = null;
        consoleOut = null;
        stderrAvailable = false;
        process = null;
    }

    public ExecutorService worker() {
        return worker;
    }

    /** Executor for operations that must not wait for the current exec(). */
    public ExecutorService ioWorker() {
        return ioWorker;
    }

    public void shutdown() {
        stopRequested = true;
        killProcess();
        worker.shutdownNow();
        ioWorker.shutdownNow();
    }

    public String configSummary() {
        return configSummary;
    }

    // ------------------------------------------------------- runtime extraction

    /**
     * Copies kernel + rootfs out of the APK once per runtime version.
     *
     * <p>The stamp holds the manifest's sha256 of both files, so upgrading
     * Alpine (new runtime) re-extracts automatically while a normal app start
     * does no work.
     */
    private void ensureRuntimeExtracted() throws Exception {
        File dir = rootfsDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        String manifestJson;
        try (InputStream in = context.getAssets().open("linux/manifest.json")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            manifestJson = bos.toString("UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException("RUNTIME_MISSING: assets/linux/manifest.json not found. "
                    + "Run `npm run fetch-runtime` in plugins/capacitor-linux and rebuild the app.", e);
        }
        JSONObject manifest = new JSONObject(manifestJson);
        JSONObject kernel = manifest.getJSONObject("kernel");
        JSONObject rootfs = manifest.getJSONObject("rootfs");
        String want = kernel.getString("sha256") + ":" + rootfs.getString("sha256");

        String have = null;
        if (stampFile().isFile()) {
            try (FileInputStream in = new FileInputStream(stampFile())) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[512];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                have = bos.toString("UTF-8").trim();
            }
        }
        if (want.equals(have) && kernelFile().isFile() && rootfsFile().isFile()) {
            return;
        }

        Log.i(TAG, "extracting VM runtime (" + kernel.optLong("bytes") / 1048576 + " MB kernel + "
                + rootfs.optLong("bytes") / 1048576 + " MB rootfs)");
        // Asset names come from the manifest: AAPT renames some assets (.gz is
        // stripped), so never hardcode them here.
        copyAsset(kernel.getString("asset"), kernelFile(), kernel.getLong("bytes"), kernel.getString("sha256"));
        copyAsset(rootfs.getString("asset"), rootfsFile(), rootfs.getLong("bytes"), rootfs.getString("sha256"));
        try (FileOutputStream out = new FileOutputStream(stampFile())) {
            out.write(want.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void copyAsset(String asset, File dest, long expectBytes, String expectSha) throws Exception {
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (InputStream in = context.getAssets().open(asset);
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                total += n;
            }
        }
        if (expectBytes > 0 && total != expectBytes) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("asset " + asset + " truncated (" + total + " != " + expectBytes + ")");
        }
        String sha = hex(md.digest());
        if (expectSha != null && !expectSha.isEmpty() && !expectSha.equals(sha)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("asset " + asset + " sha256 mismatch (got " + sha + ")");
        }
        if (!tmp.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("cannot move " + tmp + " to " + dest);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    // --------------------------------------------------------------- utilities

    /**
     * Maps a key name to the bytes a real terminal sends for it.
     *
     * <p>Ctrl+letter is the terminal control character (Ctrl-C = 0x03, Ctrl-D =
     * 0x04, Ctrl-Z = 0x1a ...). The guest's tty turns Ctrl-C into SIGINT for the
     * foreground process group, which is what lets it interrupt a running
     * program. Cursor/editing keys use xterm sequences; the guest's TERM is
     * xterm-256color (set in /init).
     *
     * @return the sequence, or {@code null} when the name is unknown
     */
    public static byte[] keyBytes(String rawKey) {
        if (rawKey == null) {
            return null;
        }
        String key = rawKey.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("ctrl+") && key.length() == 6) {
            char c = key.charAt(5);
            if (c >= 'a' && c <= 'z') {
                return new byte[]{(byte) (c - 'a' + 1)};
            }
            if (c == '@' || c == ' ') {
                return new byte[]{0x00};
            }
        }
        switch (key) {
            case "enter":
            case "return":
                return new byte[]{0x0d};              // CR: what a real return key sends
            case "tab":
                return new byte[]{0x09};
            case "shift+tab":
            case "backtab":
                return new byte[]{0x1b, '[', 'Z'};
            case "backspace":
            case "bs":
                return new byte[]{0x7f};
            case "escape":
            case "esc":
                return new byte[]{0x1b};
            case "space":
                return new byte[]{0x20};
            case "up":
                return new byte[]{0x1b, '[', 'A'};
            case "down":
                return new byte[]{0x1b, '[', 'B'};
            case "right":
                return new byte[]{0x1b, '[', 'C'};
            case "left":
                return new byte[]{0x1b, '[', 'D'};
            case "home":
                return new byte[]{0x1b, '[', 'H'};
            case "end":
                return new byte[]{0x1b, '[', 'F'};
            case "pageup":
            case "pgup":
                return new byte[]{0x1b, '[', '5', '~'};
            case "pagedown":
            case "pgdn":
                return new byte[]{0x1b, '[', '6', '~'};
            case "insert":
            case "ins":
                return new byte[]{0x1b, '[', '2', '~'};
            case "delete":
            case "del":
                return new byte[]{0x1b, '[', '3', '~'};
            case "f1":
                return new byte[]{0x1b, 'O', 'P'};
            case "f2":
                return new byte[]{0x1b, 'O', 'Q'};
            case "f3":
                return new byte[]{0x1b, 'O', 'R'};
            case "f4":
                return new byte[]{0x1b, 'O', 'S'};
            case "f5":
                return new byte[]{0x1b, '[', '1', '5', '~'};
            case "f6":
                return new byte[]{0x1b, '[', '1', '7', '~'};
            case "f7":
                return new byte[]{0x1b, '[', '1', '8', '~'};
            case "f8":
                return new byte[]{0x1b, '[', '1', '9', '~'};
            case "f9":
                return new byte[]{0x1b, '[', '2', '0', '~'};
            case "f10":
                return new byte[]{0x1b, '[', '2', '1', '~'};
            case "f11":
                return new byte[]{0x1b, '[', '2', '3', '~'};
            case "f12":
                return new byte[]{0x1b, '[', '2', '4', '~'};
            default:
                return null;
        }
    }

    private void setState(State next, String detail) {
        state = next;
        listener.onStateChanged(next, detail);
    }

    private static int pidOf(Process p) {
        try {
            // Process.pid() is API 26+; fall back to reflection-free parsing.
            return (int) Process.class.getMethod("pid").invoke(p);
        } catch (Exception e) {
            return -1;
        }
    }

    private static int exitValueOf(Process p) {
        try {
            return p.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Accumulates one stream of an exec() call and extracts the payload. */
    private static final class ExecCapture {
        private final StringBuilder raw = new StringBuilder();
        private final String begin;
        private final Pattern endPattern;
        volatile boolean finished = false;
        volatile int exitCode = -1;
        private int beginIndex = -1;
        private int payloadEnd = -1;

        ExecCapture(String begin, String id) {
            this.begin = begin;
            // Guest emits: \x1f E <id> : <exitcode> \x1f
            this.endPattern = Pattern.compile(
                    Pattern.quote(SEP + "E" + id + ":") + "(-?\\d+)" + Pattern.quote(String.valueOf(SEP)));
        }

        /** True once the guest started executing this command (BEGIN marker seen). */
        synchronized boolean hasBegun() {
            return beginIndex >= 0;
        }

        synchronized void append(String chunk) {
            raw.append(chunk);
            if (finished) {
                return;
            }
            if (beginIndex < 0) {
                int i = raw.indexOf(begin);
                if (i >= 0) {
                    beginIndex = i + begin.length();
                }
            }
            if (beginIndex >= 0) {
                Matcher m = endPattern.matcher(raw);
                if (m.find(beginIndex)) {
                    try {
                        exitCode = Integer.parseInt(m.group(1));
                    } catch (NumberFormatException ignored) {
                        exitCode = -1;
                    }
                    payloadEnd = m.start();
                    finished = true;
                }
            }
        }

        synchronized String text() {
            if (beginIndex < 0) {
                return "";
            }
            int endAt = payloadEnd >= 0 ? payloadEnd : raw.length();
            return raw.substring(Math.min(beginIndex, endAt), Math.min(endAt, raw.length()));
        }
    }
}
