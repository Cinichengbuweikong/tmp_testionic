# capacitor-python

在 Capacitor App 里运行**真正的 Python**。两套引擎、一套 API：

| 平台 | 引擎 | Python | 依赖 WebView？ | 崩溃隔离 |
| --- | --- | --- | --- | --- |
| **Android 7+** | **Chaquopy**（原生 CPython 内嵌在 APK 里） | **3.11.x** | ❌ 不依赖 | 会话级（同进程，见「已知限制」） |
| iOS 15+ / Web | **Pyodide**（CPython → WebAssembly） | 3.14.x | ✅ 需要较新 WebView | Worker 级 |

> **为什么 Android 用原生、iOS/Web 用 WASM**：Chaquopy 是 Android 专用（Gradle 插件 + JVM 桥），
> 没有 iOS 版本；iOS 的原生等价物是 BeeWare `Python-Apple-support`（同样是 CPython 3.11），
> 但它要求宿主 App 改 Xcode 工程（Run Script 把扩展模块转 framework 并签名）+ 安装包 +55MB，
> 所以目前 iOS 走 Pyodide。两端 Python 都是 **3.11 语义**（Pyodide 314 是 3.14，标准库行为基本一致）。

```ts
import { Python } from 'capacitor-python';
import { initEditor, getEditorValue } from 'capacitor-python/editor';

await Python.initialize();                                  // Android: 启动 CPython（~1s）
const s = await Python.createSession({ name: 'demo' });
Python.addListener('sessionOutput', (e) => console.log(e.stream, e.data));

const editor = initEditor('#editor', { value: 'print("hi")' });
const out = await Python.exec(s.sessionId, getEditorValue('#editor'));
console.log(out.stdout, out.result);                        // "hi\n", null
```

## 安装

```bash
# 1) 构建插件（生成 dist/）
cd plugins/capacitor-python && npm install && npm run build

# 2) 回到项目根：插件装好后 Android 不需要任何额外步骤（Chaquopy 的 Python 运行时随 AAR 打进 APK）
npm install ./plugins/capacitor-python
npx cap sync

# 3) 只有 iOS/Web 需要下载 Pyodide 运行时（13MB，不入库）
node plugins/capacitor-python/scripts/fetch-python-runtime.mjs
```

- **Android**：`plugins/capacitor-python/android/build.gradle` 里已应用 Chaquopy（`com.chaquo.python`），
  Python 3.11 + 三个 ABI（arm64-v8a / armeabi-v7a / x86_64）随 AAR 进入 APK，宿主 app 零配置。
  APK 体积约 +19MB（三个 ABI）；正式发布建议用 ABI split（`splits { abi { enable true } }`）或 App Bundle。
- **iOS/Web**：`public/python-runtime/` 是 Pyodide 资源，`npx cap sync` 会一起拷进 App。
- 想换 Python 版本：Android 改 `chaquopy { defaultConfig { version = "3.12" } }`（3.12+ 只支持 64 位）；
  Web/iOS 用 `fetch-python-runtime.mjs --version 315.0.0`（详见「升级 Python 版本」）。

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

两端的策略不同（这是原生 vs WASM 的本质差异）：

### Android（原生 CPython 3.11）

**C 扩展包在构建期用 Chaquopy 的 pip 打进 APK**（运行时没有 pip 进程）：

```groovy
// plugins/capacitor-python/android/build.gradle
chaquopy {
    defaultConfig {
        version = '3.11'
        pip {
            // 纯 Python 与带 Android wheel 的包都可以（numpy/pandas/cryptography 在
            // chaquo.com/pypi 有预编译 wheel；构建机需要同版本 Python，如 3.11）
            install 'requests'
            // install '-r', 'requirements.txt'
        }
    }
}
```

**运行时只能安装纯 Python wheel**（插件负责下载 → 解压到 `files/python-packages` → `sys.path`）：

```ts
await Python.installPackages(s.id, ['https://example.com/wheels/six-1.17.0-py2.py3-none-any.whl']);
await Python.installWheels(s.id, ['python-wheels/six-1.17.0-py2.py3-none-any.whl']);
```

> `installPackages(['numpy'])` 这种"按包名联网安装"在 Android 上会明确报
> `NATIVE_PIP_UNSUPPORTED`：需要 C 编译的包只能在构建期打包。

### iOS / Web（Pyodide）

micropip 支持联网安装与离线 wheel：

```ts
await Python.installPackages(s.id, ['numpy', 'requests']);          // 联网（PyPI + Pyodide CDN）
await Python.installWheels(s.id, ['python-wheels/xxx.whl']);        // 离线
```

限制：只能装 **纯 Python** 或 **Pyodide 已编译**（`pyemscripten_2026_0` ABI）的轮子。

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

### Android（原生引擎）

