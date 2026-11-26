/**
 * Python 运行时管理器（主线程侧）。
 *
 * 一个 `PythonRuntime` 实例管理 N 个 `PythonSession`；每个 session 对应一个 Web Worker，
 * Worker 里加载 Pyodide（CPython → WebAssembly）。这样：
 *   - 会话之间互不影响（各自的解释器/全局变量/异常）；
 *   - Python 死循环或崩溃只会毁掉那个 Worker，`terminate()` 后重建即可，App 不受影响；
 *   - stdout/stderr 通过 postMessage 实时回传，不需要等执行结束。
 */

import type {
  PythonCreateSessionOptions,
  PythonDoneEvent,
  PythonErrorEvent,
  PythonExecResult,
  PythonInstallResult,
  PythonInitializeOptions,
  PythonOutputEvent,
  PythonRunResult,
  PythonRuntimeFile,
  PythonRuntimeInfo,
  PythonSessionInfo,
  PythonSessionState,
  PythonStdinMode,
  PythonStateEvent,
} from '../definitions';

export const DEFAULT_RUNTIME_URL = 'python-runtime/';
/**
 * 默认的 Pyodide 版本目录（314 = CPython 3.14）。
 * 升级 Python = 用 `scripts/fetch-python-runtime.mjs --version <新版本>` 下载新目录，
 * 然后改这里（或调用 `initialize({ version })` 覆盖），旧目录可以保留做回滚。
 */
export const DEFAULT_PYODIDE_VERSION = '314.0.8';

const SAB_HEADER_BYTES = 16;
const SAB_LINE_CAPACITY = 64 * 1024 - SAB_HEADER_BYTES;

type Listener<T> = (payload: T) => void;

interface PendingRun {
  executionId: string;
  resolve: (result: { result: string | null; stdout: string; stderr: string }) => void;
  reject: (error: Error) => void;
  stdout: string;
  stderr: string;
  timer?: ReturnType<typeof setTimeout>;
}

export class PythonSession {
  readonly sessionId: string;
  readonly name: string;
  readonly createdAt: number;
  state: PythonSessionState = 'loading';
  stdinMode: PythonStdinMode = 'queued';
  pythonVersion?: string;

  private worker: Worker | null = null;
  private readonly runtime: PythonRuntime;
  private readonly pending = new Map<string, PendingRun>();
  private current: PendingRun | null = null;
  private seq = 0;
  private readonly stdinQueue: string[] = [];
  private sab: SharedArrayBuffer | null = null;
  private stdinPump: ReturnType<typeof setInterval> | null = null;
  private readonly encoder = new TextEncoder();
  private pipIndexUrls: string[] | undefined;
  private createResolve!: (info: PythonSessionInfo) => void;
  private createReject!: (error: Error) => void;
  private loadTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(runtime: PythonRuntime, id: string, name: string, createdAt: number) {
    this.runtime = runtime;
    this.sessionId = id;
    this.name = name;
    this.createdAt = createdAt;
  }

