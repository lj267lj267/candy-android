// SPDX-License-Identifier: MIT
#include "jvm_bridge.h"

#include <android/log.h>
#include <cstdarg>
#include <mutex>

namespace candy {
namespace android {

namespace {

constexpr const char *kControllerClass = "com/candy/vpn/core/CandyController";
constexpr const char *kTag = "candy-native";

JavaVM *g_vm = nullptr;

std::mutex g_mutex;
jclass g_controller = nullptr;
jmethodID g_establishTun = nullptr;
jmethodID g_closeTun = nullptr;
jmethodID g_onRouteAdded = nullptr;
jmethodID g_onLog = nullptr;

// Attaches the calling native thread to the JVM on demand. Threads that already
// have a JNIEnv (i.e. threads that entered from Java) are left untouched, so we
// never detach a thread the JVM is still tracking.
struct ThreadEnv {
    JNIEnv *env = nullptr;
    bool owned = false;

    ~ThreadEnv() {
        if (owned && g_vm != nullptr) {
            g_vm->DetachCurrentThread();
        }
    }
};

ThreadEnv &threadEnv() {
    static thread_local ThreadEnv cached;
    if (cached.env != nullptr) {
        return cached;
    }

    JavaVM *vm = g_vm;
    if (vm == nullptr) {
        return cached;
    }

    JNIEnv *env = nullptr;
    jint status = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_OK) {
        cached.env = env;
        cached.owned = false;
        return cached;
    }
    if (vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        cached.env = env;
        cached.owned = true;
    }
    return cached;
}

void logToLogcat(const char *format, ...) {
    va_list args;
    va_start(args, format);
    __android_log_vprint(ANDROID_LOG_WARN, kTag, format, args);
    va_end(args);
}

} // namespace

void setJavaVM(JavaVM *vm) {
    g_vm = vm;
}

JavaVM *getJavaVM() {
    return g_vm;
}

void forgetJavaVM() {
    // Thread-local caches hold a stale JNIEnv after unload; clearing the vm
    // pointer makes them no-ops instead of crashes.
    clearController();
    g_vm = nullptr;
}

bool initController(JNIEnv *env) {
    if (env == nullptr) {
        return false;
    }

    jclass local = env->FindClass(kControllerClass);
    if (local == nullptr) {
        env->ExceptionClear();
        logToLogcat("CandyController not found: %s", kControllerClass);
        return false;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_controller != nullptr) {
        env->DeleteLocalRef(local);
        return true;
    }

    g_controller = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (g_controller == nullptr) {
        logToLogcat("failed to create a global ref for CandyController");
        return false;
    }

    g_establishTun = env->GetStaticMethodID(g_controller, "establishTun", "(Ljava/lang/String;II)I");
    g_closeTun = env->GetStaticMethodID(g_controller, "closeTun", "(I)V");
    g_onRouteAdded = env->GetStaticMethodID(g_controller, "onRouteAdded", "(Ljava/lang/String;ILjava/lang/String;)Z");
    g_onLog = env->GetStaticMethodID(g_controller, "onNativeLog", "(ILjava/lang/String;)V");

    if (g_establishTun == nullptr || g_closeTun == nullptr || g_onRouteAdded == nullptr || g_onLog == nullptr) {
        env->ExceptionClear();
        logToLogcat("CandyController is missing one or more expected methods");
        env->DeleteGlobalRef(g_controller);
        g_controller = nullptr;
        return false;
    }
    return true;
}

void clearController() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_controller != nullptr) {
        ThreadEnv &cached = threadEnv();
        if (cached.env != nullptr) {
            cached.env->DeleteGlobalRef(g_controller);
        }
        g_controller = nullptr;
    }
    g_establishTun = nullptr;
    g_closeTun = nullptr;
    g_onRouteAdded = nullptr;
    g_onLog = nullptr;
}

bool controllerReady() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_controller != nullptr;
}

int establishTun(const std::string &cidr, int prefix, int mtu) {
    ThreadEnv &cached = threadEnv();
    JNIEnv *env = cached.env;
    if (env == nullptr || !controllerReady()) {
        return -1;
    }

    jstring jCidr = env->NewStringUTF(cidr.c_str());
    if (jCidr == nullptr) {
        env->ExceptionClear();
        return -1;
    }

    jint fd = env->CallStaticIntMethod(g_controller, g_establishTun, jCidr, static_cast<jint>(prefix), static_cast<jint>(mtu));
    env->DeleteLocalRef(jCidr);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return -1;
    }
    return static_cast<int>(fd);
}

int closeTun(int fd) {
    ThreadEnv &cached = threadEnv();
    JNIEnv *env = cached.env;
    if (env == nullptr || !controllerReady()) {
        return -1;
    }
    env->CallStaticVoidMethod(g_controller, g_closeTun, static_cast<jint>(fd));
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return -1;
    }
    return 0;
}

bool onRouteAdded(const std::string &dst, int prefix, const std::string &nexthop) {
    ThreadEnv &cached = threadEnv();
    JNIEnv *env = cached.env;
    if (env == nullptr || !controllerReady()) {
        return false;
    }

    jstring jDst = env->NewStringUTF(dst.c_str());
    jstring jNextHop = env->NewStringUTF(nexthop.c_str());
    if (jDst == nullptr || jNextHop == nullptr) {
        env->ExceptionClear();
        if (jDst != nullptr) {
            env->DeleteLocalRef(jDst);
        }
        return false;
    }

    jboolean needsRestart =
        env->CallStaticBooleanMethod(g_controller, g_onRouteAdded, jDst, static_cast<jint>(prefix), jNextHop);
    env->DeleteLocalRef(jDst);
    env->DeleteLocalRef(jNextHop);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }
    return needsRestart == JNI_TRUE;
}

void onLog(int level, const std::string &message) {
    ThreadEnv &cached = threadEnv();
    JNIEnv *env = cached.env;
    if (env == nullptr || !controllerReady()) {
        return;
    }

    jstring jMessage = env->NewStringUTF(message.c_str());
    if (jMessage == nullptr) {
        env->ExceptionClear();
        return;
    }
    env->CallStaticVoidMethod(g_controller, g_onLog, static_cast<jint>(level), jMessage);
    env->DeleteLocalRef(jMessage);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

} // namespace android
} // namespace candy
