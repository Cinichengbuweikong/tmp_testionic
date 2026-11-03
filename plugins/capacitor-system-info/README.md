# capacitor-system-info

系统与硬件信息插件：屏幕 / 视口 / 安全区 / 状态栏 / 导航栏 / 挖孔 / 系统 / WebView / 内存 / 存储 / 电量 / 网络 / App 信息，共 13 个独立只读 API。

## 设计原则

- **每种信息一个独立 API**：问什么就收集什么，按需调用，没有聚合接口；
- **互不影响**：每个 API 独立实现、独立容错，某个 API 因系统差异失败不影响其他 API；
- **纯只读**：不需要任何运行时权限弹窗（仅声明 `ACCESS_NETWORK_STATE` 普通权限用于判断网络类型，系统自动授予）；
- **三端可用**：Android（原生）/ iOS（原生）/ Web（浏览器降级，保证不报错，数值为估算）。

## 安装

```bash
npm install ./plugins/capacitor-system-info
npx cap sync
```

## 单位约定（先读这个）

- 字段名带 **`px`** 的是**物理像素**：Android 原生 px；iOS 为 pt × scale。
- 字段名带 **`css`** 的是 **CSS px**：Android 的 dp / iOS 的 pt——**就是 WebView 里 CSS 使用的单位**。
- 换算公式：**物理像素 = CSS px × density**（`density` 由 `getScreenInfo()` 返回，真机实测 2.75、模拟器 2.625）。
- 示例：`getViewport().css` 就是页面里 `100%` 的实际大小；`getSafeAreaInsets().css.top` 可以直接当 `padding-top` 用。

---

## API 详细文档

### getScreenInfo()

**功能**：读取屏幕分辨率、CSS 尺寸、像素密度、刷新率、朝向。

**签名**：`getScreenInfo(): Promise<ScreenInfo>`

**返回值**：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `px` | `{ width, height }` | 物理分辨率（**跟随当前朝向**：横屏时宽高会交换） |
| `css` | `{ width, height }` | CSS px 尺寸（= px ÷ density） |
| `orientation` | `'portrait' \| 'landscape' \| 'unknown'` | 当前朝向 |
| `rotationDegrees` | `number` | 旋转角度 0/90/180/270；iOS 在拿不到方向时为 0（近似值） |
| `density` | `number` | 像素密度：1 CSS px 对应多少物理像素（如 2.75） |
| `densityDpi` | `number` | DPI（如 440） |
| `scaledDensity` | `number` | 字体缩放后的密度（Android scaledDensity；iOS 与 density 相同） |
| `refreshRate` | `number` | **用户设置的刷新率档位**（Hz），如 90 |
| `maxRefreshRate` | `number` | 屏幕硬件支持的最高刷新率（Hz），如 144 |

**注意事项**：
- `refreshRate` 是**档位不是瞬时值**：Android 11+ 读官方 `peak_refresh_rate`，MIUI 读 `user_refresh_rate`，都没有时才退回当前激活模式。MIUI 智能刷新率会让 `Display.getRefreshRate()`/`getMode()` 返回瞬时档（实测设置 90 时报 50），本插件已规避。
- 旋转屏幕后请重新调用。

---

### getViewport()

**功能**：读取 WebView 视口尺寸——**CSS 里 `100%` 对应的就是这个 `css` 值**。

**签名**：`getViewport(): Promise<ViewportInfo>`

**返回值**：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `px` | `{ width, height }` | WebView 视口物理像素 |
| `css` | `{ width, height }` | WebView 视口 CSS px（= 页面 `window.innerWidth/Height` 的量级） |

**注意事项**：
- Android 默认（未启用 webview-to-edge 铺满整屏）时，视口会让出状态栏/导航栏：`viewport.height = screen.height − 状态栏 − 导航栏`（真机实测 840 = 872.73 − 32.73）。
- 启用 webview-to-edge 后视口 = 整个屏幕，此时请改用 `getSafeAreaInsets()` 做避让。
- iOS 上 Capacitor 默认贴边满屏，视口 = 屏幕。

---

### getSafeAreaInsets()

**功能**：读取四边安全区（iOS safe-area 语义：内容需要避让的区域），**双单位**。

**签名**：`getSafeAreaInsets(): Promise<SafeAreaInsets>`

