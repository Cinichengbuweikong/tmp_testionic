# capacitor-set-force-dark

设置 / 关闭 **WebView 的 forceDark（算法暗色）**，并把 App 期望的主题注入页面，解决
ROM（尤其 MIUI）把页面颜色反坏的问题。

> 注意：这个插件管的是 **WebView 里的页面**，**不控制**系统的深色模式开关，也不影响系统 UI。

## 解决什么问题

Android 10 起系统提供 **Force Dark（算法暗色）**：App 没做深色适配时，系统会把界面反色成深色，
WebView 也支持这套机制。但在 **MIUI 12（Android 10）** 上它是灾难：

| 实测项（Redmi K30S Ultra / MIUI 12 / WebView 87） | 结果 |
| --- | --- |
| Activity 的 `Configuration` 是否 night | ✅ 是（App 确实进了深色模式） |
| WebView 里 `matchMedia('(prefers-color-scheme: dark)')` | ❌ **false**（MIUI 的 WebView 不报告） |
| 页面实际渲染 | 按亮色渲染后，再被 MIUI 强制反色 → 红/黄/绿等主题色严重偏色 |

结论：**不能指望 MIUI 的 WebView 告诉你现在是不是深色**。

本插件的做法：

1. **默认关掉 WebView 的算法暗色**（`setForceDarkAllowed(false)` + `WebSettingsCompat`），
   消灭“颜色被反掉”；
2. 把当前主题**注入页面**：`<html data-theme="dark" style="color-scheme: dark">`；
3. **兼容老 CSS**：把页面里 `@media (prefers-color-scheme: dark)` 的规则复制成
   `html[data-theme="dark"]` 选择器，没改过 CSS 的项目也能正确变深色；
4. 监听系统深浅色切换，重新注入并派发 `themeChanged`。

## 安装

```bash
npm install ./plugins/capacitor-set-force-dark
npx cap sync
```

## 用法

```js
import { SetForceDark } from 'capacitor-set-force-dark';

// 显式关闭 WebView 的算法暗色（插件加载时默认就是关的）
await SetForceDark.setForceDark({ enabled: false });

// 让 WebView 跟随系统 / 强制亮色 / 强制暗色
await SetForceDark.setTheme({ theme: 'system' });
await SetForceDark.setTheme({ theme: 'dark' });

// 查询当前状态
const theme = await SetForceDark.getTheme();
// { requestedTheme: 'system', isDark: true, isForced: false }

// 监听主题变化（系统切换 / 调用 setTheme 后都会触发）
const handle = await SetForceDark.addListener('themeChanged', (t) => {
  console.log(t.isDark);
});
// await handle.remove();

// 调试信息：forceDark 状态、是否已注入、UA
const info = await SetForceDark.getInfo();
```

## 页面里拿到的 `data-theme`（重要）

`setTheme()` 最终会往 `<html>` 上写 `data-theme`，**值永远是 `"light"` 或 `"dark"` 两种之一**——
亮色时是 `data-theme="light"`，**不是"没有属性"**。

| 调用 | 当前系统 | `<html>` 上 | `color-scheme` |
| --- | --- | --- | --- |
| `setTheme({ theme: 'light' })` | 任意 | `data-theme="light"` | `light` |
| `setTheme({ theme: 'dark' })` | 任意 | `data-theme="dark"` | `dark` |
| `setTheme({ theme: 'system' })` | 深色 | `data-theme="dark"` | `dark` |
| `setTheme({ theme: 'system' })` | 亮色 | `data-theme="light"` | `light` |

为什么不做成"亮色时没有属性"：

1. 两种主题都显式，`html[data-theme='light']` / `html[data-theme='dark']` 都能直接命中；
2. 没被覆盖到的元素仍然走 CSS 默认值，两种写法可以混用；
3. 和媒体查询补丁一致——补丁映射出来的是 `html[data-theme="dark"]`，亮色时自然不命中。

