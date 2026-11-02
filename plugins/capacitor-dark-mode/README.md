# capacitor-dark-mode

修复 Android / WebView 上「暗色模式把颜色搞坏」的问题，并提供跨平台的主题查询与变化监听。

## 解决什么问题

Android 10 起系统提供 **Force Dark（算法暗色）**：App 没做深色适配时，系统会把界面反色成深色。
WebView 也支持这套机制，但在 **MIUI 12（Android 10）** 上它是灾难：

| 实测项 | 结果 |
| --- | --- |
| Activity 的 `Configuration` 是否 night | ✅ 是（App 确实进了深色模式） |
| WebView 里 `matchMedia('(prefers-color-scheme: dark)')` | ❌ **false**（MIUI 的 WebView 不报告） |
| 页面实际渲染 | 按亮色渲染后，再被 MIUI 强制反色 → 红/黄/绿等主题色严重偏色 |

结论：**不能指望 MIUI 的 WebView 告诉你现在是不是深色**。

本插件换了一条路：

1. **默认关掉 WebView 的算法暗色**（`setForceDarkAllowed(false)` + `WebSettingsCompat`），
   彻底消灭“颜色被反掉”；
2. 由插件把当前主题**注入页面**：
   ```html
   <html data-theme="dark" style="color-scheme: dark">
   ```
3. **自动兼容老 CSS**：把页面里 `@media (prefers-color-scheme: dark)` 的规则复制成
   `html[data-theme="dark"]` 选择器，这样没改过 CSS 的项目也能在 MIUI 上正确变深色；
4. 监听系统深浅色切换，重新注入并派发 `themeChanged` 事件。

## 安装

```bash
npm install ./plugins/capacitor-dark-mode
npx cap sync
```

## 用法

```js
import { DarkMode } from 'capacitor-dark-mode';

// 跟随系统（默认）
await DarkMode.setTheme({ theme: 'system' });

// 强制亮色 / 暗色（会忽略系统设置）
await DarkMode.setTheme({ theme: 'light' });
await DarkMode.setTheme({ theme: 'dark' });

// 查询当前状态
const info = await DarkMode.getTheme();
// { mode: 'system', isDark: true, overridden: false }

// 监听系统深浅色变化
const handle = await DarkMode.addListener('themeChanged', (info) => {
  console.log('theme changed', info);
});
// await handle.remove();

// 调试信息（含 forceDark 状态、UA、是否已注入）
const debug = await DarkMode.getInfo();
```

## 页面怎么写样式

两种写法都支持：

```css
/* 方式一：标准媒体查询（插件会自动映射，无需改动） */
@media (prefers-color-scheme: dark) {
  :root { --bg: #121212; --text: #eee; }
}

/* 方式二：直接用 data-theme（推荐，行为最可控，两端一致） */
html[data-theme='dark'] {
  --bg: #121212;
  --text: #eee;
}
```

> 插件注入的映射只处理**页面加载时已存在**的样式表，并且是对 `@media (prefers-color-scheme: dark)`
> 的静态复制；运行时动态插入的样式不会被自动映射。新项目建议直接用 `html[data-theme]` 写。

## API

| 方法 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `setTheme` | `{ theme: 'system' \| 'light' \| 'dark' }` | `ThemeInfo` | 设置主题并注入页面 |
| `getTheme` | — | `ThemeInfo` | 当前主题状态 |
| `setForceDark` | `{ enabled: boolean }` | `void` | 开关 WebView 算法暗色，默认关闭 |
| `getInfo` | — | `DarkModeInfo` | 调试信息：forceDark 状态、是否已注入、UA |
| `addListener` | `'themeChanged'` | `PluginListenerHandle` | 系统主题变化 / setTheme 之后触发 |

`ThemeInfo`：`{ mode, isDark, overridden }`。

## 各平台行为

| 平台 | 实现 |
| --- | --- |
| Android | 关 forceDark；`evaluateJavascript` 注入 `data-theme` / `color-scheme`；CSSOM 重写媒体查询；`onConfigurationChanged` 派发事件 |
| iOS | `prefers-color-scheme` 本身正常；`overrideUserInterfaceStyle` 实现强制亮/暗；隐藏 UIView 监听 `traitCollection` 变化 |
| Web | 用 `matchMedia` 判断，注入同样的 `data-theme` |

## 已验证环境

- Redmi K30S Ultra / Android 10 / MIUI 12 / WebView 87
- Pixel 6 模拟器 / Android 16
- iOS 模拟器（GitHub Actions / Xcode 26）

> ⚠️ 与 MIUI 相关的结论都基于 **MIUI 12 / Android 10 / WebView 87**。换 ROM 或 WebView 大版本后，
> 先用 `getInfo()` 看 `prefers-color-scheme` 与 forceDark 的实际状态，再决定是否调整策略。

## 已知限制

- 只处理 `prefers-color-scheme: dark` 的媒体查询；`light` 分支（浅色为默认时一般不需要）不处理。
- 带复合条件的媒体查询（如 `(prefers-color-scheme: dark) and (min-width: 600px)`）会被简化为只按主题匹配。
- 跨域样式表读不到 `cssRules`，会被跳过（Capacitor 应用一般是同源，不受影响）。
- 运行时通过 JS 动态插入的 `<style>` 不会自动映射，建议这类样式直接用 `html[data-theme]` 写。
