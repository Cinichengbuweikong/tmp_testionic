# capacitor-permissions · 插件概览

**一句话**：一套权限 id 覆盖 Android 运行时权限、iOS 系统权限、Web Permissions API，
既能查状态也能发起申请；同时它本身是一个**可裁剪的插件模板**。

## 它解决什么问题

Capacitor 的每个官方插件各自管自己的权限（相机插件申请相机…），但业务里经常需要
"先问一下有没有权限，再决定要不要弹窗""把用户带去设置页""一次申请多个权限"。
这个插件把这些统一成：

```ts
const { results } = await Permissions.check({ permissions: ['camera', 'notifications'] });
// results.camera === 'granted' | 'denied' | 'prompt' | 'prompt-with-rationale' | 'limited' | 'restricted' | 'unsupported'
const after = await Permissions.request({ permissions: ['camera'] });
await Permissions.openAppSettings();               // 被"不再询问"后的兜底
const info = await Permissions.getSupported();     // 每个 id 需要什么宿主配置
```

## 支持的 id（16 个）

`camera` / `microphone` / `location` / `locationCoarse` / `notifications` / `photos` / `storage` /
`contacts` / `calendar` / `reminders` / `bluetooth` / `motion` / `activityRecognition` /
`bodySensors` / `tracking` / `speech`

各平台不支持的 id 返回 `unsupported`（例如 iOS 的 `storage`、Android 的 `tracking`）。

## 作为"插件模板"

- Android 的核心是一张 `DEFINITIONS` 表（`PermissionDef` + 版本相关的 `AliasResolver`）；
- iOS 的核心是一个 `descriptors` 数组（每条带 `check` / `request` 两个小闭包）；
- 加一个权限 = 表里加一行 + 平台配置（Android `@Permission` 注解、iOS Info.plist key）；
  `check/request/getSupported` 自动支持；
- 还有两个扩展点：`REFINERS`（部分授权 → `limited`，已用于 location / photos）与
  `TEMPLATE.md`（逐文件改造指南）。

## 必须知道的坑

1. **iOS 缺 Info.plist usage description 会直接崩溃**（不是返回 denied）→
   `getSupported().requires` 会列出每个 id 需要的 key；
2. **iOS 一次只能弹一个授权框**，`request()` 是串行的；一次请求 5 个权限 = 用户连点 5 次；
3. **Android 13+ 的 `notifications` 是运行时权限**，其它版本安装即授予；
4. **Android 12+ 用户只给"大致位置"** 时 `location` 返回 `limited`（不是 `prompt`，别反复弹窗）；
5. **iOS 的 `location` 只申请"使用期间"**，要"始终允许"（后台定位）得自己再调 `requestAlwaysAuthorization`
   并加 `UIBackgroundModes: location`；
6. 插件会把 12 组 `<uses-permission>` 合并进宿主，上架前要审一遍敏感权限（联系人/身体传感器等）。

## 实测

Android 10 真机 / Android 16 模拟器：`check()` 状态正确、`request()` 真实弹窗（拒绝后
`prompt-with-rationale`）、粗略定位场景返回 `limited`；iOS 模拟器（CI）验证 16 个 id 的状态与
`getSupported()` 结构。autotest 只 `check` 不 `request`（避免系统弹窗阻塞 CI）。
