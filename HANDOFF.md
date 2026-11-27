# 项目上下文存档（HANDOFF）

> ## 🆕 2026-11-27（深夜 3）测试视角代码审查：10 个问题 + 修复
>
> 用户要求"从测试角度查 bug/隐患并修"。这一轮实际发现并修掉的：
>
> | # | 问题 | 后果 | 修复 |
> | --- | --- | --- | --- |
> | 1 | **同一会话并发 `exec`**：门面用 `current`（按 sessionId）聚合输出 | 两个并发 exec 的 stdout 会**串台**（后一个的输出去前一个的结果里） | 门面加 `execChains`：**同一会话串行**，不同会话并行；实测同一会话两个 exec 总耗时 627ms（串行）、各自 stdout 干净 |
> | 2 | 原生 `run()` 的 `executionId` 是**门面自己编的**，事件里是原生编的 | 调用方拿 `{executionId}` 和 `sessionDone` 事件**对不上** | 门面把 executionId 传给原生（TS 定义 + Java + Swift 都用传入值）；实测 `matched: true` |
> | 3 | Android `listSessions()/getInfo` 每次 `callAttr("python_version")` | 解释器在跑用户代码（持 GIL）时，**Capacitor 插件线程会被堵住** → 所有插件调用卡死 | 启动时缓存 `cachedVersion`，之后只读字段 |
> | 4 | Android `terminateSession/terminateAll` 在插件线程上 `callAttr("reset_session")` | 同上，可能等 GIL | 移到 `PythonEngine.EXECUTOR` 上执行后再 resolve |
> | 5 | Android wheel 下载/解压**无体积上限** | 恶意/异常服务器可写满磁盘；zip bomb | 下载 64MB、解压总量 256MB 上限 |
> | 6 | iOS `pushStdin` 队列**无上限** | 脚本不消费输入时内存无限增长 | 上限 1000 行（丢最旧的） |
> | 7 | 门面 `attachNativeEvents()` 注册失败后 `nativeAttached` 仍为 true | 之后**永远收不到事件** | 注册失败时重置标志，下次调用可重试 |
> | 8 | `terminateAll()` 不清 `handles`/`execChains` | 句柄引用泄漏、旧 chain 残留 | 一并清空 |
> | 9 | `installPackages(sessionId, [])` 空数组直接打到原生 | 错误信息含糊 | 门面抛 `PACKAGES_EMPTY` |
> | 10 | `handle.restart()` 通过 `as unknown as {handles}` 强转私有字段 | 易碎 | 门面加 `adoptHandle()` |
>
> 另外补了文档：**`installPackages` vs `installWheels` 的区别表**（语义/平台/何时用）。
>
> 验证：Android 真机 autotest `ok:true`；探针结果 `serialized.elapsedMs=627`（不串台）、
> `runCorrelation.matched=true`、跨会话并发 507ms、`terminateAll` 后 0 个会话。

> ## 🆕 2026-11-27（深夜 2）autotest 与实验台的隔离 + 权限残留
>
> 用户在真机上玩实验台后发现"残留输入输出"，并且 autotest 变成 `ok:false`。
> 排查结论与修复：
>
> 1. **`ok:false` 的真正原因**：之前调试时 `pm clear com.myapp.testionic` 把
>    `MANAGE_EXTERNAL_STORAGE`（native-files 的"所有文件访问"）也清掉了 →
>    native-files 往 `/storage/emulated/0/Documents` 写文件报 `EACCES` → 该用例判失败。
>    → 改成：`EACCES / Permission denied / MANAGE_EXTERNAL_STORAGE` 时记
>    `results.fileUrl = 'skipped: 需要 MANAGE_EXTERNAL_STORAGE'`，不判失败。
> 2. **编辑器被互相覆盖**：autotest 原来复用 `#python-editor`，会把用户实验台里的代码
>    替换成 `print("hi")` 并在结束时 destroy 掉。
>    → 新增隐藏元素 `#python-editor-autotest`，autotest 与"编辑器自检"按钮都只操作它。
>    另外原来 `getEditorValue/destroyEditor` 的替换**替换到了错误的位置**（第一次出现的
>    `pythonEditorTest` 里），导致 autotest 读到的是实验台内容 → `python 校验失败`；
>    已修正为两边都用隐藏编辑器。
> 3. **残留会话**：实验台离开页面时没终止 Python 会话。
>    → `beforeDestroy` 里 `void this.pySession.terminate()` + `dispose()`。
> 4. **autotest 干净起点**：开头清理 `Python.terminateAll()` / `BackgroundKeepalive.stop()` /
>    `SecureStorage.clear()`（都 try/catch，不影响断言）。
> 5. 实验台加"清空输出"按钮（`pyClear`）。
>
> 验证（Android 真机）：autotest `ok:true`（`fileUrl: skipped`、python 段 `editor.after = "x = 1"`），
> 之后实验台仍能完整跑 `input()` → 输入 Bob → `0 hello, Bob…` → `[结果] 3` → 停止。
> 教训：**测试页要假设"上一次运行/手工操作会留下状态"**，要么清理，要么让断言对状态容错。

> ## 🆕 2026-11-27（深夜）`capacitor-python`：会话句柄 API + 真机实验台 + README 重写
>
> 用户验收时提了 6 点：① 解释文档里"崩溃"的语义；② 实时 stdio 是否做到；③ 多会话并发；
> ④ pip 包（尤其 iOS 构建期嵌入 C 扩展）；⑤ 编辑器；⑥ 版本升级。并要求：
> 把回答补进 README、详细写 API 字段、**重设 addListener 为会话句柄**、补三平台 pip 示例、
> 写维护/升级章节、**真机上做一个能编辑+运行+流式 stdin/stdout 的编辑器**。
>
> ### 代码改动
>
> - **`Python.createSession()` 返回 `PythonSessionHandle`**（`src/index.ts` 的
>   `PythonSessionHandleImpl` + `definitions.ts` 的接口）：
>   `onStdout/onStderr/onOutput/onState`、`writeStdin`、`exec/run`、`installPackages/installWheels`、
>   `terminate/restart/dispose`；同时保留 `sessionId/name/state/stdinMode/pythonVersion` 字段，
>   旧的 `Python.exec(sessionId, ...)` 用法仍然可用。
> - 测试页新增 **Python 实验台**：CodeMirror 编辑器 + 运行/停止/重启 + stdin 输入框 + 流式输出 `<pre>`；
>   `Mod-Enter` 运行；`beforeDestroy` 清理监听与编辑器。
> - 真机实测（Android 10）：运行示例脚本 → 实时出现 `你叫什么？ ` → 输入 `Bob` → `0 hello, Bob…`
>   → `[结果] 3` → 停止后状态变"已停止"。
>
> ### README 重写（按要求）
>
> 新增/重写了：**能力边界（六个问题逐条回答）**、**API 详解（每个方法的入参字段 + 返回字段表）**、
> **PythonSessionHandle 全字段**、**stdin 两种模式**、**多会话/并发/状态（附 sleep 1010ms 实测）**、
> **pip 三个平台 ×（普通包/C 扩展包）完整示例**、**维护与升级 Python 版本（文件地图/步骤/回滚/坑）**、
> **iOS C 扩展（numpy）接入指南**（Briefcase/cibuildwheel 拿 iOS wheel + Run Script + 签名说明）。
>
> 关键结论（写进 README 了）：
> - "崩溃"要分两种：**Python 未捕获异常 → 隔离**；**段错误/原生崩溃 → App 退出**（同进程，没法隔离）；
> - iOS C 扩展**签名不是门槛**（普通开发者账号即可，`install_python` 用 `$EXPANDED_CODE_SIGN_IDENTITY`），
>   **拿 iOS arm64 wheel 才是门槛**（PyPI 一般没有，要 Briefcase/cibuildwheel 交叉编译）；
> - **iOS 构建期嵌入纯 Python 包已支持**（`ios/requirements.txt` + pip --target + 拒绝 .so），
>   CI autotest 断言 `six 1.17.0`；
> - Android 构建期 `pip { install 'requests' }` 已验证（清数据后仍可 import），运行时装纯 Python wheel 也验证了。
>
> ### 踩坑
>
> - 会话句柄的 `terminate()` 最初没翻本地状态 → 调用方读到的 `state` 停在 `idle`；现在先置
>   `terminated` 并通知监听者，再销毁。
> - PowerShell 写 README 时 `\n` 变成字面量；改用 Node 脚本修。

> ## 🆕 2026-11-27（晚）`capacitor-python` iOS 也走原生 CPython（Python-Apple-support）
>
> 用户要求"iOS 也用原生，统一环境"。**Chaquopy 没有 iOS 版本**（Gradle/JVM 专用），
> 所以 iOS 换成 BeeWare `Python-Apple-support`（静态 libPython 3.11.18 + stdlib），
> 两端仍然统一在 **CPython 3.11 + 同一份 `capython/runtime.py` + 同一套插件 API**。
>
> ### 实现（不需要宿主改 Xcode 工程）
>
> - `scripts/fetch-python-ios-runtime.mjs`：下载 `Python-3.11-iOS-support.b13.tar.gz`
>   （30.7MB，sha256 `e1f55eef…`）→ 解出 `Python.xcframework` → 裁剪 stdlib（46MB→14MB）→
>   **合并所有 slice 的 Headers**（`pyconfig-<arch>.h` 分散在不同 slice，不合并编不过）→
>   头文件放到 `PythonShim/python-headers`（不能放 `include/`，SPM 不允许 umbrella header 与子目录混放）。
> - `Package.swift`：`binaryTarget(path: "ios/Python.xcframework")` + stdlib `.copy("python")` 资源
>   + C target `PythonShim`（binaryTarget 不给 Swift 生成 module map）+ linkerSettings(z/bz2/lzma)。
> - `PythonShim.c`：`Py_Initialize` + `PyRun_String` + **C 注册的 `capython_bridge` 模块**
>   （`emit`/`readline`）。不用 ctypes：它也是 C 扩展，纯 stdlib 阶段没有。
>   `Py_BEGIN_ALLOW_THREADS` 包住回调（否则 stdin 阻塞会饿死其它会话的 GIL）。
> - `PythonEngineIOS.swift`：一个解释器 + 每会话独立 globals；输出经 delegate → `sessionOutput`；
>   stdin 用 NSCondition 阻塞 30s。
> - CI 加了 "Fetch Python iOS runtime" 步骤（失败时把原因抛成 annotation 才看得到日志）。
>
> ### 验证
>
> - CI（macOS，iOS 模拟器）autotest 在 iOS 上**强制 engine=native** 跑通：
>   `initialize`（3.11.18）、`createSession`、`exec` 返回 `3`、stdout 事件、编辑器全过；CI 绿。
> - Android 真机回归：仍然 `ok:true`（native 3.11.17、阻塞 stdin）。
> - 因此默认引擎已切成 **Android/iOS 都原生**，`initialize({ engine: 'pyodide' })` 可显式退回 WASM。
>
> ### iOS 当前边界
>
> - 没有 `lib-dynload`（zlib/ssl/sqlite3/_ctypes 等 C 扩展）→ `installPackages`/`installWheels`
>   抛 `IOS_EXTENSIONS_UNAVAILABLE`；要补齐需要宿主 App 加 Run Script
>   （`install_python` 把 .so 转 framework 并签名 + `ENABLE_USER_SCRIPT_SANDBOXING=NO`）。
> - 体积：stdlib ~14MB + 静态 libPython ~6MB。
>
> ### 踩坑清单（iOS）
>
> 1. SPM：`include/` 里同时有 umbrella header 和子目录 → "invalid header layout"；
> 2. `pyconfig.h` 需要 `pyconfig-<arch>.h`，它们在**不同 slice** 的 Headers 里，要合并；
> 3. CAPPlugin 没有 `handleOnDestroy()`（那是 Android），Swift 里 `override` 会编译失败；
> 4. ctypes 在 iOS 纯 stdlib 方案里不可用 → 改成 C 注册模块；
> 5. 回调必须释放 GIL，否则 stdin 阻塞时其它会话卡死；
> 6. `notifyListeners` 从 Python 线程调用要回主线程（`DispatchQueue.main.async`）。

> ## 🆕 2026-11-27 `capacitor-python` 重构：Android 改成**原生 CPython**（Chaquopy）
>
> 用户明确否掉了"WebView 里跑 WASM"的初版：① 他的手机 WebView 是 Chrome/87，跑不了；
> ② WASM 局限在浏览器沙盒，不是他要的"真机 Python"。要求换成原生，最低支持 Android 7 / iOS 15。
>
> ### 结论（已实现并真机验证）
>
> **Android = Chaquopy 17.2 + CPython 3.11（原生，不依赖 WebView）；iOS/Web = Pyodide（WASM）。**
>
> - Chaquopy **支持装在插件的 `com.android.library` 模块里**（官方文档明确），宿主 app 零配置；
>   唯一约束：一个 App 只能有一个模块应用它（重复会 "More than one file found with OS independent path"）。
> - **Python 3.11 而不是 3.13**：Chaquopy 从 3.12 起只支持 64 位 ABI，而 Android 7 时代有大量
>   armeabi-v7a 设备；3.11 同时覆盖 arm64/arm32/x86_64。
> - AAR 23MB，APK 58MB（三个 ABI，Python 部分约 +19MB）→ 正式发布要用 ABI split / App Bundle。
> - **Chaquopy 没有 iOS 版本**（官方 FAQ 明确）。iOS 原生等价物是 BeeWare `Python-Apple-support`
>   （CPython 3.11 静态库 + stdlib + 67 个 `.so`），但必须由**宿主 App** 在 Xcode 里加 Run Script
>   把 `.so` 转成 framework 并 codesign（SPM 插件做不到），且体积 +55MB → 分步实现，目前 iOS 走 Pyodide。
>
> ### 代码结构
>
> - `src/index.ts` = **门面**：`Capacitor.getPlatform() === 'android'` → 原生插件；否则 → `PyodideRuntime`。
>   事件在门面里统一成 `sessionOutput/sessionState/sessionDone/sessionError`。
> - `android/src/main/python/capython/runtime.py`：`sys.stdout/stderr` 线程级代理（按 `threading.local`
>   的 session_id 分流）、`sys.stdin` 阻塞 `readline`（`PythonBridge.readLine` 轮询队列）、
>   `exec` + 最后一个表达式 `eval`（返回 JSON）、`add_site_dir`、`interrupt`（ctypes）。
> - Java：`PythonBridge`（emit/readLine/队列）、`PythonEngine`（Chaquopy 启动、会话、wheel 安装）、
>   `PythonSession`、`PythonPlugin`（11 个 @PluginMethod + 事件）。
> - 门面 `exec` 用原生 resolve 收尾（Java 的 `exec` 直接 resolve，不像 `run` 那样发 sessionDone 事件）。
>
> ### 真机实测（Android 10 / WebView 87，正是 Pyodide 跑不动的环境）
>
> - `initialize` → `pythonVersion 3.11.17`、`engine native`；
> - `createSession` → `stdinMode: blocking`；
> - `exec('print(...); 1+2')` → `result: "3"`，stdout 实时事件 `hello native\n3.11.17\n`；
> - `raise ValueError` → 正常 reject，会话仍可继续；两个会话 `x=42` 互不可见（NameError）；
> - **阻塞 stdin**：`input("Name? ")` 实时输出提示符 → 800ms 后 `writeStdin('Alice')` → `hello, Alice`；
> - 完整 autotest `ok:true`（python 段 engine=native / 3.11.17 / result=3 / 编辑器全过）。
>
> ### 踩坑
>
> 1. Chaquopy 的 `PyObject.toJava(Map.class)` **不支持 dict → Map**（TypeError）→ Python 侧返回 JSON 字符串。
> 2. `new org.json.JSONObject(json)` 抛受检 `JSONException`，必须 try/catch。
> 3. Chaquopy 在 library 模块里**必须**声明 `ndk.abiFilters`。
> 4. `PluginCall.reject(String, Throwable)` 不存在 → catch 用 `Exception`。
> 5. 门面最初在原生 `exec` 上等一个不会来的事件 → 120s 超时；改成用 promise 结果收尾。
> 6. 仍要避免 PowerShell 的 `Get-Content/Set-Content` 改 UTF-8 源码（会写坏）。
>
> ### 下一步（iOS 原生）
>
> 方案已写进插件 README「iOS 原生引擎」：下载 `Python-3.11-iOS-support.b13.tar.gz`
> （30.7MB，sha256 `e1f55eef…`）→ `binaryTarget(path:)` + stdlib 资源 → **宿主 pbxproj 加 Run Script**
> （`install_python` + `ENABLE_USER_SCRIPT_SANDBOXING=NO`）→ Swift `PyConfig`/`PyRun_StringFlags` +
> ctypes 回调实现实时 stdout/stdin。Swift 侧的 11 个插件方法已经就位（当前返回
> `NATIVE_ENGINE_NOT_AVAILABLE`），第二步只需替换实现。

> ## 🆕 2026-11-26 新插件：`capacitor-python`（第 19 个插件）
>
> 需求：App 内集成 Python 解释器 —— 崩溃不影响主程序、实时 stdin/stdout/stderr、多份代码并发、
> pip 包、框架无关的代码编辑器、可升级 Python 版本，平台 Android + iOS。
>
> ### 结论：Pyodide（CPython 3.14 → WebAssembly）+ 每会话一个 Web Worker
>
> 调研/取舍全文见 **`docs/python-plugin-research.md`**。核心理由：**需求 1 在 iOS 上只有 Worker 能做到**
> （Apple 禁止 fork/exec/子进程，原生嵌入 CPython 一旦段错误就是整个 App 崩，CPython 官方文档明确写了
> "An iOS app cannot use any form of subprocessing…"）。Android 原生（Chaquopy 17.2，MIT）可行但要做
> `:python` 进程 + AIDL，约 3~5 周；iOS 原生（Python-Apple-support xcframework）也要 3~5 周且无隔离。
>
> ### 实现
>
> - 插件目录：`plugins/capacitor-python/`（TS 引擎 + Android/iOS 原生壳 + `runtime/worker.mjs` + 编辑器）
> - 运行时：`scripts/fetch-python-runtime.mjs` 下载 Pyodide 到 `public/python-runtime/<version>/`
>   （**13MB，不入库**，`.gitignore` 已加；iOS workflow 里新增了 fetch 步骤）
> - 会话：`Python.createSession()` → 独立 Worker；`exec/run/writeStdin/installPackages/installWheels/
>   restartSession/terminateSession`；事件 `sessionOutput/sessionState/sessionDone/sessionError`
> - stdout/stderr：Pyodide **Writer** 接口（`write(buffer)`）逐字节回传（用 batched 会吞掉
>   `print(end="")` 和 `input()` 的提示符）
> - 编辑器：CodeMirror 6（`capacitor-python/editor`，esbuild 打成 440KB / ~140KB gzip），
>   `initEditor/destroyEditor/getEditorValue/setEditorValue/insertEditorText/focusEditor/
>   setEditorReadOnly/setEditorTheme/onEditorChange/undo/redo`，Python 高亮 + 自动缩进 + 内置函数补全
> - pip：micropip；**核心 wasm 走本地**，包走 Pyodide CDN（`loadPyodide({ packageBaseUrl })`）；
>   `installPackages(pkgs, { indexUrls })` / `createSession({ pipIndexUrls })` 可指向自建 simple index（需 CORS）
>
> ### 踩过的坑（都已修）
>
> 1. **worker 的 `emit('fatal', {...describePythonError(err)})` 把事件 `type` 覆盖成 `'Error'`** →
>    主线程永远收不到 fatal/error，`createSession` 与 Python 异常都会**永久挂起**。改成 `errorType` 字段。
> 2. `createSession` 在 worker 加载失败时不 reject → 加 `createReject` + 150s 加载超时 + 把
>    `Unexpected token` 映射成 `UNSUPPORTED_WEBVIEW`。
> 3. Android 10 真机 WebView 是 **Chrome/87**，Pyodide 314 要求 ≥90 → 真机按预期报
>    `UNSUPPORTED_WEBVIEW`（autotest 里用 `checkPythonSupport()` 跳过而不是判失败）。
> 4. `pip install` 一开始报 `No module named 'micropip'`：micropip 不在 core 里，要 `loadPackage('micropip')`；
>    且**包的 CDN 基址**要用 `packageBaseUrl` 指过去，否则会去本地 404。
> 5. audit/verify 脚本原来取 `definitions.ts` 的**第一个** interface → 对多接口插件数错方法数；
>    已改成优先匹配 `*Plugin`。
> 6. PowerShell 的 `Get-Content -Raw` + `Set-Content` 会把 UTF-8 文件写成乱码（模板字符串反引号被替换），
>    以后改这些文件一律用 write/edit 工具。
>
> ### 验证
>
> - **Android 16 模拟器**：autotest `ok:true`，python 段 `pyodide 314.0.8 / CPython 3.14.2`、
>   `exec('1+2')=3`、stdout `python-autotest\n3.14.2\n`、编辑器 init/get/set/destroy 全通过；
>   另外单独验证了：多会话隔离（NameError）、死循环 `exec(..., 3000)` 只杀该会话、`pip install six` 成功、
>   `raise ValueError` 正常 reject 且会话可继续用。
> - **Android 10 真机（WebView 87）**：`UNSUPPORTED_WEBVIEW` + 明确升级提示，不崩溃不挂起。
> - **iOS**：等 GitHub Actions 模拟器结果（Pyodide 在 WKWebView 的 module worker 里跑）。
>
> ### 已知限制（README 有完整列表）
>
> stdin 默认 `queued`（Capacitor 拿不到 SharedArrayBuffer，没有真阻塞的 `input()`）；
> 不能装任意 C 扩展；`ssl` 是桩；WebView 版本要求；内存 50MB/会话；后台会被挂起。

