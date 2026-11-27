/**
 * capacitor-python 的对外入口（门面）。
 *
 * 两个引擎，同一套 API：
 *   - **原生引擎**（Android / iOS）：真机 CPython（Android = Chaquopy 内嵌解释器；
 *     iOS = Python-Apple-support 静态库），不依赖 WebView，性能接近桌面 CPython；
 *   - **Web 引擎**（浏览器）：Pyodide（CPython → WebAssembly），因为浏览器里没有别的选择。
 *
 * 判断规则很简单：`Capacitor.getPlatform() === 'web'` → Pyodide，否则 → 原生插件。
 *
 * ```ts
 * import { Python } from 'capacitor-python';
 *
 * await Python.initialize();                       // 启动解释器
 * const s = await Python.createSession({ name: 'demo' });
 * Python.addListener('sessionOutput', (e) => console.log(e.stream, e.data));
 * const out = await Python.exec(s.sessionId, 'print("hi"); 1 + 1');
 * ```
 */

import { Capacitor, registerPlugin } from '@capacitor/core';

import type {
  PythonCreateSessionOptions,
  PythonDoneEvent,
  PythonErrorEvent,
  PythonExecResult,
  PythonInstallOptions,
  PythonInstallResult,
  PythonNativeInfo,
  PythonOutputEvent,
  PythonPlugin,
  PythonRunResult,
  PythonRuntimeInfo,
  PythonSessionHandle,
  PythonSessionInfo,
  PythonSessionState,
  PythonStdinMode,
  PythonStateEvent,
} from './definitions';
import { PythonRuntime as PyodideRuntime } from './engine/runtime';

/** 原生插件（Android = Chaquopy；iOS = Python-Apple-support）。 */
export const PythonNative = registerPlugin<PythonPlugin>('Python');

type Listener<T> = (payload: T) => void;

interface PendingExecution {
  executionId: string;
  stdout: string;
  stderr: string;
  resolve: (result: { result: string | null; stdout: string; stderr: string }) => void;
  reject: (error: Error) => void;
}

/**
 * 门面：把 web 引擎与原生插件统一成同一个对象。
 *
 * 原生侧的输出事件只带 sessionId，所以这里按 session 维护"当前执行"，
 * 把输出归到对应的 execution 上（`exec` 用；`run` 只转发事件）。
 */
class PythonFacade {
  private readonly web = new PyodideRuntime();
  private readonly nativeListeners = new Map<string, Set<Listener<any>>>();
  private readonly current = new Map<string, PendingExecution>();
  private readonly handles = new Map<string, PythonSessionHandle>();
  private nativeAttached = false;
  private info: PythonRuntimeInfo | null = null;
  private forcedEngine: 'auto' | 'native' | 'pyodide' = 'auto';

  /**
   * 是否使用原生引擎。
   *
   * 默认：**Android / iOS = 原生**（Android = Chaquopy CPython 3.11；iOS = Python-Apple-support 3.11）。
   * 两者都不依赖 WebView；`initialize({ engine: 'pyodide' })` 可以显式退回 WASM（仅 iOS/Web 有意义）。
   */
  get isNative(): boolean {
    if (this.forcedEngine === 'native') return true;
    if (this.forcedEngine === 'pyodide') return false;
    const platform = Capacitor.getPlatform();
    return platform === 'android' || platform === 'ios';
  }

  /** 当前引擎信息（initialize 之后才有）。 */
  get runtimeInfo(): PythonRuntimeInfo | null {
    return this.info;
  }

  /** 启动/解析运行时；幂等。 */
  async initialize(
    options: { runtimeUrl?: string; version?: string; verify?: boolean; engine?: 'auto' | 'native' | 'pyodide' } = {},
  ): Promise<PythonRuntimeInfo> {
    this.forcedEngine = options.engine ?? 'auto';
    if (this.isNative) {
      const result = await PythonNative.initialize();
      this.info = {
        engine: 'native',
        pythonVersion: String(result.pythonVersion ?? 'unknown'),
        platform: String(result.platform ?? Capacitor.getPlatform()),
      };
      return this.info;
    }
    this.info = await this.web.initialize(options);
    return this.info;
  }

