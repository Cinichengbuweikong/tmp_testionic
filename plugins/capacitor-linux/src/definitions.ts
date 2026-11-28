/**
 * capacitor-linux — 在 App 内运行一个**真正的 Linux 虚拟机**。
 *
 * 设计要点（细节见 README）：
 * - Android 用 QEMU（TCG 软件模拟）+ 官方 Alpine 内核/rootfs，全部离线随 APK 分发；
 * - 客户机是独立进程：Linux 崩溃（kernel panic / OOM / 死循环）只杀 QEMU，不连累 App；
 * - 控制台是真实的串口（/dev/ttyAMA0），所以 stdin/stdout/stderr 是**流式**的，
 *   运行中的程序（node、python、vi…）都能正常交互；
 * - `/dev/ttyAMA1` 是第二条串口，专门承载 `exec()` 的 stderr，用来把 stdout 和 stderr
 *   分成两条独立的流；
 * - iOS 目前不支持（Apple 不允许 JIT/fork，需要把解释器级内核移植进来，见 README「iOS 路线」）。
 */

/** VM 生命周期状态。 */
export type LinuxVmState =
  | 'stopped'
  | 'starting'
  | 'running'
  | 'stopping'
  | 'crashed'
  | 'error';

/** `getCapabilities()` 的返回：这台设备/这个平台能不能跑，以及跑的是什么。 */
export interface LinuxCapabilities {
  /** 当前平台。 */
  platform: 'android' | 'ios' | 'web';
  /** 是否可用。false 时看 `reasons`。 */
  supported: boolean;
  /** 运行时引擎。Android 目前只有 `qemu-tcg`；不支持时是 `none`。 */
  engine: 'qemu-tcg' | 'none';
  /** 客户机架构。 */
  architecture: string;
  /** 是否直接在本机执行客户机二进制（QEMU 是模拟执行，所以是 false）。 */
  nativeExecution: boolean;
  /** 隔离级别：`process` = 独立进程（崩溃不影响 App）；`none` = 无隔离。 */
  isolation: 'process' | 'none';
  /** 客户机里是否自带 apk 包管理器（Alpine）。 */
  apkAvailable: boolean;
  /** 重启后数据是否保留：`none` = rootfs 在内存里，重启即恢复初始状态。 */
  persistence: 'none' | 'disk';
  /** 最低要求（超出时应当先判断 `supported`）。 */
  minimum: {
    /** Android API level。 */
    androidSdk?: number;
    /** 需要的 ABI 列表（当前只有 arm64-v8a）。 */
    abis?: string[];
    /** 建议的最小物理内存（MB）。 */
    deviceRamMb?: number;
    /** Web 端的最低 WebView/浏览器版本（当前不支持）。 */
    webViewMajor?: number;
  };
  /** 不支持时的原因（人类可读，可直接展示/记录日志）。 */
  reasons: string[];
}

/** VM 与随包运行时（kernel/rootfs/QEMU）的版本信息。 */
export interface LinuxInfo {
  /** 是否支持当前平台。 */
  supported: boolean;
  /** 发行版名（alpine）。 */
  distribution: string;
  /** 发行版版本，例如 `3.23.8`。 */
  distributionVersion?: string;
  /** 客户机内核版本，例如 `6.18.60-0-virt`。 */
  kernelRelease?: string;
  /** QEMU 版本，例如 `1:11.1.3`。 */
  qemuVersion?: string;
  /** 引擎名。 */
  engine: string;
  architecture: string;
  /** rootfs 形态：initramfs = 解压进内存，不需要磁盘镜像。 */
  rootfsMode: 'initramfs' | 'disk';
  /** 是否直接执行客户机二进制（false = 模拟）。 */
  nativeExecution: boolean;
  /** apk 是否可用。 */
  apkAvailable: boolean;
  /** QEMU 可执行文件的绝对路径（在 nativeLibraryDir 里）。 */
  executable: string;
  /** QEMU 可执行文件是否存在（false 通常意味着宿主 App 没开 extractNativeLibs）。 */
  executablePresent: boolean;
  /** 随包运行时是否已解压到 App 私有目录。 */
  runtimeExtracted: boolean;
  /** APK 里是否带了运行时资源（false 说明没跑 `npm run fetch-runtime`）。 */
  runtimeBundled: boolean;
  /** runtimeBundled=false 时的原因。 */
  runtimeError?: string;
  /** kernel 字节数。 */
  kernelBytes?: number;
  /** rootfs 字节数（压缩后）。 */
  rootfsBytes?: number;
  /** QEMU 二进制 + 依赖库字节数。 */
  qemuBytes?: number;
  state: LinuxVmState;
}