  /** @internal 创建 Worker 并等待 ready */
  start(indexURL: string, options: PythonCreateSessionOptions): Promise<PythonSessionInfo> {
    const ready = new Promise<PythonSessionInfo>((resolve, reject) => {
      this.createResolve = resolve;
      this.createReject = reject;
    });
    try {
      this.sab = typeof SharedArrayBuffer === 'function' ? new SharedArrayBuffer(SAB_HEADER_BYTES + SAB_LINE_CAPACITY) : null;
    } catch {
      this.sab = null;
    }
    const workerUrl = this.runtime.workerUrl;
    this.worker = new Worker(workerUrl, { type: 'module', name: `python:${this.name}` });
    this.worker.onmessage = (event) => this.onMessage(event.data || {});
    this.worker.onerror = (event) => {
      const raw = (event && (event as ErrorEvent).message) || 'worker failed to start';
      const message = /SyntaxError|Unexpected token|Invalid or unexpected/i.test(raw)
        ? `UNSUPPORTED_WEBVIEW: 当前 WebView 无法解析 Pyodide 运行时（${raw}）。` +
          'Android 请升级 Android System WebView（≥ Chrome/90），iOS 需要 16.4 及以上。'
        : `WORKER_ERROR: ${raw}`;
      const error = new Error(message);
      this.failAll(error);
      // 关键：如果错误发生在 ready 之前，必须 reject createSession，否则调用方会永久挂起
      this.createReject?.(error);
      this.setState('error', message);
    };
    // 加载超时兜底（模拟器/低端机加载 13MB wasm 可能较慢，给 150s）
    this.loadTimer = setTimeout(() => {
      const error = new Error('SESSION_LOAD_TIMEOUT: Python 运行时加载超时（150s），已终止会话');
      this.failAll(error);
      this.createReject?.(error);
      this.killWorker();
      this.setState('error', error.message);
    }, 150_000);
    this.pipIndexUrls = options.pipIndexUrls;
    this.worker.postMessage({
      type: 'init',
      indexURL,
      pyodideVersion: this.runtime.info?.pyodideVersion ?? null,
      sab: this.sab,
      options: { outputBatchMs: options.outputBatchMs ?? 0, pipIndexUrls: this.pipIndexUrls },
    });
    return ready;
  }

  private onMessage(msg: any): void {
    switch (msg.type) {
      case 'state':
        if (msg.state === 'loading' || msg.state === 'idle' || msg.state === 'installing') {
          this.setState(msg.state as PythonSessionState, msg.message);
          if (msg.stdinMode) this.stdinMode = msg.stdinMode;
        }
        break;
      case 'ready':
        if (this.loadTimer) {
          clearTimeout(this.loadTimer);
          this.loadTimer = null;
        }
        this.pythonVersion = msg.pythonVersion;
        this.stdinMode = msg.stdinMode === 'blocking' ? 'blocking' : 'queued';
        this.setState('idle');
        this.createResolve(this.info);
        break;
      case 'stdout':
      case 'stderr': {
        const stream = msg.type === 'stdout' ? 'stdout' : 'stderr';
        if (this.current) {
          if (stream === 'stdout') this.current.stdout += msg.data;
          else this.current.stderr += msg.data;
        }
        this.runtime.emit('sessionOutput', { sessionId: this.sessionId, stream, data: msg.data } as PythonOutputEvent);
        break;
      }
      case 'done': {
        const run = this.pending.get(msg.id);
        if (!run) break;
        this.pending.delete(msg.id);
        if (this.current === run) this.current = null;
        if (run.timer) clearTimeout(run.timer);
        run.resolve({ result: msg.result ?? null, stdout: run.stdout, stderr: run.stderr });
        this.runtime.emit('sessionDone', {
          sessionId: this.sessionId,
          executionId: run.executionId,
          result: msg.result ?? null,
        } as PythonDoneEvent);
        if (this.pending.size === 0) this.setState('idle');
        break;
      }
      case 'error': {
        const run = this.pending.get(msg.id);
        if (!run) break;
        this.pending.delete(msg.id);
        if (this.current === run) this.current = null;
        if (run.timer) clearTimeout(run.timer);
        const error = new Error(`${msg.errorType}: ${msg.message}`);
        (error as any).pythonType = msg.errorType;
        (error as any).traceback = msg.traceback ?? null;
        run.reject(error);
        this.runtime.emit('sessionError', {
          sessionId: this.sessionId,
          executionId: run.executionId,
          type: msg.errorType ?? 'Error',
          message: msg.message ?? String(error),
          traceback: msg.traceback ?? null,
        } as PythonErrorEvent);
        if (this.pending.size === 0) this.setState('idle');
        break;
      }
      case 'pipDone':
        this.runtime.resolvePip(msg.id, msg.packages ?? []);
        break;
      case 'pipError':
        this.runtime.rejectPip(msg.id, new Error(`${msg.errorType}: ${msg.message}`));
        break;
      case 'fatal': {
        const raw = msg.message ?? 'unknown';
        // 典型场景：WebView 太旧，pyodide.asm.mjs 里的新语法解析失败。
        // 给用户一个可操作的错误，而不是 "FATAL: Unexpected token '{'"。
        const unsupported = /Unexpected token|SyntaxError|Invalid or unexpected/i.test(raw);
        const message = unsupported
          ? `UNSUPPORTED_WEBVIEW: 当前 WebView 无法解析 Pyodide 运行时（${raw}）。` +
            'Android 请升级 Android System WebView（≥ Chrome/90），iOS 需要 16.4 及以上。'
          : `FATAL: ${raw}`;
        const fatal = new Error(message);
        this.failAll(fatal);
        this.createReject?.(fatal);
        this.setState('error', message);
        break;
      }
      default:
        break;
    }
  }

