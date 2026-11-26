/**
 * 框架无关的 Python 代码编辑器（CodeMirror 6）。
 *
 * 为什么是 CodeMirror 6 而不是 Monaco：
 *   - Monaco 官方明确"不支持移动端浏览器"（隐藏 textarea 方案，iOS/Android 键盘与选择手柄问题一堆），
 *     且打包后 ~850KB gzip + 独立 worker，离线 WebView 里很重；
 *   - CodeMirror 6 用 contenteditable，针对 Android IME / iOS 选择手柄有持续修复，
 *     Python 语言包自带缩进规则（`if x:` 回车自动缩进）、括号匹配、折叠，
 *     以及"内置函数/关键字"级别的补全（正好是要的"简单提示"），打包约 140KB gzip。
 *
 * 设计约束：
 *   - **不依赖任何框架**：只用 DOM 选择器 + 普通对象句柄；
 *   - 一个页面可以同时存在多个编辑器，按 selector 注册/销毁；
 *   - 所有样式由 CodeMirror 运行时注入（无 CSS 文件），不会和宿主样式打架。
 */

import { autocompletion, closeBrackets, closeBracketsKeymap, completionKeymap } from '@codemirror/autocomplete';
import { defaultKeymap, history, historyKeymap, indentWithTab, redo, undo } from '@codemirror/commands';
import { globalCompletion, localCompletionSource, python } from '@codemirror/lang-python';
import {
  bracketMatching,
  defaultHighlightStyle,
  foldGutter,
  foldKeymap,
  indentOnInput,
  syntaxHighlighting,
} from '@codemirror/language';
import { highlightSelectionMatches, searchKeymap } from '@codemirror/search';
import { Compartment, EditorState, type Extension } from '@codemirror/state';
import { oneDark } from '@codemirror/theme-one-dark';
import {
  drawSelection,
  dropCursor,
  EditorView,
  highlightActiveLine,
  highlightActiveLineGutter,
  keymap,
  lineNumbers,
  placeholder as placeholderExt,
  rectangularSelection,
} from '@codemirror/view';

/** `initEditor()` 的选项。 */
export interface EditorInitOptions {
  /** 初始代码，默认空串 */
  value?: string;
  /** 只读模式 */
  readOnly?: boolean;
  /** 主题：light（默认）或 dark */
  theme?: 'light' | 'dark';
  /** 是否显示行号，默认 true */
  lineNumbers?: boolean;
  /** Tab 宽度，默认 4（Python 惯例） */
  tabSize?: number;
  /** 编辑器高度（CSS 长度），默认 320px */
  height?: string;
  /** 占位文案 */
  placeholder?: string;
  /** 按 Ctrl/Cmd+Enter 时触发（参数是当前代码） */
  onRun?: (code: string) => void;
  /** 内容变化时触发 */
  onChange?: (code: string) => void;
}

/** `initEditor()` 的返回值。 */
export interface EditorHandle {
  selector: string;
  /** 当前代码 */
  getValue(): string;
  /** 设置代码（保留撤销历史） */
  setValue(value: string): void;
  /** 在光标处插入文本 */
  insert(text: string): void;
  /** 聚焦 */
  focus(): void;
  /** 只读切换 */
  setReadOnly(readOnly: boolean): void;
  /** 主题切换 */
  setTheme(theme: 'light' | 'dark'): void;
  /** 销毁（从注册表移除并释放 DOM/监听） */
  destroy(): void;
}

interface EditorEntry {
  view: EditorView;
  handle: EditorHandle;
  options: EditorInitOptions;
  readOnlyCompartment: Compartment;
  themeCompartment: Compartment;
  changeListeners: Set<(code: string) => void>;
}

const registry = new Map<string, EditorEntry>();

function resolveElement(target: string | HTMLElement): HTMLElement {
  if (typeof target !== 'string') return target;
  const el = document.querySelector<HTMLElement>(target);
  if (!el) throw new Error(`EDITOR_ELEMENT_NOT_FOUND: ${target}`);
  return el;
}

function themeExtension(theme: 'light' | 'dark'): Extension {
  return theme === 'dark' ? oneDark : [];
}

function readOnlyExtension(readOnly: boolean): Extension {
  return readOnly ? [EditorState.readOnly.of(true), EditorView.editable.of(false)] : [];
}

