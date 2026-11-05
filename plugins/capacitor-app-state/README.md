# capacitor-app-state

应用**前后台状态**插件：让 WebView 里的 JS 知道应用当前是否在前台，并在切换时收到事件。

> 典型用途：切后台暂停视频/动画/轮询（省电）、切后台前保存草稿、统计用户离开时长、
> 长任务（如 data-channel 传输）在 iOS 切后台时提示用户。

## 安装

```bash
npm install ./plugins/capacitor-app-state
npx cap sync
```

> 无需任何权限。Android 用 Capacitor 插件生命周期钩子；iOS 用 `UIApplication` 通知。

## 用法

```ts
import { AppState } from 'capacitor-app-state';

// 读取当前状态
const state = await AppState.getState();
// { isActive: true, state: 'active', changedAt: 1793882940085, lastBackgroundAt: 0, backgroundDuration: 0 }

// 监听变化
const handle = await AppState.addListener('stateChanged', (s) => {
  console.log('state:', s.state, 'isActive:', s.isActive);
  if (s.state === 'background') {
    // 暂停视频/轮询、保存草稿……
  } else if (s.isActive) {
    // 回前台：恢复
    console.log('在后台停留了', s.backgroundDuration, 'ms');
  }
});

// 取消单个监听
await handle.remove();
// 取消所有监听
await AppState.removeAllListeners();
```

## API

### getState() → AppState

读取当前状态（不影响任何东西）。

### addListener('stateChanged', cb)

状态变化时派发。事件载荷与 `getState()` 相同。

### removeAllListeners()

移除本插件的所有监听。单个监听用 `handle.remove()`。

## AppState 字段

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `isActive` | `boolean` | 是否前台活跃（等价于 `state === 'active'`） |
| `state` | `'active' \| 'inactive' \| 'background'` | 当前状态 |
| `changedAt` | `number` | 本次状态变化的时间戳（毫秒） |
| `lastBackgroundAt` | `number` | 最近一次进入后台的时间戳；从未进入过为 0 |
| `backgroundDuration` | `number` | 若当前是 active 且刚从后台返回：在后台停留的毫秒数；否则 0 |

## 平台差异

| 平台 | 状态 | 说明 |
| --- | --- | --- |
| Android | `active` / `background` | 用 `handleOnResume` / `handleOnPause`；**没有** iOS 那样的独立 `inactive` 通知 |
| iOS | `active` / `inactive` / `background` | `didBecomeActive` → active；`willResignActive` → inactive；`didEnterBackground` → background；`willEnterForeground` → inactive（随后 active） |
| Web | `active` / `background` | `Page Visibility API` 模拟，语义与原生不完全等价 |

> iOS 的 `inactive` 常见于：来电、下拉控制中心、App 切换器、锁屏。它可能回到 `active`，
> 也可能继续进入 `background`——业务上一般把 `inactive` 当作"准备暂停"的信号。

## 实测记录（真机 Redmi K30S / Android 10）

| 操作 | 事件 |
| --- | --- |
| 初始 | `{isActive:true, state:'active', backgroundDuration:0}` |
| 按 HOME 键 | `{isActive:false, state:'background', lastBackgroundAt:..., backgroundDuration:0}` |
| 回到 App | `{isActive:true, state:'active', backgroundDuration:3232}`（后台停留 3.2 秒，精确） |

## 开发

```bash
cd plugins/capacitor-app-state
npm install
npm run build   # tsc → dist/esm
```

- Android：`android/src/main/java/com/testionic/appstate/AppStatePlugin.java`
- iOS：`ios/Sources/AppStatePlugin/AppStatePlugin.swift`