  /** 原生平台信息 / WebView 版本检查（web 上直接 supported=true）。 */
  async getInfo(): Promise<PythonNativeInfo> {
    if (!this.isNative) return { platform: 'web', engine: 'pyodide', supported: true };
    return PythonNative.getInfo();
  }

  /**
   * 创建会话（原生：独立 globals + 阻塞 stdin；Web：独立 Worker）。
   *
   * 返回的 `PythonSessionHandle` 同时保留了老字段（`sessionId` 等），并额外提供
   * **流式输出 + 写 stdin + 执行** 的方法：
   * ```ts
   * const s = await Python.createSession();
   * s.onStdout((text) => console.log(text));      // 实时
   * s.onStderr((text) => console.error(text));
   * s.writeStdin('Alice');                         // Android/iOS 上 input() 会真阻塞
   * await s.exec('name = input("Name? ")');
   * ```
   */
  async createSession(options: PythonCreateSessionOptions = {}): Promise<PythonSessionHandle> {
    const info = this.isNative
      ? ((await this.nativeCreateSession(options)) as unknown as PythonSessionInfo)
      : await this.web.createSession(options);
    const handle = new PythonSessionHandleImpl(this, info);
    this.handles.set(info.sessionId, handle);
    return handle;
  }

  /** @internal 供会话句柄回调 */
  releaseHandle(sessionId: string): void {
    this.handles.delete(sessionId);
  }

  /** @internal 供会话句柄调用。 */
  private async nativeCreateSession(options: PythonCreateSessionOptions): Promise<PythonSessionInfo> {
    this.attachNativeEvents();
    return (await PythonNative.createSession(options)) as unknown as PythonSessionInfo;
  }

  async listSessions(): Promise<PythonSessionInfo[]> {
    if (this.isNative) {
      const result = await PythonNative.listSessions();
      return (result.sessions ?? []) as PythonSessionInfo[];
    }
    return this.web.listSessions();
  }

  /** 执行并等待：返回结果 + 本次执行的 stdout/stderr。 */
  async exec(sessionId: string, code: string, timeoutMs?: number): Promise<PythonExecResult> {
    if (this.isNative) return this.nativeExec(sessionId, code, timeoutMs);
    return this.web.exec(sessionId, code, timeoutMs);
  }

