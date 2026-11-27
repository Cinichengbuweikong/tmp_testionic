#include "PythonShim.h"

#include <Python.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static cp_emit_fn g_emit = NULL;
static cp_readline_fn g_read_line = NULL;
static PyObject *g_runtime_dict = NULL;
static char g_last_error[1024] = {0};

void cp_set_output(cp_emit_fn emit) {
    g_emit = emit;
}

void cp_set_input(cp_readline_fn read_line) {
    g_read_line = read_line;
}

const char *cp_last_error(void) {
    return g_last_error;
}

const char *cp_version(void) {
    static char version[32] = {0};
    if (version[0] == 0) {
        snprintf(version, sizeof(version), "%d.%d.%d", PY_MAJOR_VERSION, PY_MINOR_VERSION, PY_MICRO_VERSION);
    }
    return version;
}

static char *copy_cstring(const char *text) {
    if (text == NULL) {
        return NULL;
    }
    size_t length = strlen(text);
    char *copy = (char *)malloc(length + 1);
    if (copy != NULL) {
        memcpy(copy, text, length + 1);
    }
    return copy;
}

static void json_escape(const char *src, char *dst, size_t capacity) {
    size_t out = 0;
    for (size_t i = 0; src[i] != '\0' && out + 2 < capacity; i++) {
        unsigned char c = (unsigned char)src[i];
        switch (c) {
            case '"': dst[out++] = '\\'; dst[out++] = '"'; break;
            case '\\': dst[out++] = '\\'; dst[out++] = '\\'; break;
            case '\n': dst[out++] = '\\'; dst[out++] = 'n'; break;
            case '\r': dst[out++] = '\\'; dst[out++] = 'r'; break;
            case '\t': dst[out++] = '\\'; dst[out++] = 't'; break;
            default:
                if (c < 0x20) {
                    out += (size_t)snprintf(dst + out, capacity - out, "\\u%04x", c);
                } else {
                    dst[out++] = (char)c;
                }
        }
    }
    dst[out] = '\0';
}

/* ------------------------------------------------------------------ 桥模块
 *
 * 为什么不走 ctypes：iOS 上 ctypes 是 C 扩展模块（lib-dynload 里），而 iOS 要求
 * Frameworks/ 之外不能有可执行代码，纯 stdlib 方案里没有它。所以直接在 C 里注册一个
 * `capython_bridge` 模块给 Python 调用：
 *     capython_bridge.emit(session, stream, text)
 *     capython_bridge.readline(session) -> str | None
 *
 * 调用时 Python 持有 GIL；回调里可能阻塞（等 stdin），所以先释放 GIL。
 */
static PyObject *bridge_emit(PyObject *self, PyObject *args) {
    const char *session = NULL;
    const char *stream = NULL;
    const char *text = NULL;
    if (!PyArg_ParseTuple(args, "sss", &session, &stream, &text)) {
        return NULL;
    }
    if (g_emit != NULL) {
        Py_BEGIN_ALLOW_THREADS
        g_emit(session, stream, text);
        Py_END_ALLOW_THREADS
    }
    Py_RETURN_NONE;
}

static PyObject *bridge_readline(PyObject *self, PyObject *args) {
    const char *session = NULL;
    if (!PyArg_ParseTuple(args, "s", &session)) {
        return NULL;
    }
    char *line = NULL;
    if (g_read_line != NULL) {
        Py_BEGIN_ALLOW_THREADS
        line = g_read_line(session);
        Py_END_ALLOW_THREADS
    }
    if (line == NULL) {
        Py_RETURN_NONE;
    }
    PyObject *result = PyUnicode_FromString(line);
    free(line);
    return result;
}

static PyMethodDef bridge_methods[] = {
    {"emit", bridge_emit, METH_VARARGS, "emit(session, stream, text)"},
    {"readline", bridge_readline, METH_VARARGS, "readline(session) -> str | None"},
    {NULL, NULL, 0, NULL}
};

static struct PyModuleDef bridge_module = {
    PyModuleDef_HEAD_INIT, "capython_bridge", "native bridge for capacitor-python", -1, bridge_methods, NULL, NULL, NULL, NULL
};

static PyObject *create_bridge_module(void) {
    return PyModule_Create(&bridge_module);
}