> **用途**：这份文件保存了截至 2026-11-24 本对话中掌握的所有关键信息——本机环境、项目结构、
> 已完成的 18 个自定义 Capacitor 插件、插件开发手册、验证体系、踩过的坑，以及 2026-11-21~22
> 关于 Capacitor 官方/内置能力的调研结论。
> **用法**：开新对话后，第一句让我「读一下根目录的 HANDOFF.md」，即可快速重建上下文继续工作。

> ## 🆕 2026-11-25 21:20 CI 假失败复盘（runner 容量，不是代码）
>
> 昨晚（11-24 23:46）`5f04656` 的 iOS run **45429422859** 失败，原因**与代码无关**：
> `build-ios` job 的 **`steps count = 0`**，注解是
> `The job was not acquired by Runner of type hosted even after multiple attempts`，
> 15 分钟后 `conclusion=cancelled` —— macOS runner 池子没抢到。
> （当晚 `8a29e6f` 的 run 45426100152 还是绿的，说明代码没问题。）
>
> **处理方式**（无 GitHub token 时）：`rerun` API 需要认证（实测 401），所以用
> `git commit --allow-empty -m "ci: 重新触发..."` + push 重新排队。
> 也可以让人在网页点 "Re-run all jobs"。
>
> 重跑结果：run **45620445957 全绿**（Build local plugins / Build app / 模拟器 autotest 全 success），
> 同时验证了**清理后的插件目录在 CI 的 `npm ci` 路径下能正常构建**（这正是清理轮担心的点）。
>
> 经验：遇到 "job was not acquired" 这类失败，**先看 steps 是不是 0**，
> 是 0 就是调度问题，不要动代码；换时间点重跑即可。
> 另外 CI 里 `Build local plugins` 会对每个插件跑 `npm ci && npm run build`，
> 所以**插件目录不要提交 `node_modules`/`dist`**，但 `package-lock.json` 必须保留。

> ## 🆕 2026-11-25 00:30 Android 16 模拟器验证轮（新系统兼容性 + 概览文档）
>
> 在 **Pixel_6 / Android 16（API 36）** 模拟器上完整跑了一遍四个插件，并新增 4 份 `INTRO.md`。
>
> ### 新系统上发现并修掉的 bug（1 个）
>
> - **`isBiometryAvailable()` / `getInfo().biometry.enrolled` 在 API 36 上误报 `enrolled:true`**：
>   模拟器没有录入指纹时 `canAuthenticate()` 返回 `NONE_ENROLLED(11)`，旧判断
>   `status != NO_HARDWARE && status != HW_UNAVAILABLE` 得到 true → 出现
>   **"available:false 但 enrolled:true"** 的矛盾状态（UI 会误以为用户只是暂时不可用）。
>   iOS 侧同样问题：`authError == biometryNotAvailable`（无硬件/模拟器没开 Face ID）也被算作已录入。
>   修：Android 只在 `OK / HW_UNAVAILABLE / SECURITY_UPDATE_REQUIRED` 时为 true；
>   iOS 把 `biometryNotEnrolled` **和** `biometryNotAvailable` 都判 false。两端 `getInfo` 同步修。
>   实测（API 36）：`{ available:false, type:'none', enrolled:false, reason:'NONE_ENROLLED' }` ✅
>
> ### API 36 上的实际行为（都已实测）
>
> - **autotest `ok:true`**；`npm run verify:android` 双设备（真机 + 模拟器）**PASS**。
> - **permissions**：`storage` → `unsupported`（33+ 设计如此）；`photos`/`notifications`/`location` → `prompt`；
>   `getSupported().supported` 为 11（storage 被排除）。
>   用 `pm grant` 只给 `ACCESS_COARSE_LOCATION` 后：`location → limited`、`locationCoarse → granted` ✅
> - **keepalive**：Android 16 上 `specialUse` 前台服务正常；**授予 `POST_NOTIFICATIONS` 后
>   `notificationVisible:true`**（未授予时 false，符合文档）；起停正常。
> - **live-update**：端到端跑通 —— 安装 51 文件 / 1.19MB → reload 到 `9.9.9`（`pending:true`）→
>   `notifyAppReady` → **冷启动仍是 9.9.9** → `rollback()` 回 `1.0`。
> - **secure-storage**：AES-256-GCM 正常；`requireBiometric` 在未录入设备上被 `BIOMETRY_NOT_ENROLLED` 拒绝。
>
> ### 额外验证：START_STICKY 恢复（`adb root` + `kill -9`）
>
> 模拟器可 `adb root`，于是直接 `kill -9` 掉 App 进程来验证上一轮的修复：
> `dumpsys activity services` 里 `KeepaliveService` **被系统重建**（`startRequested=true`、
> `isForeground=true`、`types=0x40000000`），通知标题 `sticky-test` 从持久化配置**恢复成功**
> （修复前这里会 `stopSelf()`，服务/通知都不会回来）。**这是本轮最有价值的一次回归验证。**
>
> ### 模拟器使用的坑（写给自己）
>
> - 软件渲染（`-gpu swiftshader_indirect`）下 **`com.android.systemui` 频繁 ANR**；
>   `uiautomator dump` / 点击系统权限弹窗**不可靠** → 权限相关验证优先用
>   `adb shell pm grant/revoke`，UI 弹窗验证放真机做。
> - ANR 卡住时：`adb shell input keyevent 4` + `adb shell am force-stop com.android.systemui`
>   （它会自己重启），必要时 force-stop App 重新拉起。
> - autotest 构建启动后会自己跑一遍并用 `stop()` 结束 keepalive，**别和 autotest 抢时序**
>   （本轮就出现过"刚 start 就被 autotest 的 stop 关掉"的假失败）。
>
> ### 文档
>
> - 四份 README 各加了「与其他插件一起使用（注意事项）」表（上一轮）；
>   本轮再补两条**只能文档化**的限制：`secure-storage` 的 iOS「先删后加」可能丢旧值、
>   `keepalive` 的「重建时机由系统决定 / iOS 进程被终止时事件不会补发」。
> - 新增 **4 份 `INTRO.md`**（各插件概览：定位、平台差异、核心 API、典型场景、坑、实测），
>   可直接拿去当插件介绍用。
> - 版本：secure-storage **0.2.1**（enrolled 修复）。

> ## 🆕 2026-11-24 23:20 测试视角自查轮（13 个 bug 修复 + 跨插件注意事项）
>
> 用户要求"以测试视角重读四个插件、找 bug 并修，同时考虑与其它插件共用的问题"。
> 下面按插件列出**本轮实际改掉的 bug**（都已编译/真机验证，见末尾）：
>
> ### background-keepalive
> 1. **Android 粘性服务重启即自杀**：`START_STICKY` 重建时 `intent == null`，旧代码把它等同于
>    `ACTION_STOP` → `stopSelf()`，所谓"常驻"名存实亡。修：把启动配置（文案/渠道/fgsType/
>    keepScreenOn）持久化到 `capacitor_keepalive_service` prefs；`intent == null` 时恢复并重新
>    `startForeground`；只有用户 `stop()`（ACTION_STOP）或 `dataSync` 超时才清配置。
> 2. **`updateNotification()` 会悄悄重置服务配置**：旧实现用 update intent 重建整个 Config，
>    没带 `keepScreenOn` → 先 `start({keepScreenOn:true})` 再更新文案，**唤醒锁被释放**、
>    fgsType 也可能回落。修：ACTION_UPDATE 走 `mergeUpdate()`，只覆盖非空文案字段。
> 3. **iOS `start({mode:'backgroundTask'})` 状态可能假报成功**：旧顺序是先
>    `beginBackgroundTask()` 再 `activeMode = .backgroundTask`，而 `beginBackgroundTask` 失败会把
>    activeMode 置 `.none` → 被后一行覆盖。修：先设模式再申请，失败时保持 `.none`。
>
> ### live-update
> 4. **Android 原生升级自愈失效**：`runStartupSelfHeal` 先 `clearState()` 再判断
>    `getCurrentBundleId() != null` → 永远 false，**WebView 继续用已被删除的 OTA 目录**。
>    修：先取 `hadOta` 再清理。
> 5. **`clearState()` 漏清版本号**：`st_current_version` / `st_previous_version` 残留 →
>    `getState().previousVersion` 显示已不存在的版本。修：一起清。
> 6. **重装同一个包后 rollback 无效**：`previous == current`（同 bundleId），"回滚到自己"。
>    修：`sameBundle` 时不写 previous（两端同步）。
> 7. **iOS 会删掉别人的 OTA 包（跨插件/跨工具）**：`clearBundles()` / `pruneBundles()` 无差别
>    删除 `Library/NoCloud/ionic_built_snapshots` 下所有目录 —— 那是 Ionic 公共约定目录，
>    **Ionic Appflow Live Updates / Capgo 也把包放这里**。修：新增
>    `isOwnBundleId()`（`<version>-<sha8>` 命名规则），清理与 `getState()` 只处理自己的包。
> 8. **阻塞 Capacitor 串行插件线程（影响所有插件）**：网络请求（最长 30s）、下载、解压都跑在
>    `CapacitorPlugins` 单线程上 → 期间 Toast/状态栏/键盘等**所有插件调用全被堵住**。
>    修：Android 用专用单线程 `WORKER`（顺带串行化安装），iOS 把 `checkForUpdate` /
>    `verifyBundle` 挪到全局队列（`downloadAndInstall` 原本就在）。
> 9. **下载体积无上限**：只有"解压后 ≤ 200MB"，但恶意/出错的服务端可以无限流式写盘。
>    修：两端都加 **512MB** 下载硬上限。
>
> ### permissions
> 10. **Android `storage` 在 API 30–32 永远拿不到 granted**：别名同时包含
>     `WRITE_EXTERNAL_STORAGE`，而它在 Manifest 里是 `maxSdkVersion=29` → 30+ 上该权限必然 denied，
>     别名"全部授予才算 granted"的语义被带崩。修：30–32 用只读别名 `storageRead`；33+ 直接
>     `unsupported`（`effectiveAlias` 返回 null）。
> 11. **Android 部分授权被误报**：12+ 只给"大致位置"时旧实现报 `prompt`（**会反复弹窗**）；
>     13+ 只给图片或只给视频时 `photos` 也报非 granted。修：新增模板扩展点 `REFINERS`
>     （`StateRefiner`），`location` / `photos` 在"部分授予"时返回 **`limited`**。
>
> ### secure-storage
> 12. **Android 线程泄漏**：`BiometricGate.prompt` 每次弹窗 `Executors.newSingleThreadExecutor()`
>     且从不 shutdown → 多次认证线程数持续增长。修：全类共用一个守护线程 `EXECUTOR`。
> 13. **Android 坏记录会抛到桥外**：`get()` 里 base64 解码 / 空 IV 在 `try` 之外，
>     `IllegalArgumentException` 可能直接崩。修：捕获 → `CORRUPTED_DATA`；空 iv/ct 也拒绝。
>
> ### 跨插件注意事项（写进了四份 README 的「与其他插件一起使用」）
> - **secure-storage × back-button**：认证弹窗期间返回键可能同时触发 `backButton` → 建议等待
>   Promise 时忽略 backButton；`androidx.biometric` 版本要全项目统一。
> - **permissions × keepalive**：Android 13+ 先申请 `notifications` 再 `start()`，否则通知不显示；
>   **permissions × native-files**：`storage` 只读，写公共目录用 native-files 的 all-files 权限。
> - **keepalive × foreground-state**：FGS 保的是进程，不是"App 在前台"；划掉后 JS 已死。
>   **keepalive × data-channel**：Android 需电池优化豁免，iOS 后台 socket 会被挂起。
>   通知 id 固定 **4301**（与其它通知可能互相覆盖）。
> - **live-update × 一切**：reload 会重建 JS 上下文 —— `data-channel` 的原生 server 还在但
>   端口/OTP 变量丢了（建议装前 `revokeAllOtps`）；各插件 `addListener` 全部失效需重注册；
>   `native-statusbar`/`webview-to-edge`/`fullscreen` 的原生状态保留；`set-force-dark` 会自动重注入；
>   **不要与 Ionic Appflow / Capgo 同时启用**（抢同一个 `serverBasePath`）。
>
> ### 版本 & 验证
> - 版本：permissions **0.3.0**、background-keepalive **0.3.0**、live-update **0.2.0**、
>   secure-storage **0.2.0**。
> - `build:plugins` / `audit:plugins` 通过；Android `assembleDebug` 编译通过。
> - 真机 autotest **ok:true**；`verify:android` **PASS**。
> - **真机定向验证**：`pm grant` 只给 COARSE、revoke FINE 后
>   `check(['location','locationCoarse','storage','photos'])` →
>   `location: limited`、`locationCoarse: granted`、`storage: granted`（API 29 走 READ+WRITE 别名）。
> - 设备已恢复为普通构建（`#/`，`version 1.0`，无 OTA）。

> ## 🆕 2026-11-24 22:40 文档答疑轮（live-update 语义 / 版本比较 / 通知渠道）
>
> 用户提了 4 个文档问题，**在回答过程中发现 2 个真 bug，一并修了**：
>
> 1. **live-update 的语义化版本比较有 bug**（README 原来写「`1.0.0 > 1.0.0-beta`」，但实现是反的）。
>    原实现按 `.`/`-`/`+` 切段后，对「一侧段数少」的情况直接走字符串比较 → `1.2.0` vs `1.2.0-beta`
>    时 `""` < `"beta"`，于是**预发布版被当成更新版本**（正式版用户会被"升级"到 beta）。
>    新规则（Android `LiveUpdatePlugin.compareVersions` / iOS 同名方法，两端逻辑一致）：
>    - 数字段按数值比、字母段按字典序、同位置数字段 > 字母段；
>    - **缺段按 `0` 补** → `1.2 == 1.2.0`、`1.0.0 < 1.0.0.1`；
>    - 缺段 vs **字母段** → 缺段是正式版，更新 → `1.0.0 > 1.0.0-beta`（修复点）；
>    - 已知取舍（README 已写）：`1.0.0-1` 被当成补丁段（比 `1.0.0` 新）、区分大小写、别加 `v` 前缀。
>    **验证**：用 JBR 21 `javac` 把同一份算法抽出来跑 18 组用例，全部通过（含上面所有边界）。
> 2. **`configure({ allowDowngrade })` 之前只存不用**（`checkForUpdate` 从来没读它，
>    README 却承诺"允许降级"）。现在 `checkForUpdate` 读 `call.allowDowngrade`（可临时覆盖配置），
>    `true` 且远端更旧时视为可安装。**真机实测**（本地 dev server 故意发 `0.9.0`，当前 `1.0`）：
>    默认 → `available:false, reason:DOWNGRADE_BLOCKED`；`allowDowngrade:true` → `available:true`；
>    `force:true` → `available:true`。
>
> 文档补充（4 处，都在用户要求的位置）：
> - live-update：**查询参数表**（`appId`/`platform`/`channel`/`version` 的来源、示例、后端用法、
>   为什么不能放 token、灰度别靠客户端随机数）；
> - live-update：**`size` 单位 = bytes**，并顺带给 `bytes`（安装结果 / updates[] / downloadProgress）
>   都标了单位；强调 `size` 不参与校验（校验看 `sha256`）；
> - live-update：**「版本比较规则与示例」** 13 行对照表 + 3 条已知取舍；
>   同时把 `available` 判定（更新/相等但字符串不同/降级/空版本）写清楚；
> - keepalive：**「`channelId` 是什么」**（Android 通知渠道：用户可见的分组开关、同 id 复用、
>   换 id 会新建、重要性创建后 App 改不了、`channelName` 只用于展示、建议加 app 前缀）。
>
> ⚠️ 用户本轮手动往 4 个 README 里都加了「使用例(前端)」Vue 片段（约 275 行）。
> **改 README 前先 `git diff` 看用户改动，只做插入/局部替换，不要整篇重写。**
>
> 本轮验证：`build:plugins` / `audit:plugins` / `verify:ios` 通过；真机 autotest **ok:true**；
> 降级行为真机验证（见上）；设备已恢复为普通构建（`#/`，无 OTA）。

> ## 🆕 2026-11-24 21:15 插件打磨轮（权限模板化 / keepalive iOS 默认值 / 文档补全）
>
> **HEAD**：`138f5e7`（feat）→ 本轮结束时会再补一个 HANDOFF 提交。
> ⚠️ **用户手动改过三个 README，不要覆盖**：
> 1. `capacitor-live-update/README.md`：Android 一节里完整的 `network_security_config.xml` 代码块；
> 2. `capacitor-permissions/README.md`：映射表前一行「注: unsupported 即本平台不支持应用申请此权限」；
> 3. `capacitor-secure-storage/README.md`：`## 使用例` 整段 Vue 片段（约 100 行）。
> 改动 README 时请用「读取 → 增补」而不是整篇重写（本轮已保留）。
>
> ### 1. 权限插件 → 插件模板
>
> - **Android**：`PermissionsPlugin.java` 里的 4 个 switch（effectiveAlias / autoState /
>   permissionStringFor / getSupported）合并成一张 `DEFINITIONS` 表（`PermissionDef` +
>   `AliasResolver`）。加权限 = 表里加一行 + `@Permission` 注解（编译期，绕不开）+ Manifest。
> - **iOS**：`PermissionsPlugin.swift` 改成 `static let descriptors: [PermissionDescriptor]`，
>   `check/request` 都传 plugin 实例（避免闭包强引用 self）。加权限 = 一条 descriptor +
>   一对 `xxxState()/requestXxx()` 小方法。
> - **TS**：`PermissionId` 末尾加 `| (string & {})`，允许传自定义 id（未实现时返回 `unsupported`）。
> - **新增 `plugins/capacitor-permissions/TEMPLATE.md`**：逐文件职责、加/删/改名/细分状态的步骤、
>   验证方式、9 条坑与检查清单。README 顶部加了「这是插件模板」的提示与链接。
> - 行为保持不变：`check` 只读、`request` 串行（iOS）、`getSupported` 顺序 = 注册表顺序。
>
> ### 2. keepalive：iOS 默认改 backgroundTask + 新事件
>
> - iOS `mode: 'auto'` 现在**直接走 `backgroundTask`**（不再嗅探 `UIBackgroundModes: audio`），
>   `getCapabilities()` 里 `backgroundTask.recommended = true`、`audio.recommended = false`。
>   `audio`/`location` 必须**显式**指定。
> - **新增 `keepaliveExpired` 事件**（iOS `backgroundTask` 专用）：payload
>   `{ expiredAt, deliveredAt, deliveredAfterResume, mode, backgroundTimeRemaining, reason, note }`。
> - 实现：`expirationHandler` 里**尽力**立刻 `notifyListeners`，同时把 payload 缓存到
>   `pendingExpirationEvent`；`UIApplication.didBecomeActiveNotification` 时 `flushPendingExpiration()`
>   补发一次（`deliveredAfterResume:true`、`deliveredAt` 为真正送达时间）。另外在
>   `didEnterBackgroundNotification` 时重新 `beginBackgroundTask`（它的计时从进后台开始）。
> - **回答用户的疑问**：`expirationHandler` 里**可以**调 `notifyListeners`，但此刻 App 正在被冻结，
>   JS 通常**不会立即执行**；如果进程随后被系统直接终止，这段 JS 永远不执行。
>   所以做成「立即投递 + 缓存 + 回前台补发」，README「iOS 细节」里有专门一节解释。
>
> ### 3. 文档补全（四份 README）
>
> - **live-update README**（本轮重写，已保留用户改动）新增：
>   - `## API 详解`：10 个方法 + `syncUpdate()` 辅助函数的参数/返回字段表与错误码表；
>   - `## 安全设计：做了哪些措施、为什么、防什么`：18 条措施表（措施/为什么/防什么）、
>     「为什么签文件字节而不是 manifest」「为什么有签名还要 SHA-256」「密钥管理与轮换」
>     「这套设计不解决什么」「商店政策」；
>   - `## 应用端如何成功接收一次热更新（端到端时序）`：阶段 0~4、每阶段"你要保证什么"、
>     失败模式对照表、推荐自测顺序；
>   - `## 实测记录` 保留并更新；
>   - **附录 A/B**：`scripts/dev-ota-server.py` 与 `scripts/make-live-update-fixture.py`
>     的完整源码（带详细注释）。两个脚本本身的注释也补全了。
>     README 里的代码块与仓库文件**逐字一致**（用脚本比对过）。
> - **permissions README**：4 个 API 的完整字段表、`getSupported()` 的 `PermissionInfo` 字段、
>   状态含义 + 下一步建议、使用模式、平台差异、实测（新增真机授权弹窗实测）。
> - **secure-storage README**：8 个 API 的完整字段表（含 `title/reason/cancelText` 的平台差异）、
>   `## 数据存在哪里（排查用）`（SharedPreferences 名、Keystore 别名、Keychain service、
>   UserDefaults 索引、Web localStorage 前缀）、容量建议。
> - **keepalive README**：9 个 API/事件的完整字段表、`KeepaliveStatus` 全字段、
>   `KeepaliveExpiredEvent` 语义、`getCapabilities()` 结构、iOS 默认值变更说明。
>
> ### 本轮验证
>
> - `npm run build:plugins` / `audit:plugins`（18 插件 0 问题）/ `verify:ios` 全部通过；
> - 真机 `32e93a6d`（Android 10 / MIUI 12）：autotest **ok:true**
>   （permissions 注册表重构后 7 项状态正常、`getSupported()` 12 项/10 supported、
>   `request(notifications)` 在 API 29 直接 granted、keepalive 起停 + `keepaliveExpired` 监听器可注册/移除、
>   live-update 离线验签 4 文件）；
> - `npm run verify:android` → **PASS**；
> - iOS CI：push `138f5e7` 触发 run `45402057815`（**失败**：keepalive Swift 的
>   `NotificationCenter` 观察者闭包写成 `{ [weak self] in }`，Swift 报
>   "contextual type for closure argument list expects 1 argument, which cannot be implicitly ignored"
>   → 改成 `{ [weak self] _ in }`）；修复 push `aaafc7e` 后 run `45403046356` **全绿**
>   （Build / 模拟器 autotest 全 success，说明权限注册表重构与 keepalive 的 `override load()` 都能编译运行）。
>
> ### 注意 / 遗留
>
> - 本轮没有模拟器（`emulator-5554` 未启动），Android 只在真机验证；
> - `keepaliveExpired` 的**真实到期**只能在 iOS 真机/长时间后台触发，CI 只验证监听器注册与默认模式；
> - permissions 的 `request()` 弹窗路径依赖系统 UI，autotest 只 check；真机手动验证过（拒绝 → `prompt-with-rationale`）。