**返回值**：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `px` | `{ top, right, bottom, left }` | 四边安全区，物理像素（整数） |
| `css` | `{ top, right, bottom, left }` | 四边安全区，CSS px（两位小数） |

**注意事项**：
- `css.top` 通常等于状态栏高度；`css.bottom` 通常等于导航栏/手势条/Home Indicator 高度；横屏时挖孔会跑到 left/right。
- Android 手势导航（如 MIUI 手势模式）下 `bottom = 0`。
- 可直接用于 CSS：`padding-top: ${css.top}px`（或配合 webview-to-edge 注入的 `--safe-area-inset-*` 变量）。

---

### getStatusBarInfo()

**功能**：读取状态栏宽高。

**签名**：`getStatusBarInfo(): Promise<StatusBarInfo>`

**返回值**：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `px` | `{ width, height }` | 宽 = 屏幕宽；高 = 状态栏物理像素（真机 90 / 模拟器 128） |
| `css` | `{ width, height }` | 同上，CSS px（真机 32.73 / 模拟器 48.76） |
| `visible` | `boolean` | 状态栏是否可见（高度 > 0） |

**注意事项**：
- **挖孔机的状态栏高度包含挖孔**：红米 K30S 的挖孔高 90px，状态栏高即 90px（不是标准 24dp）。
- iOS 上状态栏高度 = 顶部安全区（`safeAreaInsets.top`）；灵动岛机型会含岛边距。

---

### getNavigationBarInfo()

**功能**：读取底部导航栏宽高与导航方式。

**签名**：`getNavigationBarInfo(): Promise<NavigationBarInfo>`

**返回值**：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `px` | `{ width, height }` | 宽 = 屏幕宽；高 = 导航栏物理像素（模拟器三键 126 / 真机手势 0） |
| `css` | `{ width, height }` | 同上，CSS px（模拟器 48 / 真机 0） |
| `visible` | `boolean` | 是否可见（高度 > 0） |
| `mode` | `'three_button' \| 'two_button' \| 'gesture' \| 'none' \| 'unknown'` | 导航方式（见下） |

**`mode` 说明**：
- `three_button`：经典三键导航（有实体占用）；`two_button`：Android 9 药丸两键；`gesture`：手势导航；
- `none`：**未检测到可见导航栏**（可能是手势模式，也可能是沉浸全屏）——配合 `height=0` 使用；
- `unknown`：无法判断。

**注意事项**：
- 优先读系统 `navigation_mode` 设置；MIUI 等 ROM 读不到时按实测占用推断。
- **iOS 上没有导航栏**：此 API 对应 **Home Indicator** 区域（有则 `mode: 'gesture'`，无则 `mode: 'none'`）。
- 已知怪癖：MIUI 手势模式下 insets API 假报三键高度 130px，本插件用窗口实际占用反推，正确返回 0。

---

### getCutoutInfo()

**功能**：读取刘海/挖孔信息。

**签名**：`getCutoutInfo(): Promise<CutoutInfo>`

**返回值分两类数据，不要混淆**：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `exists` | `boolean` | 是否有刘海/挖孔 |
| `insetsPx` / `insetsCss` | `{ top, right, bottom, left }`（可选） | **避让距离**：四边各需要让出多少。通常**只有有挖孔的那条边 > 0**（如竖屏只有 `top`，左横屏只有 `left`） |
| `boundingRectsPx` / `boundingRectsCss` | `Array<{ left, top, right, bottom, width, height }>`（可选，**仅 Android**） | **挖孔区域矩形**：`{left,top,right,bottom}` 是**矩形四条边的坐标**（不是到四边的距离！），`width/height` 是宽高（已算好）。多挖孔屏会有多个矩形 |
| `centerPx` / `centerCss` | `{ x, y }`（可选，仅 Android） | **主挖孔中心点**（取第一个矩形中心）。想知道"挖孔大概在屏幕哪个位置"直接看这个 |

