# capacitor-python 调研报告与设计决策

> 2026-11-26。目标：在 Capacitor App（Android + iOS）里集成一个 Python 解释器，
> 要求「崩溃不影响主程序 / 实时 stdin-stdout-stderr / 多份代码并发 / pip 包 / 内置代码编辑器」。
> 本文记录**调研结论、方案取舍、放弃项**，实现细节见插件 README。

## 1. 结论先行

**选定方案：Pyodide（CPython 3.14 编译成 WebAssembly）跑在「每个会话一个 Web Worker」里。**

理由（按需求优先级）：

| 需求 | Pyodide + Worker | 原生 CPython（Android Chaquopy / iOS Python-Apple-support） |
| --- | --- | --- |
| **Python 崩溃不影响主程序** | ✅ Worker 是独立线程，`terminate()` 是可靠 kill 开关 | ❌ **iOS 上做不到**：Apple 明确禁止 fork/exec/子进程，进程内 CPython 段错误 = App 崩溃；Android 需要额外的 `:python` 进程 + AIDL + LocalSocket，约 3~5 周 |
| 实时 stdout/stderr | ✅ `setStdout({batched})` 按行回调 → postMessage | ✅ 但需要自己替换 `sys.stdout`（iOS 无 console） |
| 多份代码并发 + 隔离 | ✅ N 个 Worker = N 个解释器，天然隔离 | ⚠️ Android 需多进程；iOS 只能 sub-interpreter（共享 GIL 或 per-interpreter GIL），崩溃仍然一起死 |
| pip 包 | ✅ micropip：纯 Python + PyEmscripten 轮子，支持离线 wheel | ⚠️ 构建期 pip（Chaquopy 只允许 `--only-binary`）/ iOS 运行期只能装纯 Python 包 |
| 一次性覆盖 Android+iOS | ✅ 一份 JS/WASM | ❌ 两套原生实现（Gradle 插件 + podspec/script phase），~6~10 周 |
| 体积 | 运行时 ~13MB（wasm 9.2MB + stdlib 2.4MB + glue 1.2MB） | Android +12~22MB；iOS +35~38MB |
| 升级 Python 版本 | ✅ 换 `python-runtime/<version>/` 目录即可 | ⚠️ 重新交叉编译/换 xcframework，Xcode 脚本链要一起改 |

**关键证据**：App Store 上已有 Capacitor 8 + Pyodide 314 的正式应用（Finanze），它在
iOS 的 `capacitor://localhost` 里用 `new Worker(..., {type:'module'})` 加载 Pyodide，
并同时跑两个 Worker —— 说明「Capacitor iOS 的 module worker + WASM + 自定义 scheme」这条路是通的。

## 2. 各平台调研摘要

### 2.1 Android 原生（Chaquopy）

- **Chaquopy 17.2.0**（2026-10），Maven `com.chaquo.python:gradle`；**MIT**，12.0.1 起对闭源商用也免费。
- Python 3.10（默认）/3.11/3.12/3.13/3.14/3.15；3.8/3.9 在 17.0 被移除；minSdk 24、AGP 8.x 可用。
- 体积（实测官方产物）：arm64 7.0MB + x86_64 7.2MB + 共享 stdlib 4.1MB ⇒ 单 ABI ~11MB、双 ABI ~19MB。
- pip **仅构建期**，17.0 起 `--only-binary`（不接受 sdist），需要与本机 Python 小版本匹配；**没有运行期 pip**。
- `sys.stdout` 默认按行进 Logcat；`sys.stdin` 永远是 EOF → 想交互必须自己写 io 对象。
- **官方不支持独立进程**（FAQ 原话："there is no separate Python process… will significantly complicate your app"），
  一个进程一个解释器；要隔离只能自己做 `android:process=":pyserver"` + Bound Service + AIDL/LocalSocket。
- `isolatedProcess="true"` 会剥夺网络/存储权限，pip 和 requests 都用不了，不能作为方案。
- 结论：技术上可行，但「崩溃隔离」需要 3~5 周工程量；且插件必须携带 Chaquopy（只能有一个模块应用它）。