  private setState(state: PythonSessionState, message?: string): void {
    this.state = state;
    this.runtime.emit('sessionState', { sessionId: this.sessionId, state, message } as PythonStateEvent);
  }

  private failAll(error: Error): void {
    for (const [, run] of this.pending) {
      if (run.timer) clearTimeout(run.timer);
      run.reject(error);
    }
    this.pending.clear();
    this.current = null;
  }

  get info(): PythonSessionInfo {
    const out: PythonSessionInfo = {
      sessionId: this.sessionId,
      name: this.name,
      state: this.state,
      stdinMode: this.stdinMode,
      createdAt: this.createdAt,
    };
    if (this.pythonVersion) out.pythonVersion = this.pythonVersion;
    return out;
  }

  /** 执行代码并等待结束（结果 + 本次输出）。 */
  async exec(code: string, timeoutMs?: number): Promise<PythonExecResult> {
    this.assertUsable();
    const executionId = `exec-${++this.seq}`;
    const promise = new Promise<{ result: string | null; stdout: string; stderr: string }>((resolve, reject) => {
      const run: PendingRun = { executionId, resolve, reject, stdout: '', stderr: '' };
      if (timeoutMs && timeoutMs > 0) {
        run.timer = setTimeout(() => {
          this.pending.delete(executionId);
          reject(new Error(`EXEC_TIMEOUT: 超过 ${timeoutMs}ms，会话已终止（可用 restartSession 重建）`));
          this.killWorker();
        }, timeoutMs);
      }
      this.pending.set(executionId, run);
      this.current = run;
      this.worker!.postMessage({ type: 'run', id: executionId, code });
    });
    const out = await promise;
    return { executionId, ...out };
  }

  /** 触发执行但不等结果（输出走事件流）。 */
  run(code: string): PythonRunResult {
    this.assertUsable();
    const executionId = `run-${++this.seq}`;
    const run: PendingRun = { executionId, resolve: () => undefined, reject: () => undefined, stdout: '', stderr: '' };
    this.pending.set(executionId, run);
    this.current = run;
    this.worker!.postMessage({ type: 'run', id: executionId, code });
    return { executionId };
  }

  /** 向 stdin 写入一行（会自动补换行符）。 */
  writeStdin(data: string): void {
    this.assertUsable();
    const line = data.endsWith('\n') ? data : `${data}\n`;
    if (this.stdinMode === 'blocking' && this.sab) {
      this.stdinQueue.push(line);
      this.pumpStdin();
      return;
    }
    this.worker!.postMessage({ type: 'stdin', lines: [line] });
  }

