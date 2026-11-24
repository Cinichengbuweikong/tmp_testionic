# capacitor-permissions

用一个统一的权限 id 覆盖 Android 运行时权限、iOS 系统权限与 Web Permissions API。

> ## 🧩 这是一个「插件模板」
>
> 这个插件**能直接用**（现成的 16 个 id 覆盖常见需求），但它**不是最终形态**：
> 每个平台的核心都是一张**声明式注册表**（Android `DEFINITIONS` / iOS `descriptors`），
> 新增/删除权限只需要改表 + 少量平台配置，`check/request/getSupported` 会自动支持。
>
> **想改造它 → 先读 [TEMPLATE.md](./TEMPLATE.md)**（逐文件、逐平台的改法、验证清单与坑）。
> 本 README 则专注于"**怎么用**"：每个 API 的参数/返回值字段、平台差异、注意事项。

```ts
import { Permissions } from 'capacitor-permissions';

const { results } = await Permissions.check({ permissions: ['camera', 'notifications'] });
// results = { camera: 'prompt', notifications: 'granted' }

if (results.camera !== 'granted') {
  const after = await Permissions.request({ permissions: ['camera'] });
  if (after.results.camera === 'denied') {
    await Permissions.openAppSettings(); // 用户点了"不再询问"
  }
}
```

## 权限 id 与平台映射

注: unsupported 即本平台不支持应用申请此权限

| id | Android | iOS | Web |
| --- | --- | --- | --- |
| `camera` | `CAMERA` | `AVCaptureDevice(.video)` | `permissions.query('camera')` / `getUserMedia` |
| `microphone` | `RECORD_AUDIO` | `AVCaptureDevice(.audio)` | `permissions.query('microphone')` / `getUserMedia` |
| `location` | `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` | `CLLocationManager`（WhenInUse） | `permissions.query('geolocation')` / `getCurrentPosition` |
| `locationCoarse` | `ACCESS_COARSE_LOCATION` | 同 `location`（iOS 只有一个定位权限） | 同 `location` |
| `notifications` | `POST_NOTIFICATIONS`（Android 13+；12 及以下直接 `granted`） | `UNUserNotificationCenter` | `Notification.requestPermission()` |
| `photos` | `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO`（13+）/ `READ_EXTERNAL_STORAGE`（≤12） | `PHPhotoLibrary`（可返回 `limited`） | `unsupported` |
| `storage` | `READ/WRITE_EXTERNAL_STORAGE`（Android 13+ 请改用 `photos`） | `unsupported`（iOS 是沙盒文件系统） | `unsupported` |
| `contacts` | `READ_CONTACTS` | `CNContactStore` | `unsupported` |
| `calendar` | `READ_CALENDAR` | `EKEventStore(.event)` | `unsupported` |
| `reminders` | —（Android 用 `calendar`） | `EKEventStore(.reminder)` | `unsupported` |
| `bluetooth` | `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`（12+；11 及以下安装即授予） | `CBManager` | `unsupported` |
| `motion` | —（Android 用 `activityRecognition`） | `CMMotionActivityManager` | `unsupported` |
| `activityRecognition` | `ACTIVITY_RECOGNITION`（Android 10+） | `unsupported`（iOS 用 `motion`） | `unsupported` |
| `bodySensors` | `BODY_SENSORS` | `unsupported` | `unsupported` |
| `tracking` | —（Android 用 AD_ID，无运行时权限） | `ATTrackingManager`（iOS 14+） | `unsupported` |
| `speech` | —（Android 用 `microphone`） | `SFSpeechRecognizer` | `unsupported` |

## 状态含义

| 状态 | 含义 | 建议的下一步 |
| --- | --- | --- |
| `granted` | 已授权 | 直接调用业务 API |
| `denied` | 被拒绝（Android 上"拒绝且不再询问"也是这个；iOS 上用户点过"不允许"） | 提示用户，必要时 `openAppSettings()` |
| `prompt` | 还没问过，可以弹窗 | 用户触发某个操作时 `request()` |
| `prompt-with-rationale` | Android：被拒过一次，建议先解释再请求 | 先展示"为什么需要"，再 `request()` |
| `limited` | iOS 14+ 照片"仅选中部分"；iOS 18+ 联系人部分授权 | 判断当前选择是否够用，不够再引导 |
| `restricted` | 系统策略限制（家长控制、MDM），用户改不了 | 功能降级，不要引导去设置页 |
| `unsupported` | 当前平台/系统版本没有这个权限 | 走业务降级路径 |

