/**
 * Python 会话 worker（每个 session 一个 Worker）。
 *
 * 设计要点
 * --------
 * 1. **每个 session 一个 Worker，而不是一个 Worker 里多解释器**：Pyodide 一个实例就是一个
 *    CPython 解释器，无法在一个 Worker 里安全地隔离开多个"会话"；用 Worker 做隔离，
 *    `worker.terminate()` 就是唯一可靠的 kill 开关（Pyodide 在 Capacitor 里没有
 *    SharedArrayBuffer，`setInterruptBuffer` 不可用），一个会话崩了/死循环了不影响 App。
 *
 * 2. **运行时资源由 worker 自己按 indexURL 加载**：Capacitor 8 的 WebView 会把
 *    `python-runtime/<version>/pyodide.mjs` 当 ESM 返回、`.wasm` 以 `application/wasm` 返回，
 *    所以同一份代码在 Android(https://localhost) 与 iOS(capacitor://localhost) 都能跑。
 *    （这一点在 iOS 上由 Capacitor 8 的 WebViewAssetHandler 保证；Appflow/Capgo 之类不影响。）
 *
 * 3. **stdout/stderr 实时转发**：`setStdout({batched})` 按行回调，直接 postMessage 回主线程，
 *    主线程再以事件形式抛给 JS，因此是"流式"而不是"等结束才拿到"。
 *
 * 4. **stdin 两种模式**：
 *    - 有 SharedArrayBuffer（Android WebView 有；iOS 通常没有）→ `Atomics.wait` 阻塞等待主线程
 *      写入，`input()` / `sys.stdin.readline()` 可以真正阻塞交互；
 *    - 没有 SAB（iOS）→ 只用预输入的队列；队列空时按 EOF 处理（`input()` 抛 EOFError）。
 *      这是 WebAssembly 沙箱的硬限制，插件通过 `stdinMode` 明确告知调用方。
 *
 * 5. 协议全部是 `{ type, id?, ... }` 的 JSON；大文件（wasm/stdlib）走 HTTP，不走 postMessage，
 *    避免复制 13MB 内存。
 */

/** @type {any} */
let pyodide = null;
/** @type {string} */
let indexURL = '';
/** @type {SharedArrayBuffer | null} */
let stdinSab = null;
/** @type {Int32Array | null} */
let stdinView = null;
/** @type {TextEncoder | null} */
let stdinEncoder = null;
/** @type {TextDecoder | null} */
let stdinDecoder = null;
/** @type {string[]} */
const stdinQueue = [];
/** 当前正在执行的任务 id（用于把异常归属到正确的 run 调用） */
let activeTaskId = null;
/** 会话级 pip index（createSession 的 pipIndexUrls） */
let pipIndexUrls = null;

const SAB_HEADER_BYTES = 16;
const SAB_MAX_LINE_BYTES = 64 * 1024 - 16;

function emit(type, payload) {
  self.postMessage({ type, ...payload });
}

function hasSharedArrayBuffer() {
  try {
    // 仅 typeof 检查不够：某些环境有构造函数但构造会抛（无 cross-origin isolation）
    return typeof SharedArrayBuffer === 'function' && new SharedArrayBuffer(8).byteLength === 8;
  } catch {
    return false;
  }
}

/** 从 SAB 读一行（阻塞），主线程用 Atomics.notify 唤醒 */
function readLineFromSab() {
  // 等待主线程写满一行：flag=1
  for (;;) {
    const flag = Atomics.load(stdinView, 0);
    if (flag === 1) break;
    // 200ms 超时只是为了能周期性检查 worker 是否被终止
    Atomics.wait(stdinView, 0, 0, 200);
  }
  const len = Atomics.load(stdinView, 1);
  const bytes = new Uint8Array(stdinSab, SAB_HEADER_BYTES, len);
  const text = stdinDecoder.decode(bytes);
  Atomics.store(stdinView, 1, 0);
  Atomics.store(stdinView, 0, 0);
  return text;
}

function makeStdin() {
  return {
    stdin: () => {
      if (stdinSab) return readLineFromSab();
      if (stdinQueue.length > 0) return stdinQueue.shift();
      // 没有 SAB 且队列为空：返回空串 = EOF。避免在 iOS 上把 worker 卡死。
      return '';
    },
  };
}

