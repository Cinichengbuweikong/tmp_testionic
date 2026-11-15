# capacitor-my-plugin

一个用于学习/演示的最小 Capacitor 插件（Android + iOS + Web），提供 `hello` 方法。

## 安装

```bash
npm install ./plugins/capacitor-my-plugin
npx cap sync
```

## 用法

```ts
import { MyPlugin } from 'capacitor-my-plugin';

const { message } = await MyPlugin.hello({ name: 'jack' });
console.log(message);
```

> 参数必须传**对象**（`{ name }`）。Capacitor 的桥只接受对象形式的 options，
> 直接传 `hello('jack')` 原生侧会读不到参数（会使用默认值 `world`）。

## API

| 方法 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `hello(options)` | `{ name?: string }`，默认 `world` | `{ message: string }` | 返回问候语 |

## 平台支持

| Android | iOS | Web |
| --- | --- | --- |
| ✅ `HelloPlugin.java` | ✅ `MyPlugin.swift` | ✅ `MyPluginWeb` |

## 构建

```bash
cd plugins/capacitor-my-plugin
npm install
npm run build      # tsc → dist/esm
```

然后回到项目根：

```bash
npx cap sync
npm run android:deploy -- -Device <id>
# iOS：推 CI 或 Xcode 构建
```