`check()` / `request()` 都返回 `{ results, details }`，`details` 里是一句话诊断（便于直接显示在调试页）。

---

## API 详解

### `check(options): Promise<PermissionResults>`

**只查询，绝不弹窗**。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `options.permissions` | `PermissionId[]` | 是 | 要查询的权限 id 列表（重复项会被忽略） |

返回 `PermissionResults`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `results` | `Record<string, PermissionState>` | key = 请求的 id，value = 状态。**未知 id 也会出现在里面**，值为 `unsupported` |
| `details?` | `Record<string, string>` | 每个 id 的一句话诊断：Android 会给"被拒过一次/需要去设置页"，iOS 会给原始状态码 |

行为细节：

- Android：`checkSelfPermission` + `shouldShowRequestPermissionRationale`；没授权但没问过 → `prompt`，
  被拒过一次 → `prompt-with-rationale`，被拒且不再询问 → `denied`；
- iOS：只读各框架的 `authorizationStatus`（**不会**触发授权框）；`notifications` 用
  `getNotificationSettings` 并同步等待（最多 3 秒）；
- Web：`navigator.permissions.query`；浏览器不认识的权限名返回 `unsupported`；
- **不会 reject**（除非参数类型完全不对）。

```ts
const { results, details } = await Permissions.check({
  permissions: ['camera', 'location', 'notifications'],
});
console.log(results.camera, details.camera);
```

### `request(options): Promise<PermissionResults>`

发起授权请求（会弹系统对话框）。返回值是**请求完成之后**的状态快照，结构与 `check()` 相同。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `options.permissions` | `PermissionId[]` | 是 | 要申请的权限 id 列表（顺序会被保留） |

平台差异：

- **Android**：一次调用里所有权限合并成一次 `requestPermissions`（系统可能分组弹多个对话框）；
  已经 `granted` 的不会重复弹；被永久拒绝的会**直接返回 `denied`**（不会再弹）。
- **iOS**：**一次只能弹一个**，插件内部串行执行（前一个回调后才弹下一个）；
  已经 `granted` 的不会弹。
- **Web**：必须在用户手势（click/touch）回调里调用；`camera`/`microphone` 会真的打开一次设备再释放；
  `location` 用 `getCurrentPosition` 触发；`notifications` 用 `Notification.requestPermission()`。

```ts
// 推荐模式：先 check，只在需要时 request
let { results } = await Permissions.check({ permissions: ['camera'] });
if (results.camera === 'prompt' || results.camera === 'prompt-with-rationale') {
  if (results.camera === 'prompt-with-rationale') {
    await showRationaleDialog(); // 先解释用途（Android 官方建议）
  }
  ({ results } = await Permissions.request({ permissions: ['camera'] }));
}
if (results.camera === 'denied') {
  await Permissions.openAppSettings();
}
```

### `getSupported(): Promise<{ platform, permissions }>`

当前平台支持哪些 id，以及每个 id 需要什么宿主配置。**顺序 = 平台注册表的声明顺序**。

| 返回字段 | 类型 | 说明 |
| --- | --- | --- |
| `platform` | `'android' \| 'ios' \| 'web'` | 平台 |
| `permissions` | `PermissionInfo[]` | 见下表 |

`PermissionInfo`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `PermissionId` | 权限 id |
| `supported` | `boolean` | 当前平台/系统版本是否支持申请（例如 Android 12 上的 `notifications` 为 `false`） |
| `requires` | `string[]` | 需要的**宿主配置**（插件之外、必须由你自己的 App 工程配的东西）；空数组 = 不需要（详见下节） |
| `note?` | `string` | 平台差异说明 |