  /** 触发执行、立即返回；输出与完成都走事件。 */
  run(sessionId: string, code: string): PythonRunResult {
    if (this.isNative) {
      this.attachNativeEvents();
      const executionId = `run-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
      this.nativeEmit('sessionState', { sessionId, state: 'running' });
      void PythonNative.run({ sessionId, code });
      return { executionId };
    }
    return this.web.run(sessionId, code);
  }

  /** 写 stdin（原生：真阻塞 input()；Web：queued 模式）。 */
  async writeStdin(sessionId: string, data: string): Promise<void> {
    if (this.isNative) {
      await PythonNative.writeStdin({ sessionId, data });
      return;
    }
    this.web.writeStdin(sessionId, data);
  }

  /** 安装包：原生只支持纯 Python wheel（URL/路径）；Web 走 micropip。 */
  async installPackages(
    sessionId: string,
    packages: string[],
    options: PythonInstallOptions = {},
  ): Promise<PythonInstallResult> {
    if (this.isNative) {
      if (Capacitor.getPlatform() === 'ios') {
        // zipfile 依赖 zlib（C 扩展）；iOS 纯 stdlib 阶段没有 lib-dynload
        throw new Error(
          'IOS_EXTENSIONS_UNAVAILABLE: iOS 原生引擎目前不含 C 扩展（zlib/ssl/sqlite3 等），' +
            '因此无法解压 wheel。请用宿主 App 的 Run Script 接入 lib-dynload（见 README「iOS 原生引擎」）。',
        );
      }
      return (await PythonNative.installPackages({
        sessionId,
        packages,
      })) as unknown as PythonInstallResult;
    }
    return this.web.installPackages(sessionId, packages, options.indexUrls);
  }

  /** 从 URL 安装 wheel（原生与 web 都支持）。 */
  async installWheels(sessionId: string, urls: string[]): Promise<PythonInstallResult> {
    if (this.isNative) {
      if (Capacitor.getPlatform() === 'ios') {
        throw new Error('IOS_EXTENSIONS_UNAVAILABLE: iOS 原生引擎暂不支持安装 wheel（需要 lib-dynload 的 zlib）');
      }
      return (await PythonNative.installPackages({
        sessionId,
        packages: urls,
      })) as unknown as PythonInstallResult;
    }
    return this.web.installWheels(sessionId, urls);
  }

  async restartSession(sessionId: string): Promise<PythonSessionInfo> {
    if (this.isNative) {
      return (await PythonNative.restartSession({ sessionId })) as unknown as PythonSessionInfo;
    }
    return this.web.restartSession(sessionId);
  }

  async terminateSession(sessionId: string): Promise<void> {
    this.current.delete(sessionId);
    this.handles.delete(sessionId);
    if (this.isNative) {
      await PythonNative.terminateSession({ sessionId });
      return;
    }
    this.web.terminateSession(sessionId);
  }

  async terminateAll(): Promise<void> {
    this.current.clear();
    if (this.isNative) {
      await PythonNative.terminateAll();
      return;
    }
    this.web.terminateAll();
  }

  /**
   * 事件监听。返回 `{ remove() }`（与 web 引擎一致；原生的异步注册在后台完成）。
   */
  addListener<K extends keyof PythonFacadeEvents>(
    event: K,
    listener: Listener<PythonFacadeEvents[K]>,
  ): { remove: () => void } {
    if (!this.isNative) {
      return this.web.addListener(event as never, listener as never);
    }
    this.attachNativeEvents();
    let set = this.nativeListeners.get(event);
    if (!set) {
      set = new Set();
      this.nativeListeners.set(event, set);
    }
    set.add(listener);
    return { remove: () => this.nativeListeners.get(event)?.delete(listener) };
  }

  removeAllListeners(): void {
    this.nativeListeners.clear();
    if (!this.isNative) this.web.removeAllListeners();
  }

  // ------------------------------------------------------------------ native

  private nativeEmit<K extends keyof PythonFacadeEvents>(event: K, payload: PythonFacadeEvents[K]): void {
    const set = this.nativeListeners.get(event);
    if (!set) return;
    for (const listener of set) {
      try {
        listener(payload);
      } catch (error) {
        console.error(`[capacitor-python] listener for "${event}" threw`, error);
      }
    }
  }

  /** 只挂一次原生监听，把事件转成和 web 引擎一致的形状。 */
  private attachNativeEvents(): void {
    if (this.nativeAttached) return;
    this.nativeAttached = true;

    void PythonNative.addListener('sessionOutput', (event: any) => {
      const { sessionId, stream, data } = event;
      const pending = this.current.get(sessionId);
      if (pending) {
        if (stream === 'stdout') pending.stdout += data;
        else pending.stderr += data;
      }
      this.nativeEmit('sessionOutput', { sessionId, stream, data });
    });
    void PythonNative.addListener('sessionDone', (event: any) => {
      const pending = this.current.get(event.sessionId);
      if (pending) {
        this.current.delete(event.sessionId);
        pending.resolve({
          result: event.result ?? null,
          stdout: pending.stdout,
          stderr: pending.stderr,
        });
      }
      this.nativeEmit('sessionDone', {
        sessionId: event.sessionId,
        executionId: pending ? pending.executionId : event.executionId,
        result: event.result ?? null,
      });
      this.nativeEmit('sessionState', { sessionId: event.sessionId, state: 'idle' });
    });
    void PythonNative.addListener('sessionError', (event: any) => {
      const pending = this.current.get(event.sessionId);
      if (pending) {
        this.current.delete(event.sessionId);
        const error = new Error(`${event.type}: ${event.message}`);
        (error as any).pythonType = event.type;
        (error as any).traceback = event.traceback;
        pending.reject(error);
      }
      this.nativeEmit('sessionError', {
        sessionId: event.sessionId,
        executionId: pending ? pending.executionId : event.executionId ?? null,
        type: event.type ?? 'Error',
        message: event.message ?? '',
        traceback: event.traceback ?? null,
      });
      this.nativeEmit('sessionState', { sessionId: event.sessionId, state: 'idle' });
    });
  }

  private nativeExec(sessionId: string, code: string, timeoutMs?: number): Promise<PythonExecResult> {
    this.attachNativeEvents();
    const executionId = `exec-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
    return new Promise<PythonExecResult>((resolve, reject) => {
      const timer = timeoutMs
        ? setTimeout(() => {
            this.current.delete(sessionId);
            reject(new Error(`EXEC_TIMEOUT: 超过 ${timeoutMs}ms，已终止该会话`));
            void this.terminateSession(sessionId);
          }, timeoutMs)
        : null;

      this.current.set(sessionId, {
        executionId,
        stdout: '',
        stderr: '',
        resolve: (out) => {
          if (timer) clearTimeout(timer);
          resolve({ executionId, ...out });
        },
        reject: (error) => {
          if (timer) clearTimeout(timer);
          reject(error);
        },
      });

      this.nativeEmit('sessionState', { sessionId, state: 'running' });
      // 原生 exec 直接 resolve（不像 run 那样发 sessionDone 事件），所以这里主动收尾：
      // 用 pending 里累积的 stdout/stderr 拼出本次执行结果，并补发事件给监听方。
      void PythonNative.exec({ sessionId, code })
        .then((result) => {
          const pending = this.current.get(sessionId);
          if (!pending) return;
          this.current.delete(sessionId);
          pending.resolve({ result: result.result ?? null, stdout: pending.stdout, stderr: pending.stderr });
          this.nativeEmit('sessionDone', {
            sessionId,
            executionId: pending.executionId,
            result: result.result ?? null,
          });
          this.nativeEmit('sessionState', { sessionId, state: 'idle' });
        })
        .catch(
          (error: Error & { data?: { traceback?: string } }) => {
            const pending = this.current.get(sessionId);
            if (!pending) return;
            this.current.delete(sessionId);
            (error as any).traceback = error?.data?.traceback ?? null;
            this.nativeEmit('sessionError', {
              sessionId,
              executionId: pending.executionId,
              type: (error as any).pythonType ?? 'Error',
              message: error.message,
              traceback: error?.data?.traceback ?? null,
            });
            this.nativeEmit('sessionState', { sessionId, state: 'idle' });
            pending.reject(error);
          },
        );
    });
  }
}

/**
 * 会话句柄：`Python.createSession()` 的返回值。
 *
 * 这是"面向对象"的会话 API —— 不需要再自己按 sessionId 过滤事件：
 * ```ts
 * const s = await Python.createSession({ name: 'demo' });
 * s.onStdout((text) => append(text));
 * s.onStderr((text) => appendError(text));
 * await s.exec('print("hi")');
 * s.writeStdin('Alice');            // input() 会读到这里
 * await s.terminate();
 * ```
 */
class PythonSessionHandleImpl implements PythonSessionHandle {
  private readonly info: PythonSessionInfo;
  private readonly outputListeners = new Set<(event: PythonOutputEvent) => void>();
  private readonly stateListeners = new Set<(event: PythonStateEvent) => void>();
  private offOutput: () => void = () => undefined;
  private offState: () => void = () => undefined;

