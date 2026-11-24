/**
 * 权限插件**模板**（Android Runtime Permissions / iOS Info.plist 权限 / Web Permissions API）。
 *
 * ============================ 这是一个模板 ============================
 * 这是一个「能直接用、但不是最终形态」的插件模板：
 *
 * - 现成的 16 个 id 覆盖了常见需求（相机/麦克风/定位/通知/照片/联系人/日历/蓝牙/运动/ATT/语音…）；
 * - 每个平台的实现都是一张**声明式注册表**（Android `DEFINITIONS` / iOS `descriptors`），
 *   新增一个权限 = 表里加一行 + 两条平台配置，`check/request/getSupported` 自动支持；
 * - 业务方可以删掉用不到的 id（减少宿主无关权限）、也可以改名以贴合自己的业务语义。
 *
 * 想扩展/改造请先读插件目录下的 **TEMPLATE.md**（逐文件、逐平台的改法清单）。
 *
 * 设计目标：**一套权限 id 三端通用**，业务代码不用关心平台差异。
 *
 * ```ts
 * const { results } = await Permissions.check({ permissions: ['camera', 'notifications'] });
 * if (results.camera !== 'granted') {
 *   const r = await Permissions.request({ permissions: ['camera'] });
 * }
 * ```
 *
 * 状态含义：
 * - `granted`：已授权
 * - `denied`：被拒绝（Android 上「拒绝且不再询问」也是这个；iOS 上用户点过"不允许"）
 * - `prompt`：还没问过，可以弹窗
 * - `prompt-with-rationale`：Android 上被拒绝过一次，应该先解释再请求
 * - `limited`：iOS 14+ 照片"仅选中部分"、iOS 18+ 联系人部分授权
 * - `restricted`：系统策略限制（家长控制、MDM 等），用户改不了
 * - `unsupported`：当前平台/系统版本没有这个权限
 */
export interface PermissionsPlugin {
  /**
   * 只查询，不弹任何系统对话框。
   *
   * - Android：读 `checkSelfPermission` + `shouldShowRequestPermissionRationale`
   * - iOS：读各框架的 `authorizationStatus`（不会触发授权框）
   * - Web：`navigator.permissions.query`
   *
   * 未知 id 会返回 `unsupported`（并在 `details` 里说明），**不会 reject**。
   */
  check(options: { permissions: PermissionId[] }): Promise<PermissionResults>;

  /**
   * 发起授权请求（会弹系统对话框）。
   *
   * - Android：一次请求里所有权限合并成一个系统对话框（同一次 `requestPermissions`）
   * - iOS：逐个弹（系统限制，一次只能申请一个，串行执行）
   * - Web：浏览器只允许在用户手势里请求，且多数权限没有"查询后请求"的能力
   *
   * 已经 `granted` 的权限不会再弹。返回值是**请求之后**的状态快照。
   *
   * ⚠️ iOS 上必须先在宿主 `Info.plist` 里写好对应的 usage description，
   * 否则系统**直接崩溃**（不是返回 denied）。README 有完整 key 列表。
   */
  request(options: { permissions: PermissionId[] }): Promise<PermissionResults>;

  /**
   * 当前平台支持哪些权限 id（含需要宿主配置的说明）。
   *
   * 顺序 = 平台注册表的声明顺序，方便 UI 直接渲染成列表。
   */
  getSupported(): Promise<{
    platform: 'android' | 'ios' | 'web';
    permissions: PermissionInfo[];
  }>;

  /** 打开本 App 的系统设置页（权限被永久拒绝后的兜底）。iOS/Android 都支持，Web 返回 `opened:false`。 */
  openAppSettings(): Promise<{ opened: boolean }>;
}

/**
 * 权限 id。
 *
 * 内置了下列 16 个；**模板使用者可以自由增删**（改三端注册表 + 这里）。
 * 末尾的 `(string & {})` 让 TS 既保留自动补全、又允许传入自定义 id（自定义 id 在没实现前
 * 会返回 `unsupported`，方便渐进式扩展）。
 */
export type PermissionId =
  | 'camera'
  | 'microphone'
  | 'location'
  | 'locationCoarse'
  | 'notifications'
  | 'photos'
  | 'storage'
  | 'contacts'
  | 'calendar'
  | 'reminders'
  | 'bluetooth'
  | 'motion'
  | 'activityRecognition'
  | 'bodySensors'
  | 'tracking'
  | 'speech'
  | (string & {});

/**
 * 权限状态。
 *
 * | 值 | 含义 | 下一步 |
 * | --- | --- | --- |
 * | `granted` | 已授权 | 直接调用业务 API |
 * | `denied` | 被拒绝（含"不再询问"） | 提示 + `openAppSettings()` |
 * | `prompt` | 没问过 | 可以直接 `request()` |
 * | `prompt-with-rationale` | Android 拒绝过一次 | 先解释用途再 `request()` |
 * | `limited` | 部分授权（iOS 照片/联系人） | 按业务决定是否够用 |
 * | `restricted` | 系统策略限制 | 无法通过 App 改变 |
 * | `unsupported` | 当前平台/版本没有 | 走业务降级路径 |
 */
export type PermissionState =
  | 'granted'
  | 'denied'
  | 'prompt'
  | 'prompt-with-rationale'
  | 'limited'
  | 'restricted'
  | 'unsupported';

export interface PermissionResults {
  /** key 是请求的权限 id；没被支持的会是 `unsupported` */
  results: Record<string, PermissionState>;
  /** 便于 UI 直接展示的诊断信息（每个 id 一句话，含平台原始状态码/原因） */
  details?: Record<string, string>;
}

export interface PermissionInfo {
  /** 权限 id */
  id: PermissionId;
  /** 当前平台/系统版本是否支持申请 */
  supported: boolean;
  /** 需要的宿主配置：Android 权限名 / iOS Info.plist key；空数组表示不需要 */
  requires: string[];
  /** 平台差异说明（例如"Android 13 以下不需要通知权限"） */
  note?: string;
}