function buildExtensions(entry: Omit<EditorEntry, 'view' | 'handle'>, options: EditorInitOptions): Extension[] {
  const extensions: Extension[] = [
    python(),
    autocompletion({ override: [localCompletionSource, globalCompletion], activateOnTyping: true }),
    closeBrackets(),
    bracketMatching(),
    indentOnInput(),
    foldGutter(),
    history(),
    drawSelection(),
    dropCursor(),
    rectangularSelection(),
    highlightActiveLine(),
    highlightActiveLineGutter(),
    highlightSelectionMatches(),
    syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
    keymap.of([
      ...completionKeymap,
      ...closeBracketsKeymap,
      ...defaultKeymap,
      ...searchKeymap,
      ...historyKeymap,
      ...foldKeymap,
      indentWithTab,
    ]),
    EditorState.tabSize.of(options.tabSize ?? 4),
    EditorView.theme({
      '&': { fontSize: '13px', height: options.height ?? '320px' },
      '.cm-scroller': { overflow: 'auto', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' },
      '.cm-content': { caretColor: 'currentColor' },
    }),
    entry.readOnlyCompartment.of(readOnlyExtension(options.readOnly ?? false)),
    entry.themeCompartment.of(themeExtension(options.theme ?? 'light')),
    EditorView.updateListener.of((update) => {
      if (!update.docChanged) return;
      const code = update.state.doc.toString();
      entry.options.onChange?.(code);
      for (const listener of entry.changeListeners) listener(code);
    }),
  ];

  if (options.lineNumbers !== false) extensions.push(lineNumbers());
  if (options.placeholder) extensions.push(placeholderExt(options.placeholder));
  if (options.onRun) {
    const onRun = options.onRun;
    extensions.push(
      keymap.of([
        {
          key: 'Mod-Enter',
          preventDefault: true,
          run: (view) => {
            onRun(view.state.doc.toString());
            return true;
          },
        },
      ]),
    );
  }
  return extensions;
}

/**
 * 在 `target`（CSS 选择器或元素）上初始化一个 Python 编辑器。
 * 同一个 selector 重复调用会先销毁旧实例（幂等）。
 */
export function initEditor(target: string | HTMLElement, options: EditorInitOptions = {}): EditorHandle {
  const selector = typeof target === 'string' ? target : `#${target.id || 'editor'}`;
  if (registry.has(selector)) destroyEditor(selector);

  const parent = resolveElement(target);
  parent.innerHTML = '';

  const readOnlyCompartment = new Compartment();
  const themeCompartment = new Compartment();
  const entry: Omit<EditorEntry, 'view' | 'handle'> = {
    options,
    readOnlyCompartment,
    themeCompartment,
    changeListeners: new Set(),
  };

  const state = EditorState.create({
    doc: options.value ?? '',
    extensions: buildExtensions(entry, options),
  });
  const view = new EditorView({ state, parent });

  const fullEntry: EditorEntry = {
    view,
    options,
    readOnlyCompartment,
    themeCompartment,
    changeListeners: entry.changeListeners,
    handle: {
      selector,
      getValue: () => view.state.doc.toString(),
      setValue: (value: string) => {
        view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } });
      },
      insert: (text: string) => {
        view.dispatch(view.state.replaceSelection(text));
      },
      focus: () => view.focus(),
      setReadOnly: (readOnly: boolean) => {
        view.dispatch({ effects: readOnlyCompartment.reconfigure(readOnlyExtension(readOnly)) });
      },
      setTheme: (theme: 'light' | 'dark') => {
        view.dispatch({ effects: themeCompartment.reconfigure(themeExtension(theme)) });
      },
      destroy: () => destroyEditor(selector),
    },
  };
  registry.set(selector, fullEntry);
  return fullEntry.handle;
}

/** 销毁指定编辑器；不存在时静默返回。 */
export function destroyEditor(target: string | HTMLElement): void {
  const selector = typeof target === 'string' ? target : `#${target.id || 'editor'}`;
  const entry = registry.get(selector);
  if (!entry) return;
  entry.changeListeners.clear();
  entry.view.destroy();
  registry.delete(selector);
}

/** 销毁所有编辑器（页面卸载时调用）。 */
export function destroyAllEditors(): void {
  for (const selector of Array.from(registry.keys())) destroyEditor(selector);
}

/** 当前已创建的编辑器 selector 列表。 */
export function listEditors(): string[] {
  return Array.from(registry.keys());
}

/** 读取代码；编辑器不存在时抛错（避免静默返回空串掩盖 bug）。 */
export function getEditorValue(target: string | HTMLElement): string {
  return requireEditor(target).view.state.doc.toString();
}

/** 设置代码（保留撤销历史）。 */
export function setEditorValue(target: string | HTMLElement, value: string): void {
  const { view } = requireEditor(target);
  view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } });
}

/** 在光标处插入文本（例如插入代码片段）。 */
export function insertEditorText(target: string | HTMLElement, text: string): void {
  const { view } = requireEditor(target);
  view.dispatch(view.state.replaceSelection(text));
}

/** 聚焦编辑器（移动端可用于主动拉起键盘）。 */
export function focusEditor(target: string | HTMLElement): void {
  requireEditor(target).view.focus();
}

/** 只读切换。 */
export function setEditorReadOnly(target: string | HTMLElement, readOnly: boolean): void {
  requireEditor(target).handle.setReadOnly(readOnly);
}

/** 主题切换。 */
export function setEditorTheme(target: string | HTMLElement, theme: 'light' | 'dark'): void {
  requireEditor(target).handle.setTheme(theme);
}

/** 撤销 / 重做。 */
export function undoEditor(target: string | HTMLElement): boolean {
  return undo(requireEditor(target).view);
}

export function redoEditor(target: string | HTMLElement): boolean {
  return redo(requireEditor(target).view);
}

/** 监听内容变化；返回的 `remove()` 用于取消监听。 */
export function onEditorChange(
  target: string | HTMLElement,
  listener: (code: string) => void,
): { remove: () => void } {
  const entry = requireEditor(target);
  entry.changeListeners.add(listener);
  return { remove: () => entry.changeListeners.delete(listener) };
}

function requireEditor(target: string | HTMLElement): EditorEntry {
  const selector = typeof target === 'string' ? target : `#${target.id || 'editor'}`;
  const entry = registry.get(selector);
  if (!entry) throw new Error(`EDITOR_NOT_FOUND: ${selector}`);
  return entry;
}