  private pumpStdin(): void {
    if (!this.sab || this.stdinMode !== 'blocking') return;
    const view = new Int32Array(this.sab, 0, 2);
    const writeNext = () => {
      while (this.stdinQueue.length > 0) {
        // flag !== 0 表示 worker 还没消费上一行，等下一次 tick
        if (Atomics.load(view, 0) !== 0) return;
        const line = this.stdinQueue.shift()!;
        const bytes = this.encoder.encode(line);
        if (bytes.length > SAB_LINE_CAPACITY) {
          // 超长行：截断到容量上限（避免越界写）
          new Uint8Array(this.sab!, SAB_HEADER_BYTES, SAB_LINE_CAPACITY).set(bytes.subarray(0, SAB_LINE_CAPACITY));
          Atomics.store(view, 1, SAB_LINE_CAPACITY);
        } else {
          new Uint8Array(this.sab!, SAB_HEADER_BYTES, bytes.length).set(bytes);
          Atomics.store(view, 1, bytes.length);
        }
        Atomics.store(view, 0, 1);
        Atomics.notify(view, 0);
        if (this.stdinQueue.length === 0) return;
      }
    };
    writeNext();
    if (this.stdinQueue.length > 0 && !this.stdinPump) {
      this.stdinPump = setInterval(() => {
        writeNext();
        if (this.stdinQueue.length === 0 && this.stdinPump) {
          clearInterval(this.stdinPump);
          this.stdinPump = null;
        }
      }, 30);
    }
  }

  /**
   * 安装 PyPI 包（micropip）。
   *
   * `indexUrls` 可以指向自建镜像/私有源（PEP 503 simple index，需要 CORS）；不传则用
   * 会话创建时的 `pipIndexUrls`，再退回默认 `https://pypi.org/simple`。
   */
  async installPackages(packages: string[], indexUrls?: string[]): Promise<PythonInstallResult> {
    this.assertUsable();
    const id = `pip-${++this.seq}`;
    const result = this.runtime.registerPip(id);
    this.worker!.postMessage({
      type: 'installPackages',
      id,
      packages,
      indexUrls: indexUrls ?? this.pipIndexUrls,
    });
    await result;
    return { installed: packages };
  }

  /** 从 URL 安装 wheel（离线时把 wheel 放进 App 资源，用相对/绝对 URL 传入）。 */
  async installWheels(urls: string[]): Promise<PythonInstallResult> {
    this.assertUsable();
    const id = `pip-${++this.seq}`;
    const result = this.runtime.registerPip(id);
    const absolute = urls.map((u) => new URL(u, location.href).href);
    this.worker!.postMessage({ type: 'installPackages', id, packages: absolute });
    await result;
    return { installed: urls };
  }

  get isBusy(): boolean {
    return this.pending.size > 0 || this.state === 'running' || this.state === 'installing';
  }

  private assertUsable(): void {
    if (this.state === 'terminated') throw new Error('SESSION_TERMINATED: 会话已终止');
    if (!this.worker) throw new Error('SESSION_NOT_STARTED: 会话尚未创建完成');
  }

  private killWorker(): void {
    if (this.stdinPump) {
      clearInterval(this.stdinPump);
      this.stdinPump = null;
    }
    if (this.worker) {
      this.worker.terminate();
      this.worker = null;
    }
    this.failAll(new Error('SESSION_TERMINATED: 会话已终止'));
  }

  /** 终止会话（销毁 Worker；之后可调用 runtime.restartSession 重建）。 */
  terminate(): void {
    this.killWorker();
    this.setState('terminated');
  }
}

/** 运行时单例所暴露的事件类型。 */
export interface PythonRuntimeEvents {
  sessionOutput: PythonOutputEvent;
  sessionState: PythonStateEvent;
  sessionDone: PythonDoneEvent;
  sessionError: PythonErrorEvent;
}

export class PythonRuntime {
  info: PythonRuntimeInfo | null = null;
  private runtimeBase = '';
  private version = DEFAULT_PYODIDE_VERSION;
  private readonly sessions = new Map<string, PythonSession>();
  private readonly listeners = new Map<string, Set<Listener<any>>>();
  private readonly pipWaiters = new Map<string, { resolve: (v: string[]) => void; reject: (e: Error) => void }>();
  private seq = 0;

