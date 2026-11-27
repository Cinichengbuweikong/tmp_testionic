# capacitor-python

在 Capacitor App 里运行**真正的 Python**（不是 WebView 沙盒里的玩具）。两套原生引擎、一套 API：

| 平台 | 引擎 | Python | 依赖 WebView？ |
| --- | --- | --- | --- |
| **Android 7+** | **Chaquopy**（CPython 3.11 内嵌在 APK/AAR 里） | **3.11.x** | ❌ 不依赖 |
| **iOS 15+** | **Python-Apple-support**（CPython 3.11 静态库 + stdlib） | **3.11.x** | ❌ 不依赖 |
| Web | **Pyodide**（CPython → WebAssembly） | 3.14.x | ✅ 需要较新浏览器 |

> Chaquopy 只有 Android 版（Gradle 插件 + JVM 桥）；iOS 用 BeeWare 的 Python-Apple-support。
> 两者都是 **CPython 3.11**，共用同一份 `capython/runtime.py`（会话、stdout/stderr 代理、阻塞 stdin、
> JSON 结果）—— API 一样、Python 版本一样、行为一样。只有 Web 走 WASM。

```ts
import { Python } from 'capacitor-python';
import { initEditor } from 'capacitor-python/editor';

const editor = initEditor('#editor', { value: 'name = input("Name? ")\nprint("hi", name)' });

const session = await Python.createSession({ name: 'demo' });
session.onStdout((text) => console.log(text));     // 实时
session.onStderr((text) => console.warn(text));
session.onState((event) => console.log(event.state));

await session.exec(editor.getValue());             // 执行到 input() 会挂起
await session.writeStdin('Alice');                 // 输入 → 脚本继续
await session.terminate();
```

---

## 能力边界（你关心的六个问题）

### 1. "独立进程或线程运行 Python，崩溃不影响主程序" —— 做到一半，需要分清"崩溃"指什么

| 情况 | 会不会影响主程序 | 说明 |
| --- | --- | --- |
| Python 代码**抛未捕获异常**（`raise`、`NameError`、`ValueError`…） | ❌ 不影响 | 运行时在 Python 侧捕获所有 `BaseException`，转成 `{type, message, traceback}` 返回；会话继续可用，其它会话不受影响 |
| 用户代码 `sys.exit()` / `SystemExit` | ❌ 不影响 | 被捕获，当作正常结束 |
| 死循环 | ⚠️ 部分 | `exec(code, timeoutMs)` 超时会**终止该会话**（清空 globals）；但线程要等下一条 bytecode 才退出，iOS 上无法强杀 |
| **段错误 / 原生崩溃**（C 扩展越界、`os._exit()`、JVM/ObjC 层崩溃） | ✅ **会影响，App 直接退出** | 原生引擎是**同进程**的。要真隔离只能把 Python 放进独立 OS 进程（Android `:python` 服务 / iOS 不可能），本插件没做 |
| Web（Pyodide） | ❌ 不影响 | 每会话独立 Web Worker，`terminate()` 可强杀 |

**结论**：会话级隔离（异常、变量互不污染、死循环可丢弃）做到了；**进程级隔离还没做**。
如果你要求"Python 段错误也不能带走 App"，那 Android 需要加 `:python` 独立进程 + AIDL（路线图里有），
iOS 上则**没有任何办法**（Apple 禁止 fork/exec/子进程）。

### 2. 实时 stdin / stdout / stderr —— stdout/stderr 完全做到；stdin 原生真阻塞

- **stdout / stderr**：Python 的 `write()` 一发生就通过回调（Android: Java 桥；iOS: C 模块）立刻推给 JS，
  是真正的流式；`print("x", end="")`、`input("提示：")` 的提示符都会即时出现（用 Pyodide 的 `batched` 会吞掉这些）。
- **stdin**：Android/iOS 上是**真阻塞**——`input()` 会挂住该会话的 Python 线程，等你 `writeStdin()` 才返回。
  实测（Android 真机）：`input("你叫什么？ ")` 的提示符实时出现 → 900ms 后 `writeStdin('Bob')` → 脚本继续。
- **Web**：浏览器拿不到 SharedArrayBuffer，只能"预先推入多行、脚本按顺序消费"（`queued` 模式）。

### 3. 多份代码同时运行 + 检测状态 + 交互 —— 做到了（但受 GIL 限制）

```ts
const a = await Python.createSession({ name: 'a' });
const b = await Python.createSession({ name: 'b' });
a.onStdout((t) => console.log('A:', t));
b.onStdout((t) => console.log('B:', t));

await Promise.all([a.exec('import time; time.sleep(1)'), b.exec('import time; time.sleep(1)')]);
// 实测总耗时 ~1010ms（不是 2000ms）→ 两个会话确实在交替执行
```

- **状态**：`session.state`（`idle/running/installing/terminated/error`）+ `onState()` 事件；
  `Python.listSessions()` 列出全部；
- **交互**：每个会话独立 `writeStdin` / `exec` / `run` / `terminate` / `restart`，globals 互不可见（`x=42` 在另一个会话里是 `NameError`）；
- **限制**：CPython 有 GIL，**CPU 密集**的代码不会真正并行（一个会话算 10 秒，另一个要排队）；
  I/O（`sleep`、网络、文件）会释放 GIL，所以能交错。要真并行只能多进程（同第 1 条）。