> ## 🆕 2026-11-22 15:10 新增 4 个插件（安全存储 / 后台保活 / 热更新 / 权限）
>
> **仓库**：HEAD `018c442`（已 push），`plugins/` 共 **18 个**插件。本轮新增四个：
>
> | 插件 | Android | iOS | Web |
> | --- | --- | --- | --- |
> | `capacitor-secure-storage` | ✅ 真机+模拟器 | ✅ CI | ✅（WebCrypto，非系统级） |
> | `capacitor-background-keepalive` | ✅ 真机+模拟器 | ✅ CI | ❌（返回 running:false 并说明） |
> | `capacitor-live-update` | ✅ 真机（含端到端 OTA） | ✅ CI | ❌（只能 check） |
> | `capacitor-permissions` | ✅ 真机+模拟器 | ✅ CI | ✅（Permissions API） |
>
> ### 各插件一句话总结
>
> - **secure-storage**：iOS Keychain（`kSecClassGenericPassword` + 可选
>   `SecAccessControl(.biometryCurrentSet)`）/ Android AndroidKeyStore AES-256-GCM（密文进
>   SharedPreferences）/ Web WebCrypto AES-GCM（不可导出 CryptoKey 存 IndexedDB）。
>   `requireBiometric: true` 的条目：iOS 读取时系统自动弹 Face ID，Android 用
>   `androidx.biometric` + `CryptoObject`（写入与读取都弹）。错误码区分
>   `BIOMETRY_NOT_ENROLLED` / `BIOMETRY_KEY_INVALIDATED`（换指纹后旧密文永久失效）/
>   `VAULT_INVALIDATED`（备份恢复导致密钥不匹配）/ `USER_CANCELED`。
> - **background-keepalive**：Android 前台服务 + 常驻通知（`specialUse` 默认，可选
>   `mediaPlayback`/`dataSync`；处理 Android 14+ 类型声明、15+ `onTimeout` 6 小时限制、
>   `FOREGROUND_SERVICE_IMMEDIATE`、电池优化豁免、`keepScreenOn`）。
>   iOS **没有**等价能力，提供 `audio`（静音循环，App Store 2.5.4 拒审风险）/ `location`
>   （需要 UIBackgroundModes + Always 权限）/ `backgroundTask`（`beginBackgroundTask` 续期，
>   约 30s）三种尽力模式，`auto` 会在没有 audio 背景模式时退化为 `backgroundTask`。
> - **live-update**：`latest.json` → 下载 zip → **SHA-256 + ECDSA P-256（CryptoKit /
>   `SHA256withECDSA`）验签** → 解压到 staging → rename（原子）→ 写 Capacitor 自己的
>   `serverBasePath`（Android `CapWebViewSettings` prefs / iOS
>   `KeyValueStore["serverBasePath"]`）→ reload。**先 resolve 再 reload**（否则 promise 收不到）。
>   启动自愈：`pending` 未 `notifyAppReady()` 时每次冷启动 +1，到 2 次自动回滚；原生
>   versionCode/CFBundleVersion 变化时丢弃 OTA。iOS 自带 `ZipArchive.swift`（解析中央目录 +
>   `Compression` 框架 raw deflate + CRC32 校验 + 防 zip-slip，**零第三方依赖**）。
> - **permissions**：16 个统一 id（camera/microphone/location/locationCoarse/notifications/
>   photos/storage/contacts/calendar/reminders/bluetooth/motion/activityRecognition/
>   bodySensors/tracking/speech）。Android 用 Capacitor `@Permission` 别名 +
>   `requestPermissionForAliases`；iOS 逐框架映射 + 串行请求（iOS 一次只能弹一个）。
>   `check()` 绝不弹窗，autotest 只 check 不 request（避免 CI 被弹窗卡住）。
>
> ### 本轮踩到/修掉的新坑（重要）
>
> 1. **插件 Manifest 里的 `<service android:name=".XxxService">` 会被解析成宿主包名**
>    （`com.myapp.testionic.XxxService`）→ 运行时 `Unable to start service ... not found`，
>    必须写**全限定类名** `com.testionic.backgroundkeepalive.KeepaliveService`。
> 2. **平台 `android.hardware.biometrics.BiometricPrompt` 的构造器是包级私有、且没有
>    `PromptInfo`** → 用 `androidx.biometric:biometric:1.1.0`（自带依赖，需网络下载）。
> 3. **`JSObject.put(key, null)` 会删除这个 key**（org.json 语义），要 JS 拿到 `null`
>    必须 `put(key, JSONObject.NULL)`。
> 4. **iOS `CNAuthorizationStatus.limited` 是 iOS 18 才有的枚举** → 低版本 SDK 编译报
>    "only available in iOS 18"，用 `rawValue` 比较（EventKit 同理）。
> 5. **iOS 持久化 `serverBasePath` 只取路径最后一段**，去
>    `Library/NoCloud/ionic_built_snapshots/<最后一段>` 找资源 → bundle 必须装在这个目录下。
> 6. **`addListener` 的返回类型**别写成 `Promise<Handle> & Handle`，Web 实现会类型不匹配；
>    统一 `Promise<PluginListenerHandle>`。
> 7. **`capacitor-my-plugin` 的 Android 返回值与 autotest 断言不一致**（"HelloCIfrom
>    AndroidPlugin~" vs "Hello CI from AndroidPlugin!"）——之前只有 iOS 跑过 autotest 所以没暴露，
>    本轮修掉。
> 8. **`scripts/audit-plugins.mjs` 的 TS 方法计数**原来要求签名写在同一行，跨行签名（参数或
>    返回值为对象）会漏数 → 改为按行首 `name(` 解析（与 verify-ios.mjs 一致）。
> 9. 模拟器 API 36 上 `uiautomator` / systemui 偶发 ANR（软件渲染），权限弹窗自动化建议在真机做。
>
> ### 验证矩阵（本轮实测）
>
> - **真机 Redmi K30S（Android 10 / MIUI 12 / API 29）**：
>   - `npm run android:deploy -- -Device 32e93a6d`（VITE_AUTOTEST=1）→ autotest `ok:true`
>     （secure-storage 写入读回、permissions 7 项状态、keepalive 前台服务起停、
>     live-update 离线验签 4 文件全过）；
>   - `npm run verify:android` → **PASS**；
>   - 热更新端到端（`scripts/dev-ota-server.py` + `adb reverse tcp:8899`）：
>     安装 51 文件/1.19MB → reload 后 `currentVersion 9.9.9`、`pending:true` →
>     `notifyAppReady` 清 pending → **冷启动直接进 9.9.9**（无闪屏）→ `rollback()` 回 1.0 →
>     再装一次不确认，**第 2 次冷启动自动回滚**并带 `note`；
>   - 权限真实弹窗：`check(camera)=prompt` → `request(camera)` 弹 MIUI 授权框 → 点"拒绝" →
>     返回 `prompt-with-rationale`。
> - **模拟器 Pixel_6（Android 16 / API 36）**：autotest `ok:true` + `verify:android` **PASS**
>   （notifications/photos 状态为 `prompt`，符合 13+ 才有该权限）。
> - **iOS**：`npm run verify:ios` 静态检查通过；**CI 全绿**
>   （run `44997416394`，commit `fa6a8f5`：Build local plugins / Build app for simulator /
>   模拟器 autotest 全部 success，即 4 个插件的 Swift 全部编译通过，且 iOS autotest
>   `ok:true` —— 包含 `verifyBundle` 的 deflate zip 解析 + CryptoKit 验签）。
>   - 第一次 push（`018c442` / run `44996891496`）在 Swift 编译阶段失败：
>     `LiveUpdatePlugin.swift` 里把 `private func currentVersion()` 当值用（`let current = currentVersion`），
>     报 "function produces expected type 'String'"；改成计算属性 `private var currentVersion` 后通过。
>     **CI 日志不需要 token**：`https://api.github.com/repos/<owner>/<repo>/check-runs/<jobId>/annotations`
>     可以直接拿到 `::error::` 注解（比抓 HTML 更省事，本轮就是这么定位的）。
>
> ### 本轮新增的开发工具
>
> - `scripts/make-live-update-fixture.py`：生成/复用 `.perf/fixture-key.pem`（P-256），
>   产出 `src/plugin-test-fixtures.js`（真实 deflate zip + sha256 + 签名，供三端
>   `verifyBundle` 自检）；`sign <zip>` 子命令可给任意 zip 签名。
> - `scripts/dev-ota-server.py`：本地热更新后端（`/latest.json` + `/bundle.zip`，自动
>   zip + 签名并打印公钥），配合 `adb reverse` 做端到端联调。
> - 测试页新增 4 张卡片 + 自动测试断言；`src/plugin-test-fixtures.js` 是它们的数据源。
>
> ### 尚未完成 / 下次可做
>
> - iOS **真机**未验证（无 Mac）：secure-storage 的 Face ID 门控、keepalive 的
>   `audio`/`location` 模式、live-update 的真实 OTA 都只在模拟器/静态检查层面验证过。
> - secure-storage 的生物识别**弹窗路径**没有进 autotest（真机会阻塞自动化），
>   只在真机上验证了"未录入时必须 reject"与 `isBiometryAvailable()`。真机上已录指纹
>   （`available:true`），手动点测试页的"写入（需生物识别）"可验证完整链路。
> - `background-keepalive` 的 `mediaPlayback`/`dataSync` 类型只做了声明与代码路径，
>   没有分别实测（默认 `specialUse` 已实测）。
> - Android 端热更新只实测了 `http://127.0.0.1`（adb reverse）；公网 HTTPS 未实测。