  constructor(private readonly defaults: PythonInitializeOptions = {}) {}

  /** Worker 脚本的绝对 URL（`python-runtime/worker.mjs`）。 */
  get workerUrl(): string {
    return new URL(`${this.runtimeBase}worker.mjs`, location.href).href;
  }

  /** 运行时目录的绝对 URL（以 / 结尾）。 */
  get runtimeUrl(): string {
    return new URL(this.runtimeBase, location.href).href;
  }

  /**
   * 解析并校验运行时资源。可重复调用（幂等）；`initialize` 不会启动任何 Python 进程，
   * 真正的解释器在 `createSession()` 时才在 Worker 里加载。
   */
  async initialize(options: PythonInitializeOptions = {}): Promise<PythonRuntimeInfo> {
    const runtimeUrl = options.runtimeUrl ?? this.defaults.runtimeUrl ?? DEFAULT_RUNTIME_URL;
    const base = runtimeUrl.endsWith('/') ? runtimeUrl : `${runtimeUrl}/`;
    this.runtimeBase = base;
    this.version = options.version ?? this.defaults.version ?? DEFAULT_PYODIDE_VERSION;

    const manifestUrl = new URL(`${base}${this.version}/manifest.json`, location.href).href;
    const response = await fetch(manifestUrl);
    if (!response.ok) {
      throw new Error(
        `RUNTIME_NOT_FOUND: 找不到 ${manifestUrl}（HTTP ${response.status}）。` +
          '请先运行 `node plugins/capacitor-python/scripts/fetch-python-runtime.mjs` 把运行时放进 public/。',
      );
    }
    const manifest = (await response.json()) as {
      engine?: string;
      pyodideVersion?: string;
      pythonVersion?: string;
      minimum?: { androidWebView?: number; ios?: string };
      files?: PythonRuntimeFile[];
    };

    const files = manifest.files ?? [];
    const verify = options.verify ?? true;
    if (verify && files.length > 0 && typeof crypto !== 'undefined' && crypto.subtle) {
      await Promise.all(
        files.map(async (file) => {
          const url = new URL(`${base}${this.version}/${file.name}`, location.href).href;
          const res = await fetch(url);
          if (!res.ok) throw new Error(`RUNTIME_FILE_MISSING: ${file.name} (HTTP ${res.status})`);
          const buffer = await res.arrayBuffer();
          const digest = await crypto.subtle.digest('SHA-256', buffer);
          const hex = Array.from(new Uint8Array(digest))
            .map((b) => b.toString(16).padStart(2, '0'))
            .join('');
          if (hex !== file.sha256) {
            throw new Error(`RUNTIME_CORRUPTED: ${file.name} sha256 不匹配（期望 ${file.sha256}，实际 ${hex}）`);
          }
        }),
      );
    }

    this.info = {
      engine: 'pyodide',
      pyodideVersion: manifest.pyodideVersion ?? this.version,
      pythonVersion: manifest.pythonVersion ?? 'unknown',
      runtimeUrl: this.runtimeUrl,
      minimum: {
        androidWebView: manifest.minimum?.androidWebView ?? 90,
        ios: manifest.minimum?.ios ?? '16.4',
      },
      files,
    };
    return this.info;
  }

  /** 创建一个新的 Python 会话（独立 Worker + 独立解释器）。 */
  async createSession(options: PythonCreateSessionOptions = {}): Promise<PythonSessionInfo> {
    if (!this.info) await this.initialize();
    const id = `py-${++this.seq}-${Math.random().toString(36).slice(2, 8)}`;
    const name = options.name ?? `session-${this.seq}`;
    const session = new PythonSession(this, id, name, Date.now());
    this.sessions.set(id, session);
    const indexURL = new URL(`${this.runtimeBase}${this.version}/`, location.href).href;
    try {
      const info = await session.start(indexURL, options);
      if (options.bootstrap) {
        await session.exec(options.bootstrap);
      }
      if (options.packages && options.packages.length > 0) {
        await session.installPackages(options.packages);
      }
      return { ...info, state: session.state };
    } catch (error) {
      this.sessions.delete(id);
      session.terminate();
      throw error;
    }
  }

