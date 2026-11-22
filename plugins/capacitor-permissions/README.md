# capacitor-permissions

用一个统一的权限 id 覆盖 Android 运行时权限、iOS 系统权限与 Web Permissions API。

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

| 状态 | 含义 |
| --- | --- |
| `granted` | 已授权 |
| `denied` | 被拒绝（Android 上"拒绝且不再询问"也是这个；iOS 上用户点过"不允许"） |
| `prompt` | 还没问过，可以弹窗 |
| `prompt-with-rationale` | Android：被拒过一次，建议先解释再请求 |
| `limited` | iOS 14+ 照片"仅选中部分"；iOS 18+ 联系人部分授权 |
| `restricted` | 系统策略限制（家长控制、MDM），用户改不了 |
| `unsupported` | 当前平台/系统版本没有这个权限 |

`check()` / `request()` 都返回 `{ results, details }`，`details` 里是一句话诊断（便于直接显示在调试页）。

## API

| 方法 | 说明 |
| --- | --- |
| `check({ permissions })` | **只查询**，绝不弹窗 |
| `request({ permissions })` | 发起授权请求（Android 一次合并弹窗；iOS 逐个弹；Web 走具体 API） |
| `getSupported()` | 当前平台支持哪些 id、需要什么宿主配置 |
| `openAppSettings()` | 打开本 App 的系统设置页 |

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

`getSupported().permissions[].requires` 会列出每个 id 需要的 key，便于自查。

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

## 实测记录

- **真机 Redmi K30S（Android 10 / MIUI 12 / API 29）**：`check()` 返回
  `camera: prompt, microphone: prompt, location: prompt, notifications: granted,
  photos: granted, contacts: prompt, bluetooth: granted`；`getSupported()` 返回 12 项。
- **模拟器 Pixel_6（Android 16 / API 36）**：同上（`notifications` 为 `prompt`，因为 13+ 才有该权限）。
- **iOS 模拟器（CI）**：`check()` 对 camera/microphone/location/notifications/photos/contacts/bluetooth
  返回合法状态（`prompt` 或 `denied`），`getSupported()` 返回 16 项。
- autotest **只 check 不 request**，避免系统授权弹窗阻塞 CI。

## 相关文件

- `src/definitions.ts`：id 与状态类型定义
- Android：`PermissionsPlugin.java`（Capacitor `@Permission` 别名机制）
- iOS：`PermissionsPlugin.swift`（逐框架状态映射 + 串行请求）
- Web：`src/web.ts`