### 4. 安装 pip 包 / 打包时嵌入 —— 矩阵

| | Android | iOS | Web |
| --- | --- | --- | --- |
| **普通（纯 Python）包** | ✅ 构建期 `pip { install }` + ✅ 运行时 `installWheels()` | ✅ 构建期 `ios/requirements.txt` 嵌入 | ✅ 运行时 `micropip`（在线/离线） |
| **C 扩展包（numpy 等）** | ✅ 构建期 `pip { install 'numpy' }`（Chaquopy 有 Android wheel） | ⚠️ 需要宿主 App 加 Run Script + 自备 iOS wheel | ✅ 用 Pyodide 已编译的 numpy 轮子 |

实测：Android 构建期打包 `requests 2.34.2`（清数据后仍可 `import`）、运行时装 `six 1.17.0`；
iOS 构建期嵌入 `six 1.17.0`（autotest 断言通过）；Web 走 micropip。
详细命令见 **[pip 包：三个平台的完整示例](#pip-包三个平台的完整示例)**。

### 5. 代码编辑器 —— 做到了

`capacitor-python/editor`：CodeMirror 6，Python 高亮、`if:` 自动缩进、括号匹配/折叠、
内置函数补全、`Mod-Enter` 运行、get/set/insert/focus/readOnly/theme/undo/redo、多实例、干净销毁。
测试页里有一个**真机实验台**（编辑器 + 运行 + 停止 + 重启 + stdin 输入框 + 流式输出）。

### 6. 未来升级 Python 版本 —— 设计好了，见 [维护与升级](#维护与升级python-版本)

- Android：改 `chaquopy { version }` 一行；
- iOS：改 `scripts/fetch-python-ios-runtime.mjs` 里的 `RELEASES` 表（版本号/URL/sha256），Swift 从
  `PythonRuntime.json` 读取 `series`，**不需要改代码**；
- Web：`fetch-python-runtime.mjs --version` + `DEFAULT_PYODIDE_VERSION`。
- 三端都有"升级检查清单"和回滚方式。

---

## 安装

```bash
# 1) 构建插件（生成 dist/）
cd plugins/capacitor-python && npm install && npm run build

# 2) 安装插件并同步原生工程
npm install ./plugins/capacitor-python
npx cap sync

# 3) iOS：下载原生 CPython 运行时（Python-Apple-support xcframework + 裁剪后的 stdlib，不入库）
node plugins/capacitor-python/scripts/fetch-python-ios-runtime.mjs

# 4) 只在还需要 Web 端时：下载 Pyodide 运行时（13MB，不入库）
node plugins/capacitor-python/scripts/fetch-python-runtime.mjs
```

- **Android**：`android/build.gradle` 已应用 Chaquopy，Python 3.11 + 三个 ABI（arm64-v8a / armeabi-v7a / x86_64）
  随 AAR 进入 APK，宿主 app 零配置。APK 约 +19MB（三 ABI）；正式发布建议 ABI split 或 App Bundle。
- **iOS**：`fetch-python-ios-runtime.mjs` 把 `Python.xcframework` 放到 `ios/`、裁剪后的 stdlib 放进 SPM 资源；
  `npx cap sync` 后 Xcode 自动链接/打包（**不需要 Podfile，也不需要改 Xcode 工程**）。
- **Web**：`public/python-runtime/` 是 Pyodide 资源，`npx cap sync` 会拷进 App；只做原生可以跳过第 4 步。

---

## 快速上手

```ts
import { Python, checkPythonSupport } from 'capacitor-python';
import { initEditor, getEditorValue } from 'capacitor-python/editor';

// 0) 平台能力（原生恒为 supported；Web 会检查浏览器版本）
const support = await checkPythonSupport();

// 1) 启动解释器（幂等；Android/iOS 约 1s）
const info = await Python.initialize();
console.log(info.engine, info.pythonVersion);      // 'native' | 'pyodide', '3.11.17'

// 2) 建会话 —— 返回会话句柄，自带流式输出 / stdin / 执行
const session = await Python.createSession({ name: 'demo' });
session.onStdout((text) => console.log(text));
session.onStderr((text) => console.warn(text));
session.onState((event) => console.log(event.state));

// 3) 执行（两种方式）
const r1 = await session.exec('print("a"); 1 + 1');   // { result:'2', stdout:'a\n', stderr:'' }
const r2 = session.run('for i in range(3): print(i)'); // 立即返回 { executionId }，输出走事件

// 4) stdin
await session.writeStdin('Alice');

// 5) 收尾
await session.terminate();
```

---

## API 详解

### 3.1 `Python`（门面，单例）

#### `initialize(options?) → Promise<PythonRuntimeInfo>`

启动/解析运行时（幂等）。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `options.engine` | `'auto' \| 'native' \| 'pyodide'` | 否 | 默认 `auto`：Android/iOS 用原生、Web 用 Pyodide。`'pyodide'` 可在 iOS 上强制走 WASM |
| `options.runtimeUrl` | `string` | 否 | Web 专用：Pyodide 资源目录，默认 `python-runtime/` |
| `options.version` | `string` | 否 | Web 专用：Pyodide 版本目录，默认 `314.0.8`（= CPython 3.14） |
| `options.verify` | `boolean` | 否 | Web 专用：是否校验每个文件的 sha256，默认 `true` |

返回 `PythonRuntimeInfo`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `engine` | `'native' \| 'pyodide'` | 实际使用的引擎 |
| `pythonVersion` | `string` | 解释器版本（原生 `3.11.17`/`3.11.18`，Web `3.14.2`） |
| `platform` | `string?` | 原生平台（`android` / `ios`）；Web 没有 |
| `pyodideVersion` | `string?` | 仅 Web：Pyodide 版本 |
| `runtimeUrl` | `string?` | 仅 Web：资源目录绝对 URL |
| `minimum` | `{ androidWebView: number; ios: string }?` | 仅 Web：最低 WebView 要求 |
| `files` | `{ name; bytes; sha256 }[]?` | 仅 Web：资源清单（可用于校验/展示） |

#### `getInfo() → Promise<PythonNativeInfo>`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `platform` | `'android' \| 'ios' \| 'web'` | 平台 |
| `engine` | `'native' \| 'pyodide'` | 引擎 |
| `webViewVersion` | `string?` | Android：WebView 的 Chrome 主版本；iOS：系统版本 |
| `supported` | `boolean?` | 原生恒 `true`（不依赖 WebView）；Web 看浏览器版本 |
| `note` | `string?` | 说明（例如"原生 CPython，不依赖 WebView"） |

#### `createSession(options?) → Promise<PythonSessionHandle>`

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `options.name` | `string` | 否 | 会话名（默认 `session-N`），只用于展示/日志 |
| `options.packages` | `string[]` | 否 | 创建后自动安装（Android：纯 Python wheel URL；Web：包名；iOS：会抛错） |
| `options.bootstrap` | `string` | 否 | `ready` 后先执行一次的初始化代码 |
| `options.pipIndexUrls` | `string[]` | 否 | Web 专用：PEP 503 simple index（自建源需 CORS） |
| `options.runtimeUrl` | `string` | 否 | Web 专用：本会话的运行时覆盖 |
| `options.outputBatchMs` | `number` | 否 | 输出批量间隔（默认 0 = 立即推送） |


**`options.packages` 的示例**（三个平台差异很大）：

```ts
// Web：包名（micropip 在线安装；纯 Python 或 Pyodide 已编译的轮子）
await Python.createSession({ packages: ['numpy', 'pandas', 'requests'] });

// Android：纯 Python wheel 的 URL（下载 → 解压到 files/python-packages → sys.path）
await Python.createSession({
  packages: ['https://cdn.jsdelivr.net/pyodide/v314.0.8/full/six-1.17.0-py2.py3-none-any.whl'],
});

// Android：本地 wheel 路径（先由 native-files 等放进 App 私有目录）
await Python.createSession({
  packages: ['/data/user/0/com.myapp.testionic/files/wheels/six-1.17.0-py2.py3-none-any.whl'],
});

// iOS：**不能**在创建时装包（会抛 IOS_EXTENSIONS_UNAVAILABLE，缺 zlib）。
// 纯 Python 包写进 plugins/capacitor-python/ios/requirements.txt，构建期嵌入；
// C 扩展包见「iOS C 扩展（numpy）」章节。
```

返回 **`PythonSessionHandle`**（见 3.2）。

#### 其它方法

| 方法 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `listSessions()` | — | `Promise<PythonSessionInfo[]>` | 所有会话的信息（字段见 3.3） |
| `exec(sessionId, code, timeoutMs?)` | `string, string, number?` | `Promise<PythonExecResult>` | 执行并等待；超时会终止该会话 |
| `run(sessionId, code)` | `string, string` | `PythonRunResult` | 触发执行立即返回，输出走事件 |
| `writeStdin(sessionId, data)` | `string, string` | `Promise<void>` | 写一行到 stdin（自动补 `\n`） |
| `installPackages(sessionId, packages, options?)` | `string, string[], { indexUrls?: string[] }?` | `Promise<PythonInstallResult>` | 安装包（平台差异见 pip 章节） |
| `installWheels(sessionId, urls)` | `string, string[]` | `Promise<PythonInstallResult>` | 按 URL 安装 wheel |
| `restartSession(sessionId)` | `string` | `Promise<PythonSessionInfo>` | 终止并重建同名会话（返回新 id） |
| `terminateSession(sessionId)` | `string` | `Promise<void>` | 终止并释放 |
| `terminateAll()` | — | `Promise<void>` | 终止全部 |
| `addListener(event, cb)` | 见 3.4 | `{ remove(): void }` | 全局事件（不带会话过滤，建议用句柄） |
| `removeAllListeners()` | — | `void` | 清空全局监听 |

`PythonExecResult` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `executionId` | `string` | 本次执行 id |
| `result` | `string \| null` | 最后一个表达式求值的 `repr`（无返回值时 `null`） |
| `stdout` | `string` | 本次执行期间的 stdout 聚合 |
| `stderr` | `string` | 本次执行期间的 stderr 聚合 |


#### 安装示例（`installPackages` / `installWheels`）

```ts
const s = await Python.createSession();

// ---- Android：纯 Python wheel（URL 或本地路径）----
await s.installWheels(['https://example.com/wheels/six-1.17.0-py2.py3-none-any.whl']);
await s.installPackages(['https://example.com/wheels/requests-2.34.2-py3-none-any.whl']);
await s.installWheels(['/data/user/0/com.myapp.testionic/files/wheels/urllib3-2.8.0-py3-none-any.whl']);
// 装完直接 import（不需要重启会话）
await s.exec('import six; six.__version__');

// ---- Web：包名（micropip）----
await s.installPackages(['requests']);
await s.installPackages(['numpy', 'pandas']);
// Web：自定义源（PEP 503 simple index，需要 CORS）
await s.installPackages(['six'], { indexUrls: ['https://mirror.example/simple'] });
// Web：离线 wheel（放在 public/python-wheels/ 下）
await s.installWheels(['python-wheels/numpy-2.4.6-cp314-cp314-pyodide_2026_0_wasm32.whl']);

// ---- iOS：两者都会抛错 ----
try {
  await s.installWheels(['python-wheels/six-1.17.0-py2.py3-none-any.whl']);
} catch (error) {
  // IOS_EXTENSIONS_UNAVAILABLE: iOS 原生引擎暂不支持安装 wheel（需要 lib-dynload 的 zlib）
}
```

| 平台 | `installPackages([...])` 接受什么 | `installWheels([...])` 接受什么 |
| --- | --- | --- |
| Android | 纯 Python wheel 的 URL / 本地绝对路径（传包名会报 `NATIVE_PIP_UNSUPPORTED`） | 同左 |
| iOS | 不支持（抛 `IOS_EXTENSIONS_UNAVAILABLE`） | 不支持（同上） |
| Web | 包名（micropip）或 wheel URL | wheel 的 URL/相对路径 |

### 3.2 `PythonSessionHandle`（`createSession` 的返回值）

**这就是"一个会话"的对象**：流式输出、写 stdin、执行、状态都在它身上。

| 成员 | 类型 | 说明 |
| --- | --- | --- |
| `sessionId` | `string`（只读） | 会话 id（与事件里的 `sessionId` 一致） |
| `name` | `string`（只读） | 创建时传的名字 |
| `createdAt` | `number`（只读） | 创建时间戳（ms） |
| `state` | `PythonSessionState`（只读） | 当前状态：`loading/idle/running/installing/terminated/error` |
| `stdinMode` | `'blocking' \| 'queued'`（只读） | 原生 `blocking`（真阻塞）；Web `queued` |
| `pythonVersion` | `string?`（只读） | 版本（ready 后才有） |
| `onStdout(cb)` | `(text: string) => void` → `{remove}` | **实时** stdout |
| `onStderr(cb)` | `(text: string) => void` → `{remove}` | **实时** stderr |
| `onOutput(cb)` | `(event: PythonOutputEvent) => void` → `{remove}` | 合并流（事件里带 `stream: 'stdout'\|'stderr'`） |
| `onState(cb)` | `(event: PythonStateEvent) => void` → `{remove}` | 状态变化 |
| `writeStdin(data)` | `string` → `Promise<void>` | 写一行到 stdin（原生上会唤醒阻塞的 `input()`） |
| `exec(code, timeoutMs?)` | `string, number?` → `Promise<PythonExecResult>` | 执行并等待 |
| `run(code)` | `string` → `PythonRunResult` | 立即返回，输出走 `onStdout/onStderr` |
| `installPackages(packages, options?)` | 同门面 | `Promise<PythonInstallResult>` |
| `installWheels(urls)` | 同门面 | `Promise<PythonInstallResult>` |
| `terminate()` | → `Promise<void>` | 终止（状态置 `terminated`，监听自动清理） |
| `restart()` | → `Promise<PythonSessionHandle>` | 重建并返回**新句柄**（旧句柄失效） |
| `dispose()` | → `void` | 只取消本地监听，不终止 Python 会话 |

### 3.3 `PythonSessionInfo`（`listSessions()` 的元素）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sessionId` | `string` | 会话 id |
| `name` | `string` | 名字 |
| `state` | `PythonSessionState` | 状态 |
| `stdinMode` | `'blocking' \| 'queued'` | stdin 模式 |
| `createdAt` | `number` | 创建时间戳（ms） |
| `pythonVersion` | `string?` | 解释器版本 |

### 3.4 事件

| 事件 | payload 字段 |
| --- | --- |
| `sessionOutput` | `sessionId: string`、`stream: 'stdout' \| 'stderr'`、`data: string` |
| `sessionState` | `sessionId: string`、`state: PythonSessionState`、`message?: string` |
| `sessionDone` | `sessionId: string`、`executionId: string`、`result: string \| null` |
| `sessionError` | `sessionId: string`、`executionId: string \| null`、`type: string`（Python 异常类名）、`message: string`、`traceback?: string \| null` |

### 3.5 其它类型

| 类型 | 字段 |
| --- | --- |
| `PythonInstallResult` | `installed: string[]`（本次请求的包/wheel 列表） |
| `PythonRunResult` | `executionId: string` |
| `PythonNativeInfo` | 见 3.1 `getInfo()` |
| `PythonSessionState` | `'loading' \| 'idle' \| 'running' \| 'installing' \| 'terminated' \| 'error'` |

---

## stdin 的两种模式

| 模式 | 平台 | `input()` 行为 |
| --- | --- | --- |
| `blocking` | **Android / iOS** | 真正阻塞，`writeStdin()` 立刻让 `input()` 返回（实测：提示符实时出现，900ms 后输入，脚本继续） |
| `queued` | Web | 从预输入队列按顺序取；队列空时 `EOFError` |

```ts
const s = await Python.createSession();
s.onStdout((text) => append(text));       // 会先看到 "Name? "
s.run('name = input("Name? ")');
await s.writeStdin('Alice');              // 之后继续执行
```

> Web 上要"边跑边打字"需要本地 HTTP 服务 + Worker 同步 XHR（路线图，未实现）。

---

## 多会话、并发与状态

```ts
const a = await Python.createSession({ name: 'a' });
const b = await Python.createSession({ name: 'b' });

await a.exec('secret = 42');
await b.exec('secret');                    // NameError：两个会话的 globals 完全隔离

// 并发（I/O 型）：sleep 期间释放 GIL，两个会话交替执行
const t0 = Date.now();
await Promise.all([a.exec('import time; time.sleep(1)'), b.exec('import time; time.sleep(1)')]);
console.log(Date.now() - t0);              // 实测 ~1010ms，而不是 2000ms

// 死循环：超时只丢弃该会话
try {
  await a.exec('while True: pass', 3000);
} catch (error) {
  console.log(error.message);              // EXEC_TIMEOUT...
}
await b.exec('"still alive"');             // b 不受影响
```

状态流转：`loading → idle → running → idle`；安装包时 `installing`；终止后 `terminated`。
崩溃（异常）不会让会话变 `error`——异常通过 `exec` 的 reject 返回，会话仍是 `idle`。

---

## pip 包：三个平台的完整示例

### Android

**（A）普通包 + C 扩展包：构建期用 Chaquopy 的 pip 打进 APK**

```groovy
// plugins/capacitor-python/android/build.gradle
chaquopy {
    defaultConfig {
        version = '3.11'
        // 构建机需要有同版本 Python（3.11）；Windows 示例：'C:\\python311\\python.exe'
        buildPython '/usr/bin/python3.11'
        pip {
            install 'requests'          // 纯 Python（依赖 urllib3/certifi/idna 自动带上）
            install 'numpy==2.2.6'      // C 扩展：Chaquopy 从 chaquo.com/pypi 拿 Android wheel
            // install '-r', 'requirements.txt'
        }
    }
}
```

```ts
const s = await Python.createSession({ name: 'deps' });
await s.exec('import requests, numpy; (requests.__version__, numpy.__version__)');
```

> 实测：`pip { install 'requests' }` → 清空 App 数据后仍能 `import requests`（2.34.2）。
> numpy 需要构建机联网 + 同版本 Python；Chaquopy 的 index 里有 `numpy 1.26.2`（cp311，android_24）。

**（B）运行时安装纯 Python wheel（不需要重新打包）**

```ts
const s = await Python.createSession();
await s.installWheels(['https://example.com/wheels/six-1.17.0-py2.py3-none-any.whl']);
// 或者 Python.installPackages(s.sessionId, [url])
await s.exec('import six; six.__version__');   // 实测 '1.17.0'
```

> 只能装**纯 Python** wheel（解压到 `files/python-packages` + `sys.path`）。
> 传包名（如 `installPackages(['numpy'])`）会明确报 `NATIVE_PIP_UNSUPPORTED`。

### iOS

**（A）普通包：构建期嵌入（`ios/requirements.txt`）**

```bash
# plugins/capacitor-python/ios/requirements.txt
six==1.17.0
requests==2.34.2
```

```bash
node plugins/capacitor-python/scripts/fetch-python-ios-runtime.mjs
# 它会：下载 xcframework → 裁剪 stdlib → python3 -m pip install --target <stdlib>/site-packages
#       --only-binary=:all: -r requirements.txt → 扫描并拒绝任何 .so/.dylib
```

装好后随 SPM 资源进 App，直接 `import`。CI 的 autotest 已断言 `six.__version__ == 1.17.0`。

**（B）C 扩展包（numpy 等）：需要宿主 App 的 Run Script**

先回答几个最容易迷路的问题：

| 疑问 | 答案 |
| --- | --- |
| `plugins/capacitor-python/ios/Python.xcframework` 在哪？为什么看不到？ | **仓库里没有，要先生成**：跑 `node plugins/capacitor-python/scripts/fetch-python-ios-runtime.mjs` 之后才会出现在 `plugins/capacitor-python/ios/Python.xcframework/`（已被 `.gitignore` 忽略）。里面的 `build/utils.sh` 是 BeeWare 官方脚本，随 xcframework 一起下载，不是本仓库的文件。 |
| `app_packages/` 要自己新建吗？ | **要**。它是你放"要打进 App 的 C 扩展包"的目录，建议建在 `ios/App/app_packages/`（和 `App.xcodeproj` 同级）。空目录也可以，脚本会跳过。 |
| App target 在哪看？ | 用 Xcode 打开 **`ios/App/App.xcodeproj`**（本项目用 SPM，没有 `.xcworkspace`）→ 左侧 `TARGETS` → `App`。 |
| Build Settings 在哪设？ | 同一个 Xcode 窗口：选中 `App` target → `Build Settings` → 搜 `ENABLE_USER_SCRIPT_SANDBOXING` 改 `NO`；`IPHONEOS_DEPLOYMENT_TARGET` 改 `15.0`。 |
| `PYTHONPATH` 在哪设？ | **不用自己设**。插件启动时会把 `App.app/python/lib/python3.X`、`site-packages`、`App.app/app_packages` 拼成 `PYTHONPATH` 传给 CPython（`PythonEngineIOS.swift`）。检测到 `App.app/python` 时优先用它，否则退回 SPM 资源里的纯 Python stdlib。 |

**第一步：用 Briefcase 交叉编译出 numpy 的 iOS arm64 轮子**

PyPI 上没有 iOS 轮子（`pip install numpy` 只会得到 macOS 版本）。用 BeeWare 官方工具：

```bash
# 0) 需要 macOS + Xcode；Python 3.11（要和插件里的 CPython 版本一致）
python3 -m pip install briefcase

# 1) 建一个临时 Briefcase 工程
briefcase new -Q "app_name=wheelbuilder" -Q "formal_name=WheelBuilder" \
  -Q "bundle=com.example" -Q "project_name=wheelbuilder" \
  -Q "author=you" -Q "author_email=you@example.com" \
  -Q "url=https://example.com" -Q "license=MIT" \
  -Q "gui_framework=None" -Q "test_framework=None" \
  -Q "build_system=setuptools" -Q "sources=src" -Q "python_version=3.11"
cd wheelbuilder

# 2) 在 pyproject.toml 的 [tool.briefcase] 下加依赖
#    requires = ["numpy==2.2.6"]

# 3) 生成 iOS 工程并构建（这一步会交叉编译 numpy 及其 C 扩展）
briefcase create iOS
briefcase build iOS

# 4) 产物：numpy 的纯 Python 部分 + .so
ls build/wheelbuilder/ios/xcode/app_packages
```

把 `build/<app>/ios/xcode/app_packages/` **整个目录**复制到 `ios/App/app_packages/`。

> 关键：Briefcase 用的 Python 版本必须和插件一致（当前是 **3.11**），否则 `.so` 的 ABI 对不上
> （`cp311-cp311-iphoneos`）。
> 替代方案：mobile-forge / conda-forge 的 iOS 配方；或自己用 Xcode clang 交叉编译
> （`CC="xcrun -sdk iphoneos clang -arch arm64"` + `_PYTHON_HOST_PLATFORM=iphoneos`）。

**第二步：加 Run Script（只做一次，之后一直有效）**

Xcode → `App` target → `Build Phases` → `+` → `New Run Script Phase`，
拖到 **`Copy Bundle Resources` 之后、`Embed Frameworks` 之前**，脚本填：

```sh
set -e
# 注意：SRCROOT = ios/App，插件在仓库根的 plugins/ 下 → 往上两级
PLUGIN_DIR="$SRCROOT/../../plugins/capacitor-python"
XCFRAMEWORK="$PLUGIN_DIR/ios/Python.xcframework"
if [ ! -d "$XCFRAMEWORK" ]; then
  echo "error: 先运行 node plugins/capacitor-python/scripts/fetch-python-ios-runtime.mjs" >&2
  exit 1
fi
source "$XCFRAMEWORK/build/utils.sh"
# 参数：xcframework、app 目录（可空）、app_packages 目录（放 C 扩展）
install_python "$XCFRAMEWORK" "$SRCROOT/app" "$SRCROOT/app_packages"
```

它会把 `app_packages` 里的每个 `.so` 转成 `Frameworks/<模块名>.framework`，并用
`$EXPANDED_CODE_SIGN_IDENTITY` 签名（就是给 App 签名的那个身份），同时把 stdlib 拷到 `App.app/python`。

**第三步：Build Settings**

- `ENABLE_USER_SCRIPT_SANDBOXING = NO`（否则脚本不能写 App bundle；CPython 官方也要求）；
- `IPHONEOS_DEPLOYMENT_TARGET = 15.0`。

**第四步：验证**

```ts
const s = await Python.createSession();
await s.exec('import numpy; (numpy.__version__, int(numpy.arange(3).sum()))');
```

> 结论：**签名不是门槛**（普通开发者账号即可；模拟器未签名构建可跳过），**拿 iOS 轮子才是**。
> App Store 允许内嵌解释器和第三方库，但不要用它下发"改变 App 功能"的代码（2.5.2）。

### Web

```ts
const s = await Python.createSession({ pipIndexUrls: ['https://pypi.org/simple'] });

// 普通包（micropip 在线安装）
await s.installPackages(['requests']);
// C 扩展包：用 Pyodide 已编译的轮子（numpy/pandas/scipy/scikit-learn/lxml/pillow/cryptography…）
await s.installPackages(['numpy']);

// 离线：把 wheel 放进 public/python-wheels/，按 URL 安装
await s.installWheels(['python-wheels/numpy-2.4.6-cp314-cp314-pyodide_2026_0_wasm32.whl']);
```

自定义源（PEP 503 simple index，需要 CORS）：

```ts
const s = await Python.createSession({ pipIndexUrls: ['https://mirror.example/simple'] });
await s.installPackages(['six'], { indexUrls: ['https://mirror.example/simple', 'https://pypi.org/simple'] });
```

---

## 代码编辑器（框架无关）

```ts
import {
  initEditor, destroyEditor, getEditorValue, setEditorValue,
  insertEditorText, focusEditor, setEditorReadOnly, setEditorTheme,
  onEditorChange, undoEditor, redoEditor, destroyAllEditors, listEditors,
} from 'capacitor-python/editor';

const handle = initEditor('#editor', {
  value: 'print("hi")',
  theme: 'dark',          // 'light' | 'dark'
  lineNumbers: true,
  tabSize: 4,
  height: '320px',
  placeholder: '输入 Python 代码…',
  onRun: (code) => run(code),   // Ctrl/Cmd+Enter
  onChange: (code) => {},
});

handle.setValue('x = 1');
handle.insert('# 光标处插入');
handle.focus();
handle.setReadOnly(true);
handle.setTheme('light');
handle.destroy();
```

| `initEditor` 选项 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `value` | `string` | `''` | 初始代码 |
| `theme` | `'light' \| 'dark'` | `'light'` | 主题 |
| `lineNumbers` | `boolean` | `true` | 行号 |
| `tabSize` | `number` | `4` | Tab 宽度 |
| `height` | `string` | `'320px'` | 编辑器高度（CSS） |
| `placeholder` | `string` | — | 占位文案 |
| `readOnly` | `boolean` | `false` | 只读 |
| `onRun` | `(code) => void` | — | `Mod-Enter` 回调 |
| `onChange` | `(code) => void` | — | 内容变化 |

返回的 `EditorHandle`：`selector`、`getValue()`、`setValue()`、`insert()`、`focus()`、
`setReadOnly()`、`setTheme()`、`destroy()`。

**真机实验台**：测试页的 "Python 实验台" 卡片就是完整示例（编辑器 + 运行/停止/重启 + stdin 输入框 +
流式输出）。实测 Android 真机：运行后实时出现 `你叫什么？ ` → 输入 `Bob` → 输出
`0 hello, Bob …` → `[结果] 3`。

---

## 维护与升级 Python 版本

### 插件结构（改哪里）

| 文件 | 作用 |
| --- | --- |
| `src/index.ts` | 门面：按平台分流到原生/Pyodide；会话句柄 |
| `src/engine/runtime.ts` | Web 引擎（Pyodide）：Worker、会话、stdin 队列 |
| `src/definitions.ts` | 全部公开类型（API 参数/返回值） |
| `android/.../PythonEngine.java` / `PythonSession.java` / `PythonBridge.java` | Android 原生引擎（Chaquopy） |
| `android/src/main/python/capython/runtime.py` | Android 侧运行时（stdout/stderr 代理、阻塞 stdin、exec） |
| `ios/Sources/PythonPlugin/PythonEngineIOS.swift` | iOS 原生引擎（Py_* C API） |
| `ios/Sources/PythonShim/PythonShim.c` | C 包装层 + `capython_bridge` 模块 |
| `ios/Sources/PythonPlugin/python/capython/runtime.py` | iOS 侧运行时（与 Android 逻辑一致，桥不同） |
| `scripts/fetch-python-ios-runtime.mjs` | 下载/裁剪 iOS 运行时 + 装 `ios/requirements.txt` |
| `scripts/fetch-python-runtime.mjs` | 下载 Pyodide（Web） |

### 升级 Android 的 Python

1. `android/build.gradle`：`chaquopy { defaultConfig { version = '3.12' } }`
   （3.12+ 只支持 64 位 ABI，若还要 32 位设备就留在 3.11）；
2. 有构建期 pip 的，把 `buildPython` 换成对应小版本；
3. `gradlew :app:assembleDebug` + 真机跑测试页 autotest。

### 升级 iOS 的 Python

1. 在 `scripts/fetch-python-ios-runtime.mjs` 的 `RELEASES` 表里加新条目
   （`url` / `sha256` / `series` / `python`），改成默认版本；
2. `ios/requirements.txt` 里的包版本可能也要跟着换；
3. 重跑脚本（会重新生成 `python/lib/python3.X` 与 `PythonRuntime.json`）；
4. Swift **不用改**：`PythonEngineIOS` 从 `PythonRuntime.json` 读 `series` 来拼 stdlib 路径；
5. CI 跑一次 autotest（iOS 模拟器会真实执行 `print`/`1+1`/`import six`）。

### 升级 Web 的 Pyodide

```bash
node plugins/capacitor-python/scripts/fetch-python-runtime.mjs --version 315.0.0
# 然后改 src/engine/runtime.ts 的 DEFAULT_PYODIDE_VERSION，或调用 initialize({ version })
```

### 升级 / 改动后的检查清单

```bash
cd plugins/capacitor-python && npm run build
npm run audit:plugins           # 方法数 TS/Java/Swift 一致性
npm run verify:ios              # Swift 导出 vs TS 声明
# Android：gradlew :app:assembleDebug + 真机 autotest
# iOS：push 让 CI 跑（模拟器 autotest 会真实执行 Python）
```

### 回滚

- Android：把 `version` 改回去重新构建；
- iOS：`RELEASES` 表里旧条目还在，改回去重跑脚本（stdlib 会重新生成）；
- Web：旧版本目录可以并存（`public/python-runtime/<old>/`），`initialize({version})` 指定即可。

### 已知的版本坑

- Chaquopy 3.12+ 无 32 位 ABI；
- iOS 的 Python-Apple-support 分支是 `3.11-b13`/`3.13-b18`/`3.14-b14` 这种 tag，**不是** PyPI 版本号；
- Pyodide 的版本号规则是 `314.x` = CPython 3.14，改版本时注意对应关系。

---

## 已知限制

### Android（原生引擎）

1. **没有进程级隔离**：段错误 / `os._exit()` 会带走 App；普通异常/死循环不影响其它会话；
2. **中断是协作式的**：`exec(..., timeoutMs)` 超时只清空会话，线程要下一条 bytecode 才退出；
3. **C 扩展只能构建期装**（`pip { install }`），运行时只能纯 Python wheel；
4. **APK +19MB（三个 ABI）**：正式发布用 ABI split / App Bundle；
5. **`sys.stdout` 代理按线程转发**：用户代码自己开线程 `print()` 时没有会话关联，输出会被丢弃。

### iOS（原生引擎）

6. **没有 `lib-dynload`**：`zlib`/`ssl`/`sqlite3`/`_ctypes` 等 C 扩展缺失（需宿主 Run Script，见上）；
7. **运行时装 wheel 不可用**：`installPackages`/`installWheels` 抛 `IOS_EXTENSIONS_UNAVAILABLE`（缺 zlib）；
8. **中断**：同 Android，只能丢弃会话；
9. **体积**：stdlib ~14MB + 静态 libPython ~6MB。

### Web（Pyodide 引擎）

10. **浏览器版本**：Chrome ≥ 90 / iOS Safari ≥ 16.4；
11. **stdin 队列模式**；没有真线程/`subprocess`/`signal`；`ssl` 是桩；
12. **首次加载慢**：9.2MB wasm 编译，5~10s。

---

## 与其他插件一起使用

- **capacitor-background-keepalive**：长任务在后台会被系统挂起（原生引擎也受 App 生命周期影响）；
  需要继续跑只能靠前台服务 —— 不要依赖后台跑 Python。
- **capacitor-native-files**：可以用它把用户的 `.py` 读进来再 `exec`；也可以把 wheel 放到用户目录再
  `installWheels`（需要 URL，可配合本地 HTTP 服务）。
- **capacitor-secure-storage**：不要把 API key 写死在 Python 代码里；用 secure-storage 取出后通过
  `bootstrap` 注入（如 `os.environ`）。
- **capacitor-live-update**：OTA 只换 Web 资源，**不会**换原生 Python 运行时；新 Web 包要兼容旧运行时。
- **capacitor-permissions**：pip 联网需要 INTERNET（Capacitor 默认已有）；无额外权限。

---

## iOS 原生引擎（实现细节）

Chaquopy 没有 iOS 版本，iOS 用 BeeWare [Python-Apple-support](https://github.com/beeware/Python-Apple-support)：

1. `scripts/fetch-python-ios-runtime.mjs` 下载 `Python-3.11-iOS-support.b13.tar.gz`（30.7MB，校验 sha256），
   解出 xcframework，裁剪 stdlib（46MB → ~14MB），**合并所有 slice 的 Headers**
   （`pyconfig-<arch>.h` 分散在不同 slice）；
2. `Package.swift`：`binaryTarget(name: "Python", path: "ios/Python.xcframework")` +
   `.copy("python")`（stdlib 作为 SPM 资源）+ C target `PythonShim`；
3. C shim 把 `Py_Initialize`/`PyRun_String` 包成稳定 API，并注册 `capython_bridge` 模块
   （`emit`/`readline`）；**不用 ctypes**（它也是 C 扩展）；
4. 回调里用 `Py_BEGIN_ALLOW_THREADS` 释放 GIL（否则阻塞 stdin 会饿死其它会话）；
5. 输出经 delegate → `DispatchQueue.main.async` → `notifyListeners`。

---

## 实测记录（2026-11-27）

| 环境 | 结果 |
| --- | --- |
| **Android 10 真机（WebView 87）** | ✅ 原生 CPython 3.11.17：`exec('1+2')=3`、实时输出、`ValueError` 正常、异常后会话可继续、多会话隔离、**阻塞 stdin**、构建期 `requests 2.34.2`、运行时 `six 1.17.0`、编辑器实验台全流程；autotest `ok:true` |
| Android 16 模拟器 | ✅ 同一引擎（x86_64 ABI） |
| **iOS 模拟器（GitHub Actions）** | ✅ 原生 CPython 3.11.18：`initialize/createSession/exec`、stdout 事件、编辑器、`import six`（构建期嵌入）；autotest `ok:true` |
| 多会话并发 | ✅ 两个 `sleep(1)` 并发总耗时 ~1010ms |

---

## 路线图

- [ ] Android 进程级隔离（`:python` 服务 + AIDL）
- [ ] iOS 的 `lib-dynload` 自动转换（插件提供 Run Script 片段 + 一个自写的 C 扩展 CI 验证）
- [ ] Web 的真阻塞 stdin（本地 HTTP 服务 + Worker 同步 XHR）
- [ ] 会话快照/恢复（MEMFS/IDBFS）
- [ ] 编辑器补全升级（基于包 API 的提示）

---

## 参考

- Python 官方：<https://docs.python.org/3/using/android.html>、<https://docs.python.org/3/using/ios.html>
- Chaquopy：<https://chaquo.com/chaquopy/doc/current/android.html>
- Python-Apple-support：<https://github.com/beeware/Python-Apple-support/releases>
- Pyodide：<https://pyodide.org/en/stable/usage/webworker.html>
- CodeMirror：<https://codemirror.net/docs/guide/>
