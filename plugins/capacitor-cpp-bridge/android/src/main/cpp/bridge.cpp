/*
 * bridge.cpp —— Android 侧的 JNI 薄桥。
 *
 * 这一层只做「Java 方法 → C 函数」的转发，**不要**在这里写业务逻辑：
 *  - 业务逻辑放 shared/core（两端共用）；
 *  - 这层负责：字符串转换、异常转换、句柄（jlong）与指针的互转。
 *
 * 函数名规则：`Java_<包名下划线化>_<类名>_<方法名>`。
 * 这里对应 com.testionic.cppbridge.CppBridgePlugin 的 native 方法。
 */
#include <jni.h>

#include "cppcore.h"

#include <cstdint>

namespace {

/** 把 C++ 侧的错误转成 Java 异常（C++ 异常不能跨 JNI 边界，必须转成 Java 异常）。 */
void throwIllegalArgument(JNIEnv* env, const char* message) {
    jclass clazz = env->FindClass("java/lang/IllegalArgumentException");
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message);
        env->DeleteLocalRef(clazz);
    }
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_testionic_cppbridge_CppBridgePlugin_nativeBuildInfo(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(cppcore_build_info());
}

JNIEXPORT jint JNICALL
Java_com_testionic_cppbridge_CppBridgePlugin_nativeAdd(JNIEnv* /*env*/, jobject /*thiz*/, jint a, jint b) {
    return cppcore_add(static_cast<int32_t>(a), static_cast<int32_t>(b));
}

JNIEXPORT jint JNICALL
Java_com_testionic_cppbridge_CppBridgePlugin_nativeCAdd(JNIEnv* /*env*/, jobject /*thiz*/, jint a, jint b) {
    return cppcore_c_add(static_cast<int32_t>(a), static_cast<int32_t>(b));
}

JNIEXPORT jint JNICALL
Java_com_testionic_cppbridge_CppBridgePlugin_nativeCStrlen(JNIEnv* env, jobject /*thiz*/, jstring text) {
    if (text == nullptr) {
        return -1;
    }
    const char* chars = env->GetStringUTFChars(text, nullptr);
    if (chars == nullptr) {
        return -1; // OOM
    }
    const jint length = cppcore_c_strlen(chars);
    env->ReleaseStringUTFChars(text, chars);
    return length;
}

JNIEXPORT jlong JNICALL
Java_com_testionic_cppbridge_CppBridgePlugin_nativeBufferAlloc(JNIEnv* /*env*/, jobject /*thiz*/, jlong size) {
    return reinterpret_cast<jlong>(cppcore_buffer_alloc(static_cast<int64_t>(size)));
}

JNIEXPORT jint JNICALL
Java_com_testionic_cppbridge_CppBridgePlugin_nativeBufferFill(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jlong size,
    jint value
) {
    void* ptr = reinterpret_cast<void*>(handle);
    if (ptr == nullptr) {
        throwIllegalArgument(env, "buffer handle is null");
        return -1;
    }
    const int32_t rc = cppcore_buffer_fill(ptr, static_cast<int64_t>(size), static_cast<int32_t>(value));
    if (rc != 0) {
        throwIllegalArgument(env, "cppcore_buffer_fill failed (size must be positive multiple of 4)");
    }
    return rc;
}

JNIEXPORT jlong JNICALL
Java_com_testionic_cppbridge_CppBridgePlugin_nativeBufferSum(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jlong size
) {
    const void* ptr = reinterpret_cast<const void*>(handle);
    if (ptr == nullptr) {
        throwIllegalArgument(env, "buffer handle is null");
        return 0;
    }
    int64_t sum = 0;
    const int32_t rc = cppcore_buffer_sum(ptr, static_cast<int64_t>(size), &sum);
    if (rc != 0) {
        throwIllegalArgument(env, "cppcore_buffer_sum failed (size must be positive multiple of 4)");
        return 0;
    }
    return static_cast<jlong>(sum);
}

JNIEXPORT void JNICALL
Java_com_testionic_cppbridge_CppBridgePlugin_nativeBufferFree(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    cppcore_buffer_free(reinterpret_cast<void*>(handle));
}

} // extern "C"