> ⚠️ **首屏例外**：注入是异步的（页面加载后几十~几百毫秒；Android 上还会在 200/800/2000ms 补发一次），
> 所以页面刚出现的一瞬间 `data-theme` 可能还不存在。建议 CSS 默认值按亮色写，
> 这样"还没注入"时是正常的亮色，不会闪烁。

判断当前是否深色，**不要**用"有没有属性"，用：

```js
const { isDark } = await SetForceDark.getTheme();

// 或者注入完成后同步读取
const isDarkNow = document.documentElement.dataset.theme === 'dark';
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

/* 也可以两种主题都显式写 */
html[data-theme='light'] {
  --bg: #ffffff;
  --text: #222;
}
```

> 插件注入的映射只处理**页面加载时已存在**的样式表，并且是对 `@media (prefers-color-scheme: dark)`
> 的静态复制；运行时动态插入的样式不会被自动映射。新项目建议直接用 `html[data-theme]` 写。

## API 一览

| 方法 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `setTheme` | `{ theme: 'system' \| 'light' \| 'dark' }` | `ThemeState` | 设置 WebView 主题并注入页面 |
| `getTheme` | — | `ThemeState` | 当前 WebView 主题状态 |
| `setForceDark` | `{ enabled: boolean }` | `void` | 开关 WebView 算法暗色，默认关闭 |
| `getInfo` | — | `ForceDarkInfo` | 调试信息：forceDark 状态、是否注入、UA |
| `addListener` | `'themeChanged'` | `PluginListenerHandle` | 主题变化回调 |

`ThemeState`：`{ requestedTheme, isDark, isForced }`
`ForceDarkInfo`：`ThemeState & { forceDarkEnabled, themeInjected, webViewUserAgent }`

## API 行为详解

### `setTheme()` 到底做了什么

它不只是改一个属性，而是一次完整的"应用主题"动作，按顺序做 5 件事：

| 步骤 | Android | iOS | Web |
| --- | --- | --- | --- |
| ① 记录 `requestedTheme` | ✅ | ✅ | ✅ |
| ② 解析实际 `isDark` | `dark`→true / `light`→false / `system`→读 Activity 的 `Configuration.uiMode` | 同左，读 `traitCollection` | 同左，读 `matchMedia` |
| ③ 注入页面 | `evaluateJavascript` 写 `data-theme` + `color-scheme` | 同左 | 同左 |
| ④ 首次注入时打补丁 | 扫描样式表，把 `@media (prefers-color-scheme: dark)` 复制成 `html[data-theme="dark"]` | 同左 | 同左 |
| ⑤ 派发 `themeChanged` 并 resolve | ✅ | ✅ | ✅ |

注入脚本实际做的事：

```js
document.documentElement.setAttribute('data-theme', 'dark'); // 或 light
document.documentElement.style.colorScheme = 'dark';

// 只有第一次会执行，结果插到 <style id="cap-force-dark-patch">
for (const sheet of document.styleSheets) {
  for (const rule of sheet.cssRules) {
    if (mediaMatches(rule, 'prefers-color-scheme: dark')) {
      copyRuleWithSelector('html[data-theme="dark"]');
    }
  }
}
window.__capForceDarkPatched = true;
```

两个容易忽略的细节：

- **iOS 会真的改变 `prefers-color-scheme`**：因为 iOS 端额外设置了 `overrideUserInterfaceStyle`
  （作用在 viewController 和 WKWebView 上），媒体查询本身会翻转；
  Android 上 MIUI 的 WebView 不吃这套，只能靠注入 + 补丁。
- **不会动系统设置**：不调用 `AppCompatDelegate`，也不改系统深色模式开关，
  只影响这个 WebView 里的页面。

### `getTheme()` 返回的字段

```js
const theme = await SetForceDark.getTheme();
// { requestedTheme: 'system', isDark: true, isForced: false }
```