> ## 🧠 2026-11-22 13:59 收尾状态（本轮只补知识，没有改代码）
>
> **本轮没有修改任何插件代码，也没有重新 build/deploy。** 下面是把 2026-11-21~22 的问答
> 沉淀成"下次开新对话可直接用"的结论。结论基于本机 **Capacitor 8.6.0**；也对比了 npm 最新
> **8.6.2** 的对应二进制/HTTP 代码，关键结论未变。各插件版本在对应条目中注明。
>
> **仓库现状**：HEAD `3a2b005`（`docs: 插件指南补充 CLI 识别插件的两个隐蔽规则`）；
> 本轮开始前工作区干净，**本轮只修改了 `HANDOFF.md`（尚未提交）**；`plugins/` 下共
> **14 个插件**；`@capacitor/core|android|ios|cli` 均为 `8.6.0`。
>
> **新对话怎么接**：第一句让助手读 `HANDOFF.md`，然后直接说要做哪个插件/哪一步。
> 插件脚手架用 `plugins/capacitor-my-plugin`，规范看 `docs/capacitor-plugin-guide.md`，
> 不要再从零解释 Capacitor 基础。
>
> **用户背景/偏好**：Vue2/Web 前端，无原生开发经验；喜欢先看源码/代码级证据再下结论；
> 不需要额外文档时不要主动加；README 里用户手改过的段落要保留。
>
> ### 1. Browser / InAppBrowser / iframe
>
> - **`@capacitor/browser@8.0.5`**（Ionic 官方）：系统 in-app browser。
>   - iOS：`SFSafariViewController`；Android：Chrome Custom Tabs（`androidx.browser`，
>     无 Custom Tabs 时回退 Chrome / `ACTION_VIEW`）；Web：`window.open`。
>   - API：`open({url, toolbarColor, presentationStyle, width, height})`、`close()`、
>     `browserFinished`、`browserPageLoaded`；**没有** JS 注入 / 自定义 Header / DOM 访问。
>   - 共享系统浏览器 Cookie（Safari / Chrome），适合 OAuth/SSO/外部链接。
> - **`@capacitor/inappbrowser@4.1.0`**（OutSystems，repo `ionic-team/capacitor-os-inappbrowser`，
>   peer `>=8`）：三种模式：
>   - `openInWebView`：新建独立 WebView，可定制工具栏/URL/导航按钮/UA/自定义 Header；
>     Android API 28+ 默认独立进程 `:OSInAppBrowser` + 隔离存储（`isIsolated:false` 可关，
>     不建议关）；iOS 默认隔离；真正文件字节不过桥；
>   - `openInSystemBrowser`：SFSafariViewController / Custom Tabs；
>   - `openInExternalBrowser`：`ACTION_VIEW` / `UIApplication.open`，离开 App。
>   - 事件：`browserClosed`、`browserPageLoaded`、`browserPageNavigationCompleted`；
>     Android minSdk 26；无 Web 实现。
> - **为什么不用 iframe**：`X-Frame-Options` / CSP `frame-ancestors` 会直接拒绝；第三方 Cookie /
>   storage 分区导致登录态丢失；OAuth 规范（RFC 8252）与 Google 都拒绝嵌入式 WebView；
>   iframe 没有地址栏/下载/弹窗/权限/密码自动填充，且仍与主 WebView 同进程/同存储。
> - **选型**：普通链接/OAuth → `browser` 或 `inappbrowser.openInSystemBrowser`；
>   要可定制内嵌页面 → `inappbrowser.openInWebView`；离开 App → `inappbrowser.openInExternalBrowser`
>   或 `app-launcher.openUrl`；只有自己控制且明确允许 frame 的内容才用 iframe。
>
> ### 2. CapacitorCookies（文档 `/docs/apis/cookies`；不是 npm 包）
>
> - 是 `@capacitor/core` 内置插件，直接从 core 导入 `CapacitorCookies`。
> - 在 `native-bridge.js` 里 **patch `document.cookie` 的 getter/setter**，但**仅当**配置开启：
>   ```json
>   { "plugins": { "CapacitorCookies": { "enabled": true } } }
>   ```
>   默认 `false`，所以不配置时行为与浏览器原生一致。
> - **目的**：打通两套 Cookie 世界：
>   - WebView 的 Cookie（`document.cookie` / `WKWebsiteDataStore` / `android.webkit.CookieManager`）；
>   - 原生 HTTP 的 Cookie（iOS `HTTPCookieStorage.shared` + `URLSession`；Android
>     `java.net.CookieHandler` / `HttpURLConnection` / CapacitorHttp）。
> - **iOS**：`document.cookie` 的读写通过 `prompt(JSON)` 同步桥到原生
>   `CapacitorCookieManager`：写 `HTTPCookieStorage` + `syncCookiesToWebView()` 到
>   `WKHTTPCookieStore`；`CapacitorWKCookieObserver` 再把 WebView 的 cookie 反向同步回
>   `HTTPCookieStorage`。get 会过滤 `isHTTPOnly`。
> - **Android**：setter 走 `@JavascriptInterface CapacitorCookiesAndroidInterface.setCookie` →
>   `CapacitorCookieManager`（继承 `java.net.CookieManager`，内部用
>   `android.webkit.CookieManager`）；`CookieHandler.setDefault(...)` 让 Java HTTP 共享；
>   getter 保留原始 WebView `document.cookie`（避免暴露 HttpOnly）。
> - **效果**：JS 写的登录 Cookie 能被原生 HTTP 带上；原生 `Set-Cookie` 也能被 JS 看到；
>   `CapacitorCookies.*` API 与 `document.cookie` 一致。
> - **坑**：iOS 用同步 `prompt` 桥，频繁读写会阻塞；只 patch 主文档（iframe 不管）；
>   HttpOnly 仍对 JS 不可见；解析是简化版；不会和系统 Safari/Chrome 共享。
>
> ### 3. CapacitorHttp（core 内置；文档 `/docs/apis/http`）
>
> - 默认关闭；开启后 **替换 `window.fetch` 和 `window.XMLHttpRequest`**（不是网络层抓包）：
>   ```json
>   { "plugins": { "CapacitorHttp": { "enabled": true } } }
>   ```
> - **只接管**通过这两个 API 发出的 http(s) 请求；`<img>`、`<script>`、`<link>`、CSS、iframe、
>   WebSocket、EventSource、Service Worker 内的 fetch、`sendBeacon`、`<form>` 提交都不管。
> - 路径分流：
>   - **GET/HEAD/OPTIONS/TRACE**：URL 改写成
>     `https://localhost/_capacitor_http_interceptor_?u=<真实URL>`，WebView 发出后由原生
>     `shouldInterceptRequest` / `URLSchemeTask` 拦截，再用平台 HTTP 请求；
>     Android 用 `WebResourceResponse(..., InputStream)` **流式**；iOS 用
>     `URLSession.dataTask` 收完整 `Data` 再回传（**全量缓冲**）。
>   - **POST/PUT/PATCH/DELETE**：直接 `nativePromise('CapacitorHttp','request', ...)`，
>     请求/响应都过桥、全量缓冲，再在 JS 构造 `Response`。
> - **大体积/二进制的实际限制**（8.6.0 与 8.6.2 均存在）：
>   - `convertBody` 对 `Uint8Array`/`ReadableStream` 用 `new TextDecoder().decode(...)`，
>     二进制会被 UTF-8 破坏；`Blob`/`ArrayBuffer` 没有专门分支，可能变成 `[object Blob]`/
>     `[object ArrayBuffer]`；**只有 `File` 走 base64**，相对可靠；
>   - 原生默认 `responseType = text`：非 JSON 二进制响应会按 UTF-8 解码，`response.arrayBuffer()`
>     拿到的不是原始字节；`responseType: 'arraybuffer'/'blob'` 返回的是 **base64 字符串**，
>     需自己解码；
>   - 大响应全量缓冲（iOS GET 也是整包进内存）；`AbortController`/进度事件不可靠；
>     `statusText`/`type`/`redirected` 等语义与浏览器不同；
>   - 需要 `CapacitorCookies.enabled` 才能让原生请求共享 WebView 登录态。
> - **实用价值**：适合 JSON/text 的**跨域 API**（服务器没配 CORS）、需要共享 Cookie、
>   设置 `User-Agent` 等受限 Header 的场景；**不是大文件/流式/二进制通道**。
>   同源或已正确配置 CORS 时直接用原生 `fetch` 更好。
>
> ### 4. `@capacitor/file-transfer@2.0.6`（原生文件上传/下载）
>
> - 真正原生执行：Android 用 `ionfiletransfer-android`，iOS 用 `ion-ios-filetransfer`
>   （原生 HTTP + 文件流读写，iOS 是 `URLSession` 下载/上传 task + delegate 进度）。
>   **文件字节不过 JS Bridge**，只传 URL/路径/Header/进度/结果。
> - API：
>   ```ts
>   const { uri } = await Filesystem.getUri({ directory: Directory.Data, path: 'a.pdf' });
>   await FileTransfer.downloadFile({ url, path: uri, headers, progress: true });
>   FileTransfer.addListener('progress', p => { /* p.bytes, p.contentLength, p.lengthComputable */ });
>
>   const r = await FileTransfer.uploadFile({
>     url, path: uri, chunkedMode: true, mimeType: 'image/png', fileKey: 'file',
>     headers, progress: true,
>   });
>   // r: bytesSent, responseCode(string), response, headers
>   ```
> - 支持：超时 `readTimeout`/`connectTimeout`（默认 60s）、`disableRedirects`、
>   `shouldEncodeUrlParams`；上传默认 `multipart/form-data`（要原始流需显式设 `Content-Type`）。
> - 坑：不负责创建目录；**没有暴露断点续传 API**；**不是后台下载器**（无 Android
>   DownloadManager / iOS background URLSession 保证）；iOS 要扩展名；Android 公共目录仍受
>   Scoped Storage 限制；
>   Web 端是 `fetch + Blob`（内存实现，不是原生）。
> - 选型：**API 请求用 CapacitorHttp，文件传输用 FileTransfer**。
>
> ### 5. `@capacitor/file-viewer@2.0.4`
>
> - 仅 Android/iOS，无 Web。
> - **Android**：本地文件走 `FileProvider`（authority `<packageName>.opener.provider`）+
>   `Intent.ACTION_VIEW`（`FLAG_GRANT_READ_URI_PERMISSION`）；MIME 由扩展名推断。
>   - 多个 App 且无默认 → 系统弹"打开方式"；有默认 → 直接打开；**插件不强制 `createChooser`**；
>   - `openDocumentFromUrl` 在 Android 只是 `ACTION_VIEW` 这个 URL（通常打开浏览器），**不会下载预览**。
> - **iOS**：`UIDocumentInteractionController.presentPreview(...)` → **App 内 Quick Look 预览**，
>   不是"Open in..."选择器；用户可点分享按钮再用其他 App 打开。
> - **Quick Look** 是系统 `QuickLook.framework`；官方常见支持：iWork、Office、RTF、PDF、图片、
>   符合 `public.text` 的文本、CSV、USDZ；Live Photos/音视频也支持（插件媒体预览另用
>   `AVPlayerViewController`）。支持列表可能随 iOS 版本变化，第三方可用 Quick Look Extension 扩展。
>   - `.txt`：✅ 文本预览（UTF-8 最稳）；
>   - `.html`：UTI `public.html` conforms to `public.text`，会进文本类预览，但**渲染成网页不可靠**；
>     要渲染网页用 `WKWebView`/`SFSafariViewController`；
>   - `.zip`：UTI `public.zip-archive` conforms to `public.data`/`public.archive`，**不在 Quick Look
>     常见支持列表**，通常无法预览；需第三方解压 App 或自己解压。
> - **`openDocumentFromLocalPath` vs `openDocumentFromResources`**：
>   - Local：设备文件系统**绝对路径**（可带 `file://`），文件必须存在；
>   - Resources：App 资源相对路径；Android 从 **`assets/`** 读并复制到 `externalCacheDir` 再打开；
>     iOS 用 `Bundle.main.path(forResource:ofType:inDirectory:)`；Capacitor 内置 web 资源通常写
>     `public/docs/help.pdf`；iOS 要求有扩展名。
> - **`openDocumentFromUrl` 不支持 data URL**：Android/iOS 校验正则都只接受 `http[s]://`。
>   WebView 生成的 `data:` URL 要先落成带扩展名的临时文件（`Filesystem.writeFile`，
>   base64 用对应 encoding），再 `openDocumentFromLocalPath`。
>
> ### 6. WebView assets 在 Android / iOS 的哪里
>
> - **不会解压到可写存储**。
> - **Android**：`cap sync` 把 `dist/` 复制到 `android/app/src/main/assets/public/`，Gradle 打包进
>   APK；运行时用 `AssetManager` 从 APK 读（`getAssets().open("public/index.html")`、
>   `getAssets().list("public")`），不是普通文件路径，只读。查看 APK：
>   `adb pull .../base.apk` → `unzip -l base.apk | grep assets/public`。
> - **iOS**：`cap sync` 复制到 `ios/App/App/public/`，打包进 `.app` bundle；用
>   `Bundle.main.path(forResource:ofType:inDirectory:"public")` / `Bundle.main.url(...)` 读，只读。
> - JS 侧直接相对路径 `fetch('/index.html')` 即可（WebView 拦截器/AssetHandler 提供）。
> - `FileViewer.openDocumentFromResources({ path: 'public/...' })` 的根就是 assets/bundle 根。
> - 要修改/导出：先用原生 API 复制到 cache/files/documents；assets 本身不可写。
>
> ### 7. 热更新（web 资源 OTA）
>
> - **assets 只读不代表不能热更新**：Capacitor 内置 `WebView` 插件可把 WebView 的 base path
>   指向可写目录：
>   ```ts
>   import { WebView } from '@capacitor/core';
>
>   await WebView.setServerBasePath({ path: '/data/.../files/updates/1.2.3' });
>   await WebView.persistServerBasePath(); // 下次启动仍生效
>   const { path } = await WebView.getServerBasePath();
>   await WebView.setServerAssetPath({ path: 'public' }); // 指回内置 assets/bundle
>   ```
>   - 路径是**文件系统绝对路径**，不是 `file://` URI；目录里要有 `index.html`；
>   - Android `Bridge.setServerBasePath` 会 `localServer.hostFiles(path)` 并 reload；
>   - iOS 会更新 `WebViewAssetHandler` 的 asset path 并 reload；
>   - origin 不变（`https://localhost` / `capacitor://localhost`），localStorage/IndexedDB 不丢。
> - **持久化路径**：Android 写 `CapWebViewSettings` SharedPreferences；iOS 写
>   `KeyValueStore["serverBasePath"]`，重启后按
>   `Library/NoCloud/ionic_built_snapshots/<最后一段路径>` 解析（Ionic Live Updates 约定）。
> - **能更新**：HTML/JS/CSS/图片；**不能更新**：Java/Kotlin/Swift 原生代码、插件、权限、
>   大部分 native 配置。新 JS 必须兼容已安装的旧原生二进制。
> - 商店政策：Apple 允许解释型代码更新但不得改变 App 主要目的/绕过审核；Google Play 不允许
>   下载 DEX/native 代码。不要用它绕审核。
> - 生产建议：Capgo（`@capgo/capacitor-updater`）、Ionic Appflow Live Updates，或自己做
>   "HTTPS 下载 zip → 校验哈希/签名 → 解压到可写目录 → setServerBasePath → persist → reload"，
>   并保留旧版本回滚。
>
> ### 8. `@capacitor/geolocation@8.2.5` 的 `Position` 单位
>
> | 字段 | 单位 |
> |---|---|
> | `timestamp` | **毫秒（Unix epoch，UTC）**（Android `Location.getTime()`；iOS `Date*1000`） |
> | `coords.latitude` / `longitude` | **十进制度（WGS-84）** |
> | `coords.accuracy` | **米**（水平精度） |
> | `coords.altitudeAccuracy` | **米**（垂直精度；Android 8.0+ / iOS 全版本，否则 null） |
> | `coords.altitude` | **米**（Android WGS-84 椭球面以上；iOS 海平面以上） |
> | `coords.speed` | **米/秒**（iOS 可能 -1、Android 可能 0 表示未知，判断 `>= 0`） |
> | `coords.heading` | **度**（0–360，优先 trueHeading → magneticHeading → course） |
> | `coords.magneticHeading` / `trueHeading` / `headingAccuracy` | **度**（仅 `watchPosition`） |
> | `coords.course` | **度**（相对真北的行进方向；仅 `watchPosition`） |
>
> ### 9. `@capacitor/app-launcher@8.0.1` 的 `openUrl`
>
> - **不限制协议**：任何系统有处理者的 URL 都能打开，包括 `http/https`、`mailto:`、`sms:`、
>   `tel:`、`geo:`、`market://`、`whatsapp://`、`tg://`、自定义 scheme 等。
> - Android：`Intent.ACTION_VIEW` + `Uri.parse(url)` → `startActivity`；失败时还会把 `url`
>   当包名 `getLaunchIntentForPackage(url)` 尝试启动。iOS：`UIApplication.shared.open(url)`。
> - `mailto` / `sms` / `tel` **可以**，前提是设备有对应 App：
>   - `mailto:a@x.com?subject=...&body=...`（参数要 URL 编码）；
>   - `sms:+86138...?body=...`（iOS 新版本对预填 body 有限制）；
>   - `tel:+86138...`（RFC 3966，不要空格；iPad/模拟器可能没有电话 App）。
> - 返回 `{ completed: boolean }` **只表示系统成功接收 Intent/URL**，不代表用户真的发信/通话。
> - 没有处理者时通常 resolve `completed:false`（不是 reject）；`url` 缺失才 reject。
> - 如果先调 `canOpenUrl`：Android 11+ 需 `<queries>` 声明 scheme（否则误报 false）；
>   iOS 自定义 scheme 需 `LSApplicationQueriesSchemes`。`openUrl` 本身不需要这些白名单。
>
> ### 10. 原生代码里的"编译期宏"
>
> - **Capacitor 不提供统一宏系统**；原生代码由 Gradle / Xcode 编译，用各自构建系统。
> - **Android（Java/Kotlin 没有预处理器宏）**：
>   - `buildConfigField`（AGP 8 要先 `buildFeatures { buildConfig true }`）：
>     ```gradle
>     buildTypes {
>       debug   { buildConfigField "String", "API_BASE_URL", "\"https://dev...\"" }
>       release { buildConfigField "String", "API_BASE_URL", "\"https://api...\"" }
>     }
>     ```
>     生成 `BuildConfig.API_BASE_URL`（`static final` 常量，使用处可内联）。
>   - 要保证"源码里也没有 if"：用变体源码集
>     `android/src/debug/java/.../AppEnvironment.java` 与 `src/release/...`，各放一份
>     `public static final String API_BASE_URL = "...";`（Kotlin 用 `const val`）；每个变体只编译一个。
>   - `BuildConfig.DEBUG` 在新 AGP 不一定是编译期常量；不要依赖它做严格死代码消除。
> - **iOS（Swift/ObjC 有真正条件编译）**：
>   - `#if DEBUG ... #else ... #endif`；`DEBUG` 由 Xcode Debug 的
>     `Active Compilation Conditions` / `GCC_PREPROCESSOR_DEFINITIONS` 提供；
>   - 自定义 `STAGING`/`PRODUCTION` 加到对应 configuration 的 Active Compilation Conditions；
>     Xcode 默认不定义 `RELEASE`，用 `#if !DEBUG` 或手动加；
>   - Swift 的 `#if` 只能判断编译标志/平台，不能比较字符串；任意值（CI 环境）用 Run Script
>     生成 `AppEnvironment.swift`，或 `Package.swift` 的
>     `swiftSettings: [.define("DEBUG", .when(configuration: .debug))]`；
>   - ObjC 可直接 `#if DEBUG` + `#define API_BASE_URL @"..."`。
> - 编译期常量会进 APK/IPA，**不要放 secret**。
>
> ### 11. 本轮涉及的官方文档
>
> - Browser：<https://capacitorjs.com/docs/apis/browser>
> - InAppBrowser：<https://capacitorjs.com/docs/apis/inappbrowser>
> - Cookies：<https://capacitorjs.com/docs/apis/cookies>
> - HTTP：<https://capacitorjs.com/docs/apis/http>
> - FileTransfer：<https://capacitorjs.com/docs/apis/file-transfer>
> - FileViewer：<https://capacitorjs.com/docs/apis/file-viewer>
> - Geolocation：<https://capacitorjs.com/docs/apis/geolocation>
> - AppLauncher：<https://capacitorjs.com/docs/apis/app-launcher>
>
> ### 12. 下一步 / 待办
>
> - **当前没有未完成的代码任务**；用户准备开始做新插件，但尚未指定具体插件。
> - 新插件建议流程：
>   1. 复制 `plugins/capacitor-my-plugin` 为 `plugins/capacitor-xxx`，改 package name、
>      `@CapacitorPlugin(name=...)`、Android 包名/类名、iOS `jsName`/SPM product/target；
>   2. 写 `src/definitions.ts` + `index.ts` + Web fallback；Android/iOS 实现；
>   3. `npm run build:plugins` → `npm run audit:plugins`；
>   4. 在 `src/views/PluginTestView.vue` 加卡片/autotest；`npx cap sync`；
>   5. Android 真机/模拟器 + iOS CI（`npm run verify:ios`）；
>   6. 补 README（API 字段详解）+ INTRO，更新 `plugins/README.md` 与 HANDOFF。
> - 常用命令：`npm run build:plugins`、`npm run audit:plugins`、`npm run android:sync`、
>   `npm run android:deploy`、`npm run verify:android`、`npm run verify:ios`。
> - 环境/JDK/设备/CI 细节仍见下方 2026-11-14、2026-11-10、2026-11-08 的历史块；
>   本机 JDK 21 路径 `C:\Program Files\Android\Android Studio\jbr`。
> - 本轮只改了 `HANDOFF.md`，没有新增/修改插件。

> ## ⏸ 2026-11-14 22:00 收尾状态（删除 boot-autostart + 真机验证返回键）
>
> - **已删除 `capacitor-boot-autostart`**（用户决定：与期望差距大、需求不常见）；根依赖、Android
>   settings/build.gradle、iOS SPM、测试页卡片/autotest、plugins/README 全部移除，现共 **13 个插件**。
> - **真机（Redmi K30S / Android 10）验证 `capacitor-back-button`**：
>   ① `enable()` + 路由模式：`#/about` 按返回 → `#/`（事件 1 次、payload `canGoBack:true`、App 存活）；
>   ② 首页 `minimizeApp()` 模式：按返回 → 回到桌面、进程存活；
>   ③ `disable()` 后按返回 → Activity 退出回桌面。
> - 真机首次 autotest 在 data-channel 处偶发失败（1MB 上传/WS 回显疑似超 8s 超时），**重跑 `ok:true`**；
>   后续可考虑放宽 autotest 的 fetch/WS 超时，避免旧机型偶发。
> - iOS 侧滑手势的真实手感仍需 iOS 真机确认（CI 已编译通过）。
>
> ## ⏸ 2026-11-13 00:00 收尾状态（新增两个插件）
>
> **新增插件：`capacitor-back-button`（保留）；`capacitor-boot-autostart` 已按用户要求删除（2026-11-14，与期望差距大、需求不常见）。**
>
> ### capacitor-back-button（返回键拦截 / PopScope 等价）
> - API：`enable/disable/isEnabled`、`canGoBack/goBack`、`exitApp`、`minimizeApp`、
>   `addListener('backButton')`；默认**不拦截**。
> - Android：`OnBackPressedCallback` 注册在 Activity 的 dispatcher 上；enable 后返回键只派发事件。
> - iOS：无硬件返回键，`enable/disable` 仅记录状态；`canGoBack/goBack` 用 WKWebView 历史；
>   `exitApp` reject（Apple 不允许主动退出）。
> - Web：`history.back()` 模拟。
> - **实测（Android 16 模拟器）**：enable 后连按返回键 2 次 → 事件计数 2、URL 不变、Activity 不退出；
>   disable 后按返回键 → Activity 退出回桌面 ✅。
> - 用法：`await BackButton.enable()` + 监听里 `router.back()` 或 `minimizeApp()`。
>
> ### ~~capacitor-boot-autostart（开机自启，Android only）~~ —— **已删除**（结论保留供以后参考）
> - **结论**：Android 可以做到"开机保活"（`BOOT_COMPLETED` → `specialUse` 前台服务），
>   **不能**做到"开机自动弹界面"（Android 10+ 禁止后台启动 Activity）；**iOS 完全不可能**
>   （无开机广播/无公开 API），插件在 iOS 返回 `supported:false`。
> - API：`getStatus/enable/disable`、`requestNotificationPermission`、
>   `requestIgnoreBatteryOptimizations`、`openAppSettings/openAutostartSettings`。
> - Manifest 会自动合并：`RECEIVE_BOOT_COMPLETED`、`FOREGROUND_SERVICE(_SPECIAL_USE)`、
>   `POST_NOTIFICATIONS`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `BootReceiver` + `BootService`。
> - **实测（Android 16 模拟器，adb reboot）**：开机日志
>   `BOOT_COMPLETED` → `Start proc ... for broadcast {BootReceiver}` →
>   `Background started FGS: Allowed ... code:BOOT_COMPLETED`；重启后进程存活、
>   `types=0x40000000`、通知 id 1001 ✅。系统广播排队约 **50 秒**后才送达（正常）。
> - 限制：强行停止后收不到 BOOT_COMPLETED（stopped state）；MIUI 等需手动允许自启动；
>   Android 13+ 需要通知权限；Play 对 `specialUse`/电池优化白名单有政策要求。
>
> ### 其它
> - 测试页新增两张卡片：`BackButton`、`BootAutostart`；autotest 覆盖两者（Android 上
>   boot 断言 `serviceRunning true→false`；iOS 断言 `supported:false`）。
> - `npx cap sync ios` 已执行，iOS SPM 已包含两个新包；`npm run verify:ios` 通过。
> - **iOS CI 已通过**：`8f98c03`（back-button 0.2.0 含侧滑返回）run `43548899833` 全绿
>   （Build local plugins / Resolve Swift packages / Build app for simulator / 模拟器 autotest 全部 success）。
>   iOS 侧滑手势的"手感"仍需真机确认（autotest 无法模拟边缘滑动）。
> - **测试只用了模拟器（`emulator-5554`），没有重启真机**；真机 `32e93a6d` 仍在连接但未改动。
> - 新增插件同样需要在别的项目里按 `npm install file:` + `npx cap sync` 使用；
>   `back-button` 无宿主配置；`boot-autostart` 的权限/组件由 Manifest 自动合并。
> ## ⏸ 2026-11-10 17:40 收尾状态（历史记录）
>
> **今天做的事：Live Reload（真机热更新）方案落地 + data-channel 0.1.2 的 `debug` 支持。**
>
> **Live Reload 用法（不写配置，纯命令行）**：
> - Android（推荐 adb reverse，不用同 Wi-Fi、不用改 network_security_config）：
>   ```bash
>   npm run dev -- --host 127.0.0.1 --port 5173          # Vite 3 在 Windows 默认只绑 ::1，必须显式 IPv4
>   adb reverse tcp:5173 tcp:5173                         # 切 USB 模式/拔插后会丢
>   npx cap run android -l --host localhost --port 5173 --forwardPorts 5173:5173
>   ```
> - iOS：`npx cap run ios -l --external --host <LAN IP> --port 5173`（需 Mac；Info.plist 已有
>   `NSAllowsLocalNetworking`；iOS 14+ 要允许"本地网络"）。
> - `cap run -l` 只把 `server.url` 写进平台目录临时配置（`android/app/src/main/assets/capacitor.config.json`），
>   正常退出还原；**被 kill 会残留**，恢复用 `npx cap copy android`。
> - **实测通过**（真机 K30S）：`location.origin = http://localhost:5173`；改 `<h2>` 文本 HMR 即时生效/还原；
>   `cap run` 会跑 gradle，需要 **JDK 21**（JAVA_HOME 指向 AS 的 JBR）。
>
> **data-channel 0.1.2（`debug` 参数）**：
> - `startHttp({ debug: true })` / `startWebSocket({ debug: true })`：从当前 WebView URL 推导 origin
>   （如 `http://localhost:5173`）并加入 Origin 白名单；生产不要传。
> - 实现：Android `DataChannelServer`/`DataChannelWsServer` 增加 `extraAllowedOrigins`；
>   iOS 新增 `DataChannelOrigins.swift`（线程安全）+ 两个 server 改用它；TS 类型已加 `debug`。
> - **实测**：HTTP `debug:true` → `/health` 200；`debug:false` → `Failed to fetch`（丢弃）；
>   WS `debug:true` + `otp.` 子协议 → onopen；`debug:false` → onerror。
> - 注意：WS 必须带 **OTP 子协议**（`new WebSocket(url, ['otp.' + otp])`），只加 `?otp=` 不算（`extractOtp`
>   也支持 query，但项目里统一用子协议）。
>
> **已知坑**：
> - Live Reload 下 `Capacitor.convertFileSrc()` 不可用（`_capacitor_file_` 请求会打到 Vite → 404）。
> - 切换 USB 模式会丢 `adb reverse`，HMR WebSocket 也会断；重新 reverse 后刷新页面即可。
> - 其它插件（statusbar/fullscreen/rotation/keyboard/system-info/basics/foreground-state/force-dark/
>   webview-to-edge/native-files/cpp-bridge）不依赖 origin，**Live Reload 下无需改动**。
>
> **下一步**：验证 data-channel 0.1.2 的 iOS CI（push 后自动跑）；以后需要热更新时按上面的命令做即可。
> ## ⏸ 2026-11-08 23:15 收尾状态（历史记录）
>
> **仓库已重建（重要）**：旧的 `cinic <cinic@users.noreply.github.com>` 作者身份会让 GitHub 把
> 真实用户 `cinic` 算成协作者，因此已删除旧库/旧历史重来：
> - 新仓库：`Cinichengbuweikong/tmp_testionic`，**单提交** `756610f`「初始提交：12 个插件 + 验证 App」；
> - 作者：`Cinichengbuweikong <Cinichengbuweikong@users.noreply.github.com>`（仓库级 git config 已设好）；
> - 贡献者列表现在只有本人；新库第一次 iOS CI **success**；
> - 旧 `.git`（98 个提交）备份在 `D:\projs\testionic-old-git-backup.zip`（确认不要可删）；
> - 本地设备/模拟器均已部署过、`main` 跟踪 `origin/main`。
>
> **插件已清理为纯源码**：`plugins/` 从 288MB 降到 **0.9MB / 197 文件**（删了 node_modules、dist、
> android/build、.cxx、.gradle）。以后在这个仓库构建前先跑 `npm run build:plugins`（或直接
> `npm run android:deploy`，它第一步就是它）。12/12 已用"干净副本"验证过能 `npm install && npm run build`。
>
> **新增** `npm run audit:plugins`（`scripts/audit-plugins.mjs`）：检查 jsName 三端一致性、方法数、
> 包字段、陈旧引用。当前输出「未发现结构性问题」（`addListener`/`removeAllListeners` 已排除，
> 它们不需要原生 `@PluginMethod`）。
>
> **环境坑（已确认）**：本机默认 `JAVA_HOME` 是 **JDK 17**，而项目 `compileOptions = VERSION_21`
> （app 与所有插件模块）→ 终端里 `gradlew installDebug` 会因 `invalid source release: 21` 失败；
> Android Studio 用自带 **JBR 21**（`C:\Program Files\Android\Android Studio\jbr`）所以能跑。
> `scripts/deploy-android.ps1` 已显式设置该 JAVA_HOME。查看 Gradle 实际用哪个 JDK：
> `cd android; .\gradlew.bat --version` 的 `Launcher JVM / Daemon JVM` 两行。
>
> **明天第一件事：Live Reload（热更新）实验**（用户想做，方案已调研完，未实施）：
> - 方式 A：`npx cap run android -l --host localhost --port 5173`（CLI 只改平台 assets 里的 config、退出还原）；
>   配合 `adb reverse tcp:5173 tcp:5173` 最稳（不用同 Wi-Fi、不用改 network_security_config）；
>   方式 B：根 `capacitor.config.json` 加 `server.url` + `cleartext: true`（**上线前必须删**）。
> - **4 个坑**：① Vite 默认只监听 127.0.0.1，要 `--host` 或 `adb reverse`；
>   ② **data-channel 的 Origin 白名单**（`capacitor://localhost`/`https://localhost`/`http://localhost`/
>   `ionic://localhost`）不认识 `http://localhost:5173`（带端口）→ 会**静默断开**，dev 时要加；
>   ③ `Capacitor.convertFileSrc()` 在 live reload 下失效（`_capacitor_file_` 由原生本地服务提供，
>   请求会打到 Vite → 404）；④ iOS 需要 Mac（无 Mac 只能 CI，CI 连不到你的 dev server）。
> - 可选交付：`scripts/dev-live.ps1`（自动 adb reverse + 起 vite + cap run + 退出还原配置）。
>
> **可选清理**：`.perf/`（7.1MB 基准脚本/截图，已 gitignore）；`D:\projs\testionic-old-git-backup.zip`。
>
> **待办/暂缓**（更早列的）：native-files 权限 API 已并入（已完成）；`capacitor-cpp-bridge` 已完成；
> 更早的「要不要给 Android 加 CI job」用户决定**不加**（本地真机+模拟器足够）。