1. **没有进程级隔离**：所有会话共用一个解释器（独立 globals + 独立线程），
   Python 段错误 / `os._exit()` 会带走整个 App —— 这是原生 CPython 的固有限制，
   要进程级隔离只能把 Python 放进 `:python` 独立进程（本插件没做，见「路线图」）。
   日常的 `raise`、`sys.exit()`、死循环都不会影响其它会话。
2. **中断是协作式的**：`exec(..., timeoutMs)` 超时会终止**会话**（清空 globals），
   但正在跑的线程不会立刻停下（没有 `PyThreadState_SetAsyncExc` 的 Java API；
   `runtime.interrupt()` 里留了 ctypes 实现，仅在下一条 bytecode 生效）。
3. **C 扩展包只能在构建期装**：运行时只支持纯 Python wheel。
4. **APK 体积 +19MB（三个 ABI）**：正式发布请用 ABI split 或 App Bundle。
5. **`sys.stdout` 代理按线程转发**：用户代码自己开线程 `print()` 时没有会话关联，输出会被丢弃。

### iOS / Web（Pyodide 引擎）

6. **WebView 版本**：Pyodide 314 要求 Chrome **≥ 90**、iOS **≥ 16.4**；
   低于此版本会报 `UNSUPPORTED_WEBVIEW`（实测 Android 10 原生已经不受影响）。
7. **stdin 不是真阻塞**：`queued` 模式，见上一节。
8. **没有真正的线程/`multiprocessing`/`subprocess`/`signal`**：WASM 沙箱限制。
9. **`ssl` 是桩**：`requests` 走浏览器 fetch，无法控制证书/超时/代理。
10. **内存**：每会话 ~50MB 起；iOS 有 jetsam，多会话/大包要控制。
11. **首次加载慢**：9.2MB wasm 编译，5~10s。

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

## iOS 原生引擎（下一步，方案已确定）

iOS 上 Chaquopy 不可用（官方无 iOS 支持），原生等价物是 BeeWare 的
[Python-Apple-support](https://github.com/beeware/Python-Apple-support)：
预编译的 `Python.xcframework`（CPython 3.11.18 / 3.14 等）+ stdlib + `lib-dynload/*.so`。

接入需要的步骤（已调研，尚未实现）：

1. 下载 `Python-3.11-iOS-support.b13.tar.gz`（30.7MB，sha256 `e1f55eef…`），解出 `Python.xcframework`；
2. 插件 `Package.swift` 加 `binaryTarget(name: "Python", path: "Python.xcframework")`，
   stdlib 用 `.copy("python")` 作为 SPM 资源（`Bundle.module` 定位），或由宿主 Run Script 拷贝；
3. **宿主 App 的 Xcode 工程必须加一个 Run Script**（SPM 插件无法自动完成）：
   `source Python.xcframework/build/utils.sh; install_python ...` —— 它把 67 个 `.so`
   转成 `Frameworks/<name>.framework` 并 codesign（iOS 不允许 `Frameworks/` 之外的可执行代码），
   同时把 stdlib 拷进 App bundle；需要 `ENABLE_USER_SCRIPT_SANDBOXING=NO`；
4. Swift 侧用 `PyConfig`（`PYTHONHOME`/`PYTHONPATH`、`buffered_stdio=0`）初始化，
   `PyRun_StringFlags` 执行，`sys.stdout` 换成 Python 对象 + ctypes 回调实现实时输出；
5. 体积 +55MB 左右（剪掉 `test/`、`idlelib`、`tkinter`、`ensurepip` 后仍 ~35MB），
   App Store 允许内嵌解释器（2.5.2 的教育类例外），但需要跑 autotest 验证。

在这之前，iOS/Web 走 Pyodide（CI 已验证可用）。

## 实测记录（2026-11-27，原生 Android 引擎）

| 环境 | 结果 |
| --- | --- |
| **Android 10 真机（你的手机，WebView 87）** | ✅ **原生 CPython 3.11.17**：`initialize`、`createSession`、`exec('1+2')=3`、`print` 实时输出、`ValueError` 正常 reject、异常后会话可继续、多会话隔离（NameError）、**阻塞 stdin（`input()` + `writeStdin`）**、编辑器；完整 autotest `ok:true` |
| Android 16 模拟器 | ✅ 同一套原生引擎（x86_64 ABI） |
| iOS 模拟器（GitHub Actions） | ✅ Pyodide 引擎（见 CI）；原生引擎待接入 |
| Web | 未单独验证（Pyodide 引擎，与 iOS 同一实现） |

## 路线图

- [ ] 本地 HTTP 服务 + Worker 同步 XHR → 真正阻塞的 `input()`（iOS/Android 都能用）
- [ ] `session.snapshot()` / `restore()`（MEMFS/IDBFS 持久化）
- [ ] 原生引擎适配（Android Chaquopy 独立进程 / iOS Python-Apple-support），同一套 JS API 切 `engine`
- [ ] 编辑器补全升级（基于 `pyodide-lock` 的包 API 提示）
