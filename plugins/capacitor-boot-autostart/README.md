# capacitor-boot-autostart

Capacitor 开机自启插件（**Android 有效，iOS 不可行**）。

**它做什么**：注册 `BOOT_COMPLETED` 广播，开机后启动一个**前台服务**（带常驻通知）保持
App 进程存活。用户点图标时是**热启动/恢复**，而不是冷启动重新加载——效果接近微信：
点开就能直接用。

**它不做什么**：

- ❌ **不能**在开机时自动把界面弹到前台（Android 10+ 系统禁止后台启动 Activity）；
- ❌ **不能**保证在所有厂商 ROM 上生效（MIUI/HyperOS 等需要用户手动允许"自启动"）；
- ❌ **iOS 完全不支持**（系统没有给第三方 App 提供开机自启能力）。

## 调查结论（实现前必读）

### Android 能做到什么

| 方案 | 可行性 | 说明 |
| --- | --- | --- |
| **BOOT_COMPLETED + 前台服务**（本插件） | ✅ 可用 | 开机后保持进程存活；点击图标热启动 |
| BOOT_COMPLETED + 直接 `startActivity` | ❌ 被禁止 | Android 10+ 后台启动 Activity 限制；只能发通知让用户点 |
| WorkManager / JobScheduler | ⚠️ 不保证 | 由系统调度，可能在开机后很久才执行，且不能保证进程常驻 |
| Device Owner / 企业管控 | ✅ 但仅企业 | 设备管理员可以强制拉起，需要 MDM/企业分发，普通 App 用不了 |
| 无障碍/悬浮窗等 hack | ❌ 不推荐 | 需要敏感权限、上架会被拒、体验差 |

### 系统限制细节

1. **Android 10+（API 29）**：禁止后台启动 Activity。所以"开机自动亮屏并打开 App"**做不到**，
   只能保持进程在后台。
2. **Android 12+（API 31）**：限制从后台启动前台服务；`BOOT_COMPLETED` 属于**豁免场景**
   （实测日志：`Background started FGS: Allowed ... code:BOOT_COMPLETED`）。
3. **Android 14+（API 34）**：前台服务**必须声明类型**；本插件用 `specialUse`
   （需要 `FOREGROUND_SERVICE_SPECIAL_USE` 权限 + manifest 里的
   `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`）。部分类型（camera/mic/mediaProjection/phoneCall）
   禁止从 `BOOT_COMPLETED` 启动，`specialUse` 不受此限制。
4. **Android 15+（API 35）**：`dataSync` 类型的前台服务每天最多 6 小时；本插件用 `specialUse`
   规避该限制（但 Google Play 对 `specialUse` 有政策审核，上架需要说明用途）。
5. **Android 13+（API 33）**：前台服务的通知需要 `POST_NOTIFICATIONS` 运行时权限；
   没有权限时服务仍能运行，但用户看不到常驻通知，部分 ROM 会因此杀进程。
6. **"强行停止"规则**：用户在设置里 Force stop（或部分 ROM 清理后台）后，App 进入
   *stopped state*，**不再接收 BOOT_COMPLETED**，直到用户手动打开一次 App。
7. **厂商 ROM**：MIUI/HyperOS、EMUI、ColorOS、OriginOS 等都有自己的"自启动"开关，
   默认可能关闭；`BOOT_COMPLETED` 可能被拦截。插件提供 `openAutostartSettings()`
   尝试跳到厂商设置页，用户需手动允许。
8. **电池优化**：开启省电模式/电池优化可能杀掉前台服务；可调用
   `requestIgnoreBatteryOptimizations()` 申请白名单（Google Play 对该权限有政策限制）。

### iOS 为什么不行

- iOS 没有 `BOOT_COMPLETED` 之类的系统广播；
- 后台能力（`BGTaskScheduler`、定位、VoIP push、BLE）都**不能**在开机时自动启动 App，
  且由系统调度/需要服务端配合；
- 没有公开 API 能让第三方 App 注册"开机启动"。
- 因此 iOS 侧 `getStatus()` 返回 `supported: false`，`enable()` 不改变任何状态（仅保证编译通过）。

## 安装