/* ------------------------------------------------------------------ 初始化 / 执行 */

int cp_init(const char *home, const char *stdlib, const char *runtime_src) {
    if (Py_IsInitialized()) {
        return 0;
    }
    g_last_error[0] = '\0';

    if (home != NULL) {
        setenv("PYTHONHOME", home, 1);
        setenv("PYTHONDONTWRITEBYTECODE", "1", 1);
    }
    if (stdlib != NULL) {
        setenv("PYTHONPATH", stdlib, 1);
    }
    setenv("PYTHONUTF8", "1", 1);

    Py_Initialize();
    if (!Py_IsInitialized()) {
        snprintf(g_last_error, sizeof(g_last_error), "Py_Initialize failed");
        return 1;
    }

    /* 注册 capython_bridge（这样 runtime.py 里 `import capython_bridge` 直接可用） */
    PyObject *bridge = create_bridge_module();
    if (bridge == NULL) {
        snprintf(g_last_error, sizeof(g_last_error), "bridge module creation failed");
        return 2;
    }
    PyDict_SetItemString(PyImport_GetModuleDict(), "capython_bridge", bridge);

    PyObject *module = PyModule_New("capython_runtime");
    if (module == NULL) {
        snprintf(g_last_error, sizeof(g_last_error), "PyModule_New failed");
        return 3;
    }
    g_runtime_dict = PyModule_GetDict(module);
    PyObject *result = PyRun_String(runtime_src, Py_file_input, g_runtime_dict, g_runtime_dict);
    if (result == NULL) {
        PyObject *ptype = NULL, *pvalue = NULL, *ptraceback = NULL;
        PyErr_Fetch(&ptype, &pvalue, &ptraceback);
        PyObject *text = pvalue ? PyObject_Str(pvalue) : NULL;
        const char *message = text ? PyUnicode_AsUTF8(text) : "runtime init failed";
        snprintf(g_last_error, sizeof(g_last_error), "%s", message ? message : "runtime init failed");
        Py_XDECREF(text);
        Py_XDECREF(ptype);
        Py_XDECREF(pvalue);
        Py_XDECREF(ptraceback);
        return 4;
    }
    Py_DECREF(result);
    PyDict_SetItemString(PyImport_GetModuleDict(), "capython_runtime", module);
    return 0;
}

char *cp_run(const char *session, const char *source) {
    if (g_runtime_dict == NULL) {
        return copy_cstring("{\"ok\":false,\"type\":\"RuntimeError\",\"message\":\"python not initialized\"}");
    }
    PyObject *run = PyDict_GetItemString(g_runtime_dict, "run");
    if (run == NULL) {
        return copy_cstring("{\"ok\":false,\"type\":\"RuntimeError\",\"message\":\"runtime.run missing\"}");
    }
    PyObject *result = PyObject_CallFunction(run, "ss", session ? session : "default", source ? source : "");
    if (result == NULL) {
        PyObject *ptype = NULL, *pvalue = NULL, *ptraceback = NULL;
        PyErr_Fetch(&ptype, &pvalue, &ptraceback);
        PyObject *text = pvalue ? PyObject_Str(pvalue) : NULL;
        const char *message = text ? PyUnicode_AsUTF8(text) : "python error";
        char escaped[1024];
        json_escape(message ? message : "python error", escaped, sizeof(escaped));
        char *json = (char *)malloc(strlen(escaped) + 128);
        if (json != NULL) {
            snprintf(json, strlen(escaped) + 128,
                     "{\"ok\":false,\"type\":\"Error\",\"message\":\"%s\",\"traceback\":null}", escaped);
        }
        Py_XDECREF(text);
        Py_XDECREF(ptype);
        Py_XDECREF(pvalue);
        Py_XDECREF(ptraceback);
        return json;
    }

    const char *utf8 = PyUnicode_AsUTF8(result);
    char *json = copy_cstring(utf8 ? utf8 : "{\"ok\":false,\"type\":\"Error\",\"message\":\"no result\"}");
    Py_DECREF(result);
    return json;
}

void cp_shutdown(void) {
    if (Py_IsInitialized()) {
        Py_Finalize();
    }
    g_runtime_dict = NULL;
}