### 2.2 iOS 原生（Python-Apple-support）

- BeeWare 只发布 `.tar.gz`（含 `Python.xcframework`），**没有 SPM binaryTarget**；3.14-b14 压缩包 37.3MB。
- 集成方式：podspec `vendored_frameworks` + Run Script（`install_python`）把 67 个 `.so` 转成
  `Frameworks/<name>.framework` 并逐个 codesign（iOS 要求可执行代码必须在 `Frameworks/` 且已签名）。
- 体积：device 二进制 6MB + stdlib（去掉 test/idlelib 后 ~14MB）+ dynload 18MB ≈ **35~38MB 安装**。
- 流：iOS 没有 console，必须自己给每个解释器装 `sys.stdout/stderr` 的 Python 对象转发到 Swift。
- 会话：sub-interpreter（3.12+ 可 per-interpreter GIL）；空解释器 ~1MB，导入常用模块 ~5MB。
- **崩溃隔离不可能**：CPython 文档明确 "An iOS app cannot use any form of subprocessing, multiprocessing,
  or inter-process communication… will either lock up, or crash."。段错误直接杀死 App。
- 运行期 pip：**纯 Python 包**可以（装到 Documents + sys.path），C 扩展不行（签名/ABI）。
- App Store：内嵌 CPython 允许；2.5.2 关注的是"下载并执行改变 App 功能的代码"，2026 年 3 月
  Apple 收紧了对"在自己内部生成并运行新代码"的 App 的执法；Python IDE 类（Pyto/Juno）仍在架。
- 结论：**不满足需求 1**，且工程量 4~7 周 + 审核风险。

### 2.3 Pyodide（选定）

- 最新 **314.0.8**（2026-11）＝ CPython **3.14.2**；npm 包 6.2MB，解包 13.9MB。
- 运行时是 **ESM**（`pyodide.mjs` + `pyodide.asm.mjs`），**不支持 classic worker**；需要 module worker。
- Capacitor 8 已把 `.wasm` 映射为 `application/wasm`、`.mjs` 当 JS 返回，iOS 的 `WKURLSchemeHandler`
  也覆盖 worker 请求 —— 官方 issue #4764 的旧 MIME 问题已修。
- **没有 SharedArrayBuffer**（Capacitor 无法设置 COOP/COEP）→ `setInterruptBuffer` 不可用，
  唯一 kill 手段是 `worker.terminate()`；`threading` 也不支持。
- stdin：`setStdin({stdin: () => string})` 是**同步**回调；`input()` 想真正阻塞需要 SAB + `Atomics.wait`。
- 多会话：每个 Worker 一个解释器；内存 ~50MB/会话（装 numpy ~100MB），iOS 建议 ≤300MB。
- pip：micropip 支持 PyPI 纯 Python 包 + pyodide 轮子（numpy/pandas/scipy/sklearn/cryptography 都有），
  也支持从本地 URL 装 wheel（离线）。
- 启动：桌面 2~4s；中端 Android 5~10s（编译 9.2MB wasm）。

### 2.4 编辑器

- **Monaco**：官方 FAQ 写明不支持移动端浏览器；~850KB gzip + 独立 worker；离线打包还要注入 worker，
  不适合 Capacitor。
- **CodeMirror 6**：contenteditable + 大量 iOS/Android IME 修复；Python 语言包自带缩进规则、括号匹配、
  折叠，以及 `localCompletionSource`（文档内变量）+ `globalCompletion`（内置函数/关键字）——
  正好满足"关键字/函数名级别提示"；打包实测 **137.5KB gzip**（core+python+autocomplete）。
- **结论：CodeMirror 6**，用 esbuild 打成单个 ESM 入口，样式运行时注入（无 CSS 文件、无全局变量）。

## 3. 架构

