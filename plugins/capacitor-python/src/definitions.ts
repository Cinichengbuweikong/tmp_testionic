/**
 * capacitor-python 的公开类型定义。
 *
 * 架构一图流：
 *
 *   ┌──────────── App (JS) ────────────┐
 *   │ PythonRuntime（本文件 / engine）  │
 *   │   ├─ session A → Worker A ── Pyodide（CPython 3.14 / WASM）
 *   │   ├─ session B → Worker B ── Pyodide
 *   │   └─ ...                        │
 *   └──────────────────────────────────┘
 *
 * - 每个 `PythonSession` 独占一个 Web Worker：Python 死循环/崩溃只会毁掉那个 Worker，
 *   `terminate()` 即可恢复，App 主进程与其它会话不受影响。
 * - Python 解释器是 Pyodide（CPython 3.14 编译成 WebAssembly）。选择它而不是原生 CPython
 *   的原因见 README「为什么是 WebAssembly 版 CPython」：iOS 禁止子进程/多进程，
 *   原生嵌入无法做到"Python 崩溃不影响主程序"，而 Web Worker 可以。
 * - 原生壳（Android/iOS）只提供平台信息与未来原生引擎的扩展点，业务逻辑全在 JS 侧。
 */

/** 会话生命周期状态。 */
export type PythonSessionState =
  /** 已创建，worker/解释器正在加载 */
  | 'loading'
  /** 空闲，可以执行代码 */
  | 'idle'
  /** 正在执行代码 */
  | 'running'
  /** 正在安装 pip 包 */
  | 'installing'
  /** 已终止（worker 已销毁） */
  | 'terminated'
  /** 出错（fatal，通常需要重建会话） */
  | 'error';

/**
 * stdin 的工作模式：
 * - `blocking`：有 SharedArrayBuffer + Atomics.wait，`input()` 会真正阻塞等待 JS 写入（Android 通常可用）；
 * - `queued`：没有 SAB（iOS 通常如此），只能预先推入多行，队列空时按 EOF 处理。
 */
export type PythonStdinMode = 'blocking' | 'queued';

/** 运行时信息（原生引擎与 Web 引擎共用，web 专属字段可选）。 */
export interface PythonRuntimeInfo {
  /** 原生引擎（Chaquopy / Python-Apple-support）或 Web 引擎（Pyodide） */
  engine: 'pyodide' | 'native';
  /** 解释器版本，例如 `3.11.17` / `3.14.2` */
  pythonVersion: string;
  /** 原生平台（`android` / `ios`）；web 引擎没有 */
  platform?: string;
  /** 仅 Web 引擎：Pyodide 版本，例如 `314.0.8` */
  pyodideVersion?: string;
  /** 仅 Web 引擎：资源目录的绝对 URL（以 / 结尾） */
  runtimeUrl?: string;
  /** 仅 Web 引擎：该运行时要求的最低 WebView 版本 */
  minimum?: {
    /** Android WebView 的主版本号（Chrome/xx） */
    androidWebView: number;
    /** iOS 最低版本 */
    ios: string;
  };
  /** 仅 Web 引擎：资源清单（含 sha256） */
  files?: PythonRuntimeFile[];
}

export interface PythonRuntimeFile {
  name: string;
  bytes: number;
  sha256: string;
}

/** `PythonRuntime.initialize()` 的参数。 */
export interface PythonInitializeOptions {
  /**
   * 运行时目录（相对 App Web 资源根目录或绝对 URL），默认 `python-runtime/`。
   * 目录下应包含 `<version>/`（由 `scripts/fetch-python-runtime.mjs` 生成）。
   */
  runtimeUrl?: string;
  /** 指定 Pyodide 版本目录；默认取 `manifest.json` 中的版本（即最后下载的那个） */
  version?: string;
  /** 是否校验每个文件的 sha256（默认 true；13MB 校验一次约几十毫秒到几百毫秒） */
  verify?: boolean;
}

/** `createSession()` 参数。 */
export interface PythonCreateSessionOptions {
  /** 便于日志/UI 展示的名字，默认 `session-N` */
  name?: string;
  /** 创建后立即安装的 PyPI 包（通过 micropip；离线环境请用 installWheels） */
  packages?: string[];
  /**
   * pip 的 simple index（PEP 503）地址列表，可指向自建镜像 / 私有源。
   * 默认 `['https://pypi.org/simple']`。注意 WebView 里 fetch 受 CORS 限制，
   * 自建源必须返回 `Access-Control-Allow-Origin`。
   */
  pipIndexUrls?: string[];
  /** 每次会话的运行时覆盖（一般不需要） */
  runtimeUrl?: string;
  /** 在 `ready` 之后、第一次 `run` 之前执行一次的启动代码（例如 `import sys; sys.path.append(...)`） */
  bootstrap?: string;
  /** 采集输出的批量间隔（毫秒），默认 0 = 每行立即推送 */
  outputBatchMs?: number;
}

/** `installPackages()` 的可选参数。 */
export interface PythonInstallOptions {
  /** 覆盖本次安装使用的 index（不传则用会话/默认的 `pipIndexUrls`） */
  indexUrls?: string[];
}

/** 会话信息。 */
export interface PythonSessionInfo {
  sessionId: string;
  name: string;
  state: PythonSessionState;
  stdinMode: PythonStdinMode;
  /** 创建时间戳（ms） */
  createdAt: number;
  /** 解释器版本（加载完成后才有） */
  pythonVersion?: string;
}