**坐标系说明（重要）**：
- `insets` 是"距离"，`boundingRects` 是"位置和大小"——两者不是同一维度，`insets.right=0` 但矩形有 `right` 值是完全正常的。
- `boundingRects` 的 `right`/`bottom` 是**矩形右/下边缘的坐标**：例如 `{left:0, right:90, bottom:1080}` 表示矩形 x 从 0 到 90（宽 90）、y 到 1080（贴底边），而不是"距离右边 90、距离底部 1080"。
- **矩形贴边 ≠ 挖孔贴边**：系统给的是挖孔**安全区矩形**（把挖孔周围的不可用区域一并框住，因此会贴到屏幕边），真实的圆孔在矩形内部靠中间——所以你会看到"挖孔明显不贴边，但矩形贴边"。
  - 实测证据 1（模拟器，系统有圆孔轮廓）：圆孔 `x 507~573, y 31~97`（直径 57），而系统报告的安全区矩形是 `[492,0]-[610,128]`——圆孔严格落在矩形内部。
  - 实测证据 2（红米 K30S Ultra / MIUI 12，`dumpsys display` 中系统自己报告的数据）：
    `cutout DisplayCutout{ insets=Rect(0, 90 - 0, 0) boundingRect=[Rect(0, 0 - 141, 90)] }`
    即 MIUI 把安全区直接定义成**贴左上角的 141×90 矩形**；插件原样转发，没有任何加工。
- **公开 API 拿不到圆孔精确轮廓**：圆孔轮廓属于 SurfaceFlinger 的 `cutoutPathParserInfo`（私有数据，App 层不可访问，且部分 ROM 根本不提供）。因此 `boundingRects` 是能拿到的最准确数据，`centerPx` 可近似视为圆孔中心。
- 想把内容"绕开"挖孔，正确做法是用 `insetsPx/insetsCss` 做 padding/margin（这是系统给的真实避让距离）；`boundingRects` 只适合了解"哪块区域被遮挡"，不要用它推断圆孔本身是否贴边。
- `boundingRects` 的坐标随**当前朝向**变化：竖屏坐标系是 1080×2400，转成横屏后坐标系变成 2400×1080，同一个挖孔的坐标数值会完全不同。
- 判断挖孔在屏幕上哪个位置，请**先把 `getScreenInfo().px` 和 `boundingRects` 放在同一朝向下看**。

**真实示例（红米 K30S Ultra，挖孔在设备左上角）**：

```js
// 竖屏（坐标系 1080×2400）
{ exists: true,
  insetsPx: { top: 90, right: 0, bottom: 0, left: 0 },
  insetsCss: { top: 32.73, right: 0, bottom: 0, left: 0 },
  boundingRectsPx: [{ left: 0, top: 0, right: 141, bottom: 90, width: 141, height: 90 }],
  boundingRectsCss: [{ left: 0, top: 0, right: 51.27, bottom: 32.73, width: 51.27, height: 32.73 }],
  centerPx: { x: 70.5, y: 45 },        // 左上角
  centerCss: { x: 25.64, y: 16.36 } }

// 向左横屏（坐标系 2400×1080；挖孔转到了左下角）
{ exists: true,
  insetsPx: { top: 0, right: 0, bottom: 0, left: 90 },
  boundingRectsPx: [{ left: 0, top: 939, right: 90, bottom: 1080, width: 90, height: 141 }],
  boundingRectsCss: [{ left: 0, top: 341.45, right: 32.73, bottom: 392.73, width: 32.73, height: 51.27 }],
  centerPx: { x: 45, y: 1009.5 },      // 左侧、贴近底边（1080 高的坐标系里）
  centerCss: { x: 16.36, y: 367.09 } }
// 手机逆时针转过 90° 后挖孔从左上跑到左下，所以 center 的 y 很大、x 很小 —— 这就是"左下角"
```

**注意事项**：
- Android：`insets.top` 一般等于状态栏高度（挖孔机状态栏高度包含挖孔）；横屏时在 `left` 或 `right`。
- iOS：只有 `insetsPx/insetsCss`（来自安全区），没有 `boundingRects`（系统不提供挖孔轮廓）；判断标准是安全区某边 > 24pt（标准状态栏 20pt）。
- 拿挖孔轮廓做精细排版（如给内容"绕开"挖孔）时，`boundingRects` 是安全区矩形、不是精确的圆孔轮廓，需要据此留余量。

---

### getSystemInfo()

**功能**：读取系统与设备基本信息。

**签名**：`getSystemInfo(): Promise<SystemInfoData>`