  constructor(private readonly facade: PythonFacade, info: PythonSessionInfo) {
    this.info = { ...info };
    const output = facade.addListener('sessionOutput', (event) => {
      if (event.sessionId !== this.info.sessionId) return;
      for (const listener of this.outputListeners) listener(event);
    });
    this.offOutput = () => output.remove();
    const state = facade.addListener('sessionState', (event) => {
      if (event.sessionId !== this.info.sessionId) return;
      this.info.state = event.state;
      for (const listener of this.stateListeners) listener(event);
    });
    this.offState = () => state.remove();
  }

  get sessionId(): string {
    return this.info.sessionId;
  }

  get name(): string {
    return this.info.name;
  }

  get createdAt(): number {
    return this.info.createdAt;
  }

  get state(): PythonSessionState {
    return this.info.state;
  }

  get stdinMode(): PythonStdinMode {
    return this.info.stdinMode;
  }

  get pythonVersion(): string | undefined {
    return this.info.pythonVersion;
  }

  /** 合并的 stdout+stderr 流（事件里带 `stream` 字段）。 */
  onOutput(listener: (event: PythonOutputEvent) => void): { remove: () => void } {
    this.outputListeners.add(listener);
    return { remove: () => this.outputListeners.delete(listener) };
  }

  onStdout(listener: (text: string) => void): { remove: () => void } {
    return this.onOutput((event) => {
      if (event.stream === 'stdout') listener(event.data);
    });
  }