> ## ⏸ 2026-11-07 23:30 收尾状态（历史记录）
>
> **已推送且本地与 origin 同步**（`HEAD == origin/main == b05d3dc`）：
> - data-channel 6 轮加固（职责收敛 → OTP + 双 server → 逐条 OTP + 协议 → 批量 OTP →
>   性能基准 + 安全讨论 → 可续传上传 + 结构化错误），真机/模拟器/iOS CI 全绿；
> - `capacitor-app-state` → **`capacitor-foreground-state`** 改名（包名/目录/jsName/类名全同步），三端验证通过；
> - native-statusbar 两个 bug：旋转回竖屏色块只有一半高（0.3.0）、与 webview-to-edge 同用色块挡住内容（0.4.0）；
> - **native-files 0.2.0**：URI → **真实绝对路径**（可拼路径遍历）、`checkAllFilesAccess` /
>   `requestAllFilesAccess`、`getAppDirectories`、`getInfo`、`createFolder(完整路径)`、
>   `deleteFolder(recursive)`、`listFolder(recursive)`、`renameFolder` 返回新路径；
>   Android 编译通过 + 真机/模拟器公共目录全链路实测通过 + autotest/smoke PASS。
>
> **明天第一件事：验证 `b05d3dc` 的 iOS CI（还没跑完就被停了）**
> - Swift 编译已通过（`374ab92` 的 step 10 success）——关键修复是：**iOS 上
>   `.withSecurityScope` 不可用（macOS 专用）**，改用 `bookmarkData(options: [])`；
> - 上次失败在 step 11 autotest：自检目录用了 Android 专有的 `appExternalFilesDir`，
>   已改为 `appExternalFilesDir → appFilesDir → documents → applicationSupport` 回退（`b05d3dc`）；
> - 若 CI 还红：workflow 现在会把首条 `error:` 抛成 **annotation**，直接抓 job 页面 HTML 的
>   `Build app for simulator` 片段就能看到（公开页面可读，无需登录）。
>
> **网络注意**：github.com 直连不稳（`api.github.com` 正常）。push 用
> `git -c http.version=HTTP/1.1 push origin main` 并**重试几轮**；CI 池子可能抢不到，失败就重跑。
>
> **未提交/未纳入**：`src/assets/base.css`（更早的改动）、`.perf/`（基准脚本 + 截图，临时验证用）。
> **iOS 端尚未人工验证**：文件/文件夹选择器、security-scoped bookmark 持久化（无 Mac，只能靠 CI + 用户真机试）。

---

## 1. 项目概览

- 项目：`D:\projs\testionic`（学习用 demo，可以随便造）
- 技术栈：**Capacitor 8.6.0** + **Vue 2.7** + vue-router 3（hash 模式）+ **Vite 3** + `@vitejs/plugin-legacy`（targets IE11）
- 平台：Android（真机 + 模拟器）+ iOS（只有 CI，无 Mac）+ Web
- `capacitor.config.json`：appId `com.myapp.testionic`，webDir `dist`（**没有** plugins 配置，保持默认）
- Git 仓库：https://github.com/Cinichengbuweikong/tmp_testionic.git（公开），分支 `main`
- 目标：用户是 Web 前端（Vue2），不会原生开发；由 AI 代写原生插件。**插件日后要拿走复用**，所以每个插件都是独立 npm 包（`file:` 引用）

## 2. 本机开发环境（2026-11-02 实测）

| 项 | 值 |
| --- | --- |
| 系统 | Windows 10（19045） |
| 磁盘 | C: 约 57GB 可用；D: 约 34GB 可用 |
| Node / npm | v24.17.0 / 11.13.0（无 pnpm/yarn）；registry = 官方 npmjs |
| **JAVA_HOME** | ⚠️ Temurin **JDK 17**（不满足 Capacitor 8） |
| **JDK 21** | Android Studio 自带 JBR 21.0.10：`C:\Program Files\Android\Android Studio\jbr` |
| Android Studio | Otter 代（Capacitor 8 要求 AS ≥ 2025.2.1） |
| Android SDK | `C:\Users\cinic\AppData\Local\Android\Sdk`（`ANDROID_HOME` 已设置） |
| SDK Platform | android-36、android-36.1；build-tools 35.0.0 / 36.0.0 / 36.1.0 / 37.0.0 |
| Gradle | wrapper 8.14.3（腾讯云镜像下载）；AGP 8.13.0；compileSdk 36 / minSdk 24 / targetSdk 36 |
| adb | 37.0.0；Git 2.45.2 |
| iOS | **没有 Mac**。编译验证全部走 GitHub Actions 的 `macos-26` runner（Xcode 26） |

### ⚠️ 最大的坑：JDK 版本

`android/app/capacitor.build.gradle` 由 Capacitor 生成，写死 Java 21：

```gradle
sourceCompatibility JavaVersion.VERSION_21
targetCompatibility JavaVersion.VERSION_21
```

系统 `JAVA_HOME` 是 JDK 17，直接跑 gradlew 会失败。两种办法：

1. 用脚本：`npm run android:deploy`（内部自动把 JAVA_HOME 指向 JBR 21）；
2. 手动：`$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'` 后再跑 gradlew；
3. 或用 Android Studio 打开 `android/`（AS 默认用自己的 JBR 21）。

## 3. 设备与测试环境

| 设备 | 信息 | 备注 |
| --- | --- | --- |
| 真机 | Redmi K30S Ultra（M2007J3SC），Android 10 / API 29 / **MIUI 12**，arm64-v8a，serial `32e93a6d` | WebView **87.0.4280.101**；系统默认深色模式 |
| 模拟器 | AVD `Pixel_6`（android-36 default x86_64），serial `emulator-5554` | WebView **133.0.6943.137**；软件渲染 |

- 写这份文档时**真机未连接**（`adb devices` 为空）；恢复工作前先让用户插上手机并确认「开发者选项 → USB 安装」已放行（否则 `adb install` 报 `INSTALL_FAILED_USER_RESTRICTED`）。
- 模拟器无窗口启动（当前没在跑）：
  ```powershell
  emulator -avd Pixel_6 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
  ```
  首启会弹 “System UI isn't responding”，点 Wait；启动约 1 分钟。
- 手机 App 包名 `com.myapp.testionic`；曾被卸载后重装为 debug 包。release 签名文件在 `android/key/key`（密码未知，**已 gitignore**）。

## 4. 目录结构速览

```
D:\projs\testionic
├─ plugins/                      # 7 个自研插件（每个都是独立 npm 包）
│  ├─ capacitor-fullscreen/         # 沉浸式全屏：隐藏状态栏+导航栏（带状态事件）
│  ├─ capacitor-keyboard-avoiding/  # 键盘避让：keyboardChanged 事件 + 可选自动避让
│  ├─ capacitor-data-channel/      # 二进制数据通道：本地 HTTP Server（127.0.0.1+token）大文件高速传输
│  ├─ capacitor-native-basics/      # 参考实现：echo / showToast / getDeviceInfo
│  ├─ capacitor-native-statusbar/   # 状态栏背景色 + 图标亮暗
│  ├─ capacitor-native-rotation/   # 屏幕方向：监听 + 主动锁定横屏/竖屏
│  ├─ capacitor-set-force-dark/     # WebView forceDark 修复 + 主题注入
│  ├─ capacitor-system-info/        # 屏幕/系统/安全区等 13 个独立只读 API
│  ├─ capacitor-webview-to-edge/    # WebView 铺满整屏 + 安全区 insets
│  └─ README.md                     # 插件集合说明 + CLI 识别规则
├─ src/
│  ├─ main.js                     # VITE_AUTOTEST=1 时自动跳 /plugin-test?autotest=1
│  ├─ router/index.js             # hash 路由：/ /about /plugin-test
│  ├─ views/PluginTestView.vue    # 「插件自检」页：9 个插件的按钮 + autotest 逻辑
│  └─ assets/base.css             # 深色配色（⚠️ 有未提交改动，见第 10 节）
├─ scripts/
│  ├─ deploy-android.ps1          # 一键构建+安装+启动（JBR21，可 -Device 指定设备）
│  ├─ verify-android.ps1          # 真机+模拟器 CDP 冒烟测试（PASS/FAIL 汇总）
│  ├─ build-plugins.mjs           # 逐个构建 plugins/ 下的插件（本地与 CI 共用）
│  ├─ webview-eval.mjs            # 通过 WebView DevTools 协议在页面里执行 JS
│  ├─ smoke-test.js               # 冒烟脚本：DOM 点按钮、读 <pre> 结果并断言
│  └─ verify-ios.mjs              # iOS 静态检查（本机可跑，不能替代 Xcode）
├─ android/                       # Capacitor Android 工程（国内 Maven 镜像已配）
├─ ios/                           # Capacitor iOS 工程（SPM：CapApp-SPM）
├─ .github/workflows/ios.yml      # iOS CI：编译 + 模拟器运行 + autotest 断言
├─ docs/
│  ├─ development-environment.md  # 环境档案（更详细）
│  └─ ios-without-mac.md          # 无 Mac 做 iOS 的方案 + 云 Mac 报价
└─ capacitor.config.json          # 只有 appId/appName/webDir，无插件配置
```

## 5. 插件清单与 API 速查

### 5.1 capacitor-native-basics（参考实现）

- Android 包 `com.testionic.nativebasics`；iOS 包 `CapacitorNativeBasics`
- API：`echo({value})`、`showToast({text, duration?})`、`getDeviceInfo()`
- `DeviceInfo`：`manufacturer / model / osVersion / platform('android'|'ios'|'web') / sdkInt?(仅Android) / isEmulator`
- 作用：验证插件骨架、作为新插件复制的模板

### 5.2 capacitor-native-statusbar（状态栏样式）

- Android 包 `com.testionic.nativestatusbar`；iOS 包 `CapacitorNativeStatusbar`
- API：
  - `setBackgroundColor({ color })`：支持 `#RGB / #RRGGBB / #AARRGGBB(ARGB顺序) / transparent`
  - `setStyle({ style: 'light' | 'dark' | 'default' })`
  - `getInfo()` → `{ color, style, height, usingOverlay }`
- **核心实现**（Android）：`window.setStatusBarColor()` 在 Android 15+ 与 MIUI 上无效，
  所以在 DecorView 顶部**自绘等高色块**：`topMargin = -decorView.paddingTop` + `setClipToPadding(false)` + `bringToFront()`；
  有色块时**不要再调** `setStatusBarColor`（MIUI 会因此重算图标颜色）。
- **图标亮暗**：`WindowInsetsControllerCompat` + 旧 `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR` 双通道 + 350ms 补一次；
  **MIUI 12 会按背景色自动对比度**（蓝底→白图标、白底→深图标），显式 light/dark 会被覆盖（已实测，
  官方插件同样无效，0/5320 像素差异对比）；模拟器（非 MIUI）上正常生效。
- iOS：自绘 UIView（高度 = safeArea top），`CAPBridgeViewController.setStatusBarStyle()`。
- 未调用 `setStyle` 前不主动改样式（`styleApplied` 门控）。
- **2026-11-07 修复：与 webview-to-edge 一起用时色块挡住内容（Android 0.4.0）**
  - 现象：先设状态栏颜色、再 `WebViewToEdge.setEnabled(true)`（DecorView padding 清零、内容铺到
    状态栏下面）后，状态栏色块仍盖住那 128px，页面内容被挡（用户描述为"状态栏没隐藏、还盖住内容"）。
  - 修复：新增 `isStatusBarCoveredByContent()`（`insetTop > 0 && paddingTop == 0` → 内容自己画；
    `insetTop == 0` → 全屏/无状态栏）。命中时**默认不画色块**（`removeOverlay`），
    但**保留 OnGlobalLayoutListener**，这样关掉 webview-to-edge 后能自动把色块重建回来。
    新增 `setBackgroundColor({ overlayInEdgeToEdge: true })` 显式要求"盖在内容上"（用半透明色）。
    `getInfo()` 增加 `edgeToEdge` / `overlayInEdgeToEdge`。
  - 坑：第一版把监听器一起在 `removeOverlay()` 里摘掉 → 关掉 to-edge 后色块不会恢复（实测 C 场景失败），
    修正为"监听器生命周期跟随颜色，而不是跟随色块"。
  - 验证（像素量取）：模拟器 128px → **无红色（内容显示）** → 128px → 可重复；真机 K30S 90 → 无红色 → 90；
    autotest（设备/模拟器）ok:true、smoke PASS、iOS CI 绿（iOS 侧逻辑不变）。
  - iOS 说明：Capacitor 在 iOS 默认就是内容铺满（contentInsetAdjustmentBehavior=.never），
    色块=给状态栏上色属预期，无需此逻辑（README 已注明）。
- **2026-11-07 修复：旋转后色块只有一半高（Android 0.3.0）**
  - 现象（模拟器 Pixel 6 / Android 16，竖屏状态栏 128px、横屏 63px）：竖屏正常 → 横屏正常 →
    **转回竖屏时色块 rows 65..127（63px）**，只盖住下半截。
  - 根因：`handleOnConfigurationChanged` 触发时 DecorView 还没重新布局，
    `ViewCompat.getRootWindowInsets()` 返回**旧方向** Insets（63px），而 `topMargin=-paddingTop`
    用的是**新**方向（128px）→ 色块落在 128-63=65..128。
  - 修复：几何计算挪到 `ViewTreeObserver.OnGlobalLayoutListener`（布局后 padding/Insets 已更新），
    加"值未变不 setLayoutParams"去重防死循环；配置回调仍保留用于重新套用颜色/样式。
  - 验证（修复后）：模拟器 128/63/**128**/128（两轮）+ 横屏改色再转回（绿 63→128）✓；
    真机 K30S 90/90/90（该机型横竖屏同高，无回归）✓。
  - **iOS 无需修**：色块是 Auto Layout（`bottomAnchor == safeAreaLayoutGuide.topAnchor`），
    旋转由安全区自动驱动，没有缓存高度的问题。
  - 复现/验证的 adb 命令与量像素脚本见插件 README「旋转 / 横竖屏切换」一节。

### 5.3 capacitor-set-force-dark（WebView forceDark 修复）

- Android 包 `com.testionic.setforcedark`；iOS 包 `CapacitorSetForceDark`；jsName `SetForceDark`
- **注意：它不控制系统的深色模式**，只管 WebView 里的页面显示。
- API：
  - `setTheme({ theme: 'system' | 'light' | 'dark' })` → `{ requestedTheme, isDark, isForced }`
  - `getTheme()`（副作用：会再注入一次页面）
  - `setForceDark({ enabled })`（默认 false = 关闭算法反色）
  - `getInfo()` → `{ ...主题, forceDarkEnabled, themeInjected, webViewUserAgent }`
  - `addListener('themeChanged', cb)`：`setTheme` 时、系统深浅色切换且 mode=system 时触发（Android 已去重：仅 isDark 变化才发）
- **行为**：注入脚本始终把 `<html data-theme="light|dark">` 与 `color-scheme` 写上（**亮色也是 data-theme="light"，不是没有属性**）；
  首次注入时把 `@media (prefers-color-scheme: dark)` 的规则 CSSOM 重写成 `html[data-theme="dark"]`（兼容不改 CSS 的老项目）。
- **根因**：MIUI 的 WebView 不报告 prefers-color-scheme（App Configuration 是 night，但 matchMedia dark = false），
  页面按亮色渲染后被 MIUI forceDark 反色 → 颜色全错。解法=关 forceDark + 注入主题。
- iOS：`overrideUserInterfaceStyle`（真会翻转 prefers-color-scheme）；没有 forceDark，`setForceDark` 为空操作。

### 5.4 capacitor-webview-to-edge（WebView 铺满整屏）

- Android 包 `com.testionic.webviewtoedge`；iOS 包 `CapacitorWebviewToEdge`；jsName `WebViewToEdge`
- **不是隐藏系统栏的“全屏模式”**，只是内容延伸到状态栏/导航栏下面；默认关闭，纯 API 控制（无需改 config）。
- API：`enable() / disable() / setEnabled({enabled}) / isEnabled() / getInsets() / addListener('insetsChanged', cb)`
- `getInsets()` → `{ top, right, bottom, left }`（CSS px）；同时自动注入 CSS 变量 `--safe-area-inset-*`。
- Android：接管 DecorView 的 `OnApplyWindowInsetsListener`（后装覆盖 Capacitor `SystemBars` 的）；
  enable → padding 全 0（键盘弹出时保留 IME 底部）；disable → 恢复系统栏 padding；
  系统栏 insets 清零后回传（所以 **Android 上 `env(safe-area-inset-*)` 恒为 0**，统一用插件变量）。
