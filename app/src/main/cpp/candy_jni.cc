// SPDX-License-Identifier: MIT
//
// JNI entry points for the candy core.
//
// Exposed to Kotlin as com.candy.vpn.core.CandyNative.
#include "jvm_bridge.h"

#include <Poco/Channel.h>
#include <Poco/JSON/Object.h>
#include <Poco/JSON/Parser.h>
#include <Poco/Logger.h>
#include <Poco/Message.h>
#include <android/log.h>
#include <jni.h>
#include <map>
#include <mutex>
#include <string>
#include <thread>

#include "candy/client.h"
#include "candy/common.h"

namespace {

constexpr const char *kTag = "candy-native";

std::mutex g_instancesMutex;
std::map<std::string, std::thread> g_instances;

// Forwards candy's Poco log stream into Kotlin so it can be rendered in the app
// and written to logcat at the same time.
class KotlinLogChannel : public Poco::Channel {
public:
    void log(const Poco::Message &msg) override {
        const int priority = static_cast<int>(msg.getPriority());
        const std::string &text = msg.getText();
        switch (msg.getPriority()) {
        case Poco::Message::PRIO_FATAL:
        case Poco::Message::PRIO_CRITICAL:
            __android_log_print(ANDROID_LOG_FATAL, kTag, "%s", text.c_str());
            break;
        case Poco::Message::PRIO_ERROR:
            __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", text.c_str());
            break;
        case Poco::Message::PRIO_WARNING:
            __android_log_print(ANDROID_LOG_WARN, kTag, "%s", text.c_str());
            break;
        case Poco::Message::PRIO_INFORMATION:
            __android_log_print(ANDROID_LOG_INFO, kTag, "%s", text.c_str());
            break;
        default:
            __android_log_print(ANDROID_LOG_DEBUG, kTag, "%s", text.c_str());
            break;
        }
        candy::android::onLog(priority, text);
    }
};

void installLogChannel() {
    static std::once_flag once;
    std::call_once(once, [] {
        Poco::Logger::root().setChannel(new KotlinLogChannel);
    });
}

int priorityFromInt(jint level) {
    if (level < static_cast<jint>(Poco::Message::PRIO_FATAL)) {
        return static_cast<int>(Poco::Message::PRIO_FATAL);
    }
    if (level > static_cast<jint>(Poco::Message::PRIO_TRACE)) {
        return static_cast<int>(Poco::Message::PRIO_TRACE);
    }
    return static_cast<int>(level);
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    candy::android::setJavaVM(vm);
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    candy::android::forgetJavaVM();
}

JNIEXPORT void JNICALL Java_com_candy_vpn_core_CandyNative_nativeInit(JNIEnv *env, jobject thiz) {
    (void)thiz;
    installLogChannel();
    candy::android::initController(env);
}

JNIEXPORT jstring JNICALL Java_com_candy_vpn_core_CandyNative_nativeVersion(JNIEnv *env, jobject thiz) {
    (void)thiz;
    const std::string version = candy::version();
    return env->NewStringUTF(version.c_str());
}

JNIEXPORT void JNICALL Java_com_candy_vpn_core_CandyNative_nativeSetLogLevel(JNIEnv *env, jobject thiz, jint level) {
    (void)env;
    (void)thiz;
    Poco::Logger::root().setLevel(priorityFromInt(level));
}

JNIEXPORT jboolean JNICALL Java_com_candy_vpn_core_CandyNative_nativeStart(JNIEnv *env, jobject thiz, jstring jId,
                                                                          jstring jConfig) {
    (void)thiz;
    if (jId == nullptr || jConfig == nullptr) {
        return JNI_FALSE;
    }

    const char *idChars = env->GetStringUTFChars(jId, nullptr);
    const char *configChars = env->GetStringUTFChars(jConfig, nullptr);
    if (idChars == nullptr || configChars == nullptr) {
        if (idChars != nullptr) {
            env->ReleaseStringUTFChars(jId, idChars);
        }
        return JNI_FALSE;
    }

    std::string id(idChars);
    std::string configText(configChars);
    env->ReleaseStringUTFChars(jId, idChars);
    env->ReleaseStringUTFChars(jConfig, configChars);

    installLogChannel();

    bool started = false;
    try {
        Poco::JSON::Parser parser;
        Poco::Dynamic::Var parsed = parser.parse(configText);
        Poco::JSON::Object::Ptr object = parsed.extract<Poco::JSON::Object::Ptr>();
        if (object.isNull()) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "config is not a JSON object");
            return JNI_FALSE;
        }
        Poco::JSON::Object config = *object;

        std::lock_guard<std::mutex> lock(g_instancesMutex);
        auto it = g_instances.find(id);
        if (it != g_instances.end()) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "instance already running: %s", id.c_str());
            return JNI_FALSE;
        }
        g_instances.emplace(id, std::thread([id, config]() { candy::client::run(id, config); }));
        started = true;
    } catch (const std::exception &e) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "nativeStart failed: %s", e.what());
        started = false;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "nativeStart failed: unknown error");
        started = false;
    }

    return started ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_candy_vpn_core_CandyNative_nativeStop(JNIEnv *env, jobject thiz, jstring jId) {
    (void)thiz;
    if (jId == nullptr) {
        return JNI_FALSE;
    }

    const char *idChars = env->GetStringUTFChars(jId, nullptr);
    if (idChars == nullptr) {
        return JNI_FALSE;
    }
    std::string id(idChars);
    env->ReleaseStringUTFChars(jId, idChars);

    try {
        candy::client::shutdown(id);

        std::thread worker;
        {
            std::lock_guard<std::mutex> lock(g_instancesMutex);
            auto it = g_instances.find(id);
            if (it == g_instances.end()) {
                return JNI_FALSE;
            }
            worker = std::move(it->second);
            g_instances.erase(it);
        }
        if (worker.joinable()) {
            worker.join();
        }
    } catch (const std::exception &e) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "nativeStop failed: %s", e.what());
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_candy_vpn_core_CandyNative_nativeIsRunning(JNIEnv *env, jobject thiz, jstring jId) {
    (void)thiz;
    if (jId == nullptr) {
        return JNI_FALSE;
    }
    const char *idChars = env->GetStringUTFChars(jId, nullptr);
    if (idChars == nullptr) {
        return JNI_FALSE;
    }
    std::string id(idChars);
    env->ReleaseStringUTFChars(jId, idChars);

    std::lock_guard<std::mutex> lock(g_instancesMutex);
    return g_instances.find(id) != g_instances.end() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