| 字段 | 含义 | 取值 |
| --- | --- | --- |
| `requestedTheme` | 你**最后一次**通过 `setTheme` 请求的主题（默认 `'system'`） | `'system' \| 'light' \| 'dark'` |
| `isDark` | **解析后**实际该不该用深色 | `boolean` |
| `isForced` | 是否处于"强制"状态（`requestedTheme !== 'system'`） | `boolean` |

三个例子：

```js
// 系统深色 + 跟随系统
{ requestedTheme: 'system', isDark: true,  isForced: false }

// 系统深色，但强制亮色
{ requestedTheme: 'light',  isDark: false, isForced: true }

// 系统亮色 + 跟随系统
{ requestedTheme: 'system', isDark: false, isForced: false }
```

`getInfo()` 在此基础上多三个调试字段：

| 字段 | 含义 |
| --- | --- |
| `forceDarkEnabled` | WebView 的算法暗色当前是否开启（默认 `false`） |
| `themeInjected` | `data-theme` 是否已经写进页面 |
| `webViewUserAgent` | WebView 的 UA（排查 ROM/WebView 版本） |

> 注意：`getTheme()` 有副作用——它会**顺手再注入一次**页面，保证页面状态和返回值一致。
> 所以在启动时调一次是安全的。

### `addListener('themeChanged')` 的触发时机

只有两种情况会触发：

| 触发场景 | 条件 | 说明 |
| --- | --- | --- |
| **调用 `setTheme()`** | 任意值 | 即使设成和当前一样的值也会触发一次 |
| **系统深浅色切换** | 且 `requestedTheme === 'system'` | Android 走 `onConfigurationChanged`，iOS 走 `traitCollection` 变化，Web 走 `matchMedia` 变化 |

**不会**触发的场景：

- 调用 `getTheme()` / `setForceDark()`；
- 页面刷新（这时是插件自动注入，不发事件）；
- `requestedTheme` 是 `light` / `dark`（强制的）时，系统深浅色变化不会派发——结果没变。

回调参数和 `getTheme()` 的返回值完全一样：

```js
const handle = await SetForceDark.addListener('themeChanged', (theme) => {
  // { requestedTheme, isDark, isForced }
  document.body.classList.toggle('dark', theme.isDark);
});
```

> Android 上 `onConfigurationChanged` 也会因旋转、字体大小等触发，插件内部做了去重：
> **只有 `isDark` 真的变化时才派发**，不会因为转屏发无意义的事件。

## 各平台行为

| 平台 | 实现 |
| --- | --- |
| Android | 关 forceDark；`evaluateJavascript` 注入 `data-theme` / `color-scheme`；CSSOM 重写媒体查询；`onConfigurationChanged` 派发事件 |
| iOS | 没有 forceDark（`setForceDark` 是空操作）；`overrideUserInterfaceStyle` 实现强制亮/暗；隐藏 UIView 监听 `traitCollection`；同样注入 `data-theme` |
| Web | `matchMedia` 判断，注入同样的 `data-theme` |

## 已验证环境

- Redmi K30S Ultra / Android 10 / MIUI 12 / WebView 87
- Pixel 6 模拟器 / Android 16
- iOS 模拟器（GitHub Actions / Xcode 26）

> ⚠️ 与 MIUI 相关的结论都基于 **MIUI 12 / Android 10 / WebView 87**。换 ROM 或 WebView 大版本后，
> 先用 `getInfo()` 看 forceDark 的实际状态，再决定是否调整策略。

## 已知限制

- 只处理 `prefers-color-scheme: dark` 的媒体查询；`light` 分支（浅色为默认时一般不需要）不处理。
- 带复合条件的媒体查询（如 `(prefers-color-scheme: dark) and (min-width: 600px)`）会被简化为只按主题匹配。
- 跨域样式表读不到 `cssRules`，会被跳过（Capacitor 应用一般是同源，不受影响）。
- 运行时通过 JS 动态插入的 `<style>` 不会自动映射，建议这类样式直接用 `html[data-theme]` 写。