- iOS：Capacitor 默认已贴边（`contentInsetAdjustmentBehavior = .never`）；enable=.never、disable=.automatic；安全区来自 `safeAreaInsets`。
- 实测：真机 WebView bounds `[0,90]` → enable `[0,0]` → disable `[0,90]`；模拟器 `[0,128]` → `[0,0][1080,2400]`，insets top 49 / bottom 48。

### 5.5 capacitor-system-info（系统与硬件信息）

- Android 包 `com.testionic.systeminfo`；iOS 包 `CapacitorSystemInfo`；jsName `SystemInfo`
- **设计原则（用户明确要求）**：**13 个独立只读 API，问什么收集什么、互不影响**，不搞一个大 `getInfo()`。
- API：`getScreenInfo / getViewport / getSafeAreaInsets / getStatusBarInfo / getNavigationBarInfo /
  getCutoutInfo / getSystemInfo / getWebViewInfo / getMemoryInfo / getStorageInfo / getBatteryInfo /
  getNetworkInfo / getAppInfo`，全部无参数、返回 Promise。
- 单位约定：`px`=物理像素；`css`=CSS px（Android dp / iOS pt）；`css × density = px`。
  `getViewport().css` 就是 CSS 里 `100%` 的大小。
- **关键实现（Android）**：
  - API 30+ 从 `WindowManager.getMaximumWindowMetrics()` 拿系统栏真实值（显示层数据，
    绕开 webview-to-edge 常驻的 insets 监听器）；
  - API 29 **底部导航栏用 `getWindowVisibleDisplayFrame()` 反推**——MIUI 手势模式下
    insets 假报三键 130px（stable/transient 都是），但窗口实际铺满（dumpsys `mStable=[0,90][1080,2400]`、
    to-edge 收到 `{top:33,bottom:0}` 三方印证）；反推后正确报 0；
  - 导航模式优先读 `settings navigation_mode`（MIUI 读不到）→ 实测占用推断；
  - 状态栏高度含挖孔：K30S 挖孔 `[0,141]×[0,90]` → 状态栏 90px/32.73css。
- 实测数据（2026-11-03）：
  | 字段 | 真机 K30S（MIUI12/API29/WebView87） | 模拟器 Pixel_6（API36/WebView133） |
  | --- | --- | --- |
  | screen | 1080×2400 px / 392.73×872.73 css；density 2.75 (440dpi) | 1080×2400 px / 411.43×914.29 css；density 2.625 (420dpi) |
  | viewport（默认） | 1080×2310 px / 840 css（只让出状态栏） | 1080×2146 px / 817.52 css（让出状态栏+三键） |
  | statusBar | 90px / 32.73css | 128px / 48.76css |
  | navigationBar | 0（手势模式） | 126px / 48css，three_button |
  | safeArea | top 32.73 / bottom 0（css；px 90/0） | top 48.76 / bottom 48（css；px 128/126） |
  | refreshRate | 90（用户档位，读 MIUI `user_refresh_rate`；max 144） | 60（max 60） |
- iOS：`window.safeAreaInsets`（状态栏高度=safeArea.top） / `NWPathMonitor`（1.5s 超时兜底）/
  `os_proc_available_memory()` / `utsname` 硬件型号；导航栏=Home Indicator；电池模拟器=-1；
  WebView 版本取 UA `Version/x.y`。
- 两个 Capacitor 8 API 坑：`Plugin.getConfig()` 只含**插件自己的配置块**（`plugins.Xxx` 节点），
  读不到全局 appName → 从 application label 读；`Plugin.getAppId()` 是基类自带方法。
- Android 权限：仅 `ACCESS_NETWORK_STATE`（普通权限，无弹窗）。

### 5.6 capacitor-native-rotation（屏幕方向：监听 + 主动控制）

- Android 包 `com.testionic.rotation`；iOS 包 `CapacitorNativeRotation`；jsName `Rotation`；
  npm 包名 `capacitor-native-rotation`（**0.2.0 从 capacitor-rotation-listener 改名**）。
- API：
  - 监听：`getRotation()` → `RotationState { orientation, rotationDegrees, isPortrait, isLandscape }`；
    `addListener('rotationChanged', cb)`（返回 handle，`handle.remove()` 解除）；`removeAllListeners()`（桥内置）。
  - 控制：`lock({ orientation: 'landscape'|'portrait'|'auto' })`、`unlock()`、`getLock()`
    → `OrientationLockState { orientation, locked }`。**视频「全屏播放」= lock(landscape) + Fullscreen.enable()**。
- **Android**：事件源 `Plugin.handleOnConfigurationChanged`（要求 MainActivity 的 `android:configChanges`
  含 orientation|screenSize，Capacitor 模板默认已含）；方向锁定用 `Activity.setRequestedOrientation`
  （landscape→SENSOR_LANDSCAPE 支持左右两方向 / portrait→SENSOR_PORTRAIT / auto→UNSPECIFIED），
  锁定引起的旋转同样走配置变化 → 自动派发 rotationChanged。
- **iOS**：`UIDevice.orientationDidChangeNotification`（load beginGenerating、deinit 注销）；
  orientation 用 WebView 宽高比、角度由 UIDevice 映射；锁定改
  `CAPBridgeViewController.supportedOrientations`（public 可写）+ iOS16 的
  `setNeedsUpdateOfSupportedInterfaceOrientations()` + `requestGeometryUpdate()`，
  iOS15 用 `UIDevice` KVC + `attemptRotationToDeviceOrientation()`；
  解锁时把 supportedOrientations 还原成进入前记录值。不用 iOS 26 已 obsoleted 的 `interfaceOrientation`。
- **Web**：`screen.orientation` change 派发事件；锁定用 `screen.orientation.lock()/unlock()`
  （浏览器要求页面全屏且支持该 API，否则 reject）。
- 实测（2026-11-04 真机 MIUI 12）：`lock(landscape)` → 返回 locked:true、屏幕真实横屏
  （截图 2400×1080、getRotation→landscape/90）；`unlock()` → 回竖屏 portrait/0。
  旋转事件实测（模拟器 + 真机）：`landscape/90` ↔ `portrait/0`，解除监听后不再触发。
- 坑：`JSObject.getInt(String,int)` 不存在（org.json 只有单参），要用 `optInt(name, default)`。

### 5.6b capacitor-native-statusbar（0.2.0 修复：暗色模式白色失效）

- **现象**：MIUI 12 暗色模式下 `setBackgroundColor('#FFFFFF')` 后 `getInfo()` 返回 #FFFFFFFF、
  `usingOverlay:true`，但状态栏实际像素是 **rgb(27,27,27) 近黑**；橙色 #FF5722 也被压成 #D83200。
- **根因**：Android Q+ 的 **force dark**（暗色模式对 App 内 View 智能反色），MIUI 12 对第三方 App 生效；
  色块是普通 View，被系统反色。与 WebView 的 forceDark 是两回事（作用在原生 View 树）。
- **修复**：给自绘色块单独关闭 force dark（`overlayView.setForceDarkAllowed(false)`，API 29+），
  不动全局主题/窗口。**不要**用 `android:forceDarkAllowed="false"` 关整个 App。
- **实测**：修复后 白色→rgb(228,232,227)（近白，取样含图标）、橙色→**rgb(255,87,34) 精确 #FF5722** ✅

### 5.7 capacitor-fullscreen（沉浸式全屏）

- Android 包 `com.testionic.fullscreen`；iOS 包 `CapacitorFullscreen`；jsName `Fullscreen`
- API：`enable / disable / setFullscreen({enabled}) / toggle / isFullscreen()`（返回
  `FullscreenState { fullscreen, statusBarHidden, navigationBarHidden }`）；
  `addListener('fullscreenChanged')`、`handle.remove()` 解除（桥内置）。
- **与 webview-to-edge 的区别**：to-edge 系统栏仍可见（内容延伸下去）；本插件系统栏**完全隐藏**。
- **Android**：`WindowInsetsControllerCompat.hide(systemBars())` +
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`（immersive sticky：边缘滑动可临时唤出系统栏）。
  系统栏隐藏 → insets=0 → WebView padding 自动归零铺满，插件**不需要手动改布局**；
  退出后 padding 自动还原。与 webview-to-edge 的 insets 监听兼容。
- **iOS**：状态栏走 Capacitor 公开 API `bridge.statusBarVisible`；Home Indicator 无法直接隐藏，
  只能 `prefersHomeIndicatorAutoHidden` —— 借内置 `SystemBars` 插件的 `hide:`/`show:`（@objc
  运行时调用 + responds 检查，拿不到插件时自动降级为只隐藏状态栏）。
- **Web**：浏览器 Fullscreen API（需用户手势），`fullscreenchange` 事件同构派发。
- 实测（2026-11-03）：真机 `enable()` 后截图——状态栏与导航栏完全消失、内容从屏幕顶边渲染；
  `disable()` 后完整恢复；冒烟测试双端全 PASS（进入/退出/读取/监听/取消）。
- 坑：`androidx.core:core` 在 `capacitor-android` 里是 `implementation` 依赖**不传递**，
  插件必须显式声明 `implementation "androidx.core:core:$androidxCoreVersion"`。

### 5.8 capacitor-keyboard-avoiding（键盘避让）

- Android 包 `com.testionic.keyboardavoiding`；iOS 包 `CapacitorKeyboardAvoiding`；
  jsName `KeyboardAvoiding`；npm 包名 `capacitor-keyboard-avoiding`。
- API：`enable / disable / setEnabled({enabled}) / isEnabled() / getState()` →
  `KeyboardState { visible, height(css), heightPx, viewportHeight(css), viewportHeightPx,
  autoAvoid, animationDuration }`；`addListener('keyboardChanged')`、`handle.remove()` 解除。
- **核心设计**：
  - 纯监听（默认）：只派发事件，不改布局；
  - 自动避让（enable）：Android **补差**（不盲加）、iOS **contentInset**（WKWebView 默认不避让）。
- **Android 实测关键结论**：真机 MIUI 12 键盘弹出后 WebView 2310px→1382px、innerHeight 840→503css
  （键盘 928px/337css）——**系统已自动避让**，所以 autoAvoid 必须"补差"：
  用 `getRootWindowInsets().getInsets(Type.ime())` 读高度（**不注册** listener，避免与
  webview-to-edge/SystemBars 抢），用 WebView `bottomMargin` 补齐 deficit（反推原始底部计算，幂等不抖动）。
  键盘状态靠 `ViewTreeObserver.OnGlobalLayoutListener` 触发检测。
- **iOS**：`keyboardWillChangeFrameNotification` / `keyboardWillHideNotification`，
  键盘高度 = WebView 底部与 keyboardFrameEnd 顶边的重叠；autoAvoid 用
  `scrollView.contentInset.bottom`（关闭时还原基线 inset）；iOS point = CSS px。
- **Web**：`visualViewport` 差值计算键盘高度（<80px 视为浏览器 UI 折叠），同构派发事件。
- 实测（2026-11-04 真机）：键盘弹出事件 `{visible:true, height:337.45css/928px, viewportHeight:502.55css}`；
  键盘已弹出时 enable → innerHeight 保持 503（**无双重缩小**）✅；关闭键盘事件
  `{visible:false, height:0, viewportHeight:840}`、事件总数 2；disable 恢复 autoAvoid:false。
- 坑：Android 模拟器默认"硬件键盘"模式下软键盘不弹（需 `settings put secure
  show_ime_with_hard_keyboard 1`，且 AOSP 模拟器仍可能不稳定）→ 键盘相关验证以**真机**为准。
- **重要（用户实测疑问）**：真机上 enable/disable 看起来"都避让了"是**正常现象**——
  ① Capacitor 模板 `windowSoftInputMode=adjustResize` 让**系统自己缩窗口**；
  ② `webview-to-edge` 又会把 IME 高度设成 DecorView 底部 padding。
  本插件的 disable 只是"不再额外加"，撤销不了这两层；要彻底关掉得改宿主
  `adjustNothing` 并同步调整其它插件（不属于本插件职责）。README 已写明，
  并给出验证插件是否工作的方法（看 autoAvoid 字段 / 事件的 height+viewportHeight）。

### 5.9 capacitor-data-channel（原生↔WebView 二进制数据通道）

- 背景：Capacitor 桥是 JSON 序列化，复杂类型必须 base64（官方注释自证：
  "FormData, Blob, ArrayBuffer ... only directly supported on web"）→ 大文件传输代价极高。
- 方案：原生侧启动**只监听 127.0.0.1 的 HTTP Server**（随机端口 + 32 字节 Bearer token），
  WebView 用 `fetch` / `<video>` / `<img>` 直接收发原始二进制，**完全绕开桥**。
- 两端各自手写 HTTP 实现（零第三方依赖）：Android `DataChannelServer.java`（ServerSocket+线程池）、
  iOS `DataChannelServer.swift`（NWListener + 状态机）；**协议完全一致**：
  `/health`、`PUT|POST /upload`（流式、`append=1` 续传）、`GET /download`（支持 Range→206）、
  `OPTIONS` 预检（**2026-11-07 起只保留这三个路由**，见下）。
- **2026-11-07 职责收敛**（用户要求："各自只负责各自的部分"）：
  - **删除了 `checkAllFilesAccess` / `requestAllFilesAccess`**（权限 API 归 `capacitor-native-files`）；
  - **删除了 `/info`、`/list`、`DELETE /file` 路由**（文件管理归 native-files）；
  - 测试页删掉「检查/申请存储权限」「列出文件」按钮；autotest 断言这三个路由**已 404**；
  - `startHttp()` 失败时的错误信息改为提示"用 capacitor-native-files 授权"。
- **2026-11-07 OTP 鉴权改造 + 双 server 分离**（用户主导设计，核心安全升级）：
  - **两个独立 server**：`startHttp/stopHttp`（HTTP）与 `startWebSocket/stopWebSocket`（WS），
    各自随机端口、各自 OTP 表、各自连接限流；状态分开读：`getHttpInfo()` / `getWsInfo()`
    （停止时也返回结构体，`running: false`）；
  - **OTP 替代长期 token**：OTP 通过 **Capacitor 桥**下发（`createHttpOtp` / `createWsOtp`），
    桥是进程内 `postMessage`，**抓包看不到生成过程**；抓包只能看到已作废的 OTP；
  - **OTP 规则**：一次性（默认）/ reusable（**仅 scope=read**，媒体用）；**写不允许 reusable**；
    **无 TTL**（= server 生命周期）；HTTP OTP 的 `path` **必填且精确匹配**；WS OTP 只一次性；
    OTP 固定 **43 字符**（32 字节随机 base64url）；上限 256；HTTP 校验失败统一 404；
  - **必须 revoke**（README 有加粗警告）：reusable OTP 用完不 revoke → server 运行期间永久有效；
    提供 `revokeOtp` / `revokeAllOtps({server})`；测试页/autotest 覆盖 revoke 前后行为；
  - **传递方式**：HTTP `Authorization: OTP <otp>`（普通）或 `?otp=`（媒体 URL）；
  - **WS 逐条 OTP（用户要求"每条消息一个新 OTP"）**：WebSocket 消息**没有头**，所以自定义协议
    `[OTP(43 字符 ASCII)][0x00][payload 原始二进制]`；按**第一个 0x00** 分割（OTP 是 base64url
    永不含 0x00，payload 可含 0x00）；错误消息为 `[0x00][错误文本 UTF-8]`；
    - **Client→Server**：每条消息前 `createWsOtp()`；Server 校验失败 → 丢弃 + 回错误消息；
    - **Server→Client**：Server 每条消息生成一次性 nonce 放进消息前缀；JS 必须调
      **`validateAndConsumeOTP`** 验证并消费（否则表会堆积，满 256 时回 `too many pending nonces`）；
    - 为什么反向验证：若 Server 通过桥"提前通知" OTP，桥事件与 WS 消息是两条通道、顺序无保证（竞态）；
    - **helper（TS 导出）**：`encodeWsMessage(payload)` / `decodeWsMessage(data)`，内部自动
      `createWsOtp` / `validateAndConsumeOTP`；`core.ts` 单独放 `registerPlugin` 避免循环依赖；
  - **两级连接限流**：未认证 4（防扫描/DoS）/ 已认证 16（`maxConnections` 可调），HTTP/WS 分开计数；
  - **威胁模型**（README 有表）：防普通网页/非 root App/重放；**不防 root 设备与 WebView 被注入**；
  - **实测（真机 + autotest 全绿）**：重放写/读 OTP → 404；OTP 绑 A 请求 B → 404；read OTP 写 → 404；
    写+reusable → reject；穿越 path → reject；媒体 reusable 两次 200 → revoke 后 404；
    WS 握手重放 → 连接被拒；**WS 消息无 OTP → 错误消息**；**WS 消息重放 → 第二次错误消息**；
    **validateAndConsumeOTP 首次 true / 再次 false**；payload 中的 0x00 正确保留；
    6 个沉默连接 → pending 停在 4；stop 后 getHttpInfo/getWsInfo 各自 running:false；
  - 坑：Java `handleRequest` 的 catch 分支漏 `return`（编译报"缺少返回语句"）；
    `JSObject.put(key, null)` 会跳过 → 用 `org.json.JSONObject.NULL`；Swift 字典赋 nil 会**移除键** →
    用 `NSNull()`；DELETE 请求的 CORS 预检 `Allow-Methods` 不含 DELETE（路由已删，测试改用 GET）；
    `verify-ios.mjs` 原来只从 `index.ts` 找 `registerPlugin`，拆出 `core.ts` 后要两个文件都看。
- **2026-11-07 Capacitor 原生调用调度实测 + 批量 OTP**（用户要求先查清调度模型）：
  - **Android**：`Bridge` 里 `HandlerThread("CapacitorPlugins")` → `taskHandler.post()` 逐条执行
    → **所有插件调用串行在单线程**；响应 `webView.post(() -> evaluateJavascript)` → **主线程**；
  - **iOS**：`CapacitorBridge.dispatchQueue = DispatchQueue(label: "bridge")`（串行队列）→ 插件调用串行；
    响应 `DispatchQueue.main.async { evaluateJavaScript }` → **主线程**；
  - 因此：**JS 并发调原生 = 原生排队串行执行**，但往返延迟可流水（实测 100 次顺序 198ms=1.98ms/次，
    100 次并发 57ms=**0.57ms/次**，加速 3.44×）；
  - 实测性能（真机 Redmi K30S / Android 10，`/proc` 精确 CPU）：HTTP 上传 93MB/s、下载 322~333MB/s
    （峰值 PSS +46MB）；WS 1KB 4.76ms/条、300KB 15.89ms/条、1.5MB 37.4ms/条、流水线 49.3MB/s；
    峰值 PSS 均回落到基线（无泄漏）；400 次 OTP 调用期间 rAF 79→57fps（有可感知掉帧）；
  - **批量 OTP（0.4.0）**：`createWsOtps({count})` / `createHttpOtps({count,...})` /
    `validateAndConsumeOtps({otps})`；TS helper：`createWsOtpPool(n)`、`encodeWsMessageWithOtp`（同步）、
    `decodeWsMessagesBatch`（先验证后交付）。上限 256（活跃 + 请求数）。
    实测：300KB 顺序往返 **15.89 → 10.33ms/条（-35%）**，吞吐 63 → 97 条/s；autotest 覆盖
    "8 个不重复 / 批量拼包全部 ok / 批量 nonce 再验证全 false / HTTP 批量 3 个可用"；
  - 坑：`webview-eval.mjs` 在 CDP `ws.close()` 后 node 偶发不退出（会让采样/CI 卡死）→ 结尾加
    `process.exit(0)`；`.perf/` 基准脚本里"流水线 + 批量校验"版本有死锁（未修复，改用顺序池版本取证）。
- **2026-11-07 data-channel 上传协议加固（断点续传 + 结构化错误）**：
  - 旧 `append=1` 的问题：块传一半断开会把**半块字节留在目标文件**里，后续 resume 静默拼错；
    且中断时 `copyExactly` 抛异常直接进 catch（**不返回错误体、不打日志**）；
  - 新协议：分块写 `X.part`，`PUT ...&offset=<n>&total=<T>`（**append 前校验 .part 长度 == n**），
    `GET ...&status=1` 查进度，`POST ...&finalize=1&total=<T>[&sha256=]` 校验后原子 rename；
    `append=1` **废弃** → 400 `E_APPEND_DEPRECATED`；`/upload` 的所有方法都要求 write OTP；
  - 错误统一 `{error, code, size?}`：`E_INVALID_PATH/E_BAD_REQUEST/E_LENGTH_REQUIRED/
    E_PAYLOAD_TOO_LARGE/E_OFFSET_MISMATCH/E_NO_PART/E_SIZE_MISMATCH/E_HASH_MISMATCH/
    E_FINALIZE/E_IO/E_APPEND_DEPRECATED`；409 系列带服务端实际 `size`；
  - **中断处理**：对端断开（EOF/reset）只记日志不发响应（连接已不可用），`.part` 保留可续传；
    服务端写盘失败返回 500 `E_IO`；`handleRequest` 的 catch 现在会 `Log.e`；
  - **iOS 修掉静默失败**：`completeUpload` 的 `try?` 改为 `do/catch`（rename 失败返回 500 且不落地）；
    `writer.write(Data)`（非 throwing，失败抛 ObjC 异常）改为 `try writer.write(contentsOf:)`；
  - 实测（真机 autotest 全绿）：offset 错位 → 409 + size=204800；status.partSize 一致；
    finalize 总长不符 → 409 且目标 404；sha256 正确 200 / 错误 409 `E_HASH_MISMATCH`；
    `append=1` → 400；XHR 中断 → 目标不存在；手工 20MB 续传（8+8 → status 16MB → 续 4MB → finalize）
    324ms 下载 20MB ✓；smoke PASS；
  - 文档：README 新增「断点续传上传（offset + finalize）」+ 错误码链接；新增 **EXAMPLES.md**
    （上传/续传/下载/媒体/WS/错误处理/性能调优/陷阱）；INTRO 同步。
  - **坑（重要）**：
    - **autotest 用例必须幂等**：续传用例一开始写死 `selftest/resumable.bin`，上一轮 finalize 留下的
      最终文件让"finalize 前目标不存在"断言变成 200 → 改为每次 `Math.random()` 后缀的唯一路径；
    - **中断用例必须有兜底**：XHR abort 在部分平台可能不回调 `onabort/onerror/onload` → 整个 autotest
      会挂住、CI 报 "marker not found"；加 3s 兜底 resolve；
    - **CI 不能用固定 sleep 等 autotest**：iOS workflow 原来 `sleep 45`，用例变多后超时 → 改为轮询
      `PLUGIN_AUTOTEST_RESULT`（最长 180s，找到即继续）；
    - 排查技巧：CI 日志需要鉴权时，job summary（`Publish autotest result to job summary`）里的
      `::error::` 注解会出现在 **job 页面 HTML** 里，可以直接抓 HTML 判断是 "marker not found"
      还是 "reported a failure"。
- **2026-11-07 data-channel 文档定稿**：README 新增「Capacitor 调用调度模型」「安全讨论与最终结论」
  （token→OTP 动机、OTP 与 token 的等价/差异、保留逐条 OTP 的取舍、不做传输加密的 5 条推理、
  承诺/不承诺清单、提高门槛的优先项）「性能建议」；新增 [INTRO.md](plugins/capacitor-data-channel/INTRO.md)
  作为插件介绍（定位/对比表/30 秒上手/适合不适合/平台验证矩阵）。
- **2026-11-07 平台限制实测修正（重要，之前文档是错的）**：
  - 真机 Android 10（API 29）+ targetSdk 36 + `requestLegacyExternalStorage="true"`：
    `privateFlags` 含 `PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE` → **legacy 生效**；
  - 关键：`adb shell pm grant WRITE_EXTERNAL_STORAGE` **不会触发 mount namespace remount**
    （App 仍挂 `/mnt/runtime/default/emulated`，gid=1015, mask=6 → 读写都 `EACCES`）；
  - **走系统权限弹窗授权后**：`/storage/emulated` 挂载变 `gid=9997, mask=7`（write 视图）→
    **读 `/sdcard/Download`、写 `/sdcard/Download/...` 全部成功**（已实测）；
  - 所以：**Android 10 及以下可以读写公共目录**（需真实授权），之前"targetSdk>=30 时 scoped storage
    强制"的结论是**错误**的；Android 11+ 才需要 MANAGE_EXTERNAL_STORAGE；
  - App 专属外部目录（默认）**任何版本都免权限**。
- 宿主配置（必需，否则 WebView 拒绝访问明文回环）：
  - Android：`res/xml/network_security_config.xml` 只对 127.0.0.1/localhost 放行 cleartext +
    manifest `android:networkSecurityConfig`（另需 `requestLegacyExternalStorage="true"` 才能用公共目录）；
  - iOS：Info.plist `NSAppTransportSecurity → NSAllowsLocalNetworking = true`。
- 安全设计：只绑回环（iOS 用 `requiredInterfaceType = .loopback` + 连接来源校验）、随机端口、
  Origin 白名单（非法 Origin **静默断连**）、OTP 校验失败统一 **404**、两级连接限流、
  路径 canonical/软链校验、请求大小/头/超时限制、上传 `.part` 原子写、日志不记 OTP。
- **内存实测（真机，App 进程 RSS 峰值增量）**——README 已收录为示例：
  | 方式 | 105~110MB 文件 | 速度 | RSS 增量 |
  |---|---|---|---|
  | 分块 8MB×13 复用缓冲 + append（**首选**） | 920ms | 109 MB/s | +42.8MB |
  | File 直传（仅限磁盘文件场景） | 806ms | 131 MB/s | **+4.2MB** |
  | 一次性 `Uint8Array(100MB)` | 912ms | 110 MB/s | +275.3MB |
  | 整体生成后包装 `new File([buf])` | 698ms | 143 MB/s | 上传阶段 +0.6MB（生成峰值仍在） |
  → 真实场景主要是两个方向：**读**（用户选文件 → `Blob.slice` 分块加载进 WebView，
  别用 `arrayBuffer()` 一次性读）和**写**（WebView 生成数据 → **分块 + 复用 buffer** 写回磁盘，
  内存恒定 + `append` 断点续传）；File 直传只适用于"选文件后原样转发"这个少见场景；
  server 自身空闲开销仅 +0.5MB RSS / 0.13% CPU。
- 安全思路结论（用户提议"故意返回迷惑数据"）：**不做伪装**（拦不住真攻击者，还破坏 Range/CORS/
  调试），改为"默认拒绝 + 最小暴露面"（404 隐藏 + 静默断连），已实现并实测。
- 坑：JavaDoc 注释里手滑写 `*/` 导致注释提前结束、`android.util.Log` 没有 `debug()`
  （只有 `Log.d`）、NWConnection/NWListener 的队列必须是 **serial**（不能用 concurrent）。
- **2026-11-05 增强**（rootDir + WebSocket）：
  - `rootDir` 三种写法：不传 = App 专属外部目录 `/sdcard/Android/data/<pkg>/files/dataChannel`
    （无需权限）/ 相对路径 = App 私有目录 / 绝对路径 = 任意位置（受平台限制）；
  - **WebSocket server**：同端口 `/ws`（RFC6455 子集：帧解析/mask/分片重组/ping-pong/close，默认 echo），
    `ChannelInfo` 增加 `wsUrl`/`wsClients`/`webSocketEnabled`；`start({ wsEcho:false })` 关闭 echo。
  - **WS 实测**：1MB 往返 45ms、10MB 往返 181ms（55 MB/s）、64KB 帧每帧 1.7ms（588fps）、空闲 CPU 0%。
  - 坑：Swift 里 internal 方法参数用 private 类型（`HTTPConnection`）→ 必须 `fileprivate`；
    Swift 单 target 不能混合 Swift 与 C/C++（见 C/C++ 调研文档）。
  - **iOS 连接挂起根因（已修）**：`accept()` 里创建的 `HTTPConnection` 是局部变量，`start()` 后
    立即被释放 → `[weak self]` 回调全落空，连接 ready 但永不处理请求。修复：server 用
    `httpHandlers` 数组强引用，`finish()` 调 `server.unregisterHandler(self)`。

### 5.10 capacitor-foreground-state（前后台状态，原名 capacitor-app-state）

- `getState()` → `{ isActive, state, changedAt, lastBackgroundAt, backgroundDuration }`；
  `stateChanged` 事件；`handle.remove()` 取消单个、`removeAllListeners()` 取消全部。
- **2026-11-07 改名**：`capacitor-app-state` → **`capacitor-foreground-state`**（目录/包名/iOS SPM 名
  `CapacitorForegroundState`/Android 包 `com.testionic.foregroundstate`/jsName `ForegroundState` 全部同步）。
- Android 用 `handleOnResume`/`handleOnPause`（只有 active/background）；
  iOS 用 4 条 UIApplication 通知（active/inactive/background，inactive 是过渡态）。
- 真机实测：HOME 切后台 → 事件 `background`；回前台 → 事件 `active` 且 `backgroundDuration=3232ms` ✓。
- 用途：配合 data-channel 在切后台时暂停/提示（iOS 后台 socket 会被挂起）。

### 5.11 capacitor-native-files（本地文件系统）

- **2026-11-07 大改（0.2.0）：URI → 真实绝对路径**（用户要求：Markdown 阅读器需要按目录结构拼路径遍历）。
  - 所有 API 参数由 `uri` 改为 **`path`（绝对路径）**；返回值也带 `path`。
  - Android：`java.io.File` 直接操作；选择器返回的 `content://` 由插件解析成路径
    （`primary:`/`home:`/卷 UUID:/MediaStore `_data`；云盘等无 1:1 路径的 provider → 跳过并返回 `unmapped`）。
  - iOS：`FileManager` + **security-scoped bookmark**；选择时把 URL 转 bookmark 按绝对路径存
    UserDefaults（**重启后仍有效**），操作时找**最长的已授权祖先路径** start/stop access，
    所以"拼出来的子路径"也能读写。
  - 新增 `checkAllFilesAccess` / `requestAllFilesAccess`（Android 11+ 打开
    `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`，≤10 走运行时权限；iOS 返回 `applicable:false`）、
    `getAppDirectories`（appFilesDir/appExternalFilesDir/apkPath/externalStorage/downloads/documents/
    pictures/dcim/movies/music/alarms/... ，iOS 为 home/documents/library/applicationSupport/caches/
    temporary/bundle/icloudDocuments，公共目录为 null）。
  - `createFolder` 改为接收**完整路径**（递归创建）；`deleteFolder` 默认只能删空目录、`recursive:true` 递归；
    `listFolder` 支持 `recursive`/`maxEntries`；`renameFolder` 返回**新路径**；新增 `getInfo`（path）。
  - 权限与错误：错误信息带 `NOT_FOUND`/`IS_A_DIRECTORY`/`NOT_A_DIRECTORY`/`TARGET_EXISTS`/
    `FILE_ALREADY_EXISTS`/`PATH_MUST_BE_ABSOLUTE`/`PERMISSION_DENIED`（附申请权限提示）等前缀。
  - 实测：**模拟器 Android 16 + 真机 Android 10** 都在 `/storage/emulated/0/Documents` 下
    createFolder/write/read/list/getInfo/rename/递归删除 全通过；autotest（App 私有目录全链路）ok:true；
    smoke PASS；iOS CI 绿（编译 + 模拟器 autotest）。
