# capacitor-python

在 Capacitor App 里运行**真正的 Python**（不是 WebView 里的沙盒）。两套原生引擎、一套 API：

| 平台 | 引擎 | Python | 依赖 WebView？ |
| --- | --- | --- | --- |
| **Android 7+** | **Chaquopy**（CPython 3.11 内嵌在 APK/AAR 里） | **3.11.x** | ❌ 不依赖 |
| **iOS 15+** | **Python-Apple-support**（CPython 3.11 静态库 + stdlib） | **3.11.x** | ❌ 不依赖 |
| Web | **Pyodide**（CPython → WebAssembly） | 3.14.x | ✅ 需要较新浏览器 |

> Chaquopy 是 Android 专用（Gradle 插件 + JVM 桥），没有 iOS 版本；iOS 的等价物是 BeeWare
> `Python-Apple-support`。两者都是 **CPython 3.11**，同一份 `capython/runtime.py`（会话、stdout/stderr 代理、
> 阻塞 stdin、JSON 结果）—— 这就是"统一环境"：API 一样、Python 版本一样、行为一样。

```ts
import { Python } from 'capacitor-python';
import { initEditor, getEditorValue } from 'capacitor-python/editor';

await Python.initialize();                                  // 启动原生 CPython（~1s）
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

# 2) 安装插件并同步原生工程
npm install ./plugins/capacitor-python
npx cap sync

# 3) iOS：下载原生 CPython 运行时（Python-Apple-support，~30MB xcframework + 裁剪后的 stdlib，不入库）
node plugins/capacitor-python/scripts/fetch-python-ios-runtime.mjs

# 4) 只在还需要 Web 端时：下载 Pyodide 运行时（13MB，不入库）
node plugins/capacitor-python/scripts/fetch-python-runtime.mjs
```

- **Android**：`android/build.gradle` 已应用 Chaquopy，Python 3.11 + 三个 ABI（arm64-v8a / armeabi-v7a / x86_64）
  随 AAR 进入 APK，宿主 app 零配置。APK 约 +19MB（三 ABI）；正式发布建议 ABI split 或 App Bundle。
- **iOS**：`fetch-python-ios-runtime.mjs` 会把 `Python.xcframework` 放到 `plugins/capacitor-python/ios/`，
  把裁剪后的 stdlib 放到 SPM 资源目录；`npx cap sync` 后 Xcode 自动链接/打包（不需要改 Podfile）。
- **Web**：`public/python-runtime/` 是 Pyodide 资源，`npx cap sync` 会一起拷进 App；只做原生可以跳过第 4 步。
- 换 Python 版本：Android 改 `chaquopy { defaultConfig { version = "3.12" } }`（3.12+ 仅 64 位）；
  iOS/Web 改 `fetch-python-ios-runtime.mjs` / `fetch-python-runtime.mjs` 的版本（见「升级 Python 版本」）。

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

### iOS（原生引擎，暂不支持）

iOS 的纯 stdlib 方案里没有 `zlib`（C 扩展），而解压 wheel 需要它，所以
`installPackages` / `installWheels` 会抛 `IOS_EXTENSIONS_UNAVAILABLE`。

要支持 iOS 装包，步骤是：宿主 App 加 Run Script 把 `lib-dynload` 的 `.so` 转成 framework 并签名
（见「iOS 原生引擎」），之后 `zlib`/`zipfile` 可用，纯 Python wheel 就能解压进
`Application Support/python-packages` 并加入 `sys.path`（插件的 Swift 侧已经写好这段逻辑）。

### Web（Pyodide）

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

### iOS（原生引擎）

6. **C 扩展缺失**：`lib-dynload` 没有随包（`zlib`/`ssl`/`sqlite3`/`_ctypes` 等），
   所以 `installPackages` / `installWheels` 会抛 `IOS_EXTENSIONS_UNAVAILABLE`；
   需要宿主 App 加 Run Script 才能补齐（见「iOS 原生引擎」）。
7. **中断同样只能"丢弃会话"**：`exec(..., timeoutMs)` 超时后该会话的 globals 会被清空，
   但后台线程要等下一次 bytecode 才退出。
8. **体积**：stdlib ~14MB + 静态 libPython ~6MB（不含 C 扩展）。

### Web（Pyodide 引擎）

