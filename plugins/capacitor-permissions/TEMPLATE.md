# 权限插件模板 · 扩展指南（TEMPLATE）

> 这个插件是**模板**，不是成品。现成的 16 个权限 id 只是"常见的够用集合"，
> 后续的人或 AI 应该按实际业务增删、改名、补状态。
>
> 本文件说明：**代码结构 → 怎么加一个权限 → 怎么删/改 → 怎么验证 → 有哪些坑**。
> 读完这一篇 + `src/definitions.ts`，就应该能独立改造这个插件。

## 1. 代码结构与职责

| 文件 | 职责 | 改造时要动它吗 |
| --- | --- | --- |
| `src/definitions.ts` | 对外 API 契约 + `PermissionId` / `PermissionState` 类型 | ✅ 增删 id 时改这里 |
| `src/index.ts` | `registerPlugin('Permissions')` | ❌ 除非改 jsName |
| `src/web.ts` | 浏览器降级（Permissions API + getUserMedia） | ✅ 新 id 想在浏览器也支持时 |
| `android/.../PermissionsPlugin.java` | Android 实现，核心是 `DEFINITIONS` 表 + `@Permission` 注解 | ✅ 加一行 + 一条注解 |
| `android/src/main/AndroidManifest.xml` | 声明 Android 权限（会合并进宿主） | ✅ 新权限需要 |
| `ios/.../PermissionsPlugin.swift` | iOS 实现，核心是 `descriptors` 表 + 一对 check/request 方法 | ✅ 加一条 + 两个小方法 |
| `README.md` | 用法 + 完整 API 字段 + 平台差异 | ✅ 新 id 要补一行映射表 |
| `plugin/src` 之外的 `android/build.gradle` / `Package.swift` | 构建配置 | ❌ 一般不动 |

**核心思想**：把"业务语义 id"和"平台实现"解耦。

```
JS:  'photos'  ──────────────┐
                            ├─ Android: 13+ → READ_MEDIA_IMAGES/VIDEO
                            │           12- → READ_EXTERNAL_STORAGE
                            ├─ iOS:     PHPhotoLibrary (.readWrite, 可能 limited)
                            └─ Web:     unsupported（浏览器没有这个权限）
```

## 2. 加一个权限（以 `vibrate` / `cameraFlash` 之类的虚构权限为例）

下面用 **`bluetoothAdvertise`**（Android 12+ 的 `BLUETOOTH_ADVERTISE`）做例子，它是真实存在、
但本模板故意没加的权限，适合用来练手。

### 2.1 JS 类型（`src/definitions.ts`）

```ts
export type PermissionId =
  | /* ...已有的... */
  | 'bluetoothAdvertise'
  | (string & {});   // 保留这一行：允许传自定义 id，未实现时返回 unsupported
```

> `(string & {})` 让 TS 既保留补全、又允许任意字符串。如果你希望**严格**枚举（拒绝未知 id），
> 删掉它即可 —— 但那样业务方就不能渐进式扩展了。

### 2.2 Android

**① 加注解**（`@CapacitorPlugin(permissions = {...})`，编译期就必须存在）：

```java
@Permission(alias = "bluetoothAdvertise", strings = { Manifest.permission.BLUETOOTH_ADVERTISE }),
```

**② 加一行表项**（`buildDefinitions()`，位置决定 `getSupported()` 的顺序）：

```java
map.put("bluetoothAdvertise", new PermissionDef(
        "bluetoothAdvertise",
        sdk -> sdk >= 31 ? "bluetoothAdvertise" : null,  // 12 以下没有这个运行时权限
        "unsupported",                                    // 没有别名时返回什么状态
        Manifest.permission.BLUETOOTH_ADVERTISE,          // 用于 rationale 判断
        new String[]{ Manifest.permission.BLUETOOTH_ADVERTISE },  // getSupported().requires
        "Android 12+ 才有",                                // getSupported().note
        31, -1));                                         // minSdk / maxSdkExclusive
```

**③ Manifest**（如果还没有）：

```xml
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
```

`check() / request() / getSupported()` 不需要改 —— 它们都从 `DEFINITIONS` 读。

> **为什么注解不能省？** Capacitor 的 `@Permission` 别名是**注解处理器在编译期**扫描的，
> 运行时表里查不到它。少了注解，`getPermissionState("alias")` 会返回 `null`（表现为
> `unsupported`），`requestPermissionForAliases` 也会拿不到权限字符串。

### 2.3 iOS

**① 在 `descriptors` 表里加一条**：

```swift
PermissionDescriptor(
    id: "bluetoothAdvertise",
    supported: true,
    requires: ["Info.plist: NSBluetoothAlwaysUsageDescription"],
    note: "与 bluetooth 共用同一个 CoreBluetooth 授权",
    check: { $0.bluetoothState() },        // 复用现成的实现
    request: { $0.requestBluetooth($1) }   // 复用现成的实现
),
```

**② 如果现有小方法不适用**，就在"各权限的 check / request 实现"区增加一对：

```swift
private func fooState() -> (String, String) {
    let status = SomeFramework.authorizationStatus()
    return (mapFoo(status), "SomeFramework.authorizationStatus = \(status.rawValue)")
}

private func requestFoo(_ completion: @escaping (String, String) -> Void) {
    SomeFramework.request { granted in
        completion(granted ? "granted" : "denied", "SomeFramework.request")
    }
}
```

**③ 加 Info.plist key**（不加会**直接崩溃**，不是返回 denied）。测试 App 的
`ios/App/App/Info.plist` 里有一整套例子可抄。