/**
 * 会话句柄：`Python.createSession()` 的返回值。
 *
 * 除了保留原有的 `sessionId` / `name` / `state` 字段，还提供流式输出与 stdin 写入，
 * 这样不需要自己按 `sessionId` 过滤全局事件：
 *
 * ```ts
 * const s = await Python.createSession({ name: 'demo' });
 * s.onStdout((text) => append(text));
 * s.onStderr((text) => appendError(text));
 * await s.exec('name = input("Name? ")');
 * s.writeStdin('Alice');     // Android/iOS 上会唤醒阻塞的 input()
 * ```
 */
export interface PythonSessionHandle {
  /** 会话 id（与全局事件里的 sessionId 一致）。 */
  readonly sessionId: string;
  /** 名字（创建时传入）。 */
  readonly name: string;
  /** 创建时间戳（ms）。 */
  readonly createdAt: number;
  /** 当前状态（会随 `onState` 更新）。 */
  readonly state: PythonSessionState;
  /** stdin 模式（原生为 `blocking`，Web 为 `queued`）。 */
  readonly stdinMode: PythonStdinMode;
  /** 解释器版本（会话 ready 之后才有）。 */
  readonly pythonVersion?: string;

  /** 合并的 stdout+stderr 流；事件里带 `stream` 字段。 */
  onOutput(listener: (event: PythonOutputEvent) => void): { remove: () => void };
  /** 只监听 stdout。 */
  onStdout(listener: (text: string) => void): { remove: () => void };
  /** 只监听 stderr。 */
  onStderr(listener: (text: string) => void): { remove: () => void };
  /** 监听状态变化（running/idle/terminated/error…）。 */
  onState(listener: (event: PythonStateEvent) => void): { remove: () => void };

  /** 写一行到 stdin（自动补换行；原生上会让 `input()` 返回）。 */
  writeStdin(data: string): Promise<void>;
  /** 执行并等待（返回本次 `result/stdout/stderr`）。 */
  exec(code: string, timeoutMs?: number): Promise<PythonExecResult>;
  /** 触发执行并立即返回；输出通过 `onOutput/onStdout/onStderr` 实时拿。 */
  run(code: string): PythonRunResult;
  /** 安装包（Android 仅纯 Python wheel；Web micropip；iOS 抛错）。 */
  installPackages(packages: string[], options?: PythonInstallOptions): Promise<PythonInstallResult>;
  /** 按 URL 安装 wheel。 */
  installWheels(urls: string[]): Promise<PythonInstallResult>;
  /** 终止会话（释放解释器里的 globals）。 */
  terminate(): Promise<void>;
  /** 重启会话（终止并重建，返回新的句柄）。 */
  restart(): Promise<PythonSessionHandle>;
  /** 只取消本地监听，不终止会话。 */
  dispose(): void;
}
/** 输出事件（stdout/stderr 实时流）。 */
export interface PythonOutputEvent {
  sessionId: string;
  stream: 'stdout' | 'stderr';
  data: string;
}

/** 状态变化事件。 */
export interface PythonStateEvent {
  sessionId: string;
  state: PythonSessionState;
  message?: string;
}

/** 一次执行完成的事件。 */
export interface PythonDoneEvent {
  sessionId: string;
  executionId: string;
  /** 表达式结果（`repr` 字符串）；语句没有返回值时为 null */
  result: string | null;
}

/** 执行失败事件（Python 异常，不是会话崩溃）。 */
export interface PythonErrorEvent {
  sessionId: string;
  executionId: string | null;
  type: string;
  message: string;
  traceback?: string | null;
}

/** `exec()` 的返回值：一次性拿到结果 + 这段代码产生的输出。 */
export interface PythonExecResult {
  executionId: string;
  result: string | null;
  stdout: string;
  stderr: string;
}

/** `run()` 的返回值：只拿到执行 id，输出通过事件流。 */
export interface PythonRunResult {
  executionId: string;
}

/** 安装包的结果。 */
export interface PythonInstallResult {
  installed: string[];
}

/** 原生壳返回的平台信息（供 UI 判断能力/最低版本）。 */
export interface PythonNativeInfo {
  platform: 'android' | 'ios' | 'web';
  /** 当前实现固定为 pyodide；未来原生引擎会在这是 `native` */
  engine: 'pyodide' | 'native';
  /** Android: WebView 的 Chrome 主版本号；iOS: 系统版本；Web: 浏览器主版本 */
  webViewVersion?: string;
  /** 是否满足运行时的最低 WebView 要求 */
  supported?: boolean;
  /** 说明/原因 */
  note?: string;
}

/**
 * 原生插件（Android = Chaquopy；iOS = Python-Apple-support）暴露的方法。
 *
 * TS 门面（`src/index.ts`）在原生平台上把调用转发到这里；web 平台走 Pyodide 引擎。
 */
export interface PythonPlugin {
  /** 事件监听（Capacitor 内置；不计入方法数审计）。 */
  addListener(eventName: string, listener: (data: never) => void): Promise<{ remove: () => void }>;
  initialize(): Promise<PythonRuntimeInfo>;
  getInfo(): Promise<PythonNativeInfo>;
  createSession(options: PythonCreateSessionOptions): Promise<PythonSessionInfo>;
  exec(options: { sessionId: string; code: string; timeoutMs?: number }): Promise<{ executionId: string; result: string | null }>;
  run(options: { sessionId: string; code: string; executionId?: string }): Promise<{ executionId: string }>;
  writeStdin(options: { sessionId: string; data: string }): Promise<void>;
  listSessions(): Promise<{ sessions: PythonSessionInfo[] }>;
  terminateSession(options: { sessionId: string }): Promise<void>;
  terminateAll(): Promise<void>;
  restartSession(options: { sessionId: string }): Promise<PythonSessionInfo>;
  installPackages(options: { sessionId: string; packages: string[] }): Promise<PythonInstallResult>;
}