```ts
const { platform, permissions } = await Permissions.getSupported();
const needCamera = permissions.find((p) => p.id === 'camera');
```

#### `requires` 到底是什么意思

它是一份**"上线前还要去宿主工程补什么"的检查清单**（纯字符串，给人/给 AI 看，插件自己没法替你配）。
所谓"宿主"（host）指**你自己的 Capacitor App 工程**（`android/`、`ios/` 目录），而不是插件本身。

为什么必须由宿主配置：

| 项的形态 | 例子 | 为什么插件干不了 |
| --- | --- | --- |
| **Android 权限名**（`android.permission.*`） | `android.permission.CAMERA` | 权限必须出现在**合并后的 AndroidManifest** 里，`requestPermissions` 才会生效。插件的 `AndroidManifest.xml` 会自动合并进宿主，但如果你用 `tools:node="remove"` 移除了它、或按 [TEMPLATE.md](./TEMPLATE.md) 新增了一个权限，就得在**宿主**的 `android/app/src/main/AndroidManifest.xml` 里声明 |
| **iOS Info.plist key**（`Info.plist: KEY`） | `Info.plist: NSCameraUsageDescription` | Capacitor 插件**无法**修改宿主 Info.plist；调用系统 API 时若缺这个 key，iOS 会**直接崩溃**（不是返回 `denied`），所以只能由你在 `ios/App/App/Info.plist` 里补 |
| **空数组**（`[]`） | iOS 的 `notifications` | 两种情况：① 该平台这个权限**不需要**任何配置；② 该平台**根本没有**这个权限概念（如 iOS 的 `storage`，`supported` 会是 `false`） |

举几个实际读法：

```ts
const { permissions } = await Permissions.getSupported();

// iOS：相机需要 Info.plist 的 NSCameraUsageDescription
// → 值形如 ["Info.plist: NSCameraUsageDescription"]
const iosCamera = permissions.find((p) => p.id === 'camera');

// Android：相机需要 android.permission.CAMERA
// → 值形如 ["android.permission.CAMERA"]
const androidCamera = permissions.find((p) => p.id === 'camera');

// iOS 通知：不需要任何 Info.plist key → []
const iosNotifications = permissions.find((p) => p.id === 'notifications');

// iOS 存储：平台没有这个概念 → [] 且 supported === false
const iosStorage = permissions.find((p) => p.id === 'storage');
```

**常见用法**：在 App 的"诊断/设置"页把 `requires` 原样列出来，或者在自己的构建校验脚本里断言
"iOS 的 Info.plist 至少包含 `permissions.filter(p => p.supported).flatMap(p => p.requires)` 里的所有 key"，
避免上线后才发现少写了一个 usage description。

> 注意：`requires` 只描述**申请权限所必需**的宿主配置，不包含"权限通过后业务还需要的东西"
> （例如后台定位还需要 `UIBackgroundModes: location`、蓝牙后台还需要对应的 background mode）。
> 那些属于业务配置，见各 id 的 `note` 与系统文档。

### `openAppSettings(): Promise<{ opened: boolean }>`

打开本 App 的系统设置页（权限被永久拒绝后的兜底）。

| 返回字段 | 类型 | 说明 |
| --- | --- | --- |
| `opened` | `boolean` | 是否成功打开（Android 用 `ACTION_APPLICATION_DETAILS_SETTINGS`；iOS 用 `UIApplication.openSettingsURLString`；Web 恒 `false`） |

> 注意：这是**离开 App** 的操作。iOS 没有"打开权限设置"的独立入口，只能打开 App 设置页让用户自己找；
> Android 各家 ROM 的设置页层级也不同，不要假设用户能顺利找到。

## 使用模式与建议

```ts
// 1) 进入页面时只 check，不打扰用户
const states = await Permissions.check({ permissions: ['camera', 'notifications'] });

// 2) 用户点"拍照"时才 request（而且一次只要一个）
const cam = await Permissions.request({ permissions: ['camera'] });

// 3) 一次要多个 iOS 权限时，做好"连点 N 次"的预期，最好分批/按需
const both = await Permissions.request({ permissions: ['camera', 'microphone'] });

// 4) 业务降级：iOS 没有 storage 概念
const { results } = await Permissions.check({ permissions: ['storage'] });
if (results.storage === 'unsupported') {
  // 改用 App 沙盒目录（Filesystem 插件）
}
```

