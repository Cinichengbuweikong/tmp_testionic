# testionic

This template should help get you started developing with Vue 3 in Vite.

## Recommended IDE Setup

[VSCode](https://code.visualstudio.com/) + [Volar](https://marketplace.visualstudio.com/items?itemName=Vue.volar) (and disable Vetur) + [TypeScript Vue Plugin (Volar)](https://marketplace.visualstudio.com/items?itemName=Vue.vscode-typescript-vue-plugin).

## Customize configuration

See [Vite Configuration Reference](https://vitejs.dev/config/).

## Project Setup

```sh
npm install
```

### Compile and Hot-Reload for Development

```sh
npm run dev
```

### Compile and Minify for Production

```sh
npm run build
```

## 真机 / 模拟器开发（Live Reload）

改前端代码后想立即在 Android/iOS 设备上看到效果（而不是只有浏览器热更新），用 Capacitor 的 live reload：

```bash
# 1) 起 Vite（必须显式监听 IPv4：Vite 3 在 Windows 上默认只绑 ::1，adb reverse 连不上）
npm run dev -- --host 127.0.0.1 --port 5173

# 2) 设备端口反转到本机（切换 USB 模式 / 拔插数据线后会丢，重新执行即可）
adb reverse tcp:5173 tcp:5173

# 3) live reload 构建 + 安装（--forwardPorts 会自动再做一次 adb reverse）
npx cap run android -l --host localhost --port 5173 --forwardPorts 5173:5173
```

- **不写任何配置文件**：`cap run -l` 只把 `server.url` 写进平台目录里的临时配置，正常退出会还原；
  被 Ctrl+C/kill 掉时配置会残留，用 `npx cap copy android` 恢复。
- iOS：`npx cap run ios -l --external --host <LAN IP> --port 5173`（需要 Mac）。
- 需要 **JDK 21**（项目 `compileOptions = 21`）：`JAVA_HOME` 指向 Android Studio 的 JBR
  （`C:\Program Files\Android\Android Studio\jbr`）。
- 插件的 `dist/` 必须先构建：`npm run build:plugins`。
- 已知限制：live reload 下 `Capacitor.convertFileSrc()` 不可用（`_capacitor_file_` 由原生提供，请求会打到 Vite → 404）；
  `capacitor-data-channel` 需要 `startHttp/startWebSocket({ debug: true })` 才会放行 dev origin（详见其 README）。