  getSession(sessionId: string): PythonSession {
    const session = this.sessions.get(sessionId);
    if (!session) throw new Error(`SESSION_NOT_FOUND: ${sessionId}`);
    return session;
  }

  listSessions(): PythonSessionInfo[] {
    return Array.from(this.sessions.values()).map((s) => s.info);
  }

  /** 执行代码并等待结果（含这段代码的 stdout/stderr）。 */
  async exec(sessionId: string, code: string, timeoutMs?: number): Promise<PythonExecResult> {
    return this.getSession(sessionId).exec(code, timeoutMs);
  }

  /** 触发执行（不等结果，输出走事件）。 */
  run(sessionId: string, code: string): PythonRunResult {
    return this.getSession(sessionId).run(code);
  }

  /** 写入 stdin。`blocking` 模式下 `input()` 会真正阻塞等待；`queued` 模式下按顺序消费。 */
  writeStdin(sessionId: string, data: string): void {
    this.getSession(sessionId).writeStdin(data);
  }

  installPackages(sessionId: string, packages: string[], indexUrls?: string[]): Promise<PythonInstallResult> {
    return this.getSession(sessionId).installPackages(packages, indexUrls);
  }

  installWheels(sessionId: string, urls: string[]): Promise<PythonInstallResult> {
    return this.getSession(sessionId).installWheels(urls);
  }

  /** 终止会话并重建一个同名新会话（Python 死循环/崩溃后的恢复手段）。 */
  async restartSession(sessionId: string, options: PythonCreateSessionOptions = {}): Promise<PythonSessionInfo> {
    const old = this.getSession(sessionId);
    const name = old.name;
    old.terminate();
    this.sessions.delete(sessionId);
    return this.createSession({ ...options, name });
  }

  terminateSession(sessionId: string): void {
    const session = this.sessions.get(sessionId);
    if (!session) return;
    session.terminate();
    this.sessions.delete(sessionId);
  }

  terminateAll(): void {
    for (const [, session] of this.sessions) session.terminate();
    this.sessions.clear();
  }

  /** @internal */
  emit<K extends keyof PythonRuntimeEvents>(event: K, payload: PythonRuntimeEvents[K]): void {
    const set = this.listeners.get(event);
    if (!set) return;
    for (const listener of set) {
      try {
        listener(payload);
      } catch (error) {
        console.error(`[capacitor-python] listener for "${event}" threw`, error);
      }
    }
  }

  addListener<K extends keyof PythonRuntimeEvents>(
    event: K,
    listener: Listener<PythonRuntimeEvents[K]>,
  ): { remove: () => void } {
    let set = this.listeners.get(event);
    if (!set) {
      set = new Set();
      this.listeners.set(event, set);
    }
    set.add(listener);
    return {
      remove: () => {
        this.listeners.get(event)?.delete(listener);
      },
    };
  }

  removeAllListeners(): void {
    this.listeners.clear();
  }

  /** @internal */
  registerPip(id: string): Promise<string[]> {
    return new Promise<string[]>((resolve, reject) => {
      this.pipWaiters.set(id, { resolve, reject });
    });
  }

  /** @internal */
  resolvePip(id: string, packages: string[]): void {
    const waiter = this.pipWaiters.get(id);
    if (!waiter) return;
    this.pipWaiters.delete(id);
    waiter.resolve(packages);
  }

  /** @internal */
  rejectPip(id: string, error: Error): void {
    const waiter = this.pipWaiters.get(id);
    if (!waiter) return;
    this.pipWaiters.delete(id);
    waiter.reject(error);
  }
}
