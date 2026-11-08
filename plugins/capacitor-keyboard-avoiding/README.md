# capacitor-keyboard-avoiding

键盘避让插件：**监听键盘弹出/隐藏（带高度与视口信息）**，并可选**自动避让**（让 WebView 内容不被键盘遮挡）。
Android / iOS / Web 三端可用。

> 目标效果类似 React Native 的 `KeyboardAvoidingView`：
> - 由插件提供"键盘高度 / 当前可用视口高度"和键盘事件；
> - 页面可以自己据此做精细布局，也可以直接开自动避让让 WebView 自动缩到键盘上方。

## 设计要点

- **两套能力，分开用**：
  - 只监听：`addListener('keyboardChanged')` —— 页面自己实现避让（灵活，推荐给复杂表单）；
  - 自动避让：`enable()` —— 原生负责把内容限制在键盘上方（简单，适合常规页面）。
- **Android 做"补差"而不是盲加**：真机上系统（adjustResize / Capacitor SystemBars / webview-to-edge）
  通常已经把 WebView 缩到了键盘上方，插件只在"系统还没让出空间"时补齐缺口，**不会双重缩小**。
- **iOS 才是自动避让的主场**：WKWebView（`contentInsetAdjustmentBehavior = .never`）默认不避让，
  插件用 `scrollView.contentInset.bottom` 接管（iOS 标准做法）。
- **不抢 insets 监听器**：Android 侧只用 `ViewCompat.getRootWindowInsets()` 读取 IME insets，
  不注册 `OnApplyWindowInsetsListener`，因此**不会**和 `capacitor-webview-to-edge` / Capacitor
  内置 `SystemBars` 的监听器互相覆盖。
- **三端行为一致**：Web 端用 `visualViewport` 计算键盘高度，同样派发 `keyboardChanged`。

## 安装

```bash
npm install ./plugins/capacitor-keyboard-avoiding
npx cap sync
```

## 用法

### 方式一：纯监听（自己控制布局）

```ts
import { KeyboardAvoiding } from 'capacitor-keyboard-avoiding';

const handle = await KeyboardAvoiding.addListener('keyboardChanged', (s) => {
  // s.visible / s.height / s.viewportHeight ...
  document.body.style.paddingBottom = s.visible ? `${s.height}px` : '0px';
});

// 解除
await handle.remove();
```

### 方式二：自动避让（交给插件）

```ts
await KeyboardAvoiding.enable();   // 键盘弹出时 WebView 自动缩到键盘上方
await KeyboardAvoiding.disable();  // 恢复
```

### 组合使用（推荐给"输入框在底部"的表单）

```ts
await KeyboardAvoiding.enable();                       // 兜底：保证内容不被遮挡
const h = await KeyboardAvoiding.addListener('keyboardChanged', (s) => {
  // 可选：滚动到当前聚焦的输入框
  if (s.visible) document.activeElement?.scrollIntoView({ block: 'center' });
});
```

## API 详细文档

### enable() / disable()

**功能**：开启 / 关闭自动避让（不改变键盘事件派发，事件始终可用）。

**签名**：`enable(): Promise<KeyboardState>`、`disable(): Promise<KeyboardState>`

**注意事项**：
- 默认关闭（`autoAvoid: false`）——纯监听模式，不改变系统/其它插件已有的布局行为；
- **Android**：开启后插件用 WebView 的 `bottomMargin` 补足系统未让出的部分。系统已避让时不会重复缩小；
- **iOS**：开启后把键盘高度加到 `scrollView.contentInset.bottom`；关闭时还原为进入前的 inset；
- 键盘已弹出时调用 `enable()` 会立即生效（无需等下一次键盘事件）；
- 关闭时若 WebView 的 `bottomMargin` 被其它逻辑改过，插件只还原到"进入避让前的基线值"。

---

### setEnabled(options)

**功能**：按参数设置自动避让开关。

**签名**：`setEnabled(options: { enabled: boolean }): Promise<KeyboardState>`

---

### getState() / isEnabled()

**功能**：读取当前状态（键盘是否可见、自动避让是否开启、键盘高度、可用视口高度）。

**签名**：`getState(): Promise<KeyboardState>`、`isEnabled(): Promise<KeyboardState>`

**注意事项**：两个方法返回结构相同（`isEnabled` 为兼容命名保留）；只读，不改变任何状态。

---

### addListener('keyboardChanged', callback)

**功能**：监听键盘弹出/隐藏及其高度变化（例如切换输入法导致键盘变高/变矮）。

**签名**：

```ts
addListener(
  eventName: 'keyboardChanged',
  listenerFunc: (state: KeyboardState) => void,
): Promise<PluginListenerHandle>
```

**注意事项**：
- 事件**去重**：只有 `visible` 或 `height` 真正变化才派发；
- 键盘弹出时一定会先派发一次（`visible: true` + 高度），收起时派发 `visible: false` + `height: 0`；
- 解除监听：`handle.remove()`；一次性解除全部：`removeAllListeners()`。

## KeyboardState 字段

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `visible` | `boolean` | 键盘是否可见 |
| `height` | `number` | 键盘高度，**CSS px**（不可见时 0） |
| `heightPx` | `number` | 键盘高度，物理像素 |
| `viewportHeight` | `number` | 当前**可用内容区高度**（CSS px）——键盘弹出时就是"键盘上方剩余空间"，可直接用于布局 |
| `viewportHeightPx` | `number` | 同上，物理像素 |
| `autoAvoid` | `boolean` | 自动避让是否开启 |
| `animationDuration` | `number` | 键盘动画时长（毫秒）；iOS 取系统值，Android/Web 为 0 |