9. **浏览器版本**：Pyodide 314 要求 Chrome **≥ 90**、Safari/iOS **≥ 16.4**。
10. **stdin 是队列模式**：没有 SharedArrayBuffer，`input()` 只能消费预先推入的行。
11. **没有真正的线程/`multiprocessing`/`subprocess`/`signal`**；`ssl` 是桩。
12. **首次加载慢**：9.2MB wasm 编译，5~10s。

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

## iOS 原生引擎（已接入，CPython 3.11）

Chaquopy 没有 iOS 版本，iOS 用 BeeWare 的
[Python-Apple-support](https://github.com/beeware/Python-Apple-support)：
预编译的静态 `Python.xcframework`（CPython 3.11.18）+ 裁剪后的 stdlib。

实现方式（**不需要宿主改 Xcode 工程**）：

1. `scripts/fetch-python-ios-runtime.mjs` 下载 `Python-3.11-iOS-support.b13.tar.gz`（30.7MB，校验 sha256），
   解出 xcframework，裁剪 stdlib（去掉 `test/`、`idlelib`、`tkinter`、`ensurepip`、`lib2to3` 等，46MB → ~14MB），
   合并所有 slice 的 Headers（`pyconfig-<arch>.h` 分散在不同 slice 里）；
2. 插件 `Package.swift`：`binaryTarget(name: "Python", path: "ios/Python.xcframework")` +
   `.copy("python")`（stdlib 作为 SPM 资源，运行时用 `Bundle.module` 定位）；
3. `PythonShim`（C target）把 `Py_Initialize` / `PyRun_String` / 回调包成稳定 API
   （binaryTarget 不给 Swift 生成 module map，所以必须有这层 C）；
4. Swift 引擎：一个解释器 + 每会话独立 globals；`capython.runtime` 的 stdout/stderr 代理通过
   **C 注册的 `capython_bridge` 模块**（`emit` / `readline`）回调到 Swift（**不用 ctypes**，它也是 C 扩展）；
   回调里用 `Py_BEGIN_ALLOW_THREADS` 释放 GIL，避免阻塞其它会话；
5. CI 里 autotest 会在 iOS 模拟器上**真实执行** Python（`print` + `1+2` + 编辑器），已通过。

### iOS 目前的边界

- **没有 C 扩展**：`lib-dynload/*.so`（`zlib`、`ssl`、`sqlite3`、`_ctypes`、`_bz2`、`_lzma` 等）没有随包，
  因为 iOS 要求可执行代码在 `Frameworks/` 且由 Xcode 的 embed 阶段签名 —— 这必须由**宿主 App**
  加一个 Run Script（`source Python.xcframework/build/utils.sh; install_python ...`，
  并设置 `ENABLE_USER_SCRIPT_SANDBOXING=NO`）才能完成。纯 Python stdlib + 内建模块可以正常工作。
- 因此 iOS 上 `installPackages` / `installWheels` 目前会抛
  `IOS_EXTENSIONS_UNAVAILABLE`（解压 wheel 需要 `zlib`）。
- 体积：stdlib ~14MB + 静态 libPython ~6MB（App Store 会压缩/thinning）。

## 实测记录（2026-11-27，两端原生引擎）

| 环境 | 结果 |
| --- | --- |
| **Android 10 真机（你的手机，WebView 87）** | ✅ **原生 CPython 3.11.17**：`initialize`、`createSession`、`exec('1+2')=3`、`print` 实时输出、`ValueError` 正常 reject、异常后会话可继续、多会话隔离（NameError）、**阻塞 stdin（`input()` + `writeStdin`）**、编辑器；完整 autotest `ok:true` |
| Android 16 模拟器 | ✅ 同一套原生引擎（x86_64 ABI） |
| **iOS 模拟器（GitHub Actions）** | ✅ **原生 CPython 3.11.18**（Python-Apple-support）：`initialize`、`createSession`、`exec` 返回 `3`、stdout 事件、编辑器；完整 autotest `ok:true` |
| Web | Pyodide 引擎（同一 API，未单独验证） |

## 路线图

- [ ] 本地 HTTP 服务 + Worker 同步 XHR → 真正阻塞的 `input()`（iOS/Android 都能用）
- [ ] `session.snapshot()` / `restore()`（MEMFS/IDBFS 持久化）
- [ ] 原生引擎适配（Android Chaquopy 独立进程 / iOS Python-Apple-support），同一套 JS API 切 `engine`
- [ ] 编辑器补全升级（基于 `pyodide-lock` 的包 API 提示）
