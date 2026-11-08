/**
 * CppBridge —— C/C++ 桥模板（capacitor-cpp-bridge）。
 *
 * ======================= 这是什么 =======================
 *
 * 它不是一个"功能插件"，而是一个**桥梁模板**：把 WebView → native（Java/Swift）→ 共享 C/C++ 核心
 * 这条链路搭起来，并给出最小可运行的示例。真正的业务 C/C++ 代码由后续开发者补在
 * `shared/core/` 里（Android/iOS 共用同一份源码）。
 *
 * ```text
 * JS  →  CppBridgePlugin（native：参数校验 / 句柄管理）
 *     →  bridge.cpp（JNI，Android）/ CppBridgePlugin.swift（iOS）
 *     →  shared/core（cpp_core.cpp + c_core.c，跨平台）
 * ```
 *
 * ======================= C 与 C++ 都支持 =======================
 *
 * 同一个 native 库里既有 C++ 编译单元（`cpp_core.cpp`，可用 STL/模板），
 * 也有**纯 C 编译单元**（`c_core.c`，用 C 编译器编译）。
 * 头文件 `cppcore.h` 用 `extern "C"` 包住声明，所以 C 文件包含它也合法。
 *
 * ======================= 关于句柄 API（⚠️ danger）=======================
 *
 * `allocBuffer` / `fillBuffer` / `sumBuffer` / `freeBuffer` 操作的是 **native 内存**，
 * 数据不经过 JS 桥（这是"零拷贝"的演示）。代价是**生命周期要自己管**：
 *
 * - `handle` 本质是 native 指针的数值，**必须**在用完后 `freeBuffer`，否则泄漏到进程退出；
 * - 进程重启后 handle 失效；
 * - 不要并发对同一 handle 写（模板没有加锁）；
 * - 只有模板内部的 `getBufferStats()` 能帮你发现"忘了 free"。
 *
 * 如果只是想把数据从 A 传到 B，优先用普通 API（字符串/base64）或
 * `capacitor-data-channel`，不要为了"看起来快"而用句柄。
 */
export interface CppBridgePlugin {
  /**
   * 返回 native 库的构建信息（验证 `.so` / framework 已加载）。
   *
   * 返回里的 `buildInfo` 是 **C++ 侧生成的 JSON 字符串**，包含 platform / compiler / arch / cxx。
   *
   * @since 0.1.0
   */
  getCoreInfo(): Promise<CoreInfo>;

  /**
   * 两数相加（由 **C++ 编译单元** 实现）。
   *
   * @since 0.1.0
   */
  add(options: { a: number; b: number }): Promise<AddResult>;

  /**
   * 两数相加（由 **纯 C 编译单元** 实现，用来证明 C 代码也在同一个库里）。
   *
   * @since 0.1.0
   */
  cAdd(options: { a: number; b: number }): Promise<AddResult>;

  /**
   * 字符串长度（C 实现，验证 C 侧处理 `char*`）。
   *
   * @since 0.1.0
   */
  cStrlen(options: { text: string }): Promise<{ text: string; length: number; impl: 'c' }>;

  /**
   * ⚠️ **DANGER**：分配一块 native 内存，返回句柄（指针数值）。
   *
   * - `size` 必须是 **4 的倍数**（模板按 32 位整数操作）；
   * - 必须配对调用 `freeBuffer({ handle })`；
   * - 返回 `handle = 0` 表示分配失败（会 reject）。
   *
   * @since 0.1.0
   */
  allocBuffer(options: { size: number }): Promise<BufferHandle>;

  /**
   * ⚠️ **DANGER**：把 buffer 的每个 32 位整数填成 `value`（纯 native 操作，数据不过桥）。
   *
   * @since 0.1.0
   */
  fillBuffer(options: { handle: number; value: number }): Promise<FillResult>;

  /**
   * ⚠️ **DANGER**：对 buffer 按 32 位整数求和（纯 native 操作，数据不过桥）。
   *
   * @since 0.1.0
   */
  sumBuffer(options: { handle: number }): Promise<SumResult>;

  /**
   * ⚠️ **DANGER**：释放句柄。重复释放返回 `{ freed: false }`（不会崩溃）。
   *
   * @since 0.1.0
   */
  freeBuffer(options: { handle: number }): Promise<FreeResult>;

  /**
   * 当前仍在使用中的句柄统计（`count` / `bytes`），用来发现忘记释放的 buffer。
   *
   * @since 0.1.0
   */
  getBufferStats(): Promise<{ count: number; bytes: number }>;
}

/** `getCoreInfo()` 返回值。 */
export interface CoreInfo {
  /** 平台标识（native 侧硬编码，用于快速判断当前实现）。 */
  platform: 'android' | 'ios' | 'web';
  /** native 库是否加载成功。 */
  loaded: boolean;
  /** 库文件名（Android 为 `libcppbridge.so`）。 */
  library?: string;
  /**
   * C++ 侧生成的构建信息 JSON 字符串（`loaded: true` 时才有）：
   * `{"platform":"android","compiler":"clang 18.0.0","arch":"aarch64","cxx":"C++17"}`
   */
  buildInfo?: string;
  /** 加载失败时的错误信息（`loaded: false` 时才有）。 */
  error?: string;
}

/** `add` / `cAdd` 的返回值。 */
export interface AddResult {
  a: number;
  b: number;
  /** `a + b`。 */
  result: number;
  /** 实现来自哪个编译单元：`cpp` 或 `c`。 */
  impl: 'cpp' | 'c';
}

/** `allocBuffer` 的返回值（⚠️ danger）。 */
export interface BufferHandle {
  /** native 指针数值；必须交给 `freeBuffer`。 */
  handle: number;
  /** 分配的字节数（4 的倍数）。 */
  size: number;
  /** 当前仍在使用中的句柄数量。 */
  totalBuffers: number;
  /** 当前仍在使用中的总字节数。 */
  totalBytes: number;
}

/** `fillBuffer` 的返回值。 */
export interface FillResult {
  ok: true;
  handle: number;
  size: number;
  value: number;
}

/** `sumBuffer` 的返回值。 */
export interface SumResult {
  handle: number;
  size: number;
  /** 按 32 位整数求和后提升到 64 位的结果。 */
  sum: number;
}

/** `freeBuffer` 的返回值。 */
export interface FreeResult {
  /** 是否真的释放了（重复释放为 `false`）。 */
  freed: boolean;
  /** 释放的字节数（`freed: true` 时才有）。 */
  size?: number;
  /** 释放后仍在使用的句柄数。 */
  totalBuffers?: number;
  /** 释放后仍在使用的总字节数。 */
  totalBytes?: number;
  /** 未释放时的原因：`UNKNOWN_OR_ALREADY_FREED`。 */
  reason?: string;
}
