# capacitor-python · 插件概览

**一句话**：在 Capacitor App 里跑真正的 Python —— Android 用 **Chaquopy**、iOS 用 **Python-Apple-support**，
两端都是**原生 CPython 3.11**（不依赖 WebView）；Web 用 Pyodide。同一套 API：多会话、
实时 stdout/stderr、阻塞 stdin、框架无关的 Python 编辑器。

## 平台矩阵

| | Android 7+ | iOS 15+ | Web |
| --- | --- | --- | --- |
| 引擎 | **Chaquopy（原生 CPython 3.11）** | **Python-Apple-support（原生 CPython 3.11）** | Pyodide（WASM） |
| 依赖 WebView | ❌ | ❌ | ✅ 需要 Chrome 90+ |
| 多会话 | ✅ 独立 globals + 线程 | ✅ 独立 globals + 线程 | ✅ 独立 Worker |
| 实时 stdout/stderr | ✅ | ✅ | ✅ |
| 阻塞 `input()` | ✅ | ✅ | ⚠️ 队列模式 |
| C 扩展包 | ✅ 构建期 pip 打包 | ⚠️ 待宿主 Run Script（zlib/ssl/sqlite3 等） | ⚠️ 仅 Pyodide 轮子 |
| 体积 | +19MB（3 ABI） | +20MB（stdlib + libPython） | 0（13MB 资源） |

> Chaquopy 只有 Android 版；iOS 用 BeeWare 的 `Python-Apple-support`（同样是 CPython 3.11），
> 已经接入并通过 CI 的 iOS 模拟器 autotest。

## 核心 API

```ts
Python.initialize()                            // 启动解释器
Python.createSession({ name })                 // 独立会话
Python.exec(sessionId, code)                   // { result, stdout, stderr }
Python.run(sessionId, code)                    // 不等：输出走事件
Python.writeStdin(sessionId, 'Alice')          // Android 上 input() 会真阻塞
Python.installPackages(sessionId, [url])       // 原生：纯 Python wheel
Python.terminateSession / restartSession / listSessions
Python.addListener('sessionOutput' | 'sessionState' | 'sessionDone' | 'sessionError', cb)
```

编辑器（`capacitor-python/editor`，CodeMirror 6，~140KB gzip）：

```ts
initEditor('#editor', { value, theme, onRun, onChange })
destroyEditor('#editor')
getEditorValue / setEditorValue / insertEditorText / focusEditor / setEditorReadOnly
```

## 典型场景

- 教学/练习类 App：内置编辑器 + 真机执行 Python；
- 用户自定义脚本（自动化、数据处理）；
- 需要 CPython 语义（C 扩展、真实线程）而不是浏览器沙盒时。

## 必须知道的坑

1. **原生引擎没有进程级隔离**：Python 段错误会带走 App；普通异常/死循环不影响其它会话；
2. **中断是协作式的**：超时会清空该会话，但线程要下一条 bytecode 才退出；
3. **C 扩展**：Android 构建期打包（Chaquopy `pip { install }`）；iOS 目前没有 `lib-dynload`（需宿主 Run Script）；
4. **APK +19MB**（arm64 + arm32 + x86_64），正式发布建议 ABI split；
5. **只有 Web 需要较新浏览器**（Chrome 90+）；Android/iOS 原生不受 WebView 版本影响。

## 实测

Android 10 真机（WebView 87，Pyodide 跑不动的环境）：原生 CPython 3.11.17，全部用例通过。
iOS 模拟器（GitHub Actions）：原生 CPython 3.11.18，`exec` 返回 3、stdout 事件、编辑器，autotest 全绿。
Android 16 模拟器同样通过。