```
App (WebView)
├─ PythonRuntime（JS，单例）
│   ├─ session A ── Worker A ── Pyodide（CPython 3.14 / WASM）
│   ├─ session B ── Worker B ── Pyodide
│   └─ 事件：sessionOutput / sessionState / sessionDone / sessionError
├─ 运行时资源：public/python-runtime/<version>/（wasm + stdlib + glue + lock + manifest）
└─ 原生壳：PythonPlugin.getInfo()（上报 WebView/系统版本，判断最低要求；未来原生引擎挂载点）
```

- **stdin**：`blocking`（有 SAB，Android 可用，`Atomics.wait` 真阻塞）/ `queued`（iOS，队列空即 EOF）。
  会话状态里通过 `stdinMode` 告知调用方，UI 可以据此决定是否显示"实时输入"。
- **中断/恢复**：`terminate()` / `restartSession()`；因为是 Worker，Python 死循环也只影响自己。
- **包**：
  1. `installPackages(['numpy'])` → micropip 联网安装；
  2. `installWheels(['python-wheels/foo-1.0-py3-none-any.whl'])` → 离线安装（把 wheel 放进 App 资源）；
  3. 构建期把 wheel 放进 `public/python-wheels/`，不依赖网络。
- **版本升级**：`scripts/fetch-python-runtime.mjs --version <新版本>` 生成新目录 →
  改 `DEFAULT_PYODIDE_VERSION` 或 `initialize({ version })`；`manifest.json` 里带最低 WebView 要求。

## 4. 明确放弃 / 受限的部分（重要）

1. **iOS 上的"实时阻塞 stdin"**：没有 SAB，`input()` 无法在等待用户输入时挂起。
   插件支持"预输入队列"（先把多行塞进去，脚本按顺序消费），队列空时 `input()` 抛 `EOFError`。
   → 需要真交互的脚本建议用"分步执行"或把输入做成函数参数。
2. **任意 C 扩展 pip 包**：只能装 Pyodide 生态里已编译好的轮子；`psycopg2` 之类需要自己用
   Pyodide 工具链编译。
3. **原生线程 / 信号 / subprocess / multiprocessing**：WASM 沙箱内不可用。
4. **`ssl`/TLS 细节**：Pyodide 314 的 `ssl` 是桩，`requests` 走浏览器 fetch，无法控制证书/超时。
5. **旧 WebView**：Pyodide 314 要求 Android WebView（Chrome）≥90、iOS ≥16.4。
   低于此版本会加载失败（实测 Android 10 + WebView 87 直接语法错误）→ 插件用 `getInfo()` 提前检测并提示升级。
6. **后台执行**：WebView 进入后台会被挂起，长任务必须保持前台（或用原生后台插件配合，但不保证）。

## 5. 备选方案（未来可加）

- **Android 原生引擎**：Chaquopy 17 + `:python` 进程 + AIDL，同一套 JS API 后面切 `engine: 'native'`。
  适合"需要真正 CPython 性能/任意 pip 包"的场景，但不做崩溃隔离就只是换了个运行时。
- **iOS 原生引擎**：Python-Apple-support xcframework，仅在没有崩溃隔离要求时值得做。
- 两者都不改变现有 API（`engine` 字段已预留）。

## 6. 参考

- Python 官方：<https://docs.python.org/3/using/android.html>、<https://docs.python.org/3/using/ios.html>
- Pyodide：<https://pyodide.org/en/stable/usage/webworker.html>、<https://pyodide.org/en/stable/usage/wasm-constraints.html>
- Chaquopy：<https://chaquo.com/chaquopy/doc/current/android.html>、<https://chaquo.com/chaquopy/doc/current/faq.html>
- Python-Apple-support：<https://github.com/beeware/Python-Apple-support/releases>
- CodeMirror：<https://codemirror.net/docs/guide/>、<https://github.com/codemirror/lang-python>
- Capacitor WASM/MIME 与 worker：<https://github.com/ionic-team/capacitor/issues/6182>、<https://github.com/pyodide/pyodide/discussions/4764>
- 现成案例（Capacitor + Pyodide，App Store）：<https://github.com/finanze/finanze>