/** `start()` / `restart()` 的可选项。 */
export interface LinuxStartOptions {
  /** 客户机内存（MB）。默认 1024，范围 256..4096。 */
  memoryMb?: number;
  /** 客户机 vCPU 数。默认 2，范围 1..8。 */
  cpus?: number;
  /**
   * 追加到内核命令行。默认已包含 `console=ttyAMA0 rdinit=/init loglevel=6 panic=1`，
   * 一般只在调试时用（例如加 `initcall_debug`）。
   */
  kernelCmdline?: string;
  /**
   * 等待 `/init` 打印就绪标记的超时（ms），默认 180000。
   * 超时会杀掉 QEMU 并 reject `BOOT_TIMEOUT`。
   */
  bootTimeoutMs?: number;
  /**
   * 是否挂一块**持久化数据盘**（默认 false）。
   *
   * rootfs 本身永远在内存里（重启还原）；打开这个开关后，App 会在私有目录里
   * 创建一个 raw 镜像挂给客户机当 `/dev/vda`，第一次启动时由客户机自己
   * `mkfs.ext4`（e2fsprogs 已离线打进 rootfs），然后把 `/root`、`/home`、
   * `/opt`、`/usr/local` 绑定到这块盘上——这些目录里的东西重启后还在。
   *
   * 注意：`apk add` 装到 `/usr`、`/lib`，仍然在内存里，重启后要重装。
   */
  persistent?: boolean;
  /** 持久化数据盘大小（MB），默认 1024，范围 64..8192。只在 `persistent:true` 时生效。 */
  diskSizeMb?: number;
}

/** `getStatus()` 的返回。 */
export interface LinuxStatus {
  state: LinuxVmState;
  /** 是否处于 starting/running/stopping。 */
  running: boolean;
  /** QEMU 进程 pid。 */
  pid?: number;
  /** 启动时刻（Unix ms）。 */
  startedAt?: number;
  /** 已运行时长（ms）。 */
  uptimeMs?: number;
  /** 进程退出码（退出后才有）。 */
  exitCode?: number;
  memoryMb: number;
  cpus: number;
  /** 控制台设备（客户机内部路径）。 */
  consoleTty: string;
  /** stderr 侧信道设备（客户机内部路径）。 */
  stderrTty: string;
  /** 当前是否挂着持久化数据盘。 */
  persistence?: 'none' | 'disk';
  /** 宿主侧共享目录（客户机 `/mnt/host`）。 */
  shareDir?: string;
  /** 客户机里的共享目录挂载点。 */
  guestShareDir?: string;
  /** 持久化数据盘镜像的字节数（`persistence:'disk'` 时）。 */
  diskBytes?: number;
  /** 上一次错误。 */
  lastError?: string;
}

/** `write()` 的参数。 */
export interface LinuxWriteOptions {
  /** 要写到客户机 stdin 的内容。 */
  data: string;
  /** 编码：`utf8`（默认）或 `base64`（二进制安全）。 */
  encoding?: 'utf8' | 'base64';
}

/**
 * `sendKey()` 支持的按键名。
 *
 * - `ctrl+a` … `ctrl+z`：终端控制字符（Ctrl-C = `0x03`、Ctrl-D = `0x04`、Ctrl-Z = `0x1a` …），
 *   这些是**真正的**控制字节：客户机 tty 会把 Ctrl-C 变成 SIGINT 交给前台进程组，
 *   所以能中断 `sleep`/`node`/`cat` 这类正在运行的程序；
 * - 光标/编辑键是 xterm 序列（`ESC[A` 一类），客户机 `TERM=xterm-256color`；
 * - `enter` 发的是 `\r`（CR，就是真实终端回车发的东西，客户机 `ICRNL` 会转成 `\n`）。
 */
