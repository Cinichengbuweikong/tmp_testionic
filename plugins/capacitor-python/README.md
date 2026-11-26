# capacitor-python

在 Capacitor App（Android / iOS）里运行 **CPython 3.14**（Pyodide / WebAssembly），
每个会话一个独立 Web Worker —— Python 崩溃、死循环、`sys.exit()` 都只影响那个 Worker，
不会杀掉 App；stdout/stderr 实时流式回传，支持多会话、pip 包，以及一个框架无关的代码编辑器。

```ts
import { Python } from 'capacitor-python';
import { initEditor, getEditorValue } from 'capacitor-python/editor';

await Python.initialize();                                  // 校验/加载 13MB 运行时
const s = await Python.createSession({ name: 'demo' });     // 独立 Worker + 独立解释器
Python.addListener('sessionOutput', (e) => console.log(e.stream, e.data));

const editor = initEditor('#editor', { value: 'print("hi")' });
const out = await Python.exec(s.sessionId, getEditorValue('#editor'));
console.log(out.stdout, out.result);                        // "hi\n", null
```

## 为什么是 WebAssembly 版 CPython（而不是原生 CPython）

这是本插件最重要的设计决策，调研细节见 `docs/python-plugin-research.md`。

| | 本插件（Pyodide + Worker） | 原生 CPython（Chaquopy / Python-Apple-support） |
| --- | --- | --- |
| **Python 崩溃不影响主程序** | ✅ Worker 独立，`terminate()` 是可靠 kill 开关 | ❌ **iOS 上不可能**（Apple 禁止 fork/exec/子进程，段错误 = App 崩溃）；Android 需 `:python` 进程 + AIDL（约 3~5 周） |
| 实时 stdout/stderr | ✅ Writer 接口，逐字节回传 | ✅ 但要自己替换 `sys.stdout`，iOS 无 console |
| 多会话隔离 | ✅ N 个 Worker = N 个解释器 | ⚠️ Android 要多进程；iOS 只能 sub-interpreter，崩溃一起死 |
| pip | ✅ micropip（纯 Python + Pyodide 轮子，可离线） | ⚠️ 构建期 pip / iOS 运行期只能纯 Python |
| 一套代码覆盖 Android+iOS | ✅ | ❌ 两套原生实现 |
| 体积 | ~13MB | Android +12~22MB；iOS +35~38MB |

结论：需求 1（崩溃隔离）在 iOS 上**只有 Web Worker 能做到**，所以选了 Pyodide。
代价是：旧 WebView 需要 ≥ Chrome/90 / iOS 16.4；`ssl`/原生线程/`subprocess` 不可用（见「已知限制」）。

## 安装

```bash
# 1) 构建插件（生成 dist/）
cd plugins/capacitor-python && npm install && npm run build

# 2) 回到项目根：下载 Pyodide 运行时到 public/python-runtime/（不入库，13MB）
node plugins/capacitor-python/scripts/fetch-python-runtime.mjs
#    也可以指定版本：--version 314.0.8

# 3) 安装插件并同步原生工程
npm install ./plugins/capacitor-python
npx cap sync
```

- Android / iOS 的 Web 资源都走 `public/`，`npx cap sync` 会一起拷进 App；
- 运行时目录结构：`public/python-runtime/worker.mjs` + `public/python-runtime/314.0.8/{pyodide.mjs, pyodide.asm.mjs, pyodide.asm.wasm, python_stdlib.zip, pyodide-lock.json, manifest.json}`。

## 快速上手

```ts
import { Python, checkPythonSupport } from 'capacitor-python';

const support = await checkPythonSupport();     // Android 看 WebView 版本，iOS 看系统版本
if (!support.supported) console.warn(support.reason);

const info = await Python.initialize();          // { engine, pyodideVersion, pythonVersion, minimum, files }

const s = await Python.createSession({
  name: 'demo',
  packages: ['numpy'],                           // 可选：创建后自动 micropip 安装
  bootstrap: 'import sys; print(sys.version)',   // 可选：ready 后先跑一次
});

// 实时输出（流式，不是等结束）
const off = Python.addListener('sessionOutput', (e) => {
  console.log(`[${e.stream}] ${e.data}`);        // 'stdout' | 'stderr'
});

// 方式 A：等结果，同时拿到本次输出
const r = await Python.exec(s.sessionId, 'print("a"); 1 + 1');
// r = { executionId, result: '2', stdout: 'a\n', stderr: '' }

// 方式 B：触发即返回，输出全靠事件
const { executionId } = Python.run(s.sessionId, 'for i in range(3): print(i)');

// stdin（见下节）
Python.writeStdin(s.sessionId, 'hello');

await Python.terminateSession(s.sessionId);
```

