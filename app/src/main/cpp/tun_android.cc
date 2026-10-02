// SPDX-License-Identifier: MIT
//
// Android implementation of candy::Tun::Impl.
//
// Differences from the Linux implementation:
//
//  * There is no /dev/net/tun we are allowed to open: the file descriptor comes
//    from VpnService.Builder#establish().
//  * Address, prefix, MTU and routes have to be programmed into the VpnService
//    builder *before* the tunnel exists, which is why up() calls back into
//    Kotlin instead of doing the configuration itself.
//  * setSysRtTable() cannot program the kernel routing table: Android owns it.
//    Routes pushed by the server after the tunnel is up are reported to Kotlin,
//    which persists them and (once) re-creates the tunnel so they take effect.
#include <Poco/Platform.h>

#if POCO_OS == POCO_OS_ANDROID

#include "core/net.h"
#include "jvm_bridge.h"
#include "tun/tun.h"
#include "utils/log.h"
#include <Poco/Format.h>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/select.h>
#include <sys/socket.h>
#include <unistd.h>

namespace candy {

namespace {
constexpr int kDefaultMtu = 1400;
}

struct Tun::Impl {
    int setName(const std::string &name) {
        this->name = name;
        return 0;
    }

    int setMTU(int mtu) {
        if (mtu > 0) {
            this->mtu = mtu;
        }
        return 0;
    }

    // Asks the Kotlin VpnService to build and establish the tunnel for the
    // address the server assigned to us, and adopts the returned descriptor.
    int up(IP4 ip, IP4 mask) {
        if (this->tunFd >= 0) {
            candy::logger().warning("tun is already up, ignoring duplicate up()");
            return 0;
        }

        const int prefix = mask.toPrefix();
        const std::string cidr = ip.toString() + "/" + std::to_string(prefix);

        int fd = android::establishTun(cidr, prefix, this->mtu);
        if (fd < 0) {
            candy::logger().fatal(Poco::format("VpnService refused to establish the tunnel for %s", cidr));
            return -1;
        }

        int flags = fcntl(fd, F_GETFL, 0);
        if (flags < 0) {
            candy::logger().error(Poco::format("get tun flags failed: %s", std::string(strerror(errno))));
            android::closeTun(fd);
            ::close(fd);
            return -1;
        }
        if (fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) {
            candy::logger().error(Poco::format("set non-blocking tun failed: %s", std::string(strerror(errno))));
            android::closeTun(fd);
            ::close(fd);
            return -1;
        }

        this->tunFd = fd;
        candy::logger().information(Poco::format("tun up: %s mtu %d fd %d", cidr, this->mtu, fd));
        return 0;
    }

    int down() {
        if (this->tunFd >= 0) {
            // The descriptor was detached from its ParcelFileDescriptor, so
            // native code owns it: closing it here is what tears the VpnService
            // tunnel down. Kotlin is only notified so it can update the UI.
            const int fd = this->tunFd;
            this->tunFd = -1;
            android::closeTun(fd);
            ::close(fd);
        }
        return 0;
    }

    int read(std::string &buffer) {
        buffer.resize(this->mtu > 0 ? this->mtu : kDefaultMtu);

        int n = static_cast<int>(::read(this->tunFd, buffer.data(), buffer.size()));
        if (n >= 0) {
            buffer.resize(static_cast<std::size_t>(n));
            return n;
        }

        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            // Sleep in select() so the tun thread stays cheap and can still be
            // interrupted by the 1s timeout when the client shuts down.
            struct timeval timeout = {.tv_sec = 1, .tv_usec = 0};
            fd_set set;
            FD_ZERO(&set);
            FD_SET(this->tunFd, &set);
            select(this->tunFd + 1, &set, nullptr, nullptr, &timeout);
            return 0;
        }

        if (errno == EINTR || errno == EBADF) {
            return 0;
        }

        candy::logger().warning(Poco::format("tun read failed: %s", std::string(strerror(errno))));
        return -1;
    }

    int write(const std::string &buffer) {
        int n = static_cast<int>(::write(this->tunFd, buffer.data(), buffer.size()));
        if (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
            candy::logger().warning(Poco::format("tun write failed: %s", std::string(strerror(errno))));
        }
        return n;
    }

    // Android does not let us touch the routing table. Report the route upwards
    // and let the Kotlin side decide whether the tunnel has to be re-created.
    int setSysRtTable(IP4 dst, IP4 mask, IP4 nexthop) {
        const std::string dstStr = dst.toString();
        const std::string nexthopStr = nexthop.toString();
        const int prefix = mask.toPrefix();

        const bool needsRestart = android::onRouteAdded(dstStr, prefix, nexthopStr);
        candy::logger().information(
            Poco::format("route: %s/%d via %s%s", dstStr, prefix, nexthopStr, needsRestart ? " (re-creating tun)" : ""));
        return 0;
    }

private:
    std::string name;
    int mtu = kDefaultMtu;
    int tunFd = -1;
};

Tun::Tun() {
    this->impl = std::make_unique<Impl>();
}

Tun::~Tun() {}

int Tun::setName(const std::string &name) {
    return this->impl->setName(name);
}

int Tun::setMTU(int mtu) {
    return this->impl->setMTU(mtu);
}

int Tun::up() {
    return this->impl->up(this->ip, this->mask);
}

int Tun::down() {
    return this->impl->down();
}

int Tun::read(std::string &buffer) {
    return this->impl->read(buffer);
}

int Tun::write(const std::string &buffer) {
    return this->impl->write(buffer);
}

int Tun::setSysRtTable(IP4 dst, IP4 mask, IP4 nexthop) {
    return this->impl->setSysRtTable(dst, mask, nexthop);
}

} // namespace candy

#endif // POCO_OS_ANDROID