- **2026-11-08（0.3.0）：移除 `copyToAppStorage`**——data-channel 的 `rootDir` 已支持绝对路径，
  直接 `startHttp({ rootDir: 目标目录 })` 即可，复制这一步完全多余（用户提出）。
  方法数 14 → 13（iOS `pluginMethods` 同步删）；错误码 `COPY_FAILED`/`CANNOT_CREATE_DATA_CHANNEL_DIR`
  随之消失。文档改为推荐 `rootDir` 用法（native-files README、data-channel README/EXAMPLES）。
- **2026-11-08（0.4.0）：`getAppDirectories()` 拆成 14 个单条路径 API**（用户提出）：
  - 原因：一次返回所有路径时，只要一个路径就得多查全部；某个路径失败还会影响整体。
  - 现在每个方法只查一条：`getAppFilesDir` / `getAppCacheDir` / `getAppTempDir` /
    `getAppExternalFilesDir` / `getAppExternalCacheDir` / `getAppDocumentsDir` / `getSharedStorageDir` /
    `getDownloadsDir` / `getSharedDocumentsDir` / `getPicturesDir` / `getDcimDir` / `getMoviesDir` /
    `getMusicDir` / `getAppInstallPath`，统一返回 `{ path: string|null, reason? }`
    （`null` 时带 `NOT_APPLICABLE_ON_IOS` / `EXTERNAL_STORAGE_NOT_MOUNTED`）。
  - 去掉了一些冷门 key（alarms/notifications/podcasts/ringtones/nativeLibDir/appDataDir/appExternalRoot），
    iOS 侧删掉 `home/library/bundle/icloudDocuments`（可用 `getAppFilesDir` 等替代）。
  - 实测：真机 14 个 API 全部正确，单条耗时 2.3–28ms；模拟器 3.8–35ms；autotest ok:true
    （`baseSource: getAppExternalFilesDir`，顺带验证"失败回退到下一个 API"）。
  - README 新增：14 条路径的含义表（Android/iOS 对照）、「为什么选不了根目录/Download（Android 11+ SAF 限制）」、
    「申请权限没弹窗是正常的」三节。
- 其它 API：`pickFiles`（**多选**）/ `pickFolders`（**单选**，Android SAF 系统限制，iOS 为一致也单选）/
  `readFile`（base64，offset/length，单次 16MB 上限）/ `writeFile`（create|append|overwrite）。
- 文件夹多选结论：**Android 系统不支持**（`ACTION_OPEN_DOCUMENT_TREE` 只能单选），iOS 虽支持但统一单选。
- ~~2026-11-08 待写进 README 的两条实测结论（TODO）~~ → **已在 0.4.0 README 落地**：
  「为什么选择器选不了存储根目录 / Download？（Android 11+ 系统限制）」与
  「申请权限时"没弹窗"是正常的」两节（含 `adb shell appops set <pkg> MANAGE_EXTERNAL_STORAGE default` 复现命令）。
- **2026-11-08（0.5.0）：把冷门目录 API 补回来**（拆成单 API 后用不到不调即可，成本为零）：
  新增 `getAlarmsDir` / `getNotificationsDir` / `getPodcastsDir` / `getRingtonesDir` /
  `getAppDataDir` / `getAppExternalRootDir` / `getNativeLibDir` / `getLibraryDir` / `getIcloudDocumentsDir`
  （Android 专有的在 iOS 返回 `NOT_APPLICABLE_ON_IOS`，反之 `NOT_APPLICABLE_ON_ANDROID`；
  iCloud 未开启时 `ICLOUD_NOT_AVAILABLE`）。目录 API 14 → **23 个**，插件方法总数 **35**。
  实测：真机 2.3–14.8ms / 模拟器 4–52ms，全部路径正确；autotest ok:true；smoke PASS。
- **2026-11-08：验证 Capacitor 内置"文件 URL"（`Capacitor.convertFileSrc`）——结论：能用，但 Range 有 bug**
  - 生成 URL：Android `https://localhost/_capacitor_file_/<abs path>`；iOS `capacitor://localhost/_capacitor_file_/<abs path>`
    （与页面同源 → 不需要 CORS；实现见 `Bridge.CAPACITOR_FILE_START` / `CapacitorBridge.fileStartIdentifier`）。
  - 实测（真机 Android 10 + 模拟器 Android 16，Capacitor 8.6.0）：`fetch` 整文件 ✅、`<img>` ✅、`<script>` ✅；
    **Range 有 bug**：请求 `bytes=6-17` 时响应头 `Content-Range: bytes 6-17/30` 正确，
    但正文把 start 之后的内容全发回来（忽略 end），且 `Accept-Ranges` 不暴露
    （源码 `WebViewLocalServer` 的 range 分支只 skip(start) 不限长度，注释也标了 deprecated）。
  - 结论：图片/文本/脚本预览用 `convertFileSrc` 足够；**视频拖动/PDF 分页必须用 data-channel 的 HTTP**
    （有正确 206）或自己实现 scheme handler（iOS 只能在宿主注册 `WKURLSchemeHandler`）。
  - 测试页新增卡片「文件 URL（Capacitor 内置 convertFileSrc）」：fetch / Range / img / script / 一键自检 / 清理；
    autotest 断言 fetch+img+script（Range 只记录不判定），smoke 已加「一键自检」。
  - **README 第 7 节已写全**（用户要求）：三条路线对比（`convertFileSrc` / 自定义 scheme / 本地 HTTP）、
    `convertFileSrc` 的完整示例（图片/音频/文本/流式读/Markdown 阅读器场景）、
    Android 自定义 scheme 完整 handler（含 Range/CORS/白名单/LimitedStream）与 iOS `WKURLSchemeHandler`
    （必须自定义 `CAPBridgeViewController` 重写 `webViewConfiguration(for:)`）、7 条 Capacitor 8.6.0 实测坑
    （Range bug / 无 Accept-Ranges / `#`与`%` 需 encodeURIComponent / 无路径白名单 / iOS 不能注册 http(s) /
    fetch 自定义 scheme 受限 / 明文回环）、决策树。
  - 另：为对比而写的 app-local 自定义 scheme 实验代码放在 `.perf/SchemeTestPlugin.java.bak`（未入库，未注册）。
- **2026-11-08（native-files 0.5.1 / data-channel 0.1.1）：修两个真 bug + 性能实测**
  - **Bug 1（严重）**：`PluginCall.getLong()` 只接受 `Long` 类型的 JSON 值，而 JS 传小整数时 org.json
    解析成 `Integer` → **静默返回默认值**。后果：native-files 的 `readFile({offset,length})`
    **一直没生效**（永远返回整个文件）；data-channel 的 `maxBodyBytes` 参数同样被忽略。
    修复：两个插件各加 `readLong(call, key, default)`（从 `Number`/`String` 转换）。
  - **Bug 2（性能）**：`readFile` 分块读用 `FileInputStream.skip(offset)`，是 O(offset)；
    4MB/512KB 块实测只有 6.4MB/s。改用 `RandomAccessFile.seek()`（O(1)）→ 7.6MB/s。
  - **base64 桥性能实测**（真机 K30S / Android 10）：1MB 写 116ms/读 101ms（9.0/10.4 MB/s）；
    4MB 401/363ms（10.5/11.5）；8MB 784/728ms（**10.7/11.5 MB/s**）；64KB 19/21ms；1KB 9/6ms。
    JS 侧 `btoa` 8MB 471ms、`atob` 138ms → 端到端约 6.7MB/s。上限 16MB/次。
    对比 data-channel HTTP：上传 93MB/s、下载 322MB/s。
  - **官方 @capacitor/filesystem 8.1.5**（临时装包读源码 + 同条件基准，测完已卸载）：
    - 实现：`Directory` 枚举 + 相对 path（**无绝对路径**）；`readFile` 返回 base64；
      `writeFile({data})`；8.1.5 新增 `readFileInChunks({chunkSize},callback)` 原生推流；
      Android 写外部存储后 `MediaScannerConnection.scanFile`。
    - 性能：1MB 168/114ms（6.2/9.2）、4MB 398/419ms（10.5/10.0）、8MB 797/800ms（10.5/10.5）；
      **分块读 4MB 达 13.4MB/s**（比我们手动分块快）。整文件读写与我们基本持平。
  - README 新增「## 性能实测（base64 桥 vs 本地 HTTP）」：结论（≤16MB 走桥够用）+ 实测表 +
    与 data-channel / filesystem 对比 + 复现命令。
  - 顺手确认：**Capacitor 的 `_capacitor_file_` 可以读中文/空格路径**；文件名含 `#` 或 `%` 时
    必须按路径段 `encodeURIComponent`（`toFileUrl()` 已实测 200）。
- 与 data-channel 配合：`pickFiles` / `pickFolders` 拿到绝对路径 → `startHttp({ rootDir: <目录> })`
  → 用**相对 rootDir** 的 `path` 上传/下载（0.3.0 起不再需要复制文件）。

### 5.12 capacitor-cpp-bridge（C/C++ 桥模板）

- **定位**：不是功能插件，是**模板**——把 `WebView → native → 共享 C/C++ 核心` 打通；
  后续开发者往 `shared/core/` 加自己的 C/C++ 代码，再在两端的薄桥各加一个转发方法。
- **结构**：
  - `shared/core/include/cppcore.h`：`extern "C"` 纯 C 接口（Swift 可直接 import，JNI 可直接调）；
  - `shared/core/cpp_core.cpp`：**C++ 编译单元**（可用 STL）；`shared/core/c_core.c`：**纯 C 编译单元**；
  - `android/src/main/cpp/bridge.cpp`：JNI 薄桥（只转发 + 把错误转成 Java 异常）；
  - `ios/Sources/CppBridgePlugin/CppBridgePlugin.swift`：`import CppCore` 后直接调 C 函数；
  - `src/definitions.ts`：JS 类型（含 ⚠️ danger 标注）。