- **不要在 App 启动时就 request 一堆权限**：用户不知道为什么，拒绝率最高；
- **`denied` 之后不要反复 request**（Android 会直接返回 denied、iOS 不会再弹），应该引导去设置页；
- **iOS 的 `limited`**（照片）要单独处理：用户可能只给了几张照片，需要引导 `PHPhotoLibrary` 的
  选择界面（本插件不提供，属业务层）；
- **`request()` 期间不要并发调用另一个 `request()`**（iOS 弹窗会排队/互相打断）。

## 使用例

```vue
<section class="card">
  <h2>Permissions（权限检查 / 申请）</h2>
  <div class="actions">
    <button @click="permissionsCheck">检查常用权限</button>
    <button @click="permissionsRequest">申请通知权限</button>
    <button @click="permissionsRequestCamera">申请相机权限</button>
    <button @click="permissionsSupported">支持列表</button>
    <button @click="permissionsOpenSettings">打开应用设置</button>
  </div>
  <p class="hint">
    check() 只读状态、不弹窗；request() 会弹系统授权框（iOS 一次只能弹一个，串行执行）。
    iOS 记得在 Info.plist 里写好 usage description，否则系统会直接崩溃。
  </p>
</section>

<script>
import { Permissions } from 'capacitor-permissions';

async permissionsCheck() {
  try {
    const res = await Permissions.check({
      permissions: ['camera', 'microphone', 'location', 'notifications', 'photos', 'contacts'],
    });
    this.print('权限检查', res);
  } catch (err) {
    this.error('Permissions.check', err);
  }
},

async permissionsRequest() {
  try {
    const res = await Permissions.request({ permissions: ['notifications'] });
    this.print('申请通知权限', res);
  } catch (err) {
    this.error('Permissions.request(notifications)', err);
  }
},

async permissionsRequestCamera() {
  try {
    const res = await Permissions.request({ permissions: ['camera'] });
    this.print('申请相机权限', res);
  } catch (err) {
    this.error('Permissions.request(camera)', err);
  }
},

async permissionsSupported() {
  try {
    const res = await Permissions.getSupported();
    this.print('支持的权限', res);
  } catch (err) {
    this.error('Permissions.getSupported', err);
  }
},

async permissionsOpenSettings() {
  try {
    const res = await Permissions.openAppSettings();
    this.print('打开设置页', res);
  } catch (err) {
    this.error('Permissions.openAppSettings', err);
  }
},
</script>
```

## 宿主配置

### Android

插件的 `AndroidManifest.xml` 已经声明了上表里的所有权限（会合并进宿主）。
**这些权限都会出现在你自己的 App 里**，上架前请检查是否有你不需要的（例如 `READ_CONTACTS`、
`BODY_SENSORS` 会触发 Google Play 的敏感权限审核）。不需要的话，用 `tools:node="remove"` 移除：

```xml
<uses-permission android:name="android.permission.BODY_SENSORS" tools:node="remove" />
```

请求前无需任何代码配置；`POST_NOTIFICATIONS` 需要 Android 13+ 才有。

### iOS（重要）

**必须**在 `Info.plist` 里写好对应的 usage description，否则调用系统 API 会**直接崩溃**
（不是返回 `denied`）。本仓库的测试 App 已全部配好：

```xml
<key>NSCameraUsageDescription</key><string>用于拍照/扫码</string>
<key>NSMicrophoneUsageDescription</key><string>用于录音/语音输入</string>
<key>NSLocationWhenInUseUsageDescription</key><string>用于展示当前位置</string>
<key>NSPhotoLibraryUsageDescription</key><string>用于选择照片</string>
<key>NSContactsUsageDescription</key><string>用于读取联系人</string>
<key>NSCalendarsUsageDescription</key><string>用于读写日历</string>
<key>NSRemindersUsageDescription</key><string>用于读写提醒事项</string>
<key>NSBluetoothAlwaysUsageDescription</key><string>用于连接蓝牙设备</string>
<key>NSMotionUsageDescription</key><string>用于读取运动数据</string>
<key>NSUserTrackingUsageDescription</key><string>用于展示个性化广告</string>
<key>NSSpeechRecognitionUsageDescription</key><string>用于语音识别</string>
```