## API 详解

### 运行时

| 方法 | 说明 |
| --- | --- |
| `initialize(options?)` | 解析 `manifest.json`、按 sha256 校验每个文件；幂等。`options: { runtimeUrl?, version?, verify? }`。**不会**启动 Python，真正的解释器在 `createSession` 时加载。 |
| `checkPythonSupport()`（独立函数） | 调原生 `getInfo()`：Android 解析 WebView 的 Chrome 主版本，iOS 解析系统版本，返回 `{ supported, info?, reason? }`。 |
| `PythonNative.getInfo()` | 原生壳原始信息：`{ platform, engine, webViewVersion, supported, note? }`。 |

### 会话

| 方法 | 说明 |
| --- | --- |
| `createSession(options?)` | 创建会话（独立 Worker + 独立解释器）。`options: { name?, packages?, bootstrap?, runtimeUrl?, outputBatchMs? }` → `PythonSessionInfo`。 |
| `listSessions()` | 当前所有会话的 `PythonSessionInfo[]`。 |
| `exec(sessionId, code, timeoutMs?)` | 执行并等待，返回 `{ executionId, result, stdout, stderr }`。超时会**终止该会话**并抛 `EXEC_TIMEOUT`。 |
| `run(sessionId, code)` | 触发执行立即返回 `{ executionId }`，输出走事件。 |
| `writeStdin(sessionId, data)` | 写一行（自动补 `\n`）。行为取决 `session.stdinMode`，见下节。 |
| `installPackages(sessionId, packages)` | micropip 安装（联网，或 Pyodide CDN）。 |
| `installWheels(sessionId, urls)` | 安装指定 wheel（相对/绝对 URL）；离线方案。 |
| `restartSession(sessionId)` | 终止并重建同名会话（死循环/崩溃后的恢复手段）。 |
| `terminateSession(sessionId)` / `terminateAll()` | 销毁 Worker。 |
| `getSession(sessionId)` | 拿到 `PythonSession` 实例（有 `exec/run/writeStdin/isBusy/terminate` 等便捷方法）。 |

### 事件

| 事件 | payload |
| --- | --- |
| `sessionOutput` | `{ sessionId, stream: 'stdout' \| 'stderr', data: string }` |
| `sessionState` | `{ sessionId, state: 'loading'\|'idle'\|'running'\|'installing'\|'terminated'\|'error', message? }` |
| `sessionDone` | `{ sessionId, executionId, result: string \| null }` |
| `sessionError` | `{ sessionId, executionId, type, message, traceback? }`（`type` 是 Python 异常类名，如 `NameError`） |

`addListener(event, cb)` 返回 `{ remove() }`；`removeAllListeners()` 清空。

### stdout / stderr

用 Pyodide 的 **Writer** 接口逐字节回传（`write(buffer)`），所以：

- `print("a")` → `"a\n"`；
- `print("a", end="")` → `"a"`（不会被吞，也不会被补换行）；
- `input("Name: ")` 的提示符也会实时出现（如果 `input` 能返回的话，见下节）。

## stdin：两种模式（重要限制）

`session.stdinMode` 会告诉你是哪一种：

| 模式 | 条件 | `input()` 行为 |
| --- | --- | --- |
| `blocking` | 有 `SharedArrayBuffer`（需要 cross-origin isolation） | `Atomics.wait` 真正阻塞，`writeStdin` 实时喂给正在等待的 `input()` |
| `queued` | 没有 SAB（**Capacitor 的 `https://localhost` / `capacitor://localhost` 通常都没有**） | 从预输入队列按顺序取；队列空时按 EOF 处理 → `input()` 抛 `EOFError` |

实测：Android WebView 与 iOS WKWebView 在 Capacitor 里都拿不到 SAB（COOP/COEP 无法设置），
所以**默认是 `queued`**。可行的用法：

```ts
// 一次把所有输入推进去，脚本再逐个 input() 消费
Python.writeStdin(s.sessionId, 'Alice');
Python.writeStdin(s.sessionId, '42');
await Python.exec(s.sessionId, 'name = input(); age = input(); print(name, age)');
```

> 想要"边跑边打字"的真交互，需要给 App 起一个本地 HTTP 服务，让 Worker 里用同步 XHR 阻塞读
> —— 这是后续可加的能力（见 README 末尾「路线图」），当前版本没有实现。

## 多会话与崩溃隔离