**返回值**：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `platform` | `'android' \| 'ios' \| 'web'` | 平台 |
| `osVersion` | `string` | 系统版本号（如 "10" / "26.4.1"） |
| `sdkInt` | `number`（可选） | Android API Level（如 29）；iOS/Web 无此字段 |
| `manufacturer` | `string` | 制造商（Xiaomi / Apple） |
| `brand` | `string` | 品牌（Redmi / Apple） |
| `model` | `string` | 型号（M2007J3SC / iPhone13,2；**模拟器上 iOS 为模拟机型如 iPhone17,4**） |
| `deviceCode` | `string` | 设备代号 codename（apollo / sargo；iOS 与 model 相同） |
| `isEmulator` | `boolean` | 是否模拟器 |
| `cpuAbis` | `string[]` | CPU ABI 列表（如 ["arm64-v8a", "armeabi-v7a"]；iOS 为 ["arm64"]） |
| `locale` | `string` | 区域（zh-CN / en_US） |
| `language` | `string` | 语言（zh / en） |
| `timezone` | `string` | 时区（Asia/Shanghai） |
| `is24Hour` | `boolean` | 是否 24 小时制 |
| `fontScale` | `number` | 系统字体缩放倍率（Android fontScale；iOS 为动态字号的近似映射） |
| `darkMode` | `boolean` | 系统深色模式（App 当前配置看到的） |

**注意事项**：
- **MIUI 坑**：`darkMode` 在 MIUI 上可能与页面 `matchMedia('(prefers-color-scheme: dark)')` 不一致（MIUI WebView 已知怪癖，参见 capacitor-set-force-dark 插件）。
- `fontScale > 1` 说明用户开了大字体，Web 布局建议用 rem/相对单位适配。

---

### getWebViewInfo()

**功能**：读取 WebView 引擎信息（版本、UA）。

**签名**：`getWebViewInfo(): Promise<WebViewInfo>`

**返回值**：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `packageName` | `string`（可选） | Android：WebView 包名（com.google.android.webview / com.android.webview）；iOS：固定 "WKWebView" |
| `version` | `string`（可选） | Android：WebView 版本号（如 87.0.4280.101）；iOS：UA 里的 Safari 版本（iOS 26 简化 UA 无此信息时为 ""） |
| `userAgent` | `string` | 完整 UserAgent |

**注意事项**：
- Android API < 26 或个别 ROM 上拿不到包信息时，`version` 从 UA 的 `Chrome/x.y` 解析；`packageName` 可能为空。
- iOS 没有公开的 WKWebView 版本 API；iOS 26 起 UA 是简化形式（无 Version/ 字段），`version` 会为空——需要版本判断时请解析 `userAgent`。
- 典型用途：根据 WebView 版本决定 CSS/JS 兼容方案（如老 WebView 87 无 `env(safe-area-inset-*)`）。

---

### getMemoryInfo()

**功能**：读取内存信息。

**签名**：`getMemoryInfo(): Promise<MemoryInfo>`

**返回值**：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `totalBytes` | `number` | 物理内存总量（字节；真机 8GB = 8014004224） |
| `availableBytes` | `number` | 当前可用内存（字节）；iOS 拿不到时为 -1（模拟器可能为 0） |

**注意事项**：`availableBytes` 是瞬时值，适合做 OOM 预警（如可用 < 200MB 时降级渲染），不适合精确统计。

---

### getStorageInfo()

**功能**：读取存储信息（数据分区）。

**签名**：`getStorageInfo(): Promise<StorageInfo>`

**返回值**：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `totalBytes` | `number` | 数据分区总量（字节） |
| `freeBytes` | `number` | 数据分区剩余（字节） |

**注意事项**：
- Android 是 `/data` 分区；iOS 是应用沙盒所在卷（容量按卷统计）。
- 两者都不是"整机所有存储"（不含 SD 卡/系统分区）。

---

### getBatteryInfo()

**功能**：读取电量信息。

**签名**：`getBatteryInfo(): Promise<BatteryInfo>`

**返回值**：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `level` | `number` | 电量 0~100；**未知为 -1**（iOS 模拟器恒为 -1） |
| `charging` | `boolean` | 是否正在充电（含充满） |

**注意事项**：无运行时权限要求（系统广播 + BatteryManager）。

---

### getNetworkInfo()