  onStderr(listener: (text: string) => void): { remove: () => void } {
    return this.onOutput((event) => {
      if (event.stream === 'stderr') listener(event.data);
    });
  }

  onState(listener: (event: PythonStateEvent) => void): { remove: () => void } {
    this.stateListeners.add(listener);
    return { remove: () => this.stateListeners.delete(listener) };
  }

  writeStdin(data: string): Promise<void> {
    return this.facade.writeStdin(this.info.sessionId, data);
  }

  exec(code: string, timeoutMs?: number): Promise<PythonExecResult> {
    return this.facade.exec(this.info.sessionId, code, timeoutMs);
  }

  run(code: string): PythonRunResult {
    return this.facade.run(this.info.sessionId, code);
  }

  installPackages(packages: string[], options?: PythonInstallOptions): Promise<PythonInstallResult> {
    return this.facade.installPackages(this.info.sessionId, packages, options);
  }

  installWheels(urls: string[]): Promise<PythonInstallResult> {
    return this.facade.installWheels(this.info.sessionId, urls);
  }

  async terminate(): Promise<void> {
    // 本地先翻状态并通知，再销毁监听（否则调用方读到的 state 会停留在 idle）
    this.info.state = 'terminated';
    for (const listener of this.stateListeners) {
      listener({ sessionId: this.info.sessionId, state: 'terminated' });
    }
    this.dispose();
    await this.facade.terminateSession(this.info.sessionId);
  }

  async restart(): Promise<PythonSessionHandle> {
    this.dispose();
    const info = await this.facade.restartSession(this.info.sessionId);
    const handle = new PythonSessionHandleImpl(this.facade, info);
    (this.facade as unknown as { handles: Map<string, PythonSessionHandle> }).handles.set(info.sessionId, handle);
    return handle;
  }

  /** 只取消本地监听，不终止 Python 会话。 */
  dispose(): void {
    this.offOutput();
    this.offState();
    this.outputListeners.clear();
    this.stateListeners.clear();
    this.facade.releaseHandle(this.info.sessionId);
  }
}
/** 门面暴露的事件。 */
export interface PythonFacadeEvents {
  sessionOutput: PythonOutputEvent;
  sessionState: PythonStateEvent;
  sessionDone: PythonDoneEvent;
  sessionError: PythonErrorEvent;
}

/** 单例门面：`import { Python } from 'capacitor-python'`。 */
export const Python = new PythonFacade();

/**
 * 检查当前环境能否运行 Python。
 *
 * - 原生（Android/iOS）：不依赖 WebView，直接 supported；
 * - Web：Pyodide 需要较新的浏览器/WebView。
 */
export async function checkPythonSupport(): Promise<{
  supported: boolean;
  info?: PythonNativeInfo;
  reason?: string;
}> {
  if (Capacitor.getPlatform() === 'android' || Capacitor.getPlatform() === 'ios') {
    try {
      const info = await PythonNative.getInfo();
      return { supported: info.supported !== false, info };
    } catch (error) {
      return { supported: true, reason: `getInfo failed: ${String((error as Error)?.message ?? error)}` };
    }
  }
  const major = Number((navigator.userAgent.match(/Chrome\/(\d+)/) ?? [])[1] ?? 0);
  const supported = major === 0 || major >= 90;
  return {
    supported,
    reason: supported ? undefined : `WebView/browser Chrome/${major} < 90，web 版 Pyodide 无法加载（原生平台不受影响）`,
  };
}

export { PyodideRuntime };
export * from './definitions';