```ts
const a = await Python.createSession({ name: 'a' });
const b = await Python.createSession({ name: 'b' });
await Python.exec(a.sessionId, 'x = 1');       // a 的变量
// b 里看不到 x → 抛 NameError（这是预期：两个独立解释器）

// 死循环：只杀 a，b 与 App 都不受影响
try {
  await Python.exec(a.sessionId, 'while True: pass', 3000);
} catch (e) {
  console.log(e.message);                      // EXEC_TIMEOUT...
}
const still = await Python.exec(b.sessionId, '"alive"');
await Python.restartSession(a.sessionId);      // 需要的话重建 a
```

内存：每个会话约 50MB（装 numpy ~100MB）。iOS 的 WebContent 进程有 jetsam 上限，
建议同时活跃会话 ≤ 3 个，不要同时装 numpy+pandas+scipy（~750MB 必崩）。

## pip 包

1. **联网安装**（最简单，需要 INTERNET 权限）：

```ts
await Python.installPackages(s.sessionId, ['numpy', 'requests']);
```

2. **离线 wheel**（把 wheel 放进 App 资源，例如 `public/python-wheels/`）：

```ts
await Python.installWheels(s.sessionId, ['python-wheels/numpy-2.4.6-cp314-cp314-pyodide_2026_0_wasm32.whl']);
```

3. **构建期固定依赖**：在 App 的 `public/python-wheels/` 里放好 wheel，启动时批量安装；
   适合"插件作者把包打进 App Bundle"的场景。

限制：只能装 **纯 Python** 或 **Pyodide 已编译**（`pyemscripten_2026_0` ABI）的轮子。
`psycopg2`、`opencv-python` 这类没有 pyodide 轮子的包装不上（需要自己用 Pyodide 工具链编译）。

### 自定义 pip 源（在 JS 里指定）

micropip 支持 **PEP 503 simple index**，插件把它暴露成两个可配置项：

```ts
// 1) 会话级：这个会话之后所有 installPackages 都走这个源
const s = await Python.createSession({
  pipIndexUrls: ['https://pypi.tuna.tsinghua.edu.cn/simple'],
});

// 2) 单次安装覆盖
await Python.installPackages(s.sessionId, ['requests'], {
  indexUrls: ['https://mirrors.aliyun.com/pypi/simple'],
});

// 3) 多个源（按顺序尝试）
await Python.installPackages(s.sessionId, ['six'], {
  indexUrls: ['https://my-company.example/pypi/simple', 'https://pypi.org/simple'],
});
```

要点：

- **必须是 simple index**（HTML 列表，`/simple/` 那种），不是 JSON API；
- WebView 里 micropip 用 `fetch` 取包，所以镜像/私有源必须返回
  `Access-Control-Allow-Origin`（自建 `devpi` / `bandersnatch` / nginx 反代时记得加）；
- 也可以放**本地目录**：把 server 起在同一台机器的局域网地址上，或干脆用
  `installWheels(['python-wheels/xxx.whl'])` 完全离线安装；
- Pyodide 自己的包（numpy 等预编译轮子）走的是 `packageBaseUrl`（默认 Pyodide CDN），
  和 pip 源是两套：`loadPyodide({ packageBaseUrl })` 可由 `createSession` 的 `runtimeUrl`/内部默认控制，
  需要完全离线时把对应的 `.whl` 放进 `public/python-wheels/` 再用 `installWheels`。


## 代码编辑器（框架无关）

`capacitor-python/editor` 基于 CodeMirror 6（打包 ~140KB gzip），不依赖 Vue/React：

```ts
import {
  initEditor, destroyEditor, getEditorValue, setEditorValue,
  insertEditorText, focusEditor, setEditorReadOnly, setEditorTheme,
  onEditorChange, undoEditor, redoEditor, destroyAllEditors, listEditors,
} from 'capacitor-python/editor';

const handle = initEditor('#editor', {
  value: 'print("hi")',
  theme: 'dark',            // 'light' | 'dark'
  lineNumbers: true,
  tabSize: 4,
  height: '320px',
  placeholder: '输入 Python 代码…',
  onRun: (code) => runIt(code),        // Ctrl/Cmd + Enter
  onChange: (code) => console.log(code),
});

handle.setValue('x = 1');     // 保留撤销历史
handle.insert('# 片段');       // 光标处插入
handle.focus();
handle.setReadOnly(true);
handle.setTheme('light');
handle.destroy();

// 也可以用具名函数（selector 或元素都可以）
getEditorValue('#editor');
setEditorValue('#editor', 'print(2)');
```

特性：Python 语法高亮、`if x:` 回车自动缩进、括号匹配/自动闭合、代码折叠、
**内置函数/关键字补全**（CodeMirror 的 `globalCompletion` + 文档内变量 `localCompletionSource`）、
`Mod-Enter` 运行、撤销/重做、多编辑器实例、`destroy()` 干净释放。

## 升级 Python 版本（给别人/AI 的说明）

