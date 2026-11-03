# capacitor-system-info

系统与硬件信息插件：屏幕 / 视口 / 安全区 / 状态栏 / 导航栏 / 挖孔 / 系统 / WebView / 内存 / 存储 / 电量 / 网络 / App 信息。

## 设计原则

- **每种信息一个独立 API**：问什么就收集什么，按需调用；
- **互不影响**：每个 API 独立实现、独立容错，某个 API 因系统差异失败不影响其他 API；
- 纯只读，不需要任何运行时权限（仅声明 `ACCESS_NETWORK_STATE` 普通权限用于网络类型判断）。

## 安装

```bash
npm install ./plugins/capacitor-system-info
npx cap sync
```

## 用法

```ts
import { SystemInfo } from 'capacitor-system-info';

// 屏幕：物理分辨率 / CSS 尺寸 / 密度 / 刷新率 / 朝向
const screen = await SystemInfo.getScreenInfo();

// WebView 视口 —— CSS 里 100% 对应的就是 viewport.css
const viewport = await SystemInfo.getViewport();

// 安全区（iOS safe-area 语义，CSS px）
const safeArea = await SystemInfo.getSafeAreaInsets();

const statusBar = await SystemInfo.getStatusBarInfo();
const navBar = await SystemInfo.getNavigationBarInfo();
const cutout = await SystemInfo.getCutoutInfo();

const system = await SystemInfo.getSystemInfo();     // 平台/版本/型号/语言/深色模式…
const webView = await SystemInfo.getWebViewInfo();   // 包名/版本/UA
const memory = await SystemInfo.getMemoryInfo();
const storage = await SystemInfo.getStorageInfo();
const battery = await SystemInfo.getBatteryInfo();
const network = await SystemInfo.getNetworkInfo();
const app = await SystemInfo.getAppInfo();
```

## API 速查

| API | 返回 | 说明 |
| --- | --- | --- |
| `getScreenInfo()` | `ScreenInfo` | 屏幕物理分辨率 `px`、CSS 尺寸 `css`、`density`/`densityDpi`/`scaledDensity`、`refreshRate`、`orientation`、`rotationDegrees` |
| `getViewport()` | `ViewportInfo` | WebView 视口尺寸（**CSS 里 `100%` 的大小**），`px` + `css` |
| `getSafeAreaInsets()` | `SafeAreaInsets` | 四边需要避让的区域（CSS px），top≈状态栏、bottom≈导航栏/手势条 |
| `getStatusBarInfo()` | `StatusBarInfo` | 状态栏宽高（宽=屏幕宽）、`visible` |
| `getNavigationBarInfo()` | `NavigationBarInfo` | 底部导航栏宽高、`visible`、`mode`（iOS 上对应 Home Indicator） |
| `getCutoutInfo()` | `CutoutInfo` | 刘海/挖孔：`exists`、`insetsCss`、`boundingRectsPx` |
| `getSystemInfo()` | `SystemInfoData` | 平台/系统版本/API Level/厂商/型号/设备代号/是否模拟器/CPU ABI/语言/时区/24 小时制/字体缩放/深色模式 |
| `getWebViewInfo()` | `WebViewInfo` | WebView 包名 + 版本号 + UserAgent |
| `getMemoryInfo()` | `MemoryInfo` | 物理内存总量 / 当前可用（字节） |
| `getStorageInfo()` | `StorageInfo` | 数据分区总量 / 剩余（字节） |
| `getBatteryInfo()` | `BatteryInfo` | 电量 0~100（未知 -1）、是否充电 |
| `getNetworkInfo()` | `NetworkInfo` | `wifi/cellular/ethernet/vpn/none/unknown`、`connected`、`metered` |
| `getAppInfo()` | `AppInfo` | App id / 显示名 / version / build |

## 单位约定（重要）

- 字段名带 `px` 的是**物理像素**：Android 原生 px；iOS 为 pt × scale。
- 字段名带 `css` 的是 **CSS px**：Android 的 dp / iOS 的 pt，即 WebView 里 CSS 用的单位。
- 换算：`物理像素 = CSS px × density`（`density` 在 `getScreenInfo()` 里，真机实测 2.75、模拟器 2.625）。

## 平台差异与踩坑（实测结论）

### Android