```bash
npm install ./plugins/capacitor-boot-autostart
npx cap sync
```

Android 插件自带 Manifest 合并：权限（`RECEIVE_BOOT_COMPLETED`、`FOREGROUND_SERVICE`、
`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`）和 `BootReceiver`/`BootService`
都会自动并入宿主 App，**不需要手动改 Manifest**。

## 用法

```ts
import { BootAutostart } from 'capacitor-boot-autostart';

const status = await BootAutostart.getStatus();
if (status.supported) {
  // 1) 先要通知权限（Android 13+）
  await BootAutostart.requestNotificationPermission();
  // 2) 可选：申请忽略电池优化
  await BootAutostart.requestIgnoreBatteryOptimizations();
  // 3) 开启（会立即启动一次前台服务，方便验证）
  const enabled = await BootAutostart.enable();
  console.log(enabled);
}
```

### 检测"开机后进程是否还活着"

App 启动时读一次 `getStatus()`：如果 `enabled=true && serviceRunning=true`，说明这次是
**热启动**（进程从开机一直存活）；否则是冷启动。

### 引导用户去厂商设置

```ts
await BootAutostart.openAutostartSettings(); // 尝试 MIUI/华为/OPPO/vivo 的自启动页
```

## API

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `getStatus()` | `BootAutostartStatus` | 读取状态（不改设置） |
| `enable()` | `BootAutostartStatus` | 记录开关 + 启用广播接收器 + 立即启动前台服务 |
| `disable()` | `BootAutostartStatus` | 关闭开关 + 停服务 + 禁用接收器 |
| `requestNotificationPermission()` | `BootAutostartStatus` | 申请 `POST_NOTIFICATIONS`（Android 13+） |
| `requestIgnoreBatteryOptimizations()` | `BootAutostartStatus` | 打开"忽略电池优化"系统页 |
| `openAppSettings()` | `void` | 打开应用详情设置页 |
| `openAutostartSettings()` | `void` | 尝试打开厂商自启动设置页 |
| `addListener('serviceStateChanged', cb)` | `PluginListenerHandle` | 服务启停事件 |

### BootAutostartStatus

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `supported` | `boolean` | 当前平台是否支持（iOS/Web = false） |
| `enabled` | `boolean` | 用户是否开启 |
| `receiverEnabled` | `boolean?` | 广播接收器是否启用（Android） |
| `serviceRunning` | `boolean?` | 前台服务是否在运行（Android） |
| `notificationsEnabled` | `boolean?` | 通知权限（Android） |
| `batteryOptimizationIgnored` | `boolean?` | 是否已忽略电池优化（Android） |
| `sdk` | `number?` | Android API level |
| `reason` | `string?` | 不支持时的原因（iOS/Web） |

## 实测（Android 16 模拟器 / Pixel 6，2026-11-12）

| 检查 | 结果 |
| --- | --- |
| `enable()` | `enabled:true, receiverEnabled:true`；前台服务启动，`isForeground=true`，`types=0x40000000`（specialUse），通知 id 1001 ✅ |
| `disable()` | `enabled:false, serviceRunning:false` ✅ |
| **重启模拟器后** | 日志：`BOOT_COMPLETED` → `Start proc ... for broadcast {BootReceiver}` → `Background started FGS: Allowed ... code:BOOT_COMPLETED`；进程 2258 存活、服务前台、通知存在 ✅ |
| 开机耗时 | 系统广播排队，约开机后 **50 秒**才送达（正常现象，不是插件慢） |

> 注意：测试时用 `adb reboot` 重启的是**模拟器**，没有动真机。

## 注意事项 / 上架风险

1. **Google Play**：`FOREGROUND_SERVICE_SPECIAL_USE` 与 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
   都需要在 Play Console 说明用途，审核较严；如果只是内部使用/自用分发，可忽略。
2. **不要把 `enable()` 默认打开**：让用户显式同意（前台常驻通知 + 耗电影响）。
3. **不要指望"开机自动弹界面"**：Android 10+ 做不到，弹通知让用户点是唯一合规方式。
4. **iOS 端**：`enable()` 返回 `supported:false`，请在 UI 上隐藏/禁用该开关。