async function handleInit(msg) {
  indexURL = msg.indexURL;
  if (Array.isArray(msg.options?.pipIndexUrls) && msg.options.pipIndexUrls.length > 0) {
    pipIndexUrls = msg.options.pipIndexUrls;
  }
  if (msg.sab) {
    stdinSab = msg.sab;
    stdinView = new Int32Array(stdinSab, 0, 2);
    stdinEncoder = new TextEncoder();
    stdinDecoder = new TextDecoder();
  }
  emit('state', { state: 'loading', stdinMode: stdinSab ? 'blocking' : 'queued' });

  // 动态 import 绝对 URL：worker 本身是同源 module worker，import 同源资源不需要 fetch 权限
  const mod = await import(/* @vite-ignore */ indexURL + 'pyodide.mjs');
  // 核心运行时（wasm/stdlib）从本地 App 资产加载；**包**（micropip 以及 micropip 要装的轮子）
  // 默认从 Pyodide 官方 CDN 拉取 —— 因为 App 里不可能内置 357 个包。
  // 纯离线场景：把需要的 .whl 放进 public/python-wheels/，用 installWheels() 安装（不走这里）。
  const version = msg.pyodideVersion || '314.0.8';
  const packageBaseUrl = msg.options?.packageBaseUrl || `https://cdn.jsdelivr.net/pyodide/v${version}/full/`;
  pyodide = await mod.loadPyodide({ indexURL, packageBaseUrl });

  // 用 Writer 接口（`write(buffer)`）而不是 batched：
  // batched 只在整行结束时回调，`print(..., end="")` 或 `input("Name: ")` 的提示符
  // 会一直留在缓冲区里（真实的"输出丢失"）。Writer 拿到的是原始字节，最贴近终端语义。
  const decoder = new TextDecoder();
  const stdoutWriter = {
    isatty: true,
    write(buffer) {
      emit('stdout', { data: decoder.decode(buffer, { stream: true }) });
      return buffer.length;
    },
  };
  const stderrWriter = {
    isatty: true,
    write(buffer) {
      emit('stderr', { data: decoder.decode(buffer, { stream: true }) });
      return buffer.length;
    },
  };
  pyodide.setStdout(stdoutWriter);
  pyodide.setStderr(stderrWriter);
  pyodide.setStdin(makeStdin());

  // 注意：pyodide.version 是 Pyodide 的版本（314.x），不是 CPython 的 3.14.2
  let pythonVersion = 'unknown';
  try {
    pythonVersion = String(pyodide.runPython('import sys; sys.version.split()[0]'));
  } catch {
    /* ignore */
  }

  emit('ready', {
    pythonVersion,
    pyodideVersion: msg.pyodideVersion ?? null,
    stdinMode: stdinSab ? 'blocking' : 'queued',
  });
}

function describePythonError(err) {
  // 注意：字段名不能叫 `type` —— 它会被展开进 `{ type: 'error', ... }` 覆盖事件类型，
  // 导致主线程收不到 'error'/'fatal'（Python 异常会让 exec() 永久挂起）。
  const errorType = err && err.type ? String(err.type) : 'Error';
  const raw = err && err.message ? String(err.message) : String(err);
  // Pyodide 的 PythonError.message 里带着完整 traceback；message 只保留第一行便于 UI 展示，
  // 完整内容放 traceback（诊断时优先看它）。
  const newline = raw.indexOf('\n');
  const message = newline >= 0 ? raw.slice(0, newline) : raw;
  const traceback = err && err.traceback ? String(err.traceback) : raw;
  return { errorType, message, traceback };
}

function toSerializable(value) {
  if (value === undefined || value === null) return null;
  try {
    const asString = String(value);
    return asString;
  } catch {
    return null;
  }
}

async function handleRun(msg) {
  activeTaskId = msg.id;
  try {
    emit('state', { state: 'running' });
    const result = await pyodide.runPythonAsync(msg.code);
    let text = null;
    try {
      text = toSerializable(result);
    } finally {
      // PyProxy 需要显式释放，避免泄漏
      if (result && typeof result.destroy === 'function' && !(result instanceof String)) {
        try {
          result.destroy();
        } catch {
          /* ignore */
        }
      }
    }
    emit('done', { id: msg.id, result: text });
  } catch (err) {
    emit('error', { id: msg.id, ...describePythonError(err) });
  } finally {
    activeTaskId = null;
    emit('state', { state: 'idle' });
  }
}

async function handlePip(msg) {
  try {
    emit('state', { state: 'installing' });
    // 1) micropip 本体不在默认 stdlib 里，先按需加载（会从 Pyodide 包索引/ CDN 取 wheel）
    try {
      await pyodide.loadPackage('micropip');
    } catch (loadErr) {
      emit('pipError', {
        id: msg.id,
        ...describePythonError(loadErr),
        message: `loadPackage(micropip) 失败：${describePythonError(loadErr).message}`,
      });
      return;
    }
    // 2) 用 Python 侧调用 micropip.install（避免 JS→Python 关键字参数的 proxy 差异）
    const packagesJson = JSON.stringify(msg.packages);
    // 3) pip 源：本次调用 > 会话级（createSession.pipIndexUrls）> micropip 默认（pypi.org/simple）
    const indexUrls = msg.indexUrls ?? pipIndexUrls;
    const lines = ['import micropip'];
    if (Array.isArray(indexUrls) && indexUrls.length > 0) {
      lines.push(`micropip.set_index_urls(${JSON.stringify(indexUrls)})`);
    }
    lines.push(`await micropip.install(${packagesJson})`);
    await pyodide.runPythonAsync(lines.join('\n'));
    emit('pipDone', { id: msg.id, packages: msg.packages });
  } catch (err) {
    emit('pipError', { id: msg.id, ...describePythonError(err) });
  } finally {
    emit('state', { state: 'idle' });
  }
}

self.onmessage = async (event) => {
  const msg = event.data || {};
  try {
    switch (msg.type) {
      case 'init':
        await handleInit(msg);
        break;
      case 'run':
        await handleRun(msg);
        break;
      case 'installPackages':
        await handlePip(msg);
        break;
      case 'stdin':
        // 只在 queued 模式下有意义：推入队列供后续 input() 读取
        if (Array.isArray(msg.lines)) stdinQueue.push(...msg.lines);
        break;
      case 'ping':
        emit('pong', { id: msg.id, state: pyodide ? 'ready' : 'not-ready', activeTaskId });
        break;
      case 'evalInSession':
        // 调试用：在会话 globals 里执行并返回 repr
        break;
      default:
        emit('protocolError', { message: `unknown message type: ${msg.type}` });
    }
  } catch (err) {
    emit('fatal', { id: msg.id ?? null, ...describePythonError(err) });
  }
};

// 捕获未处理的异常，让主线程知道 worker 内部出了问题
self.addEventListener('unhandledrejection', (e) => {
  emit('fatal', { id: activeTaskId, ...describePythonError(e.reason) });
});