`getSupported().permissions[].requires` 会列出每个 id 需要的 key，便于自查
（这个字段到底指什么、为什么必须由宿主配，见上文
[`getSupported()` 里的 `requires` 说明](#requires-到底是什么意思)）。

> 一句话总结：**Android 的 `<uses-permission>` 和 iOS 的 `Info.plist` usage description
> 都必须在"你的 App 工程"里存在**。插件自带的 manifest 能合并 Android 权限，
> 但 iOS 的 plist 只能你自己写；少了不是返回 `denied`，而是**崩溃**。

## 行为细节

- **iOS 一次只能弹一个授权框**：`request()` 会**串行**执行（前一个回调后才弹下一个）。
  所以一次请求 5 个权限会让用户连续点 5 次 —— 建议按业务需要分批请求。
- **Android 一次请求会合并弹窗**（同一次 `requestPermissions`），但系统可能分组成多个对话框。
- **`request()` 里已经 `granted` 的权限不会再弹**。
- **iOS 的 `location`**：只 `requestWhenInUseAuthorization()`（`NSLocationWhenInUseUsageDescription` 必需）。
  需要"始终允许"（后台定位）请自己再调 `requestAlwaysAuthorization()`，并配
  `NSLocationAlwaysAndWhenInUseUsageDescription` + `UIBackgroundModes: location`。
- **`notifications`**：iOS 的 `request()` 申请 `[.alert, .badge, .sound]`；
  `provisional` 状态按 `granted` 处理。
- **`camera`/`microphone` on iOS 模拟器**：模拟器没有摄像头，`request()` 会返回 `denied`（不会崩溃）。
- **Web**：`request()` 必须在用户手势（click 等）里调用；`camera`/`microphone` 会真的打开一次设备
  再立刻释放（`getUserMedia` 成功后 `stop()`）。
- **`tracking`（iOS）**：必须配 `NSUserTrackingUsageDescription`；拒绝后系统设置里可以改，
  `check()` 会返回 `restricted`（系统级）或 `denied`。
- **`bluetooth` / `motion`（iOS）**：系统没有"请求回调"，插件用"实例化/启动监听 + 等 1.0~1.5 秒 + 读状态"
  的方式近似，所以 `request()` 会**慢一点**（~1.5s），且用户取消时可能仍显示上一次状态。

## 实测记录

- **真机 Redmi K30S（Android 10 / MIUI 12 / API 29）**：`check()` 返回
  `camera: prompt, microphone: prompt, location: prompt, notifications: granted,
  photos: granted, contacts: prompt, bluetooth: granted`；`getSupported()` 返回 12 项。
- **模拟器 Pixel_6（Android 16 / API 36）**：同上（`notifications` 为 `prompt`，因为 13+ 才有该权限）。
- **真机权限弹窗（Android 10 / MIUI 12）**：`check(camera)=prompt` → `request(camera)` 弹出
  "拍摄照片和录制视频" 授权框 → 点"拒绝" → 返回 `prompt-with-rationale`（符合 MIUI 语义）。
- **iOS 模拟器（CI）**：`check()` 对 camera/microphone/location/notifications/photos/contacts/bluetooth
  返回合法状态（`prompt` 或 `denied`），`getSupported()` 返回 16 项。
- autotest **只 check 不 request**，避免系统授权弹窗阻塞 CI。

## 相关文件

- `src/definitions.ts`：id 与状态类型定义
- Android：`PermissionsPlugin.java`（Capacitor `@Permission` 别名机制 + `DEFINITIONS` 表）
- iOS：`PermissionsPlugin.swift`（`descriptors` 表 + 逐框架状态映射 + 串行请求）
- Web：`src/web.ts`
- **改造指南：[TEMPLATE.md](./TEMPLATE.md)**
