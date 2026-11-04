# capacitor-native-rotation

屏幕方向插件：**监听**当前方向与方向变化，**主动控制**锁定横屏/竖屏。Android / iOS / Web 三端可用。

> 典型用途：视频播放器的「全屏播放」——锁定横屏 + 全屏显示，退出播放时解锁并恢复竖屏：
>
> ```ts
> await Rotation.lock({ orientation: 'landscape' });
> await Fullscreen.enable();
> // ...播放...
> await Fullscreen.disable();
> await Rotation.unlock();
> ```

## 设计要点

- **监听 + 控制双能力**：`getRotation()` / `rotationChanged` 事件负责"现在是什么方向"；`lock()` / `unlock()` 负责"锁定/恢复自动"。
- **事件驱动且去重**：原生侧在方向**真正变化**时派发 `rotationChanged`（键盘/字体等其它配置变化会自动去重，不会误报）。
- **监听/解除监听**走 Capacitor 桥的内置能力：`addListener()` 拿到的 handle 调 `handle.remove()` 即解除，原生侧不需要额外开关。
- **锁定会联动事件**：主动锁横屏时屏幕真的旋转，`rotationChanged` 会照常派发（含 rorationDegrees）。
- **三端行为一致**：Web 端用 `screen.orientation` 实现监听与锁定（锁定需浏览器支持且页面全屏）。

## 安装

```bash
npm install ./plugins/capacitor-native-rotation
npx cap sync
```

## 用法

```ts
import { Rotation } from 'capacitor-native-rotation';

// 1) 读取当前状态（不触发事件、不改变锁定）
const state = await Rotation.getRotation();
console.log(state.orientation, state.rotationDegrees); // 'portrait' 0

// 2) 监听旋转
const handle = await Rotation.addListener('rotationChanged', (state) => {
  console.log('旋转了：', state.orientation, state.rotationDegrees);
});

// 3) 主动锁定横屏（视频全屏播放）
await Rotation.lock({ orientation: 'landscape' });

// 4) 锁定竖屏 / 恢复自动旋转
await Rotation.lock({ orientation: 'portrait' });
await Rotation.unlock();                    // 等价于 lock({ orientation: 'auto' })

// 5) 查询锁定状态
const lock = await Rotation.getLock();      // { orientation: 'auto', locked: false }

// 6) 解除监听
await handle.remove();                      // 解除这一个
await Rotation.removeAllListeners();        // 解除全部
```

## API 详细文档

### getRotation()

**功能**：读取当前旋转状态（只读查询，不改变锁定、不触发事件）。

**签名**：`getRotation(): Promise<RotationState>`

**返回值 `RotationState`**：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `orientation` | `'portrait' \| 'landscape' \| 'unknown'` | 当前朝向。按**屏幕宽高比**判断（= 实际渲染方向），`unknown` 极少出现 |
| `rotationDegrees` | `number` | 旋转角度：`0` / `90` / `180` / `270`（与 `capacitor-system-info` 的 `getScreenInfo().rotationDegrees` 同语义） |
| `isPortrait` | `boolean` | 便捷布尔：是否竖屏 |
| `isLandscape` | `boolean` | 便捷布尔：是否横屏 |

**注意事项**：旋转后 `getRotation()` 返回新值，但**要收到推送变化请用 `addListener`**。

---

### lock(options)

**功能**：锁定屏幕方向。

**签名**：`lock(options: { orientation: 'landscape' | 'portrait' | 'auto' }): Promise<OrientationLockState>`

**参数**：

| 参数 | 类型 | 含义 |
| --- | --- | --- |
| `orientation` | `'landscape' \| 'portrait' \| 'auto'` | `landscape` 锁定横屏（支持左右两个横屏方向）；`portrait` 锁定竖屏；`auto` 恢复系统自动旋转（同 `unlock()`） |

**返回值 `OrientationLockState`**：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `orientation` | `'landscape' \| 'portrait' \| 'auto'` | 当前锁定的方向 |
| `locked` | `boolean` | 是否锁定中（`orientation !== 'auto'`） |

**注意事项**：
- 锁定横屏会**真实触发**屏幕旋转（界面变横屏），并通过 `rotationChanged` 事件通知；
- 横屏锁定用的是"传感器横屏"（SENSOR_LANDSCAPE）：允许左右两个横屏方向，符合视频播放习惯；
- 设备处于竖放状态锁横屏，系统会立即旋到横屏；把手机倒过来仍在横屏（不会竖屏）；
- 锁定只影响"系统是否允许转屏"，不改变全屏/系统栏状态（配合 `capacitor-fullscreen` 使用）。

---

### unlock()

**功能**：解除方向锁定，恢复系统自动旋转（等价于 `lock({ orientation: 'auto' })`）。

**签名**：`unlock(): Promise<OrientationLockState>`

**注意事项**：恢复后方向重新跟随设备传感器；此时把手机横过来，屏幕就会自动转到横屏（并派发 `rotationChanged`）。

---

### getLock()

**功能**：读取当前方向锁定状态（不改变任何状态）。

**签名**：`getLock(): Promise<OrientationLockState>`

