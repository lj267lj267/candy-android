// SPDX-License-Identifier: MIT
//
// Bridge between the candy core (C++) and the Kotlin side of the Android app.
//
// The Android port cannot create its own TUN device: only a VpnService is
// allowed to do that, and it must be handed the interface address *before* the
// tunnel is established. The candy core, however, only learns its address from
// the server after the signalling connection is up. We therefore invert the
// control flow: candy's Tun::Impl calls back into Kotlin, which builds and
// establishes the tunnel, and hands the resulting file descriptor back down.
#ifndef CANDY_ANDROID_JVM_BRIDGE_H
#define CANDY_ANDROID_JVM_BRIDGE_H

#include <jni.h>
#include <string>

namespace candy {
namespace android {

// Owned by this translation unit, for use by the other native sources.
void setJavaVM(JavaVM *vm);
JavaVM *getJavaVM();
// Called from JNI_OnUnload so per-thread env caches stop touching a dead JVM.
void forgetJavaVM();

// Binds to com/candy/vpn/core/CandyController. Must be called with a JNIEnv
// that was obtained on a thread that can see the application class loader.
bool initController(JNIEnv *env);
void clearController();
bool controllerReady();

// --- calls into Kotlin -------------------------------------------------------
// All of these may be invoked from any native thread. They return the failure
// value (-1 / false) when the Kotlin side is not available, so that a
// VpnService shutdown degrades into a clean native shutdown instead of a crash.

// Builds a VpnService tunnel for `cidr` (/prefix) and transfers ownership of the
// raw file descriptor to the caller. Returns -1 on failure.
int establishTun(const std::string &cidr, int prefix, int mtu);

// Closes the descriptor previously handed out by establishTun().
int closeTun(int fd);

// Reports a route pushed by the server. Returns true when the Kotlin side wants
// the tunnel to be re-created so the route can take effect.
bool onRouteAdded(const std::string &dst, int prefix, const std::string &nexthop);

// Poco log level (see Poco::Message::Priority) plus the formatted message.
void onLog(int level, const std::string &message);

} // namespace android
} // namespace candy

#endif // CANDY_ANDROID_JVM_BRIDGE_H
