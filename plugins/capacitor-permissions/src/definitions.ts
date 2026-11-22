/**
 * 权限插件（Android Runtime Permissions / iOS Info.plist 权限 / Web Permissions API）。
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
 * - `limited`：iOS 14+ 照片"仅选中部分"、Android 14 部分照片访问
 * - `restricted`：系统策略限制（家长控制、MDM 等），用户改不了
 * - `unsupported`：当前平台/系统版本没有这个权限
 */
export interface PermissionsPlugin {
  /** 只查询，不弹任何系统对话框。 */
  check(options: { permissions: PermissionId[] }): Promise<PermissionResults>;

  /**
   * 发起授权请求（会弹系统对话框）。
   *
   * - Android：一次请求里所有权限合并成一个系统对话框（同一次 requestPermissions）
   * - iOS：逐个弹（系统限制，一次只能申请一个）
   * - Web：浏览器只允许在用户手势里请求，且多数权限没有"查询后请求"的能力
   *
   * ⚠️ iOS 上必须先在宿主 `Info.plist` 里写好对应的 usage description，
   * 否则系统**直接崩溃**（不是返回 denied）。README 有完整 key 列表。
   */
  request(options: { permissions: PermissionId[] }): Promise<PermissionResults>;

  /** 当前平台支持哪些权限 id（含需要宿主配置的说明）。 */
  getSupported(): Promise<{
    platform: 'android' | 'ios' | 'web';
    permissions: PermissionInfo[];
  }>;

  /** 打开本 App 的系统设置页（权限被永久拒绝后的兜底）。 */
  openAppSettings(): Promise<{ opened: boolean }>;
}

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
  | 'speech';

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
  /** 便于 UI 直接展示的诊断信息（每个 id 一句话） */
  details?: Record<string, string>;
}

export interface PermissionInfo {
  id: PermissionId;
  supported: boolean;
  /** 需要的宿主配置：Android 权限名 / iOS Info.plist key */
  requires: string[];
  /** 平台差异说明 */
  note?: string;
}