**注意事项**：返回插件维护的锁定状态。如果应用/其他方式改变过方向（例如系统级旋转锁定），这里仍以本插件最后一次设置的状态为准。

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
- 可以注册多个监听，全部都会被通知；`handle.remove()` 只移除自己的；
- **去重**：只在 `orientation` 或 `rotationDegrees` 变化时触发；键盘弹出、字体缩放、深浅色切换不会误触发；
- 事件派发时机：等系统完成旋转（WebView 尺寸已更新）之后，回调里读 `window.innerWidth/innerHeight` 已是新值；
- **主动 lock/unlock 引起的旋转同样会触发本事件**；
- iOS 上 `rotationDegrees` 来自 `UIDevice` 物理方向，设备平放（face up/down）时按屏幕比例兜底为 0 或 90。

---

### removeAllListeners()

**功能**：解除本插件的所有监听（等价于逐个 `handle.remove()`）。

**签名**：`removeAllListeners(): Promise<void>`

**注意事项**：Capacitor 桥内置方法，**不需要**原生额外实现；未注册任何监听时调用也安全。

## 平台实现与注意事项

### Android

- **旋转事件**：`Activity.onConfigurationChanged` → Capacitor 桥 → `Plugin.handleOnConfigurationChanged`。
  前提是宿主 `MainActivity` 的 `android:configChanges` 含 `orientation|screenSize`（Capacitor 模板默认包含，本项目无需改动）。
  前提不满足时：旋转会重建 Activity、页面重新加载（`getRotation()` 仍准确，但事件不触发）。
- **方向锁定**：`Activity.setRequestedOrientation()`——`landscape` → `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`、
  `portrait` → `SCREEN_ORIENTATION_SENSOR_PORTRAIT`、`auto` → `SCREEN_ORIENTATION_UNSPECIFIED`。
  锁定触发的旋转会走同一条 `onConfigurationChanged` 链路，因此 `rotationChanged` 自动派发。
- 角度来自 `Display.getRotation()`：`ROTATION_90` → 90，`ROTATION_180` → 180，`ROTATION_270` → 270。

### iOS

- **旋转事件**：`UIDevice.orientationDidChangeNotification`（`load()` 注册、`deinit` 移除）；
  `orientation` 用 WebView 宽高比判断（比 `UIDevice` 更可靠），`rotationDegrees` 按物理方向映射：
  `portrait` → 0、`portraitUpsideDown` → 180、`landscapeRight` → 90、`landscapeLeft` → 270。
  不使用 iOS 26 已 obsoleted 的 `windowScene.interfaceOrientation`。
- **方向锁定**：修改 `CAPBridgeViewController.supportedOrientations`（public 可写）
  + `setNeedsUpdateOfSupportedInterfaceOrientations()` + `requestGeometryUpdate()`（iOS 16+）；
  iOS 15 走兼容路径（`UIDevice` KVC 设方向 + `attemptRotationToDeviceOrientation()`）。
  恢复自动时把 `supportedOrientations` 还原为进入锁定前记录的值。

### Web

- 监听：`screen.orientation` 的 `change` 事件（老浏览器退回 `orientationchange`），角度归一化到 0/90/180/270。
- 锁定：`screen.orientation.lock()` / `unlock()`；**浏览器要求页面处于全屏**且支持该 API，
  不满足时会 reject（错误信息 `screen.orientation.lock is not supported in this browser`）。

## 实测数据

| 场景 | 结果 |
| --- | --- |
| **主动锁定横屏**（真机 K30S Ultra / MIUI 12） | `lock({orientation:'landscape'})` → 返回 `{orientation:'landscape', locked:true}`，屏幕真实转到横屏（截图 2400×1080），`getRotation()` → `landscape/90` ✅ |
| **解锁恢复**（真机） | `unlock()` → `{orientation:'auto', locked:false}`，屏幕回到竖屏，`getRotation()` → `portrait/0` ✅ |
| 旋转事件（模拟器，`settings put system user_rotation`） | 横屏 `landscape/90`、回竖屏 `portrait/0`、**解除监听后不再触发** ✅ |
| 旋转事件（模拟器，`adb emu rotate`） | `landscape/270` ✅ |
| 旋转事件（真机 MIUI 12） | `landscape/90` → `portrait/0` ✅ |
| 冒烟测试（真机 + 模拟器） | 读取 / 监听 / 停止监听 / 锁定横屏 / 读取锁定 / 解锁 全 PASS ✅ |

## 开发

```bash
cd plugins/capacitor-native-rotation
npm install
npm run build        # tsc → dist/esm
```

- Android：`android/src/main/java/com/testionic/rotation/RotationPlugin.java`
- iOS：`ios/Sources/RotationPlugin/RotationPlugin.swift`（Windows 无法编译，靠 GitHub Actions 验证）
- 插件对外名称：npm 包 `capacitor-native-rotation`、`registerPlugin('Rotation')`、iOS SPM 包 `CapacitorNativeRotation`
- 历史：0.1.0 名为 `capacitor-rotation-listener`（只监听），0.2.0 加入方向锁定并改名为 `capacitor-native-rotation`
