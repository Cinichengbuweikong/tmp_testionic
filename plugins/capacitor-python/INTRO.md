# capacitor-python · 插件概览

**一句话**：在 Capacitor App 里跑真正的 Python —— Android 用**原生 CPython 3.11**（Chaquopy），
iOS/Web 用 **Pyodide（CPython 3.14 → WebAssembly）**；同一套 API：多会话、实时 stdout/stderr、
阻塞 stdin、包安装、框架无关的 Python 编辑器。

## 平台矩阵

| | Android 7+ | iOS 15+ | Web |
| --- | --- | --- | --- |
| 引擎 | **原生 CPython 3.11**（Chaquopy） | Pyodide（WASM） | Pyodide（WASM） |
| 依赖 WebView | ❌ 不依赖 | ✅ 需要 iOS 16.4+ | ✅ 需要 Chrome 90+ |
| 多会话 | ✅ 独立 globals + 线程 | ✅ 独立 Worker | ✅ 独立 Worker |
| 实时 stdout/stderr | ✅ | ✅ | ✅ |
| 阻塞 `input()` | ✅ | ⚠️ 队列模式 | ⚠️ 队列模式 |
| C 扩展包 | ✅ 构建期 pip 打包 | ⚠️ 仅 Pyodide 轮子 | ⚠️ 仅 Pyodide 轮子 |
| 体积 | +19MB（3 ABI） | 0 | 0（13MB 资源） |

> Chaquopy 只有 Android 版；iOS 原生等价物是 BeeWare `Python-Apple-support`，
> 但需要宿主动 Xcode 工程 + 体积 +55MB，所以暂未接入（README 有完整方案）。

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

1. **Android 没有进程级隔离**：Python 段错误会带走 App；普通异常/死循环不影响其它会话；
2. **中断是协作式的**：超时会清空该会话，但线程要下一条 bytecode 才退出；
3. **C 扩展只能构建期打包**（Chaquopy `pip { install }`），运行时只能装纯 Python wheel；
4. **APK +19MB**（arm64 + arm32 + x86_64），正式发布建议 ABI split；
5. **iOS/Web 需要较新 WebView**（Chrome 90+/iOS 16.4+），Android 原生不受影响。

## 实测

Android 10 真机（WebView 87，正是 Pyodide 跑不动的环境）：原生 CPython 3.11.17，
`initialize/createSession/exec`、实时输出、异常、多会话隔离、阻塞 stdin、编辑器、完整 autotest 全通过。
Android 16 模拟器同样通过；iOS 走 Pyodide，CI 全绿。
