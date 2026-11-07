# capacitor-foreground-state 插件介绍

> 一句话：**让 WebView 里的 JS 知道"App 现在在前台还是后台"，并在切换的那一刻收到事件。**

## 它解决什么问题

WebView 里的页面并不知道自己所在的原生 App 是否被切到了后台：

- 视频/动画/轮询在后台继续跑 → **白耗电**、被系统限制；
- 用户切走再回来 → 页面不知道"离开了多久"；
- 长任务（文件传输、上传）在 iOS 切后台会被**挂起**，需要提前提示或保存进度。

浏览器里可以用 `document.visibilitychange` 近似（本插件的 Web 实现就是这么降级的），
但在 Capacitor 里它不可靠：**App 被切到后台时 WebView 页面未必触发 visibilitychange**
（尤其 Android 上，WebView 不一定会收到 pause）。所以需要原生侧监听生命周期再通知 JS。

## 提供什么

| API | 作用 |
|---|---|
| `getState()` | 读取当前状态：`active` / `inactive`（iOS 过渡态）/ `background` |
| `addListener('stateChanged', cb)` | 状态切换时派发事件（返回 `handle.remove()`） |
| `removeAllListeners()` | 移除本插件所有监听 |

事件/返回值字段（`ForegroundStateInfo`）：

| 字段 | 说明 |
|---|---|
| `isActive` | 是否前台活跃（= `state === 'active'`） |
| `state` | `active` / `inactive` / `background` |
| `changedAt` | 本次变化时间戳（ms） |
| `lastBackgroundAt` | 最近一次进后台的时间戳；没进过为 0 |
| `backgroundDuration` | 若刚回前台：在后台停留的毫秒数（**实测精确**，见 README） |

## 30 秒上手

```ts
import { ForegroundState } from 'capacitor-foreground-state';

// 一次性读取
const s = await ForegroundState.getState();
console.log(s.state, s.isActive);

// 监听切换
const handle = await ForegroundState.addListener('stateChanged', (s) => {
  if (s.state === 'background') {
    pauseVideo();          // 停动画/暂停轮询/保存草稿
  } else if (s.isActive) {
    resumeVideo();
    console.log('离开了', s.backgroundDuration, 'ms');
  }
});

// 不用了
await handle.remove();
```

## 平台行为

| 平台 | 状态集合 | 原理 |
|---|---|---|
| Android | `active` / `background` | Capacitor 插件的 `handleOnResume` / `handleOnPause`（没有独立 `inactive`） |
| iOS | `active` / `inactive` / `background` | `UIApplication` 的 4 个通知（`willResignActive`/`didEnterBackground`/`willEnterForeground`/`didBecomeActive`） |
| Web | `active` / `background` | `Page Visibility API` 降级（语义不完全等价） |

> **`inactive` 只在 iOS 出现**（来电、控制中心、App 切换器、锁屏）。业务上把它当"准备暂停"的信号。

## 典型用法

| 场景 | 做法 |
|---|---|
| 省电 | 后台时暂停视频/动画/`requestAnimationFrame`/轮询定时器 |
| 保存草稿 | `background` 时立即 `localStorage`/IndexedDB 落盘 |
| 统计时长 | 用 `backgroundDuration` 累加"用户离开时间" |
| 传输保护 | 后台时暂停大文件传输（iOS socket 约 30 秒后被挂起），或提示用户保持前台 |
| 与 data-channel 配合 | 切后台时 `DataChannel.revokeAllOtps()`（媒体 reusable OTP 无 TTL，必须主动清） |

## 适合 / 不适合

| ✅ 适合 | ❌ 不适合 |
|---|---|
| 前后台切换时暂停/恢复业务 | 需要"App 被杀"的通知（进程都结束了，JS 收不到） |
| 统计后台时长 | 需要精确的后台执行时间（受系统限制） |
| 与长任务/传输配合 | 需要后台持续运行（要前台服务/原生后台任务，不属于本插件） |

## 平台与验证状态

| 平台 | 状态 |
|---|---|
| Android | ✅ **真机**（Redmi K30S / Android 10）+ **模拟器**（Android 16）实测：HOME 切后台/回前台事件与 `backgroundDuration` 精确 |
| iOS | ✅ **CI**（模拟器编译 + autotest：`getState` 字段 + `addListener`/`remove` 全绿） |
| Web | ✅ Page Visibility 降级 |

代码规模：Android 1 个类 + iOS 1 个文件 + TS 三个小文件；无任何权限。

---

**更多细节**：[README.md](README.md)（API 字段表、平台差异、实测记录、与其它插件配合）。
