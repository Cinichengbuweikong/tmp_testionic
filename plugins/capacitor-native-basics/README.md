# capacitor-native-basics

Capacitor 原生基础能力插件：原生 Toast、设备信息、echo 自检。
Android 已完整验证；iOS 已按 Capacitor 8 模板实现（`Package.swift` + Swift），编译验证在 CI 进行；Web 有降级实现。
也是本仓库里「插件长什么样」的参考实现。

## 安装

```bash
npm install ./plugins/capacitor-native-basics
npx cap sync android
```

## 使用

```js
import { NativeBasics } from 'capacitor-native-basics';

await NativeBasics.showToast({ text: '你好', duration: 'short' });

const info = await NativeBasics.getDeviceInfo();
// { manufacturer, model, osVersion, sdkInt, isEmulator }

const echoed = await NativeBasics.echo({ value: 'ping' }); // { value: 'ping' }
```

浏览器（`npm run dev`）里会自动走 `src/web.ts` 的降级实现，不会报错。

## API

| 方法 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `echo` | `{ value: string }` | `{ value: string }` | 通道自检 |
| `showToast` | `{ text: string, duration?: 'short' \| 'long' }` | `void` | 原生 Toast |
| `getDeviceInfo` | — | `DeviceInfo` | 制造商 / 型号 / 系统版本 / 平台 / API Level / 是否模拟器 |

`DeviceInfo`：`manufacturer`、`model`、`osVersion`、`platform`（`android` / `ios` / `web`）、`sdkInt`（仅 Android）、`isEmulator`。

## 各平台行为

| 平台 | `showToast` | `getDeviceInfo` |
| --- | --- | --- |
| Android | 系统 `Toast` | `Build.*`，`sdkInt` 为 API Level |
| iOS | 自绘浮层（系统没有 Toast，2s / 3.5s 后淡出） | `UIDevice`，不返回 `sdkInt` |
| Web | `console.log` | `navigator.userAgent`，`platform: 'web'` |

## 目录结构

```
capacitor-native-basics/
├─ package.json          # "capacitor" 字段告诉 CLI 这是插件
├─ tsconfig.json
├─ Package.swift         # iOS 的 SPM 包定义
├─ src/
│  ├─ definitions.ts     # 类型即 API 契约（TS）
│  ├─ index.ts           # registerPlugin + web 降级入口
│  └─ web.ts             # 浏览器实现
├─ android/
│  ├─ build.gradle       # library 模块，Java 21，从 rootProject.ext 取 SDK 版本
│  └─ src/main/java/com/testionic/nativebasics/NativeBasicsPlugin.java
└─ ios/
   └─ Sources/NativeBasicsPlugin/NativeBasicsPlugin.swift
```

## 修改后如何生效

- 只改 Java：直接重新 `gradlew assembleDebug` 即可，**不需要** `cap sync`。
- 改了 `src/*.ts`：先在插件目录 `npm run build`（tsc 输出到 `dist/`），再构建应用。
- 改了 `package.json` 的 `capacitor` 字段 / 增删插件：需要 `npx cap sync android`（或 `ios`）。
- 改了 Swift：本机无法编译，push 后跑 GitHub Actions 的 iOS workflow 验证；本机可先 `npm run verify:ios` 做静态检查。
