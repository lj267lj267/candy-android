// SPDX-License-Identifier: MIT
package com.candy.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.candy.vpn.MainActivity
import com.candy.vpn.R
import com.candy.vpn.core.CandyNative
import com.candy.vpn.data.LogRepository
import com.candy.vpn.data.Profile
import com.candy.vpn.data.ProfileStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the candy client instance and the VpnService tunnel.
 *
 * The interesting part is the ordering problem: a VpnService has to be told its
 * address *before* `establish()` returns a descriptor, but the candy core only
 * learns the address from the server after the signalling connection is up.
 * The core therefore pulls: `Tun::Impl::up()` calls back into
 * [establishTun] here, and the descriptor is handed straight back down.
 */
class CandyVpnService : VpnService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Blocking native calls (stop/join) must never run on the main thread. */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "candy-service")
    }

    @Volatile
    private var profile: Profile? = null

    /** Descriptor handed to the core, or -1 when no tunnel is up. */
    private val tunnelFd = AtomicInteger(-1)

    @Volatile
    private var stopping = false

    @Volatile
    private var restartCount = 0

    private var restartScheduled = false

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }

            else -> startFromIntent(intent)
        }
        return START_STICKY
    }

    private fun startFromIntent(intent: Intent?) {
        ProfileStore.init(this)
        CandyNative.load()

        val requestedId = intent?.getStringExtra(EXTRA_PROFILE_ID)
        val target = if (requestedId != null) {
            ProfileStore.profiles.value.firstOrNull { it.id == requestedId }
        } else {
            ProfileStore.activeProfile()
        }

        if (target == null) {
            LogRepository.error("没有可用的配置，无法连接")
            VpnStatus.update(VpnState.Failed("", "没有可用的配置"))
            stopVpn()
            return
        }

        if (VpnStatus.current.isActive && profile?.id == target.id) {
            // Already running this profile: just make sure we are in the foreground.
            promoteToForeground(notification(getString(R.string.notification_connecting)))
            return
        }

        if (VpnStatus.current.isActive) {
            // Switching profiles: tear the old instance down first, then start over.
            worker.execute {
                profile?.let { CandyNative.stop(it.instanceId) }
                mainHandler.post { beginSession(target) }
            }
            return
        }

        beginSession(target)
    }

    private fun beginSession(target: Profile) {
        ProfileStore.setActive(target.id)

        val prepared = target.ensureVmac()
        if (prepared.vmac != target.vmac) {
            ProfileStore.upsert(prepared)
        }

        profile = prepared
        stopping = false
        restartCount = 0
        tunnelFd.set(-1)

        CandyNative.setLogLevel(ProfileStore.settings.value.logLevel)
        acquireWakeLockIfNeeded()

        promoteToForeground(notification(getString(R.string.notification_connecting)))
        VpnStatus.update(VpnState.Connecting(prepared.title))
        LogRepository.info("正在连接：${prepared.title} → ${prepared.websocket}")

        val started = CandyNative.start(prepared.instanceId, prepared.toCandyJson())
        if (!started) {
            LogRepository.error("内核启动失败，可能是同名实例仍在运行")
            VpnStatus.update(VpnState.Failed(prepared.title, "内核启动失败"))
            stopVpn()
        }
    }

    // ------------------------------------------------------------------
    // Called from the native core (any thread).
    // ------------------------------------------------------------------

    /**
     * Builds the VpnService tunnel for the address the server assigned and hands
     * the raw descriptor to the core, which takes ownership of it.
     */
    @Synchronized
    fun establishTun(cidr: String, prefix: Int, mtu: Int): Int {
        val active = profile ?: return -1
        if (stopping) return -1

        val slash = cidr.indexOf('/')
        val address = if (slash > 0) cidr.substring(0, slash) else cidr
        val effectivePrefix = if (slash > 0) cidr.substring(slash + 1).toIntOrNull() ?: prefix else prefix

        releaseStaleTunnel()

        val builder = Builder()
        builder.setSession(active.title.ifBlank { "Candy" })
        builder.setMtu(mtu.coerceIn(576, 1500))

        // addAddress() returns the Builder for chaining and *throws* when the
        // address is rejected, so success has to be inferred from an exception.
        try {
            builder.addAddress(address, effectivePrefix)
        } catch (error: Exception) {
            LogRepository.error("VpnService 拒绝地址 $address/$effectivePrefix：${error.message}")
            return -1
        }

        // Only the candy subnet and explicitly declared routes go through the
        // tunnel. candy is a virtual LAN, not an internet gateway, so a default
        // route would blackhole everything.
        val routes = active.routes
        var installed = 0
        for (route in routes) {
            if (route.prefix == 0) {
                LogRepository.warn("忽略默认路由 ${route}：candy 不提供互联网出口")
                continue
            }
            // Same contract as addAddress(): returns the Builder, throws on a
            // bad route, so only the exception distinguishes success.
            if (runCatching { builder.addRoute(route.address, route.prefix) }.isSuccess) {
                installed++
            } else {
                LogRepository.warn("添加路由失败：$route")
            }
        }

        active.dnsServers.forEach { server ->
            runCatching { builder.addDnsServer(server) }
        }

        if (active.perAppEnabled) {
            if (active.allowedApps.isNotEmpty()) {
                active.allowedApps.forEach { pkg ->
                    runCatching { builder.addAllowedApplication(pkg) }
                        .onFailure { LogRepository.warn("无法加入白名单：$pkg") }
                }
            } else {
                active.disallowedApps.forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                        .onFailure { LogRepository.warn("无法排除应用：$pkg") }
                }
            }
        }

        val descriptor = try {
            builder.establish()
        } catch (error: Exception) {
            LogRepository.error("建立隧道异常：${error.message}")
            null
        }

        if (descriptor == null) {
            LogRepository.error("建立隧道失败：VpnService.establish() 返回空（可能未授权 VPN）")
            VpnStatus.update(VpnState.Failed(active.title, "未授权或建立隧道失败"))
            return -1
        }

        val rawFd = descriptor.detachFd()
        tunnelFd.set(rawFd)

        // The core re-asks for the same address next time and the server honours it.
        ProfileStore.rememberAddress(active.id, "$address/$effectivePrefix")
        profile = ProfileStore.profiles.value.firstOrNull { it.id == active.id } ?: active

        restartCount = 0
        restartScheduled = false

        val routeTexts = routes.map { it.toString() }
        VpnStatus.update(
            VpnState.Connected(
                title = active.title,
                address = address,
                prefix = effectivePrefix,
                routes = routeTexts,
                since = System.currentTimeMillis(),
            )
        )
        LogRepository.info("隧道已建立：$address/$effectivePrefix，额外路由 $installed 条，fd=$rawFd")
        mainHandler.post { updateNotification(getString(R.string.notification_connected)) }
        return rawFd
    }

    /** The core closed the tunnel; it will re-establish it on the next retry. */
    fun onTunClosed(fd: Int) {
        tunnelFd.compareAndSet(fd, -1)
        if (stopping) return

        val title = profile?.title.orEmpty()
        LogRepository.info("隧道已关闭，等待内核重连")
        VpnStatus.update(VpnState.Connecting(title))
        mainHandler.post { updateNotification(getString(R.string.notification_connecting)) }
    }

    /**
     * A route pushed by the server. Android only accepts routes while a tunnel
     * is being built, so remember it and re-create the tunnel once the burst of
     * route messages has settled. Because learned routes are persisted, the next
     * connection installs them up front and this stops firing.
     */
    fun onRouteAdded(dst: String, prefix: Int, nexthop: String): Boolean {
        val active = profile ?: return false
        if (stopping) return false

        val cidr = "$dst/$prefix"
        val isNew = ProfileStore.rememberRoute(active.id, cidr)
        if (!isNew) return false

        profile = ProfileStore.profiles.value.firstOrNull { it.id == active.id } ?: active
        LogRepository.info("服务端下发路由 $cidr via $nexthop，稍后重建隧道以生效")

        if (restartCount < MAX_ROUTE_RESTARTS && !stopping) {
            restartCount++
            synchronized(this) {
                if (restartScheduled) {
                    return true
                }
                restartScheduled = true
            }
            mainHandler.postDelayed({ scheduleRouteRestart() }, ROUTE_RESTART_DELAY_MS)
        }
        return true
    }

    private fun scheduleRouteRestart() {
        synchronized(this) { restartScheduled = false }
        val active = profile ?: return
        if (stopping) return

        worker.execute {
            LogRepository.info("重建隧道以应用新路由")
            CandyNative.stop(active.instanceId)
            if (stopping) return@execute
            val started = CandyNative.start(active.instanceId, active.toCandyJson())
            if (!started) {
                LogRepository.warn("重建隧道失败，内核可能已在重试")
            }
        }
    }

    // ------------------------------------------------------------------
    // Tunnel teardown
    // ------------------------------------------------------------------

    override fun onRevoke() {
        LogRepository.warn("VPN 权限被系统或用户收回，正在断开")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (!stopping) stopVpn()
        if (current === this) {
            current = null
        }
        super.onDestroy()
    }

    /**
     * Stops the core, drops the tunnel and leaves the foreground. Safe to call
     * from any thread and more than once.
     */
    fun stopVpn() {
        if (stopping) return
        stopping = true
        restartCount = 0
        restartScheduled = false

        mainHandler.post { updateNotification(getString(R.string.notification_disconnected)) }

        worker.execute {
            val active = profile
            if (active != null && CandyNative.isLoaded) {
                CandyNative.stop(active.instanceId)
            }
            mainHandler.post {
                releaseStaleTunnel()
                releaseWakeLock()
                VpnStatus.update(VpnState.Idle)
                if (current === this) current = null
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    /** Closes a descriptor the core did not get around to closing itself. */
    private fun releaseStaleTunnel() {
        val fd = tunnelFd.getAndSet(-1)
        if (fd >= 0) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                .onFailure { LogRepository.warn("关闭残留隧道描述符失败：${it.message}") }
        }
    }

    // ------------------------------------------------------------------
    // Notification + wake lock
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = getString(R.string.notification_channel_description)
        manager.createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CandyVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notification_action_disconnect), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun acquireWakeLockIfNeeded() {
        if (!ProfileStore.settings.value.holdWakeLock || wakeLock != null) return
        val manager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "candy:vpn").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.candy.vpn.action.START"
        const val ACTION_STOP = "com.candy.vpn.action.STOP"
        const val EXTRA_PROFILE_ID = "extra_profile_id"

        private const val CHANNEL_ID = "candy_status"
        private const val NOTIFICATION_ID = 0xCA7D

        /** Route messages arrive in bursts; wait for the burst to end. */
        private const val ROUTE_RESTART_DELAY_MS = 3_000L

        /** Guard against a restart loop if the server keeps pushing new routes. */
        private const val MAX_ROUTE_RESTARTS = 2

        @Volatile
        var current: CandyVpnService? = null

        /** Starts (or restarts) the tunnel for [profileId]. */
        fun start(context: Context, profileId: String? = null) {
            val intent = Intent(context, CandyVpnService::class.java)
                .setAction(ACTION_START)
            if (profileId != null) {
                intent.putExtra(EXTRA_PROFILE_ID, profileId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stops the tunnel. Works whether or not the service is still alive. */
        fun stop(context: Context) {
            val service = current
            if (service != null) {
                service.stopVpn()
            } else {
                context.stopService(Intent(context, CandyVpnService::class.java))
            }
        }
    }
}
