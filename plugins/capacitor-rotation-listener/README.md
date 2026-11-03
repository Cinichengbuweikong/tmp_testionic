# capacitor-rotation-listener

屏幕旋转监听插件：读取当前方向 + `rotationChanged` 事件（可监听、可解除），Android / iOS / Web 三端可用。

## 设计要点

- **只做监听**：不控制/锁定屏幕方向（那是另一个插件的职责），只上报"现在是什么方向"和"方向变了"。
- **事件驱动**：原生侧在方向**真正变化**时派发 `rotationChanged`（键盘/字体等其它配置变化会自动去重，不会误报）。
- **监听/解除监听**走 Capacitor 桥的内置能力：`addListener()` 拿到的 handle 调 `handle.remove()` 即解除，原生侧不需要额外开关。
- **三端行为一致**：Web 端用 `screen.orientation` / `orientationchange` 模拟，同样派发 `rotationChanged`。

## 安装

```bash
npm install ./plugins/capacitor-rotation-listener
npx cap sync
```

## 用法

```ts
import { Rotation } from 'capacitor-rotation-listener';

// 1) 读取当前状态（不触发事件）
const state = await Rotation.getRotation();
console.log(state.orientation, state.rotationDegrees); // 'portrait' 0

// 2) 监听旋转
const handle = await Rotation.addListener('rotationChanged', (state) => {
  console.log('旋转了：', state.orientation, state.rotationDegrees);
});

// 3) 解除监听（两种方式）
await handle.remove();          // 解除这一个监听
await Rotation.removeAllListeners(); // 解除本插件所有监听
```

## API 详细文档

### getRotation()

**功能**：读取当前旋转状态。

**签名**：`getRotation(): Promise<RotationState>`

**返回值 `RotationState`**：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `orientation` | `'portrait' \| 'landscape' \| 'unknown'` | 当前朝向。按**屏幕宽高比**判断（= 实际渲染方向），`unknown` 极少出现（Android `ORIENTATION_UNDEFINED`） |
| `rotationDegrees` | `number` | 旋转角度：`0` / `90` / `180` / `270`（与 `capacitor-system-info` 的 `getScreenInfo().rotationDegrees` 同语义） |
| `isPortrait` | `boolean` | 便捷布尔：是否竖屏（`orientation === 'portrait'`） |
| `isLandscape` | `boolean` | 便捷布尔：是否横屏（`orientation === 'landscape'`） |

**注意事项**：
- 只读查询，不会触发事件；适合页面初始化时取一次。
- 旋转后 `getRotation()` 返回新值，但**要收到推送变化请用 `addListener`**。

---

### addListener('rotationChanged', callback)

**功能**：监听屏幕旋转。方向（或角度）真正变化时回调。

**签名**：

```ts
addListener(
  eventName: 'rotationChanged',
  listenerFunc: (state: RotationState) => void,
): Promise<PluginListenerHandle>
```

**回调载荷**：与 `getRotation()` 返回值结构完全一致（`RotationState`）。

**返回**：`PluginListenerHandle`，对象上有 `remove()` 方法用于解除这一个监听。

**注意事项**：
- 可以注册多个监听，全部都会被通知；`handle.remove()` 只移除自己的。
- **去重**：只会在 `orientation` 或 `rotationDegrees` 变化时触发。系统其他配置变化（键盘弹出、字体缩放、深浅色切换）不会误触发。
- 事件派发时机：等系统完成旋转（WebView 尺寸已更新）之后，所以回调里拿 `window.innerWidth/innerHeight` 也是新值。
- iOS 上 `rotationDegrees` 来自 `UIDevice` 物理方向，设备平放（face up/down）时会按屏幕比例兜底为 0 或 90。

---

### removeAllListeners()

**功能**：解除本插件的所有监听（等价于逐个 `handle.remove()`）。

**签名**：`removeAllListeners(): Promise<void>`

**注意事项**：这是 Capacitor 桥内置方法，**不需要**原生额外实现；在插件未注册任何监听时调用也安全。

---

## 平台实现与注意事项

### Android

- 事件来源：`Activity.onConfigurationChanged` → Capacitor 桥 → `Plugin.handleOnConfigurationChanged`。
- **前提**：宿主 `MainActivity` 的 `android:configChanges` 必须包含 `orientation|screenSize`（Capacitor 模板默认包含），这样旋转不会重建 Activity，插件才能收到回调。
- 前提不满足时：旋转会重建 Activity，页面重新加载，插件回调不触发（但 `getRotation()` 依然是准的）。本项目的 MainActivity 已包含，无需改动。
- 角度来自 `Display.getRotation()`：`ROTATION_90` → 90，`ROTATION_180` → 180，`ROTATION_270` → 270。

### iOS

- 事件来源：`UIDevice.orientationDidChangeNotification`（插件 `load()` 时注册，`deinit` 时移除）。
- `orientation` 用 WebView 宽高比判断（比 `UIDevice` 更可靠）；`rotationDegrees` 用 `UIDevice` 物理方向映射：
  `portrait` → 0、`portraitUpsideDown` → 180、`landscapeRight` → 90、`landscapeLeft` → 270。
- 不使用 iOS 26 已 obsoleted 的 `windowScene.interfaceOrientation`（编译期会报错）。
- 监听期间会启用 `beginGeneratingDeviceOrientationNotifications()`，插件销毁时自动关闭。

### Web

- 用 `screen.orientation` 的 `change` 事件（老浏览器退回 `orientationchange`），角度归一化到 0/90/180/270 后派发同构事件。
- 桌面浏览器窗口比例变化时通常不触发 `orientation` 事件（符合预期：桌面没有"设备旋转"）。

## 实测数据（2026-11-03）

| 场景 | 结果 |
| --- | --- |
| 模拟器（Pixel 6，`settings put user_rotation 1`） | 事件 `landscape/90`；转回竖屏 `portrait/0`；**解除监听后再转，事件不再触发**（eventCount 保持不变） |
| 模拟器（`adb emu rotate`） | 事件 `landscape/270` |
| 真机（红米 K30S Ultra / MIUI 12，`adb settings put user_rotation 1`） | 事件 `landscape/90`，恢复竖屏后 `portrait/0` |
| 冒烟测试（真机 + 模拟器） | `读取旋转状态 / 开始监听 / 停止监听` 三个按钮全 PASS |

## 开发

```bash
cd plugins/capacitor-rotation-listener
npm install
npm run build        # tsc → dist/esm
```

- Android：`android/src/main/java/com/testionic/rotation/RotationPlugin.java`
- iOS：`ios/Sources/RotationPlugin/RotationPlugin.swift`（Windows 无法编译，靠 GitHub Actions 验证）
- 插件对外名称：npm 包 `capacitor-rotation-listener`、`registerPlugin('Rotation')`、iOS SPM 包 `CapacitorRotationListener`