- **构建**：
  - Android：`externalNativeBuild` + CMake，`ndkVersion = 27.1.12297006`（本机已装），
    ABI = arm64-v8a / armeabi-v7a / x86_64，`-DANDROID_STL=c++_static`；CMake 用
    `${CMAKE_CURRENT_LIST_DIR}/../../../../shared/core` 引用**模块外**源码（实测可行）；
  - iOS：SPM 拆两个 target（SwiftPM 不能混语言），`CppCore` 的 path 直接指向 `shared/core`，
    包级 `cxxLanguageStandard: .cxx17`（**不要用 `unsafeFlags`**，调研文档那处已更正）；
  - `cap sync` 会自动把插件写进 `android/capacitor.settings.gradle` 与 `ios/App/CapApp-SPM/Package.swift`。
- **示例 API**：`getCoreInfo`（buildInfo: platform/compiler/arch/cxx）、`add`（C++）、`cAdd`（纯 C）、
  `cStrlen`（C，UTF-8 字节数）、**句柄 API**（⚠️ danger）：`allocBuffer/fillBuffer/sumBuffer/freeBuffer`
  + `getBufferStats`（用于发现忘记 free）。
- **句柄语义**：handle = native 指针数值；必须 free；进程重启失效；重复 free 返回 `freed:false`；
  native 侧用 `Map<handle,size>` 记录，用于统计与双重释放保护（不做 GC）。
- **踩过的坑**：
  - `PluginCall.getLong()` 只认 `Long`（JS 小整数是 `Integer`）→ Java 侧统一用 `readLong()` 读句柄/大小；
    iOS 侧用 `call.options[key] as? NSNumber` 读（`getData()` 在 Capacitor 8 上不存在，`JSObject` 是 `[String: JSValue]`）。
  - 插件目录没有 `package-lock.json` 时 `npm ci` 会失败 → 先在插件目录 `npm install` 生成锁文件。
- **实测**：真机（aarch64）`loaded:true`、buildInfo `clang 18.0.2 / C++17`、add/cAdd 都 42、
  4MB fill 6.7ms / sum 4.8ms；模拟器（x86_64）fill 10.7ms / sum 9.3ms；autotest `ok:true`、smoke PASS。
- **CI 验证**：iOS CI 全绿（`macos-26` 上 SPM 编译 `CppCore`（C++ + 纯 C）+ Swift 桥 → 模拟器 autotest）。
  途中遇到 **`Cordova.xcframework.zip` 从 GitHub Release 下载偶发 504**（`badResponseStatusCode(504)`），
  与代码无关；workflow 已加「Resolve Swift packages (retry on CDN flake)」步骤（最多 4 次、间隔 25s），
  并把 `error:` 后续 6 行抛成 annotation（否则 annotation 只显示第一行、看不到真正原因）。
- **`c++_static` vs `c++_shared`**：本模板只有一个 .so → 用 static（APK 少一个 .so、无运行时依赖）；
  一个 App 里有多个 C++ 库时改 shared，避免 C++ 运行时重复。

## 6. Capacitor 8 插件开发手册（要点）

### 6.1 包结构（每个插件）

```
plugins/<name>/
├─ package.json        # 必须有 "capacitor": {"android":{"src":"android"},"ios":{"src":"ios"}}
├─ tsconfig.json       # outDir dist/esm，files: ["src/index.ts"]
├─ Package.swift       # iOS SPM 包（名字必须=CLI 推导名，见 6.3）
├─ src/{definitions.ts, index.ts, web.ts}
├─ android/{build.gradle, settings.gradle, gradle.properties}
│  └─ src/main/java/com/testionic/<pkg>/XxxPlugin.java
└─ ios/Sources/XxxPlugin/XxxPlugin.swift
```

### 6.2 JS 侧

```ts
// index.ts
const Xxx = registerPlugin<XxxPlugin>('Xxx', {
  web: () => import('./web').then((m) => new m.XxxWeb()),
});
```
`addListener` / `removeListener` 是 Capacitor 桥内置的，**不需要写进原生 pluginMethods**（本项目的 `verify-ios.mjs` 已把它们加白名单）。

### 6.3 Capacitor CLI 的识别规则（踩过的坑）

- `package.json` 必须有 `capacitor` 字段，否则 CLI 不把它当插件。
- Java 类里 **`package xxx.yyy;` 和 `@CapacitorPlugin(...)` 必须顶格写**（不能有缩进），
  CLI 用正则 `^@(?:CapacitorPlugin|NativePlugin)[\s\S]+?class (\w+)` + `^package ([\w.]+);?$` 找类名/包名。
- Gradle 模块名 = 包名去掉 `@`、`/` 换 `-`（如 `@scope/foo` → `scope-foo`）。
- **iOS SPM 包名 = CLI 从 npm 包名推导**（只有首字母和分隔符后大写）：
  `capacitor-native-statusbar` → `CapacitorNativeStatusbar`；`capacitor-set-force-dark` → `CapacitorSetForceDark`。
  Package.swift 的 `name` 必须一致，否则 `CapApp-SPM` 引用不到（CI 会红）。
- 插件的 `dist/` 不入库（.gitignore）。**先构建插件再构建应用**：本地/CI 都用 `npm run build:plugins`。
- 插件里 `android/build.gradle` 用 `project.hasProperty(...) ? rootProject.ext.xxx : 兜底值` 取 SDK/依赖版本，Java 21。

### 6.4 Android 实现要点

- 类骨架：
  ```java
  @CapacitorPlugin(name = "Xxx")
  public class XxxPlugin extends Plugin {
      @PluginMethod
      public void someMethod(PluginCall call) { ... call.resolve(ret); /* 或 call.reject(...) */ }
  }
  ```
- **线程**：插件方法跑在名为 `CapacitorPlugins` 的后台线程。所有 View / WebView 操作必须切主线程：
  `getActivity().runOnUiThread(...)` / `getBridge().executeOnMainThread(...)` / `webView.post(...)`。
  特别地，`WebView.getSettings()` 在非主线程调用会直接崩 App（set-force-dark 踩过）。
- 事件：`notifyListeners("eventName", data)`；生命周期钩子：`handleOnStart / handleOnConfigurationChanged / load()`。
- 页面加载钩子：`getBridge().addWebViewListener(new WebViewListener(){ onPageLoaded / onPageCommitVisible })`；
  在页面里执行 JS：`getBridge().getWebView().evaluateJavascript(js, null)`（主线程）。
- 布局/insets 事实：窗口从 y=0 开始，DecorView 用 padding 把内容顶到状态栏下（真机 90px / 模拟器 128px）；
  自绘覆盖状态栏的色块需要 `topMargin=-paddingTop` + `setClipToPadding(false)`。

### 6.5 iOS 实现要点

```swift
@objc(XxxPlugin)
public class XxxPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "XxxPlugin"
    public let jsName = "Xxx"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "someMethod", returnType: CAPPluginReturnPromise)
    ]
    @objc func someMethod(_ call: CAPPluginCall) { call.resolve([...]) }
}
```
- `pluginMethods` 里方法名必须与 `@objc func` 一致；`@objc(XxxPlugin)` 必须有。
- 线程同样要 `DispatchQueue.main.async`。
- 常用：`bridge?.viewController`（可 cast 成 `CAPBridgeViewController`，有 `setStatusBarStyle(_:)`）、`bridge?.webView`；
  `overrideUserInterfaceStyle` 可强制亮/暗；监听主题变化可用隐藏 UIView 子类重写 `traitCollectionDidChange` 或 `layoutSubviews`。
- JS console → 原生日志由 `CAPLog.print`（`print()`）转发；CI 用 `simctl launch --console-pty` 抓 stdout。

## 7. 构建 / 部署 / 验证命令

```powershell
# 一键：build:plugins → vite build → cap sync → gradlew assembleDebug → install → launch
npm run android:deploy                         # 自动选设备（优先模拟器）
npm run android:deploy -- -Device 32e93a6d     # 指定设备（npm 透传参数）

# 双端冒烟测试（真机 + 模拟器，CDP 驱动，输出 PASS/FAIL）
npm run verify:android

# iOS 静态检查（本机可跑）
npm run verify:ios

# 单独构建所有插件 / web
npm run build:plugins
npm run build
```

- 改 Java 后不需要 cap sync，直接重新 gradle（`npm run android:deploy` 会全做）。
- 改插件 TS 后：`npm run build:plugins` → 再 `npm run build`。
- 增删插件 / 改 package.json 的 `capacitor` 字段后：`npx cap sync`（android 与 ios）。

### 自动化测试体系（已搭好，别重复造）

- **Android 冒烟**：`verify-android.ps1` 每台设备 `adb forward tcp:93xx localabstract:webview_devtools_remote_<pid>`，
  `webview-eval.mjs --port <p> --file scripts/smoke-test.js`；smoke-test 在页面里 DOM 点按钮、读 `<pre>` 文本断言。
  注意：只有 debug 包支持 CDP；`webview-eval.mjs` 有 30s 超时（**不能 unref**，否则进程提前退出）。
- **iOS CI**（`.github/workflows/ios.yml`，公开仓库免费跑）：
  `npm ci` → `build:plugins` → `VITE_AUTOTEST=1 npm run build` → `cap sync ios` → `verify:ios` →
  `xcodebuild -project ios/App/App.xcodeproj -scheme App ... CODE_SIGNING_ALLOWED=NO` →
  起模拟器、`simctl launch --console-pty`、抓日志、断言 `PLUGIN_AUTOTEST_RESULT` 与 `"ok":true`、截图上传 artifact。
- **autotest**（`VITE_AUTOTEST=1` 构建后 App 自动跳 `#/plugin-test?autotest=1`）跑 4 个插件的自检并
  `console.log('PLUGIN_AUTOTEST_RESULT ' + JSON.stringify(results))`（Capacitor 会转发到原生日志）。
- GitHub 查询状态：未认证 API 有频率限制（偶尔 403）；日志需要登录。可用 workflow badge 快速看红绿：
  `https://github.com/Cinichengbuweikong/tmp_testionic/actions/workflows/ios.yml/badge.svg?branch=main`

## 8. 踩坑与关键实测结论（速查）

| # | 问题 | 结论/解法 |
| --- | --- | --- |
| 1 | JDK 17 vs Capacitor 8 要求 21 | gradlew 必须用 JBR 21（deploy 脚本已处理） |
| 2 | MIUI 拦截 `adb install` | `INSTALL_FAILED_USER_RESTRICTED` → 开发者选项开「USB 安装」 |
| 3 | 插件 `dist/` 不入库 | CI/本地必须先 `npm run build:plugins`，否则 Vite 解析不到入口 |
| 4 | Swift 包名推导 | `Package.swift` name 必须等于 CLI 推导名（见 6.3），静态检查可提前发现 |
| 5 | 插件方法不在主线程 | View/WebView 操作必须切主线程，否则 `CalledFromWrongThreadException` 崩 App |
| 6 | `setStatusBarColor` 无效 | Android 15+ 忽略；MIUI 12 也无效 → 自绘 DecorView 顶部色块（负 margin + clipToPadding=false + bringToFront） |
| 7 | MIUI 图标亮暗被覆盖 | MIUI 按背景色自动对比度，无视 `LIGHT_STATUS_BAR`（dumpsys 已确认标志写入；官方插件同环境下也无效，像素对比 0/5320） |
| 8 | MIUI WebView 不报 prefers-color-scheme | `Configuration` 是 night 但 `matchMedia(dark)=false` → 靠 set-force-dark 注入 `data-theme` |
| 9 | MIUI forceDark 反色灾难 | `setForceDarkAllowed(false)` 即可关掉；页面恢复 CSS 真实颜色 |
| 10 | 老 WebView 无安全区 env() | WebView 87 的 `env(safe-area-inset-top)=0px`（133 返回 49px）→ 用 to-edge 注入的 `--safe-area-inset-*` |
| 11 | Capacitor 默认内容在状态栏下 | `SystemBars` 插件给 DecorView 加 padding（WebView<140 或页面无 viewport-fit=cover 时）→ to-edge 插件接管 insets 监听器 |
| 12 | `onConfigurationChanged` 旋转也会触发 | 事件去重（只有 isDark/insets 真变化才派发） |
| 13 | CDP 评估挂起 | WebSocket 连接时 app 被杀；webview-eval 加 30s 超时；MIUI 会杀后台进程（`has died: cch CRE`） |
| 14 | 官方 status-bar 的 `getInfo().overlays` 误导 | 它只读 `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN`，不代表 WebView 真铺到状态栏下（实际内容在状态栏下方） |
| 15 | MIUI 手势模式假报导航栏 insets | `getSystemWindowInsetBottom`/`getStableInsetBottom` 都报 130px，但窗口实际铺满 → API29 用 `getWindowVisibleDisplayFrame()` 反推 |
| 16 | `Plugin.getConfig()` 是插件私有配置块 | 只含 `plugins.<id>` 节点，读不到全局 appName → 用 application label；`Plugin.getAppId()` 基类自带 |
| 17 | MIUI `getMode()` 返回瞬时刷新率 | 设置 90Hz 时 `getMode()/getRefreshRate()` 报 50（智能刷新率动态降档）；用户档位存 `Settings.System` 的 `user_refresh_rate`（MIUI）/ `peak_refresh_rate`（Android 11+，@hide 常量，用字符串键读） |
| 18 | iOS 26 移除 deprecated API | `UIStatusBarManager.statusBarFrame` 在 iOS 26 SDK 标记 **obsoleted（编译期就报错）**，`windowScene.interfaceOrientation` 同理 → 状态栏高度改用 `safeAreaInsets.top`（两者本就相等），旋转角改用 `UIDevice.orientation` |
| 19 | autotest 断言字段名与 API 结构脱节 | `statusBar.heightPx`（旧扁平结构）vs `statusBar.px.height`（现结构）；Android 冒烟是文本断言测不出，只有 iOS CI autotest 能抓 → 改 API 结构时同步改 autotest |
| 20 | CI 日志拿不到时怎么诊断 | 公开仓库无 token 拉不到 logs/artifact（403）；让仓库 owner 建 PAT（读权限即可）→ 下载 run logs zip 与 artifact（sim-console.log 里有完整 PLUGIN_AUTOTEST_RESULT，注意步骤日志 2000 字符截断，artifact 里的完整） |
| 21 | cutout 数据「看起来像错的」 | `boundingRects` 的 left/top/right/bottom 是**矩形四边坐标**（不是到边距离）；系统给的是**挖孔安全区矩形**（会贴屏幕边），真实圆孔在内部、看起来不贴边；横屏时坐标系换成 2400×1080（y=939~1080 即贴近底边）→ 已加 width/height/centerPx/centerCss 让数据自解释（960f52a） |

设备实测数据（真机 M2007J3SC / MIUI 12 / WebView 87）：

- WebView bounds 默认 `[0,90][1080,2180]`（模拟器 `[0,128][1080,2274]`）
- 系统深色模式：`cmd uimode night` = yes；App `Configuration` 含 night
- `matchMedia('(prefers-color-scheme: dark)')` = **false**（MIUI）；模拟器 = true
- 状态栏：蓝底→白图标、白底→深图标；`vsysui=...LIGHT_STATUS_BAR`（dark 样式时）
- to-edge enable 后：真机 `[0,0][1080,2180]`，insets `{top:33}`；模拟器 `[0,0][1080,2400]`，`{top:49,bottom:48}`

## 9. 网络与 CI

- GitHub 仓库公开：`https://github.com/Cinichengbuweikong/tmp_testionic`
- iOS workflow 每次 push 自动跑（约 8~10 分钟，免费）；本地 `git push` 用 GCM 凭据（浏览器弹窗授权）。
- 未认证 GitHub API 约 60 次/小时/出口 IP，注意限流；失败时改用 badge SVG 或让用户看网页。
- 国内网络：npm 官方源可用；Android Maven 已配国内镜像；模拟器 WebView 与系统镜像已缓存。

## 10. 当前状态 / 未完成事项

- ✅ capacitor-system-info（13 个独立只读 API）：iOS CI 全绿；Android 双端实测通过。
  `getCutoutInfo()` 已增强（width/height + centerPx/centerCss，960f52a）。
- ✅ capacitor-fullscreen（沉浸式全屏）：iOS CI 全绿（59a9e64）；Android 双端截图验证通过。
- ✅ capacitor-native-rotation **0.2.0**（原 capacitor-rotation-listener 改名）：新增
  `lock/unlock/getLock` 主动方向控制（视频全屏播放场景）。真机实测：lock(landscape) → 横屏
  （截图 2400×1080、rotation landscape/90）；unlock → 竖屏 portrait/0。**iOS CI 全绿**（65eac4c）。
  坑：iOS 的 `requestGeometryUpdate` 是 **UIWindowScene** 的方法（走 `view.window?.windowScene`），
  不是 UIViewController 的。
- ✅ capacitor-native-statusbar **0.2.0**：修复暗色模式 force dark 导致白色失效/橙色偏暗——
  给自绘色块单独 `setForceDarkAllowed(false)`；实测 白色→rgb(228,232,227)、橙色→精确 #FF5722。
- ✅ **capacitor-data-channel**（二进制数据通道）：本地 HTTP + WebSocket Server 大文件/实时流传输；
  真机实测 File 上传 105.8MB **806ms/131MB/s、内存 +4.2MB**；WS 10MB 往返 181ms/55MB/s、
  64KB 帧 1.7ms/588fps、空闲 CPU 0%；rootDir 支持默认/相对/绝对路径（Android 10 公共目录受
  scoped storage 限制，已验证并给出明确错误）；Range/404/CORS 全通过；双端冒烟 PASS。
  **iOS CI 待定**：Swift 编译错误（fileprivate）已修（198c1b6），运行验证待测（详见 5.9 节）。
- ✅ **capacitor-foreground-state**（前后台状态，原 `capacitor-app-state`，2026-11-07 改名）：真机实测
  HOME 切后台/回前台事件与 `backgroundDuration=3232ms` 全通过；iOS CI 待验证（详见 5.10 节）。
- ✅ **capacitor-native-files**（本地文件系统，新增）：11 个 API；真机验证方法注册与
  `getInfo` 无效 URI 行为；选择器为系统 UI 需手动验证；iOS CI 待验证（详见 5.11 节）。
- 📄 **C/C++ 插件调研文档**：`docs/capacitor-cpp-bridge-research.md`（Android NDK+CMake / iOS SPM
  双 target / 零拷贝 / 坑清单 / 推荐实现顺序），后续按文档实现 `capacitor-cpp-bridge` 模板。
- ✅ capacitor-keyboard-avoiding（键盘避让）：真机实测键盘事件与补差逻辑全通过
  （键盘弹出 height 337.45css/928px、enable 无双重缩小、关闭事件正常）；双端冒烟 PASS；
  **iOS CI 全绿**（e4a1fe0 attempt 2；attempt 1 是模拟器 flaky——App 未启动、console 为空，
  重跑即过，遇到了就重跑）。
- ⚠️ **未提交**：`src/assets/base.css`（`--vt-c-black: #202020`；暗色 `--color-background: var(--vt-c-black)`，
  原来 debug 用的 `red` 已替换）。用户倾向保留，待其确认后提交。
- 真机（`32e93a6d`）为 MIUI 手势模式（无底部导航栏）；模拟器（`emulator-5554`）按需启动
  （2026-11-05 晚被用户关闭，需要时重新启动）。
- 遗留可选任务：MIUI 状态栏图标强制控制（未攻破）；iOS 上 data-channel 的 WebView fetch 是否
  受 ATS/混合内容限制（已加原生 self-test 区分，待 CI 结果确认）。
- 🔴 **用户反馈（2026-11-05 晚）**：简单查看后认为"还是有问题的，各种地方都要改一下"，
  但**未列出具体问题**，且当天已疲劳暂停。**下次恢复第一件事：请用户指出具体问题点，
  逐条确认后再改**（不要自行猜测大改）。可能涉及：data-channel 的 rootDir/WS 文档与行为、
  foreground-state（原 app-state）/ native-files 的 API 细节与 README、测试页/冒烟脚本的断言等。

> **2026-11-07 进展**：data-channel 已按用户要求完成 6 轮加固（职责收敛 → OTP 鉴权 + 双 server
> → 逐条 OTP + 协议 → 批量 OTP → 性能基准 + 安全讨论 → 可续传上传 + 结构化错误），
> 真机/模拟器/iOS CI 全绿；`capacitor-app-state` 已改名 `capacitor-foreground-state`。

## 11. 新对话快速恢复 checklist

1. 让我读 `HANDOFF.md`（本文件）+ 需要的插件 README；
2. 确认设备：`adb devices`（真机 `32e93a6d` / 模拟器 `emulator-5554`，没开就按第 3 节启动）；
3. 改完代码后 `npm run android:deploy`（装到真机/模拟器），`npm run verify:android` 跑冒烟；
4. 涉及 iOS：`npx cap sync ios` + `npm run verify:ios`，然后 `git push` 看 CI；
5. 新插件：复制 `plugins/capacitor-native-basics` 结构，按第 6 节规则命名与实现。
