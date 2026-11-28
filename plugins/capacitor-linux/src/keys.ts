import type { LinuxKeyName } from './definitions';

/**
 * `sendKey()` 支持的按键名（与 Android 侧 `LinuxVm.keyBytes()` 一一对应）。
 *
 * 这个数组只是给 UI 用的"可选值清单"：键名的大小写不敏感，原生侧还接受
 * `return`/`esc`/`pgup`/`del` 等别名。新增按键时**两处都要改**：
 * `android/.../LinuxVm.java` 的 `keyBytes()` 与这里。
 */
export const LINUX_KEY_NAMES: LinuxKeyName[] = [
  'ctrl+a',
  'ctrl+b',
  'ctrl+c',
  'ctrl+d',
  'ctrl+e',
  'ctrl+f',
  'ctrl+g',
  'ctrl+h',
  'ctrl+i',
  'ctrl+j',
  'ctrl+k',
  'ctrl+l',
  'ctrl+m',
  'ctrl+n',
  'ctrl+o',
  'ctrl+p',
  'ctrl+q',
  'ctrl+r',
  'ctrl+s',
  'ctrl+t',
  'ctrl+u',
  'ctrl+v',
  'ctrl+w',
  'ctrl+x',
  'ctrl+y',
  'ctrl+z',
  'enter',
  'tab',
  'shift+tab',
  'backspace',
  'escape',
  'space',
  'up',
  'down',
  'left',
  'right',
  'home',
  'end',
  'pageup',
  'pagedown',
  'insert',
  'delete',
  'f1',
  'f2',
  'f3',
  'f4',
  'f5',
  'f6',
  'f7',
  'f8',
  'f9',
  'f10',
  'f11',
  'f12',
];

/**
 * 终端里最常按的几个键，方便直接拿来做键盘条。
 * 顺带一提：`ctrl+c` 发的是 `0x03`，客户机 tty 会给前台进程组发 SIGINT，
 * 所以它能中断 `sleep`/`node`/`cat`/`vi` 这类正在运行的程序。
 */
export const LINUX_COMMON_KEYS: LinuxKeyName[] = [
  'ctrl+c',
  'ctrl+d',
  'ctrl+z',
  'ctrl+l',
  'tab',
  'escape',
  'up',
  'down',
  'left',
  'right',
  'enter',
  'backspace',
];
