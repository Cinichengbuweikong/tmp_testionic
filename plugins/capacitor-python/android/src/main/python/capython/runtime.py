"""capython.runtime —— Android 原生 Python 运行时（Chaquopy）。

职责：
  1. 把 `sys.stdout` / `sys.stderr` 换成代理对象，按"当前执行线程所属的会话"实时转发到 Java/JS；
  2. 把 `sys.stdin` 换成阻塞式 `readline`（数据由 JS 通过 `writeStdin` 推入 Java 侧的队列）；
  3. 执行用户代码：`exec` + 捕获最后一个表达式的值（和 Python REPL 一致），异常转成结构化结果；
  4. 每个会话一份独立的 globals（互不污染）。

为什么用线程级的 `threading.local`：
  `sys.stdout` 是解释器全局的，但一次只有一个线程在执行某个会话的代码。
  代理对象在 `write()` 时读 `_local.session_id` 就知道该往哪个会话发，
  这样多个会话共享一个解释器也能正确分流（副作用是：如果用户代码自己开线程 print，
  那个线程没有 session_id，输出会被丢弃 —— 属于可接受且要在文档里写明的限制）。
"""

import ast
import io
import json
import sys
import threading
import traceback

from java import jclass

Bridge = jclass("com.testionic.python.PythonBridge")

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
                Bridge.emit(sid, self._name, str(text))
            except Exception:
                # 转发失败不能让用户代码崩掉
                pass
        return len(text)

    def flush(self):
        pass

    def isatty(self):
        return False


class _InputStream(io.TextIOBase):
    """`input()` / `sys.stdin.readline()` 会阻塞在 Bridge.readLine 上。

    Bridge.readLine 内部是带超时的队列等待（Java 侧），返回 None 表示"没有更多输入"，
    此时 readline 返回空串 → `input()` 抛 EOFError（与真实 shell 的 ^D 行为一致）。
    """

    def readline(self, size=-1):
        sid = _session_id()
        if not sid:
            return ""
        line = Bridge.readLine(sid)
        return line if line is not None else ""

    def read(self, size=-1):
        return self.readline(size)

    def isatty(self):
        return False


def install():
    """安装全局代理。幂等。"""
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
    """执行一段代码；返回 dict：{ok, result?, type?, message?, traceback?}。"""
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
        # 把最后一行表达式单独 eval，这样 `1 + 1` 能拿到 "2"（和 REPL 一致）
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
    except BaseException as exc:  # noqa: BLE001 - 用户代码什么都可能抛
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
    """把目录加入 sys.path（运行时安装的纯 Python wheel 用）。"""
    import site

    if path not in sys.path:
        sys.path.append(path)
    site.addsitedir(path)


def interrupt(thread_id):
    """尝试向指定线程注入 SystemExit（PyThreadState_SetAsyncExc）。

    只能在下一条 bytecode 边界生效；阻塞在 C 调用里的代码打断不了。
    没有 ctypes 时返回 False，由调用方决定是否降级。
    """
    try:
        import ctypes

        ctypes.pythonapi.PyThreadState_SetAsyncExc(
            ctypes.c_ulong(thread_id), ctypes.py_object(SystemExit)
        )
        return True
    except Exception:
        return False