export type LinuxKeyName =
  | 'ctrl+a'
  | 'ctrl+b'
  | 'ctrl+c'
  | 'ctrl+d'
  | 'ctrl+e'
  | 'ctrl+f'
  | 'ctrl+g'
  | 'ctrl+h'
  | 'ctrl+i'
  | 'ctrl+j'
  | 'ctrl+k'
  | 'ctrl+l'
  | 'ctrl+m'
  | 'ctrl+n'
  | 'ctrl+o'
  | 'ctrl+p'
  | 'ctrl+q'
  | 'ctrl+r'
  | 'ctrl+s'
  | 'ctrl+t'
  | 'ctrl+u'
  | 'ctrl+v'
  | 'ctrl+w'
  | 'ctrl+x'
  | 'ctrl+y'
  | 'ctrl+z'
  | 'enter'
  | 'tab'
  | 'shift+tab'
  | 'backspace'
  | 'escape'
  | 'space'
  | 'up'
  | 'down'
  | 'left'
  | 'right'
  | 'home'
  | 'end'
  | 'pageup'
  | 'pagedown'
  | 'insert'
  | 'delete'
  | 'f1'
  | 'f2'
  | 'f3'
  | 'f4'
  | 'f5'
  | 'f6'
  | 'f7'
  | 'f8'
  | 'f9'
  | 'f10'
  | 'f11'
  | 'f12'
  | (string & {});

/** `getShareDir()` 的返回：宿主目录 ↔ 客户机挂载点。 */
export interface LinuxShareDir {
  /**
   * 宿主侧目录（App 私有目录里，绝对路径）。
   *
   * 把文件放进这里，客户机里 `/mnt/host` 就能直接看到；反过来客户机里
   * `cp /root/a /mnt/host/` 之后，宿主也能读到。
   */
  hostPath: string;
  /** 客户机里的挂载点（`/mnt/host`）。 */
  guestPath: string;
  /** 宿主目录是否已创建。 */
  exists: boolean;
  /** VM 是否在运行（9p 挂载只在运行期间有效）。 */
  available: boolean;
  /** 宿主目录里的条目数（方便快速确认写进去了）。 */
  hostEntries?: number;
  /** 提示/排错信息。 */
  note?: string;
}

/** `sendKey()` 的参数。 */
export interface LinuxSendKeyOptions {
  /** 按键名（见 `LinuxKeyName`，大小写不敏感）。 */
  key: LinuxKeyName;
  /** 连续发送次数，默认 1，范围 1..100（例如 `{ key: 'down', repeat: 5 }`）。 */
  repeat?: number;
}

/** `exec()` 的参数。 */
export interface LinuxExecOptions {
  /** 在客户机里执行的 shell 命令（可以包含引号、换行、管道等）。 */
  command: string;
  /** 超时（ms），默认 30000。超时会向控制台发 Ctrl-C 并返回 `timedOut: true`。 */
  timeoutMs?: number;
}

/** `exec()` 的返回。 */
export interface LinuxExecResult {
  /** 命令退出码（被 Ctrl-C 中断时为 130）。 */
  code: number;
  /** 标准输出。 */
  stdout: string;
  /** 标准错误（走 /dev/ttyAMA1，所以不会和 stdout 混在一起）。 */
  stderr: string;
  /** 耗时（ms）。 */
  durationMs: number;
  /** 是否超时（超时会先向控制台发 Ctrl-C）。 */
  timedOut: boolean;
  /**
   * 是否被 `sendKey('ctrl+c')` / `write('\u0003')` 中断。
   *
   * 为什么需要这个字段：shell 在前台任务被 SIGINT 杀掉后会**中止整条命令列表**，
   * `exec()` 的结束标记因此永远不会打印。插件检测到这个情况会立即返回
   * （`code: 130`、`interrupted: true`），而不是干等到超时。
   */
  interrupted: boolean;
}

/** `consoleOutput` 事件的 payload。 */
export interface LinuxConsoleEvent {
  /** 这一块输出（UTF-8 解码后的文本）。 */
  data: string;
  /** `stdout` = 控制台（ttyAMA0）；`stderr` = 侧信道（ttyAMA1）。 */
  stream: 'stdout' | 'stderr';
}

/** `vmState` 事件的 payload。 */
export interface LinuxStateEvent {
  state: LinuxVmState;
  /** 附加说明（错误原因等）。 */
  detail?: string | null;
}

/** `vmExited` 事件的 payload。 */
export interface LinuxExitEvent {
  /** 进程退出码。 */
  code: number;
  /** 运行时长（ms）。 */
  uptimeMs: number;
  /** 是否是调用方主动 stop() 导致的退出。 */
  requested: boolean;
  /** `requested` / `guest-exit` / `engine-exit`。 */
  reason: string;
}