**④（可选）加一个状态映射**：放在"状态映射"区，优先复用 `mapLocation/mapPhotos/…`。

### 2.4 Web（可选）

浏览器没有对应能力时，**什么都别做**：`web.ts` 里 `queryOne` 查不到 name 就返回
`unsupported`（这正是模板期望的降级）。如果浏览器有对应能力（例如 `clipboard-read`），
在 `QUERY_NAME` 里加一行即可：

```ts
const QUERY_NAME: Partial<Record<PermissionId, PermissionName>> = {
  // ...
  clipboardRead: 'clipboard-read' as PermissionName,
};
```

需要"触发式授权"（像 camera 那样调 `getUserMedia`）时，在 `requestOne` 里加一个分支。

### 2.5 文档 & 测试

- README 的「权限 id 与平台映射」表加一行；
- 测试页 `src/views/PluginTestView.vue`：
  - 若只是新增 id，`permissionsCheck` 的数组里加一项即可（autotest 会校验状态合法性）；
  - 若新增了交互，加一个按钮 + 方法（参照 `permissionsRequestCamera`）。

## 3. 删 / 改 权限

**删**（例如业务不需要 `bodySensors`），按相反顺序做，避免留下悬空引用：

1. `descriptors` / `DEFINITIONS` 删掉那一行（Android 还要删 `@Permission` 注解）；
2. `AndroidManifest.xml` 删掉对应 `<uses-permission>`（**重要**：否则宿主 App 还是会申请这个权限）；
3. `definitions.ts` 删掉 union 里那一项；
4. README 映射表删一行。

**改名**（把 `photos` 改成 `photosReadOnly`）：三端一起改 id 字符串；
Android 的 **alias 也建议同名**（alias 可以独立于 id，但同名最省心）。

**细化状态**（Android 14 的"部分照片"、iOS 的"仅选中"）：目前 `limited` 只在 iOS 返回；
如果要支持 Android 的 `READ_MEDIA_VISUAL_USER_SELECTED`，需要在 `DEFINITIONS` 里给 `photos`
加一个"部分授权"的判定（`getPermissionState` 只能给出 granted/denied，所以要额外用
`checkSelfPermission(READ_MEDIA_VISUAL_USER_SELECTED)` 自己判断）。

## 4. 验证方式

```powershell
# 1) 构建插件（TS + 原生在 App 构建时编译）
npm run build:plugins
npm run audit:plugins      # 检查 jsName 三端一致、方法数一致

# 2) 静态检查 iOS 接线
npm run verify:ios

# 3) 部署到真机/模拟器
npm run android:deploy -- -Device 32e93a6d
```

- **autotest 只 `check`、不 `request`**（系统弹窗会阻塞自动化）。改完权限表后，autotest 至少能保证
  "所有 id 都能返回合法状态"；
- **弹窗路径要手动测**：真机点测试页的「申请相机权限」，确认弹窗文案与拒绝/允许后的状态；
- **iOS 走 CI**：`git push` 后看 `.github/workflows/ios.yml`（模拟器 autotest）。

## 5. 坑与约束（改之前先看）

| # | 坑 | 说明 |
| --- | --- | --- |
| 1 | **Android 注解是编译期** | 加了表项但忘了 `@Permission` → 该 id 永远 `unsupported`，且不会有编译错误 |
| 2 | **Manifest 合并** | 插件的 `<uses-permission>` 会合并进宿主，上架前要用 `tools:node="remove"` 去掉不需要的敏感权限（联系人/身体传感器等会触发 Play 审核） |
| 3 | **iOS 缺 Info.plist 会崩溃** | 不是返回 denied，而是 `NSInternalInconsistencyException` 级别的崩溃；`getSupported().requires` 就是给人看的清单 |
| 4 | **iOS 一次只能弹一个** | `request()` 是串行的；一次请求 5 个权限会让用户连点 5 次，建议按需分批 |
| 5 | **别名三处必须一致** | `@Permission(alias=)`、`DEFINITIONS` 的 `aliasFor()`、`AndroidManifest` 的权限名；`audit:plugins` 查不出别名问题，只能靠自觉 |
| 6 | **`supported` 与 `stateWhenNoAlias` 是两件事** | 前者是"这个版本有没有这个概念"，后者是"没有运行时权限时返回什么状态"（安装即授予 = `granted`） |
| 7 | **`limited` 不是所有平台都有** | iOS 照片/联系人会返回；Android 需要自己判断"部分授权" |
| 8 | **不要在 `check()` 里做会弹窗的事** | 例如实例化 `CBCentralManager` 就会弹蓝牙授权框；那属于 `request()` |
| 9 | **方法数会影响 audit 脚本** | `scripts/audit-plugins.mjs` 要求 TS 接口方法数 == Java `@PluginMethod` 数 == Swift `pluginMethods` 数；**新增/删除原生方法**要三端同步 |

## 6. 改造前检查清单

- [ ] 新 id 的三端行为都定义了（Android 别名/版本区间、iOS 检查+请求+Info.plist、Web 降级）
- [ ] `@Permission` 注解与 `DEFINITIONS` 表项都加了
- [ ] Android Manifest 的 `<uses-permission>` 与业务必要性一致
- [ ] iOS `Info.plist` 的 usage description 已加
- [ ] README 映射表 + API 文档已更新
- [ ] `npm run build:plugins && npm run audit:plugins && npm run verify:ios` 通过
- [ ] 真机手动点了"申请"按钮，确认弹窗与结果
- [ ] 宿主上架前复查：没有多余/敏感权限
