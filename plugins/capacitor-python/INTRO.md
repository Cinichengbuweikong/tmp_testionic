# capacitor-python · 插件概览

**一句话**：在 App 里跑真的 CPython 3.14（WebAssembly），每个会话独立 Worker 隔离，
实时 stdout/stderr，多会话并发，支持 pip 包，并自带一个框架无关的 Python 代码编辑器。

## 它解决什么问题

App 里想执行用户写的 Python（脚本/教学/自动化/插件化），但：

- 原生嵌入 CPython 在 **iOS 上无法隔离崩溃**（Apple 禁止子进程，段错误 = App 闪退）；
- 原生方案要两套实现（Android Chaquopy + iOS Python-Apple-support），3~6 周工作量，+20~40MB；
- 只想跑"一小段代码"，不需要 CPython 的全部能力。

这个插件用 **Pyodide（CPython → WebAssembly）** 跑在 Web Worker 里：
Python 崩了/死循环了，`terminate()` 掉 Worker 就行，App 与其它会话完全不受影响。

## 平台

| | Android | iOS | Web |
| --- | --- | --- | --- |
| 运行 Python | ✅ WebView ≥ Chrome/90 | ✅ iOS ≥ 16.4 | ✅ 现代浏览器 |
| 会话隔离 | ✅ 每会话一个 Worker | ✅ | ✅ |
| 实时 stdout/stderr | ✅ | ✅ | ✅ |
| pip | ✅ micropip（离线 wheel 也支持） | ✅ | ✅ |
| 阻塞式 `input()` | ⚠️ 默认队列模式 | ⚠️ 默认队列模式 | ⚠️ |

## 核心 API

```ts
Python.initialize()                      // 校验运行时（13MB）
Python.createSession({ name, packages }) // 独立解释器
Python.exec(sessionId, code)             // 等结果：{ result, stdout, stderr }
Python.run(sessionId, code)              // 不等：输出走事件
Python.writeStdin(sessionId, line)
Python.installPackages(sessionId, ['numpy'])
Python.terminateSession(sessionId) / restartSession / listSessions
Python.addListener('sessionOutput' | 'sessionState' | 'sessionDone' | 'sessionError', cb)
```

编辑器（`capacitor-python/editor`，CodeMirror 6，~140KB gzip）：

```ts
initEditor('#editor', { value, theme, onRun, onChange })
destroyEditor('#editor')
getEditorValue / setEditorValue / insertEditorText / focusEditor / setEditorReadOnly
```

## 典型场景

- 教学/练习类 App：内置 Python 编辑器 + 运行结果；
- 用户自定义脚本（自动化、数据处理）；
- 把 Python 当"插件脚本"执行，出问题不影响主程序；
- 用 Python 做原型验证（numpy/pandas 可用）。

## 必须知道的坑

1. **旧 WebView 跑不了**：Android WebView < 90、iOS < 16.4 会在解析 wasm glue 时报错，
   用 `checkPythonSupport()` 提前判断；
2. **`input()` 默认不是真交互**：没有 SharedArrayBuffer，只能预输入队列；
3. **不能装任意 C 扩展**：只有 Pyodide 已编译的轮子可用；
4. **内存 50MB/会话**，iOS 注意 jetsam（≤3 个会话）；
5. **首次加载 5~10 秒**（编译 9.2MB wasm），要有 loading UI；
6. **ssl 是桩**：`requests` 走浏览器，拿不到证书/超时控制。

## 实测

Android 16 模拟器：加载 8.4s、执行 37ms、多会话隔离、死循环超时只杀单会话、编辑器全通过。
Android 10 真机（WebView 87）：按预期 `UNSUPPORTED_WEBVIEW`，不崩溃。
iOS：GitHub Actions 模拟器 autotest。
