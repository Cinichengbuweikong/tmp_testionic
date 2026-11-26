/**
 * capacitor-python 入口。
 *
 * 用法：
 * ```ts
 * import { Python } from 'capacitor-python';
 * import { initEditor, destroyEditor, getEditorValue, setEditorValue } from 'capacitor-python/editor';
 *
 * await Python.initialize();                       // 解析/校验 Pyodide 资源
 * const s = await Python.createSession({ name: 'demo' });
 * const off = Python.addListener('sessionOutput', (e) => console.log(e.stream, e.data));
 * const out = await Python.exec(s.sessionId, 'print("hi"); 1 + 1');
 * console.log(out.stdout, out.result);             // "hi\n", "2"
 * ```
 *
 * 原生插件（`PythonNative`）只有 `getInfo()`，用来判断当前 WebView 是否满足最低版本；
 * 真正的 Python 会话逻辑在 `PythonRuntime`（JS + WASM）。
 */

import { Capacitor, registerPlugin } from '@capacitor/core';

import type { PythonNativeInfo, PythonPlugin } from './definitions';
import { PythonRuntime } from './engine/runtime';

/** 原生壳（Android/iOS）：平台信息与最小版本检查。 */
export const PythonNative = registerPlugin<PythonPlugin>('Python');

/**
 * 在创建会话之前检查当前环境能否运行 Pyodide。
 *
 * - Android：解析 WebView 的 Chrome 主版本，< 90 返回 `supported: false`；
 * - iOS：系统 < 16.4 返回 `supported: false`；
 * - Web：直接返回 supported（由浏览器决定）。
 *
 * 注意：即使这里返回 true，也建议把 `createSession()` 放进 try/catch —— 极老的 WebView
 * 可能在编译 wasm 时才失败，`createSession` 会以 `UNSUPPORTED_WEBVIEW` 错误拒绝。
 */
export async function checkPythonSupport(): Promise<{
  supported: boolean;
  info?: PythonNativeInfo;
  reason?: string;
}> {
  if (!Capacitor.isNativePlatform()) {
    return { supported: true, reason: 'web' };
  }
  try {
    const info = await PythonNative.getInfo();
    const supported = info.supported !== false;
    return { supported, info: { ...info, supported }, reason: supported ? undefined : info.note };
  } catch (error) {
    // 原生插件不可用时不阻塞主流程，交给 createSession 去暴露真实错误
    return { supported: true, reason: `getInfo failed: ${String((error as Error)?.message ?? error)}` };
  }
}

/**
 * 默认的 Python 运行时实例（单例）。
 * 需要多实例/自定义运行时目录时，可以 `new PythonRuntime({ runtimeUrl, version })`。
 */
export const Python = new PythonRuntime();

export { PythonRuntime, PythonSession, DEFAULT_PYODIDE_VERSION, DEFAULT_RUNTIME_URL } from './engine/runtime';
export * from './definitions';
