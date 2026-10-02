> 把 [lanthora/candy](https://github.com/lanthora/candy) 的官方 C++ 客户端内核搬进 Android 应用：
> 内核原样交叉编译进 APK，隧道交给 Android 的 `VpnService`，协议与服务端 100% 一致。

---

# Candy for Android

## 这是什么

[candy](https://github.com/lanthora/candy) 是一个用 C++ 实现的 P2P 虚拟组网工具（SD-WAN）：
客户端通过 WebSocket 信令服务器交换各自的公网地址，再用 STUN + 打洞建立点对点连接，
把流量以 AES-256-GCM 加密后在节点之间直接传输。

上游只提供 Linux / macOS / Windows 客户端。本项目把它的**核心库原样**编译到 Android 上，
并补上 Android 缺失的那一层：TUN 设备适配。

| | |
|---|---|
| 内核 | candy 6.1.11（`candy/include` + `candy/src` 原样引入，见 `app/src/main/cpp/third_party/candy`） |
| 隧道 | Android `VpnService`（无需 root） |
| 界面 | Jetpack Compose，配置管理 / 连接 / 实时日志 / 设置 |
| ABI | 默认 `arm64-v8a`（可选 `x86_64` 供模拟器） |
| 依赖 | 自编译静态 Poco 1.13.3 + OpenSSL 3.0.15 |

## 移植的关键点

Android 不允许普通应用自己开 `/dev/net/tun`，只能由 `VpnService` 建隧道；
而 `VpnService.Builder.establish()` 要求**先知道接口地址**，candy 内核却是连上信令服务器后
才从服务端拿到地址的。这个先后顺序是移植中唯一真正棘手的地方。

解决办法是把控制流反过来：内核的 `Tun::Impl::up()` 回调进 Kotlin，由 Kotlin 用服务端刚分配的
地址建立隧道，再把文件描述符交回内核。

```
candy 内核 (C++)                          Android (Kotlin)
────────────────────────────────────────────────────────────────
WebSocketClient 收到服务端分配的地址
        │
        ▼
Tun::handleTunAddr → setAddress + up()
        │
        └──► jvm_bridge::establishTun(cidr, prefix, mtu)
                        │
                        ▼
              CandyController.establishTun()
                        │
                        ▼
              CandyVpnService.establishTun()   ← Builder + establish()
                        │
              ┌─────────┘
              ▼  交出 raw fd（detachFd，所有权归 native）
        Tun::Impl  持有该 fd，非阻塞 + select 读
```

另外两处必须改写的地方：

* **路由**。Android 只允许在建隧道时声明路由，之后不能改。服务端下发的 SD-WAN 路由会被记到配置里，
  稍后重建一次隧道使其生效；因为路由会持久化，下一次连接时一键装好，不会反复重建。
* **TUN 读写**。`linux.cc` 里那套 `open("/dev/net/tun")` + `ioctl(TUNSETIFF/SIOCSIFADDR)` 全部不需要，
  地址、掩码、MTU、路由都由 `VpnService.Builder` 配置。对应的实现是 `app/src/main/cpp/tun_android.cc`。

`candy/src/tun/{linux,macos,windows,unknown}.cc` 在同步上游时会被自动移除。

## 目录结构

```
candy-android/
├── app/
│   └── src/main/
│       ├── cpp/                     native 层
│       │   ├── CMakeLists.txt       把内核 + JNI 桥编成 libcandycore.so
│       │   ├── tun_android.cc       Android 版 Tun::Impl（本次移植的核心）
│       │   ├── jvm_bridge.{h,cc}    C++ → Kotlin 的调用与线程附着
│       │   ├── candy_jni.cc         JNI 入口 + Poco 日志转发
│       │   └── third_party/candy/   上游内核快照（勿手改）
│       ├── java/com/candy/vpn/
│       │   ├── core/                CandyNative（JNI 声明）、CandyController（native 回调）
│       │   ├── data/                Profile / ProfileStore / LogRepository
│       │   ├── vpn/                 CandyVpnService、VpnState
│       │   ├── ui/                  Compose 界面
│       │   ├── CandyApp.kt          Application
│       │   └── BootReceiver.kt      开机自启
│       └── res/
├── scripts/                         构建脚本（见下）
├── deps-src/                        下载的第三方源码（不提交）
├── native-deps/<abi>/{include,lib}  预编译的 Poco/OpenSSL（不提交）
└── README.md
```

## 构建

### 前置条件

* JDK 17
* Android SDK：`platforms;android-35`、`build-tools;35.0.0`、`cmake;3.22.1`
  （CMake 也可以从别处装——AGP 优先读 `local.properties` 的 `cmake.dir`）
* Android NDK `27.2.12479018`
* 一个 POSIX shell（Windows 上用 MSYS2，OpenSSL 的 `Configure` 是 Perl 脚本，需要 `perl` 和 `make`）

### 在 Windows 上构建

上游工具链都是给 Unix 准备的。能自动化的部分已经写进脚本，剩下只需要准备好工具：

1. **Perl 必须是原生 Windows Perl**（Strawberry Perl）。PortableGit / MSYS2 自带的 `perl.exe`
   依赖未随附的 `msys-perl5_42.dll`，起不来。
2. **OpenSSL 的 `Configurations/` 需要打两处补丁**。`scripts/build-openssl.sh` 会在 Configure 之前
   自动用 `scripts/openssl-overlays/<版本>/` 里的文件覆盖 `deps-src/openssl`，不需要手工改源码：
   * `15-android.conf`：把 `which()` 返回的 8.3 短路径（`WORKBU~2`）用
     `Win32::GetLongPathName` 还原成长路径、再统一成 `/`，然后才和 `$ANDROID_NDK_ROOT` 比较。
     没有这一步 Configure 会报 `no NDK aarch64-linux-android-gcc on $PATH`。
   * `unix-checker.pm`：由 `die` 改为 `warn`，否则 MSWin32 Perl 会被直接拒绝。

   两处补丁在 POSIX 主机上都是空操作，所以这份 overlay 可以全平台共用。
3. **`PERL` 要传裸命令名**（`perl`，不能是完整路径）。Configure 生成的 `configdata.pm` 会经
   `cmd.exe` 再调一次 Perl，`/c/...` 形式的路径在那一步解析不了；脚本会自动把 Perl 所在目录加进
   `PATH` 并改用裸名。
4. **`JAVA_HOME` 必须导出**。Gradle 的启动脚本是子进程，只看得见导出的变量；`scripts/env.sh`
   会连同 `JAVA_HOME/bin` 一起导出。
5. **OpenSSL 只构建库**。`build-openssl.sh` 用的是 `make build_libs` 而不是默认目标：默认目标会连带
   编译 `openssl` 命令行程序，而 `no-ui-console` 会让 `configuration.h` 定义
   `OPENSSL_NO_UI_CONSOLE`，`apps/dgst.c`、`apps/enc.c` 被排除后 `apps/openssl.c` 就找不到
   `dgst_main` / `enc_main` 了。APK 不需要那个可执行文件。
6. **Poco 找 OpenSSL 要显式给库路径**。NDK 的 `CMAKE_FIND_ROOT_PATH` 会把 `find_library()` 限制在
   sysroot 内，Windows 主机的默认库后缀里也没有 `.a`，所以 `build-poco.sh` 直接传
   `OPENSSL_SSL_LIBRARY` / `OPENSSL_CRYPTO_LIBRARY` 的绝对路径。

把本机的工具路径写进 `scripts/candy.env`（从 `scripts/candy.env.example` 复制）即可：

```bash
PERL=C:/path/to/strawberry/perl/bin/perl.exe
MAKE=C:/path/to/ndk/27.2.12479018/prebuilt/windows-x86_64/bin/make.exe
CMAKE_DIR=C:/path/to/cmake          # 目录里要有 bin/cmake.exe 和 bin/ninja.exe
JAVA_HOME=C:/path/to/jdk-17
GRADLE_HOME=C:/path/to/gradle-8.11.1
```

### 一次性：编译 native 依赖

```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_ROOT=$ANDROID_HOME/ndk/27.2.12479018

./scripts/build-native-deps.sh arm64-v8a        # 想同时支持模拟器就再加 x86_64
```

这一步会下载 OpenSSL / Poco 源码到 `deps-src/`，逐个 ABI 交叉编译，
产物落到 `native-deps/<abi>/{include,lib}`。耗时主要在这里（首次约十几分钟）。

### 出 APK

```bash
export JAVA_HOME=/path/to/jdk-17
./scripts/build-apk.sh            # app/build/outputs/apk/debug/app-debug.apk
./scripts/build-apk.sh release    # release 版（用 debug key 签名，正式发布请换成自己的签名）
```

也可以直接用 Android Studio 打开工程根目录，Gradle 同步后点运行——`local.properties` 里的
`sdk.dir` 由 Android Studio 自己维护。

### 各脚本职责

| 脚本 | 作用 |
|---|---|
| `scripts/env.sh` | 路径与工具链变量，可被 `scripts/candy.env` 覆盖 |
| `scripts/fetch-sources.sh` | 下载 OpenSSL / Poco 源码 |
| `scripts/build-openssl.sh <abi>` | 交叉编译静态 OpenSSL |
| `scripts/build-poco.sh <abi>` | 交叉编译静态 Poco（只留 Foundation/XML/JSON/Net/NetSSL/Crypto/Util） |
| `scripts/build-native-deps.sh [abi...]` | 上面三步的组合 |
| `scripts/sync-upstream.sh [ref]` | 重新同步 candy 内核 |
| `scripts/build-apk.sh [debug\|release]` | 调 Gradle 出包 |
| `scripts/openssl-overlays/<版本>/` | 给 `Configurations/` 打的宿主补丁（见上文「在 Windows 上构建」） |

## 使用

1. 装好 APK，首次点「连接」时系统会弹出 VPN 授权，同意后即可。
2. 到「配置」页新建一份配置：

   | 字段 | 说明 |
   |---|---|
   | 名称 | 本地区分用 |
   | 信令地址 | `wss://server.example.com/ws`，与桌面端 `-w` 一致 |
   | 密码 | 与桌面端 `-p` 相同，用于鉴权与 P2P 密钥派生 |
   | 虚拟地址 | 留空则由服务端动态分配（推荐）；也可填 `192.168.202.10/24` 固定 |
   | STUN | `stun://stun.example.com`，不填只能局域网直连 |
   | 额外路由 | 需要访问的对端网段，逗号分隔 |

3. 回「连接」页点连接。连上后状态卡会显示分配到的虚拟地址与已装路由。
4. 「日志」页是内核的完整输出，与桌面端命令行打印的内容一致，排查问题时可直接复制。

## 与桌面端的差异

| | 桌面端 | 本应用 |
|---|---|---|
| 网卡地址配置 | 自行 `ioctl` 配置 | `VpnService.Builder` 配置 |
| 路由 | 写系统路由表 | 建隧道时声明，Android 托管 |
| `--route`（中继） | 可开启 | 字段保留，手机建议保持 0 |
| 出口流量 | 取决于系统路由 | **不接管默认路由**，仅 candy 网段与声明网段走隧道 |

## 已知限制

* **candy 是虚拟局域网，不是翻墙工具。** 它没有互联网出口/NAT，本应用也不会添加默认路由。
  需要访问互联网请配合服务端的 SD-WAN 路由规则使用。
* 只构建 `arm64-v8a`。要跑模拟器需要先 `./scripts/build-native-deps.sh x86_64`，
  再用 `-Pcandy.abis=arm64-v8a,x86_64` 构建。
* 服务端下发的路由需要重建一次隧道才能生效（约 1 秒），这是 `VpnService` 的 API 限制。
* release 包默认用 debug 密钥签名，仅供自用；上架前请替换 `app/build.gradle.kts` 里的签名配置。
* 上游 `candy/src/utils/time.cc` 依赖 `unistd.h`，在 Android 上可用；若后续上游引入桌面专属头文件，
  同步时需在 `CMakeLists.txt` 中排除对应源文件。

## 同步上游内核

```bash
./scripts/sync-upstream.sh            # 或 ./scripts/sync-upstream.sh v6.1.11
```

脚本会重新拉取并覆盖 `app/src/main/cpp/third_party/candy`，同时删掉四个不适用于 Android 的
TUN 实现。若上游版本号变了，记得同步更新 `app/build.gradle.kts` 里的 `candyCoreVersion`。

## 许可

内核部分遵循上游 [MIT License](https://github.com/lanthora/candy/blob/master/LICENSE)。
Android 外壳同样以 MIT 发布。