## 平台差异与注意事项

### Android

- **系统通常会自己避让**：实测真机（MIUI 12 / Android 10）键盘弹出后 WebView 高度 2310px → 1382px、
  `innerHeight` 840css → 503css（键盘 928px / 337css）。所以自动避让默认关闭，开启时也只做"补差"；
- 键盘高度来自 `WindowInsets.Type.ime()`（`ViewCompat.getRootWindowInsets` 读取原始 insets，
  **不注册** OnApplyWindowInsetsListener，避免与 `webview-to-edge` / `SystemBars` 抢监听器）；
- 键盘状态用 `ViewTreeObserver.OnGlobalLayoutListener` 触发检测（键盘弹出/收起都会引起布局变化）；
- Edge-to-edge（Android 15+/targetSdk 35+）场景下若没有任何组件处理 insets，autoAvoid 会补齐缺口。

### iOS

- WKWebView（Capacitor 设 `contentInsetAdjustmentBehavior = .never`）**不会**自动避让，
  所以 iOS 建议开启 autoAvoid（或页面自己处理）；
- 监听 `keyboardWillChangeFrameNotification` / `keyboardWillHideNotification`，
  键盘高度 = WebView 底部与键盘顶边的重叠（已考虑 Home Indicator 区域）；
- autoAvoid 通过 `scrollView.contentInset.bottom` 实现（不破坏 WebView 布局）；
- iOS 的 point 单位 = CSS px，因此 `height` 直接用于 CSS 即可。

### Web

- 用 `visualViewport` 计算键盘高度（`window.innerHeight - (visualViewport.height + offsetTop)`），
  同样派发 `keyboardChanged`；`enable()` 只记录开关，不能真正改变浏览器行为；
- 小于 80px 的视口变化视为浏览器 UI 折叠，不当作键盘。

## 实测数据（真机 Redmi K30S Ultra / MIUI 12 / Android 10）

| 场景 | 结果 |
| --- | --- |
| 键盘弹出（autoAvoid 关闭） | `innerHeight` 840 → **503**；事件 `{visible:true, height:337.45css/928px, viewportHeight:502.55css/1382px}`，事件总数 1 ✅ |
| 键盘已弹出时 `enable()` | `innerHeight` 保持 **503（未双重缩小）**，`autoAvoid:true` ✅（补差逻辑生效） |
| 键盘关闭 | `innerHeight` 回到 **840**；事件 `{visible:false, height:0, viewportHeight:840}`，事件总数 2 ✅ |
| `disable()` | `autoAvoid:false`，布局恢复正常 ✅ |
| 冒烟测试（真机 + 模拟器） | 读取状态 / 开启避让 / 关闭避让 / 监听 / 停止监听 全 PASS ✅ |

## 常见疑问：为什么真机上「开启/关闭避让」看起来没有区别？

这是**正常现象，不是插件失效**。在本项目真机（MIUI 12 / Android 10）上用「显示布局边界」观察，
enable 与 disable 状态下键盘弹出时 WebView 都会被顶到键盘上方，原因有两层：

1. **系统层**：Capacitor 模板的 `windowSoftInputMode` 是 `adjustResize`，键盘弹出时**Android 系统自己
   就把窗口内容缩到键盘上方**（实测 WebView 2310px → 1382px）；
2. **项目层**：`capacitor-webview-to-edge` 插件在 insets 分发时把 IME 高度作为底部 padding
   （`paddingBottom = ime.bottom`），即便系统不缩窗口它也会避让。

本插件的 `disable()` 语义是"**不再额外加避让**"，它无法撤销上面两个已经存在的行为
（那属于窗口配置 / 其它插件的职责）。

| 状态 | WebView 表现 | 原因 |
| --- | --- | --- |
| `autoAvoid: false`（disable） | 仍被键盘顶起 | 系统 adjustResize + to-edge 的 IME padding |
| `autoAvoid: true`（enable） | 仍被键盘顶起（**不会双重缩小**） | 插件检测到系统已让出空间 → 补差量 0 → 不加 margin |

**如何确认插件真的在工作**：
1. `getState().autoAvoid` 是否随 enable/disable 正确切换（true/false）；
2. `keyboardChanged` 事件的 `height` / `viewportHeight` 是否与屏幕实际一致
   （真机实测 height 337.45css/928px、viewportHeight 502.55css/1382px）；
3. 想看 autoAvoid 的"布局差异"，需要一个**系统与其它插件都没有处理避让**的环境
   （例如纯 edge-to-edge 且 `SystemBars` 的 insetsHandling 不处理、也未启用 to-edge），
   此时 disable 会让 WebView 延伸到键盘下方、enable 会把缺口补齐。

**想彻底关闭系统级避让**（键盘直接叠在内容上）：需修改宿主
`android:windowSoftInputMode="adjustNothing"`（并同步调整其它插件的 IME 处理），
这属于应用/窗口配置，不是本插件运行时可单独控制的。

## 开发

```bash
cd plugins/capacitor-keyboard-avoiding
npm install
npm run build        # tsc → dist/esm
```

- Android：`android/src/main/java/com/testionic/keyboardavoiding/KeyboardAvoidingPlugin.java`
- iOS：`ios/Sources/KeyboardAvoidingPlugin/KeyboardAvoidingPlugin.swift`（Windows 无法编译，靠 GitHub Actions 验证）
- 插件对外名称：npm 包 `capacitor-keyboard-avoiding`、`registerPlugin('KeyboardAvoiding')`、
  iOS SPM 包 `CapacitorKeyboardAvoiding`