- **MIUI 等 ROM 假报导航栏 insets**：红米 K30S Ultra（MIUI 12，手势模式）上
  `getSystemWindowInsetBottom()` 仍报告旧三键高度 130px，但窗口实际铺满全屏。
  本插件在 API 29 上用 `getWindowVisibleDisplayFrame()` 反推真实占用（实测正确报 0），
  而不是直接信 insets。
- **`webview-to-edge` 插件常驻接管 DecorView insets 监听**：本插件不依赖 View 层的
  insets 传播，API 30+ 从 `WindowManager.getMaximumWindowMetrics()`（显示层）取值，
  因此在 to-edge 开启/关闭状态下数值都真实。
- **状态栏高度包含挖孔**：挖孔机（如 K30S）状态栏高度 = 挖孔高度（实测 90px / 32.73css，
  模拟器 128px / 48.76css）。
- **导航模式**：优先读系统 `navigation_mode` setting；读不到（MIUI）时按实测占用推断：
  底部有占用 → `three_button`；insets 假报但窗口铺满 → `gesture`；都无 → `none`。
  `none` 表示"未检测到可见导航栏"（可能是手势模式或沉浸模式）。
- `refreshRate` 是**当前模式**的刷新率（MIUI 动态刷新率会变化，实测静止时 50Hz）。
- `darkMode` 报告 App 当前 Configuration 看到的值；MIUI 上它可能与页面
  `matchMedia('(prefers-color-scheme: dark)')` 不一致（这是 MIUI WebView 的已知怪癖，
  见 capacitor-set-force-dark）。
- 视口与安全区的关系：默认状态下 `viewport.css.height = screen.css.height - 状态栏 - 导航栏`；
  开启 webview-to-edge 铺满整屏后，视口 = 整个屏幕，此时请用 `getSafeAreaInsets()` 做避让。

### iOS

- 没有"底部导航栏"概念：`getNavigationBarInfo()` 对应 **Home Indicator** 区域
  （有则 `mode: 'gesture'`，无则 `mode: 'none'`）。
- `getWebViewInfo().version` 取自 UA 里的 Safari `Version/x.y`（WKWebView 没有公开版本 API）；
  `packageName` 固定为 `"WKWebView"`。
- 刘海判断：安全区顶部 > 24pt（标准状态栏 20pt）即视为有刘海/灵动岛。
- `getBatteryInfo().level` 在模拟器上为 -1（无电量数据）。
- `getNetworkInfo()` 用 NWPathMonitor 异步探测，1.5s 超时兜底 `unknown`。
- `getMemoryInfo().availableBytes` 取自 `os_proc_available_memory()`（iOS 13+，更低版本为 -1）。

### Web（浏览器降级）

- 全部用浏览器 API 估算（`screen` / `innerWidth` / UA / Battery API / Storage API 等），
  仅保证不报错；系统栏、安全区、导航栏在普通浏览器里为 0。

## 设备实测数据（2026-11-03）

| 字段 | 红米 K30S Ultra（MIUI 12, Android 10, WebView 87） | Pixel 6 模拟器（Android 16, WebView 133） |
| --- | --- | --- |
| screen | 1080×2400 px / 392.73×872.73 css，density 2.75 (440dpi) | 1080×2400 px / 411.43×914.29 css，density 2.625 (420dpi) |
| viewport | 1080×2310 px / 840 css（默认，仅让出状态栏） | 1080×2146 px / 817.52 css（让出状态栏+三键导航栏） |
| statusBar | 90 px / 32.73 css | 128 px / 48.76 css |
| navigationBar | 0（手势模式，无导航栏） | 126 px / 48 css，three_button |
| safeAreaInsets | top 32.73, bottom 0 | top 48.76, bottom 48 |
| cutout | 挖孔 [0,141]×[0,90] | 挖孔 [492,610]×[0,128] |
| refreshRate | 50（MIUI 动态刷新率当前档） | 60 |

## 开发

```bash
cd plugins/capacitor-system-info
npm install
npm run build        # tsc → dist/esm
```

- Android：`android/src/main/java/com/testionic/systeminfo/SystemInfoPlugin.java`
- iOS：`ios/Sources/SystemInfoPlugin/SystemInfoPlugin.swift`（Windows 上无法编译，靠 CI）
- 每个 API 独立实现，新增 API 时按现有模式复制即可。