**功能**：读取网络状态。

**签名**：`getNetworkInfo(): Promise<NetworkInfo>`

**返回值**：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `type` | `'wifi' \| 'cellular' \| 'ethernet' \| 'vpn' \| 'none' \| 'unknown'` | 网络类型 |
| `connected` | `boolean` | 是否有可用网络（具备互联网能力） |
| `metered` | `boolean` | 是否计费网络（蜂窝流量通常为 true） |

**注意事项**：
- Android 需 `ACCESS_NETWORK_STATE` 权限（普通权限，插件已声明，自动授予）。
- iOS 用 NWPathMonitor 异步探测，**最慢 1.5s 返回**（超时兜底 `unknown`）——调用方别在 UI 卡点同步等待；模拟器上通常报 `ethernet`。
- 典型用途：蜂窝网络下自动切省流量模式（低清图/暂停预加载）。

---

### getAppInfo()

**功能**：读取应用自身信息。

**签名**：`getAppInfo(): Promise<AppInfo>`

**返回值**：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `id` | `string` | App 包名 / bundle id（如 com.myapp.testionic） |
| `name` | `string` | 应用显示名（Android 取 application label；iOS 取 CFBundleDisplayName） |
| `version` | `string` | 版本号（如 "1.0"） |
| `build` | `string`（可选） | 构建号（Android versionCode / iOS CFBundleVersion） |

**注意事项**：iOS 若未配置 CFBundleDisplayName，`name` 可能为空——取 Bundle name 兜底可自行判断。

---

## 平台差异速查

| 项 | Android | iOS |
| --- | --- | --- |
| 状态栏高度 | 系统栏 insets（含挖孔） | 顶部安全区（灵动岛含边距） |
| 导航栏 | 三键/两键/手势/无 | 恒为 Home Indicator（gesture/none） |
| 挖孔包围盒 | ✅ boundingRectsPx | ❌ 只有 insetsCss |
| 刷新率档位 | peak_refresh_rate / user_refresh_rate / 激活模式 | maximumFramesPerSecond（即上限） |
| WebView 版本 | 包版本号 | UA 解析（iOS 26 可能为空） |
| 可用内存 | ActivityManager | os_proc_available_memory（可能 -1/0） |
| 电量 | BatteryManager（模拟器 100） | UIDevice（模拟器 -1） |
| 网络探测 | ConnectivityManager（即时） | NWPathMonitor（最多 1.5s） |

## 设备实测数据（2026-11-03）

| 字段 | 红米 K30S Ultra（MIUI 12, Android 10, WebView 87, 手势导航） | Pixel 6 模拟器（Android 16, WebView 133, 三键） | iPhone 17 Pro 模拟器（iOS 26.4.1，CI） |
| --- | --- | --- | --- |
| screen | 1080×2400 px / 392.73×872.73 css，density 2.75 (440dpi) | 1080×2400 px / 411.43×914.29 css，density 2.625 (420dpi) | 1206×2622 px / 402×874 css，density 3 (480dpi) |
| viewport（默认） | 1080×2310 px / 840 css | 1080×2146 px / 817.52 css | 1206×2622 px / 402×874 css（iOS 默认满屏） |
| statusBar | 90 px / 32.73 css | 128 px / 48.76 css | 186 px / 62 css |
| navigationBar | 0（手势） | 126 px / 48 css（three_button） | 102 px / 34 css（gesture = Home Indicator） |
| safeArea css | top 32.73 / bottom 0 | top 48.76 / bottom 48 | top 62 / bottom 34 |
| refreshRate | 90（max 144） | 60（max 60） | 60（max 60） |
| cutout | 挖孔 [0,141]×[0,90] | 挖孔 [492,610]×[0,128] | exists: true（灵动岛） |

## 开发

```bash
cd plugins/capacitor-system-info
npm install
npm run build        # tsc → dist/esm
```

- Android：`android/src/main/java/com/testionic/systeminfo/SystemInfoPlugin.java`
- iOS：`ios/Sources/SystemInfoPlugin/SystemInfoPlugin.swift`（Windows 上无法编译，靠 GitHub Actions 验证）
- 每个 API 独立实现，新增 API 时按现有模式复制即可；两侧记得同步 `pluginMethods` / `@PluginMethod` 与 TS 声明。
