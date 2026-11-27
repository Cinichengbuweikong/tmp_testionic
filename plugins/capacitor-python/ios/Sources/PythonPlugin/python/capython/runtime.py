"""capython.runtime（iOS 版）—— 原生 CPython 的会话运行时。

和 Android 版（`android/src/main/python/capython/runtime.py`）逻辑一致，区别只在桥：
  - Android 通过 Chaquopy 的 `jclass('com.testionic.python.PythonBridge')` 调 Java；
  - iOS 通过 C 注册的 `capython_bridge` 模块（emit / readline），**不用 ctypes**
    （iOS 的纯 stdlib 方案里没有 ctypes，它是 C 扩展）。

职责：
  1. `sys.stdout/stderr` → 按线程所属会话实时转发；
  2. `sys.stdin.readline` → 阻塞等待（C 侧 NSCondition，最长 30s，超时按 EOF）；
  3. 执行用户代码：`exec` + 最后一个表达式 `eval`（和 REPL 一致），异常转成 JSON。
"""

import ast
import io
import json
import sys
import threading
import traceback

import capython_bridge as _bridge

_local = threading.local()
_sessions = {}
_lock = threading.Lock()


def _session_id():
    return getattr(_local, "session_id", None)


class _OutputStream(io.TextIOBase):
    def __init__(self, name):
        self._name = name

    def write(self, text):
        sid = _session_id()
        if sid and text:
            try:
                _bridge.emit(sid, self._name, str(text))
            except Exception:
                pass
        return len(text)

    def flush(self):
        pass

    def isatty(self):
        return False


class _InputStream(io.TextIOBase):
    def readline(self, size=-1):
        sid = _session_id()
        if not sid:
            return ""
        data = _bridge.readline(sid)
        if data is None:
            return ""
        return data

    def read(self, size=-1):
        return self.readline(size)

    def isatty(self):
        return False


def install():
    if not isinstance(sys.stdout, _OutputStream):
        sys.stdout = _OutputStream("stdout")
    if not isinstance(sys.stderr, _OutputStream):
        sys.stderr = _OutputStream("stderr")
    if not isinstance(sys.stdin, _InputStream):
        sys.stdin = _InputStream()


def _globals_for(session_id):
    with _lock:
        g = _sessions.get(session_id)
        if g is None:
            g = {"__name__": "__main__", "__doc__": None}
            _sessions[session_id] = g
        return g


def reset_session(session_id):
    with _lock:
        _sessions.pop(session_id, None)


def close():
    for sid in list(_sessions):
        reset_session(sid)


def _describe(exc):
    return {
        "type": type(exc).__name__,
        "message": str(exc),
        "traceback": traceback.format_exc(),
    }


def run(session_id, source):
    """执行代码，返回 JSON 字符串：`{ok, result?, type?, message?, traceback?}`。"""
    install()
    _local.session_id = session_id
    g = _globals_for(session_id)

    try:
        tree = ast.parse(source, "<python>", "exec")
    except SyntaxError as exc:
        info = _describe(exc)
        info["ok"] = False
        return json.dumps(info, ensure_ascii=False)

    last_expr = None
    if tree.body and isinstance(tree.body[-1], ast.Expr):
        try:
            last_expr = compile(ast.Expression(tree.body[-1].value), "<python>", "eval")
            tree.body.pop()
        except SyntaxError:
            last_expr = None

    try:
        exec(compile(tree, "<python>", "exec"), g)
        if last_expr is not None:
            value = eval(last_expr, g)
            return json.dumps({"ok": True, "result": _repr(value)}, ensure_ascii=False)
        return json.dumps({"ok": True, "result": None}, ensure_ascii=False)
    except SystemExit:
        return json.dumps({"ok": True, "result": None, "exited": True}, ensure_ascii=False)
    except BaseException as exc:  # noqa: BLE001
        info = _describe(exc)
        info["ok"] = False
        return json.dumps(info, ensure_ascii=False)


def _repr(value):
    if value is None:
        return None
    try:
        return repr(value)
    except Exception:
        try:
            return str(value)
        except Exception:
            return "<unrepresentable>"


def python_version():
    return "%d.%d.%d" % sys.version_info[:3]


def add_site_dir(path):
    import site

    if path not in sys.path:
        sys.path.append(path)
    site.addsitedir(path)