/** `engineLog` 事件的 payload：QEMU 自己的 stderr（不是客户机输出）。 */
export interface LinuxEngineLogEvent {
  line: string;
}

export interface LinuxPlugin {
  /**
   * 这台设备能不能跑，跑的是什么引擎，最低要求是什么。
   * 不启动 VM、不读资产，很快。
   */
  getCapabilities(): Promise<LinuxCapabilities>;

  /** 运行时版本信息（Alpine/kernel/QEMU/体积）与当前状态。 */
  getInfo(): Promise<LinuxInfo>;

  /** 当前状态（pid/运行时长/退出码等）。 */
  getStatus(): Promise<LinuxStatus>;

  /**
   * 启动 VM。首次调用会先把 kernel/rootfs 解压到 App 私有目录（约 15MB，几百毫秒），
   * 然后启动 QEMU 并等待 `/init` 打印就绪标记（真机 TCG 大约 10–20 秒）。
   */
  start(options?: LinuxStartOptions): Promise<LinuxStatus>;

  /** 关机：先尝试 `poweroff -f`，8 秒没退出就杀进程。`force: true` 直接杀。 */
  stop(options?: { force?: boolean }): Promise<LinuxStatus>;

  /** 停止（如果在跑）再启动。 */
  restart(options?: LinuxStartOptions): Promise<LinuxStatus>;

  /**
   * 宿主与客户机之间的共享目录。
   *
   * 这是"和 Linux 交换文件"的官方通道：rootfs 本体是 initramfs（在内存里），
   * 宿主上并没有一份可写的 rootfs 目录，所以插件给了一个**双向共享目录**——
   * 宿主放进去的文件，客户机 `/mnt/host` 立即可见；客户机写进去的文件，宿主也能读。
   * 实现是 9p（QEMU `-virtfs` + 客户机 9p 模块），不是拷贝，两边看到的是同一份数据。
   */
  getShareDir(): Promise<LinuxShareDir>;

  /** 往客户机 stdin 写数据（相当于在终端里敲键盘）。 */
  write(options: LinuxWriteOptions): Promise<void>;

  /**
   * 发送一个"特殊键"，例如 Ctrl-C / Ctrl-D / 方向键 / F1。
   *
   * 与 `write({ data: '\u0003' })` 等价，但把终端控制序列集中在一处：
   * Ctrl-C 发的是真正的 `0x03`，客户机 tty 会据此给前台进程组发 SIGINT
   * （所以能中断正在运行的程序）；方向键发 xterm 的 `ESC[A` 一类序列。
   */
  sendKey(options: LinuxSendKeyOptions): Promise<void>;

  /**
   * 在客户机里跑一条命令并拿到**分离的** stdout/stderr。
   *
   * 内部做法：命令 base64 编码后写成客户机里的临时脚本再执行，stdout 留在
   * ttyAMA0、stderr 重定向到 ttyAMA1，两条流各自用唯一标记包围。
   * 同一时刻只允许一个 exec（控制台只有一个前台进程）。
   *
   * 注意：命令的输出同时也会出现在 `consoleOutput` 事件里（它确实打在了控制台上）。
   */
  exec(options: LinuxExecOptions): Promise<LinuxExecResult>;

  /** 客户机控制台输出（流式）。 */
  addListener(
    eventName: 'consoleOutput',
    listenerFunc: (event: LinuxConsoleEvent) => void,
  ): Promise<import('@capacitor/core').PluginListenerHandle>;

  /** VM 状态变化。 */
  addListener(
    eventName: 'vmState',
    listenerFunc: (event: LinuxStateEvent) => void,
  ): Promise<import('@capacitor/core').PluginListenerHandle>;

  /** VM 进程退出（崩溃也能收到）。 */
  addListener(
    eventName: 'vmExited',
    listenerFunc: (event: LinuxExitEvent) => void,
  ): Promise<import('@capacitor/core').PluginListenerHandle>;

  /** QEMU 引擎日志（排错用）。 */
  addListener(
    eventName: 'engineLog',
    listenerFunc: (event: LinuxEngineLogEvent) => void,
  ): Promise<import('@capacitor/core').PluginListenerHandle>;

  /**
   * 同步取出 `Linux` 对象（其它插件用的是 registerPlugin 返回的 proxy，
   * Capacitor 内部把插件实例挂在 proxy 上）。
   */
  /** 移除某个事件的全部监听器（桥内置）。 */
  removeAllListeners(): Promise<void>;
}