运行时是**带版本目录**的，升级不需要动代码逻辑：

```bash
# 1) 下载新版本运行时（Pyodide 版本号规则：314 = CPython 3.14）
node plugins/capacitor-python/scripts/fetch-python-runtime.mjs --version 315.0.0

# 2) 改默认版本（或运行时传参覆盖）
#    src/engine/runtime.ts → DEFAULT_PYODIDE_VERSION = '315.0.0'
#    或者：await Python.initialize({ version: '315.0.0' })

# 3) 重新构建 + 校验
cd plugins/capacitor-python && npm run build
node plugins/capacitor-python/scripts/fetch-python-runtime.mjs   # 重新生成 manifest.json
```

- `manifest.json` 里记录了 Pyodide 版本、CPython 版本、每个文件的 `bytes/sha256`、以及
  **最低 WebView 要求**（`minimum.androidWebView` / `minimum.ios`）；`initialize()` 会按 sha256 校验。
- 旧版本目录可以保留（`public/python-runtime/<old>/`），需要回滚时把默认版本改回去即可。
- 升级前请跑一遍测试页的 autotest（会真实加载 Python 并执行 `1+1`、检查 stdout/编辑器）。

## 已知限制

1. **WebView 版本**：Pyodide 314 要求 Android WebView（Chrome）**≥ 90**、iOS **≥ 16.4**。
   低于此版本会在解析 `pyodide.asm.mjs` 时报 `UNSUPPORTED_WEBVIEW`（实测 Android 10 + WebView 87）。
   用 `checkPythonSupport()` 提前判断并给用户提示。
2. **stdin 不是真阻塞**：见上一节，默认 `queued`。
3. **没有真正的线程/`multiprocessing`/`subprocess`/`signal`**：WASM 沙箱限制；`threading.start()`
   会报 `can't start new thread`。
4. **`ssl` 是桩**：`requests` 走浏览器 fetch，无法控制证书/超时/代理；需要 TLS 细节的库不可用。
5. **C 扩展**：只能用 Pyodide 生态的轮子（numpy/pandas/scipy/scikit-learn/lxml/pillow 等已提供）。
6. **内存**：每会话 ~50MB 起；iOS 有 jetsam，多会话/大包要控制。
7. **后台**：WebView 进后台会被挂起（与所有 WebView 任务一样），长任务需要前台服务配合。
8. **首次加载慢**：9.2MB wasm 编译，中端 Android 5~10s；建议初始化时显示 loading。

## 与其他插件一起使用

- **capacitor-background-keepalive**：Python 长任务在 WebView 里跑，App 切后台会被挂起；
  需要继续跑只能用前台服务保活，但 WebView 仍可能被系统冻结 —— 不要依赖后台跑 Python。
- **capacitor-native-files**：可以用它把用户的 `.py` 文件读进来（`readFile`）再 `exec`，
  也可以把 `public/python-wheels/` 换成用户目录里的 wheel（`installWheels` 需要 URL，
  可配合本地 HTTP 服务）。
- **capacitor-secure-storage**：不要把 API key 写死在 Python 代码里；用 secure-storage 取出后
  通过 `bootstrap` 注入（例如 `os.environ`）。
- **capacitor-live-update**：OTA 只换 Web 资源，**不会**换 `public/python-runtime/`（它是 App 内置资产）；
  想升级 Python 必须发原生版本。反过来，新 Web 包要兼容旧运行时的 Python 版本。
- **capacitor-permissions**：pip 联网安装需要 INTERNET（Capacitor 默认已有）；没有额外权限。

## 实测记录（2026-11-26）

| 环境 | 结果 |
| --- | --- |
| Android 16 模拟器（WebView 新） | ✅ 运行时校验、创建会话（~8.4s）、`exec`（37ms）、`result=3`、多会话隔离（NameError）、死循环超时只杀该会话、stderr、编辑器 init/get/set/destroy |
| Android 10 真机（WebView 87） | ⚠️ 按预期返回 `UNSUPPORTED_WEBVIEW`（提示升级 WebView），不崩溃、不挂起 |
| iOS 模拟器（GitHub Actions） | 见 CI；autotest 会真实加载 Pyodide 并执行 |

## 路线图

- [ ] 本地 HTTP 服务 + Worker 同步 XHR → 真正阻塞的 `input()`（iOS/Android 都能用）
- [ ] `session.snapshot()` / `restore()`（MEMFS/IDBFS 持久化）
- [ ] 原生引擎适配（Android Chaquopy 独立进程 / iOS Python-Apple-support），同一套 JS API 切 `engine`
- [ ] 编辑器补全升级（基于 `pyodide-lock` 的包 API 提示）
