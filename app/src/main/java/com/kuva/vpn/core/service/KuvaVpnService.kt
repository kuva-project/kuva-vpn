package com.kuva.vpn.core.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Notification
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.kuva.vpn.core.config.XrayConfigBuilder
import com.kuva.vpn.core.engine.CoreLogStore
import com.kuva.vpn.core.engine.XrayCoreManager
import com.kuva.vpn.core.engine.SwiftCoreManager
import com.kuva.vpn.core.stats.TrafficStatsCollector
import com.kuva.vpn.core.tunnel.Tun2Socks
import com.kuva.vpn.data.settings.RoutingMode
import com.kuva.vpn.data.settings.SettingsRepository
import com.kuva.vpn.domain.model.ConnectionState
import com.kuva.vpn.domain.model.ExitIpInfo
import com.kuva.vpn.domain.model.Profile
import com.kuva.vpn.domain.repository.ConnectionRepository
import com.kuva.vpn.domain.repository.ProfileRepository
import com.kuva.vpn.ui.notifications.VpnNotifier
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import javax.inject.Inject

/**
 * The real VPN service: establishes the TUN interface via VpnService.Builder,
 * starts hev-socks5-tunnel (TUN -> SOCKS) and the Xray core (SOCKS -> proxy),
 * and feeds live traffic counters to the UI.
 */
@AndroidEntryPoint
class KuvaVpnService : VpnService() {

    @Inject lateinit var xray: XrayCoreManager
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var profileRepo: ProfileRepository
    @Inject lateinit var connectionRepo: ConnectionRepository
    @Inject lateinit var notifier: VpnNotifier
    @Inject lateinit var statsCollector: TrafficStatsCollector
    @Inject lateinit var configTester: com.kuva.vpn.core.diagnostics.ConfigTester
    @Inject lateinit var swift: SwiftCoreManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tunInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private val startLock = Any()
    private var isStarting = false

    // ---- auto-reconnect state ----
    private var lastProfile: Profile? = null
    private var userDisconnected = false
    private var connectedNow = false

    /** Guards against two tunnel-ended watchdogs running at once. */
    @Volatile private var tunnelWatchdogActive = false
    private var pendingReconnect: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var switching = false

    override fun onCreate() {
        super.onCreate()
        xray.initCoreEnv(applicationContext)
        registerNetworkWatcher()
        // Hot-switch watcher: while the tunnel is up, selecting another config
        // in any list must tear the old tunnel down and connect to the new one
        // automatically — no manual disconnect needed.
        serviceScope.launch {
            var armed = false
            profileRepo.observeSelected().collect { p ->
                if (!armed) { armed = true; return@collect }
                if (p != null && connectedNow && !switching && p.id != lastProfile?.id) {
                    switchTo(p)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL: promote to a real foreground service IMMEDIATELY. Without
        // this call Android kills the app ~5 seconds after startForegroundService()
        // with "did not then call startForeground()" — the 'connecting then crash' bug.
        promoteToForeground()
        if (intent?.action == ACTION_DISCONNECT) {
            disconnect()
            return START_NOT_STICKY
        }
        // null intent => system restart (Always-on VPN): rebuild with last state
        startVpn()
        return START_STICKY
    }

    override fun onRevoke() {
        // VPN consent revoked by user or another VPN took over
        disconnect()
    }

    override fun onDestroy() {
        unregisterNetworkWatcher()
        pendingReconnect?.cancel()
        serviceScope.cancel()
        closeTun()
        notifier.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ start

    /** Promote to a real foreground service with a silent status notification. */
    private fun promoteToForeground() {
        try {
            val n: Notification = notifier.buildForegroundNotification()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    com.kuva.vpn.ui.notifications.VpnNotifier.FOREGROUND_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(com.kuva.vpn.ui.notifications.VpnNotifier.FOREGROUND_ID, n)
            }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "startForeground failed", t)
        }
    }

    private fun startVpn() {
        synchronized(startLock) {
            if (isStarting) return
            isStarting = true
        }
        serviceScope.launch {
            // A repeated start intent while the tunnel is already up must never
            // spawn a second core; switching to a newly selected config is the
            // watcher's job.
            if (connectedNow || coreController != null || switching) {
                // early exit happens BEFORE the try/finally that owns
                // isStarting — reset it here or every future start is skipped.
                synchronized(startLock) { isStarting = false }
                return@launch
            }
            try {
                val profile = resolveProfile() ?: run {
                    notifier.notifyError(getString(com.kuva.vpn.R.string.err_no_selection))
                    connectionRepo.setState(ConnectionState.Error("no selection"))
                    stopSelf()
                    return@launch
                }
                connectProfile(profile)
            } catch (t: Throwable) {
                // Throwable on purpose: UnsatisfiedLinkError and other Errors from
                // native libs must not kill the process — surface them as Error state.
                android.util.Log.e(TAG, "startVpn failed", t)
                connectionRepo.setState(ConnectionState.Error(t.message ?: "error"))
                notifier.notifyError(t.message ?: "error")
                stopSelf()
            } finally {
                synchronized(startLock) { isStarting = false }
            }
        }
    }

    private suspend fun resolveProfile(): Profile? {
        val selected = profileRepo.getSelected()
        if (selected != null) return selected
        // Kuva Swift needs no profile at all: with no selection (and no
        // auto-select hit) it still connects using a synthetic placeholder
        // profile that only carries the display name for the UI.
        if (settings.current.engineMode == "swift" && swift.isAvailable(applicationContext)) {
            return Profile(
                scope = Profile.Scope.FREE,
                name = "Kuva Swift",
                protocol = Profile.Protocol.SOCKS,
                address = "127.0.0.1",
                port = settings.current.socksPort,
            )
        }
        return when (settings.current.autoSelectMode) {
            "lowest_ping" -> profileRepo.bestByPing()
            "best_performance" -> profileRepo.bestByPerformance()
            else -> null
        }
    }

    private suspend fun connectProfile(profile: Profile) {
        lastProfile = profile
        userDisconnected = false
        val s = settings.current
        // Kuva Swift: the bundled MASQUE core replaces Xray entirely; the
        // TUN -> SOCKS pipeline below is identical for both engines.
        if (s.engineMode == "swift") {
            connectSwift(profile)
            return
        }
        notifier.notifyConnecting(profile.name)
        connectionRepo.setState(ConnectionState.Connecting(profile.name, profile.id))

        // 1. full Xray config
        // wipe previous session's core logs: the shared snapshot must reflect
        // exactly the LAST connection attempt (e.g. one failed Google signup).
        runCatching {
            java.io.File(applicationContext.filesDir, "logs").listFiles()
                ?.filter { it.name.startsWith("xray_") }
                ?.forEach { it.delete() }
        }
        // Pin the server's IP into dns.hosts BEFORE the tunnel comes up
        // (plain system resolver, 4s cap). Without the pin, the core would
        // resolve the proxy server's domain through its own DNS module whose
        // upstream now travels through the proxy — a recursion (the proxy dial
        // waits for the very resolution it is blocking). On reconnects while a
        // session is still up, the lookup rides the old tunnel, which works.
        val serverPin: Map<String, String> =
            if (profile.address.any { it.isLetter() }) {
                runCatching {
                    kotlinx.coroutines.withTimeout(4_000) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            java.net.InetAddress.getByName(profile.address).hostAddress
                        }
                    }
                }.getOrNull()?.let { mapOf(profile.address to it) } ?: emptyMap()
            } else emptyMap()
        val config = XrayConfigBuilder.build(
            profile,
            XrayConfigBuilder.Options(
                socksPort = s.socksPort,
                httpPort = s.socksPort + 1,
                dnsServers = s.effectiveDnsServers(),
                bypassLan = s.bypassLan,
                bypassIran = s.bypassIran,
                logDir = java.io.File(applicationContext.filesDir, "logs")
                    .apply { mkdirs() }.absolutePath,
                dnsHostPins = serverPin,
            )
        )

        // 2. establish TUN interface
        val tun = buildTunInterface(profile)
        if (tun == null) {
            connectionRepo.setState(ConnectionState.Error("establish() failed"))
            notifier.notifyError("VPN interface failed")
            stopSelf()
            return
        }
        tunInterface = tun

        // 3. TUN -> SOCKS data path
        Tun2Socks.start(
            context = applicationContext,
            vpnInterface = tun,
            mtu = s.mtu,
            ipv4 = VPN_IPV4,
            ipv6 = if (s.ipv6Enabled) VPN_IPV6 else null,
            socksPort = s.socksPort,
        )
        // If the native tunnel did not come up there is NO data path at all:
        // the UI would show a dead "Connected" with zero traffic. Fail loudly.
        delay(400)
        if (!Tun2Socks.isRunning()) {
            connectionRepo.setState(ConnectionState.Error("tunnel engine failed"))
            notifier.notifyError("tunnel engine failed")
            closeTun()
            stopSelf()
            return
        }

        // 4. Xray core (SOCKS -> proxy)
        val handler = object : CoreCallbackHandler {
            override fun startup(): Long {
                android.util.Log.i(TAG, "xray core started")
                return 0
            }
            override fun shutdown(): Long {
                android.util.Log.i(TAG, "xray core stopped")
                return 0
            }
            override fun onEmitStatus(p0: Long, p1: String?): Long {
                val msg = p1
                if (!msg.isNullOrEmpty()) {
                    android.util.Log.w(TAG, "xray: $msg")
                    CoreLogStore.append(applicationContext, msg)
                }
                return 0
            }
        }
        val controller = xray.newController(handler)
        try {
            controller.startLoop(config, tun.fd)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "core start failed", t)
            Tun2Socks.stop()
            closeTun()
            connectionRepo.setState(ConnectionState.Error(t.message ?: "core start failed"))
            notifier.notifyError(t.message ?: "core start failed")
            stopSelf()
            return
        }
        coreController = controller

        // 5. live stats + UI state.
        // start() cancels the previous sampler; it must run BEFORE the stamp so
        // the fresh connectedSinceEpochMs is never wiped.
        statsCollector.start()
        connectionRepo.setActiveProfile(profile)
        connectionRepo.updateStats {
            it.copy(connectedSinceEpochMs = System.currentTimeMillis())
        }
        connectionRepo.setState(ConnectionState.Connected(profile.name, profile.id))
        notifier.notifyConnected(profile.name)
        connectedNow = true
    }

    /**
     * Kuva Swift data path: TUN -> hev -> local SOCKS5 served by the bundled
     * MASQUE core process (no Xray). The core runs under the app's uid, which
     * the VPN builder already excludes from the TUN, so its outbound sockets
     * go straight to the internet without looping.
     */
    private suspend fun connectSwift(profile: Profile) {
        val s = settings.current
        notifier.notifyConnecting(profile.name)
        // no generic "Connecting…" hop: the scan state (with progress) is
        // shown from the very first moment of a Swift connection
        connectionRepo.setState(ConnectionState.SwiftConnecting(profile.name, 0))

        if (!swift.isAvailable(applicationContext)) {
            connectionRepo.setState(ConnectionState.Error("swift core unavailable on this ABI"))
            notifier.notifyError("swift core unavailable")
            stopSelf()
            return
        }

        val tun = buildTunInterface(profile)
        if (tun == null) {
            connectionRepo.setState(ConnectionState.Error("establish() failed"))
            notifier.notifyError("VPN interface failed")
            stopSelf()
            return
        }
        tunInterface = tun

        Tun2Socks.start(
            context = applicationContext,
            vpnInterface = tun,
            mtu = s.mtu,
            ipv4 = VPN_IPV4,
            ipv6 = if (s.ipv6Enabled) VPN_IPV6 else null,
            socksPort = s.socksPort,
        )
        delay(400)
        if (!Tun2Socks.isRunning()) {
            connectionRepo.setState(ConnectionState.Error("tunnel engine failed"))
            notifier.notifyError("tunnel engine failed")
            swift.stop()
            closeTun()
            stopSelf()
            return
        }

        if (!swift.start(
                applicationContext,
                bindPort = s.socksPort,
                h3 = s.swiftH3,
                scan = s.swiftScan,
                psiphon = s.swiftPsiphon,
                psiphonRegion = s.swiftPsiphonCountry,
            )
        ) {
            connectionRepo.setState(ConnectionState.Error("swift core failed to start"))
            notifier.notifyError("swift core failed to start")
            Tun2Socks.stop()
            closeTun()
            stopSelf()
            return
        }

        // scan-phase feedback: swap the generic "connecting" for the scan
        // state with live progress so the user sees movement, not a hang
        connectionRepo.setState(ConnectionState.SwiftConnecting(profile.name, 0))
        var lastRealPct = 0
        swift.onProgress = { pct ->
            lastRealPct = pct
            connectionRepo.setState(ConnectionState.SwiftConnecting(profile.name, pct))
        }
        // Stage labels ride with the same real events that drive the bar;
        // the UI resolves the key via stringResource (fa + en).
        swift.onStage = { _, stageKey ->
            connectionRepo.setSwiftStage(stageKey)
        }
        // Psiphon mode: the core prints "psiphon through the tunnel exit: ..."
        // only when psiphon is actually carrying traffic (~20-40 s after the
        // tunnel itself validated). Update the exit-IP card from that line so
        // the user never sees a stale WARP/Iran flag while psiphon connects.
        swift.onPsiphonExit = { ip, cc ->
            connectionRepo.setExitIp(
                ExitIpInfo(ip = ip, countryCode = cc, country = cc, city = null, isp = "Psiphon")
            )
        }

        // first start includes endpoint discovery; give it a generous window
        if (!swift.awaitSocksReady(s.socksPort, 60_000)) {
            swift.onProgress = null
            swift.onStage = null
            connectionRepo.setState(ConnectionState.Error("swift core did not come up"))
            notifier.notifyError("swift core did not come up")
            swift.stop()
            Tun2Socks.stop()
            closeTun()
            stopSelf()
            return
        }
        // The SOCKS port opens within a second or two, but the MASQUE path
        // needs a few more seconds before traffic actually flows. Hold the
        // "searching for route" state for a fixed ~10 s window while the bar
        // fills to 100%, then flip to Connected — no external probe target
        // involved (a probe can itself be blocked, which is worse than none).
        // Wait for the core to confirm real end-to-end data flow ("tunnel
        // validated" in the swift log) instead of a fixed 10 s timer. The
        // SOCKS port opens right after validation, so this normally resolves
        // instantly; the 20 s fallback only covers log-format changes, which
        // degrades to the old behaviour — never a false Connected, and no
        // new failure path.
        val holdStart = System.currentTimeMillis()
        // Real traffic gate, both transports: the core prints
        // "masque exit: ... cloudflare, warp on" (its exit-IP check) only
        // once the tunnel actually carries data — in h2 ~0.3-1 s after
        // "tunnel validated", in h3 a few seconds. Green must mean traffic.
        // Psiphon reverse mode: the MASQUE tunnel is dialled THROUGH
        // psiphon, so validation cannot happen before "psiphon is ready".
        // Small psiphon regions (FI, BE, ...) regularly need over 30 s for
        // that; a 30 s window timed out and flipped the UI to Connected
        // with no traffic behind it. Give psiphon mode 90 s; other modes
        // keep the original 30 s fallback.
        val holdMs = if (s.swiftPsiphon) 90_000L else 30_000L
        while (true) {
            if (!swift.isRunning()) {
                swift.onProgress = null
            swift.onStage = null
                connectionRepo.setState(ConnectionState.Error("swift core did not come up"))
                notifier.notifyError("swift core did not come up")
                swift.stop()
                Tun2Socks.stop()
                closeTun()
                stopSelf()
                return
            }
            // both gates from the core's own log — validated + warp on
            if (swift.tunnelValidated && swift.warpReady) break
            val elapsed = System.currentTimeMillis() - holdStart
            if (elapsed >= holdMs) break
            // Event-driven progress (patch 122): re-emit the last REAL pct
            // from the core's log — the bar never moves backwards and never
            // fakes tunnel stages with a time formula.
            val pct = lastRealPct.coerceAtLeast(35)
            connectionRepo.setState(ConnectionState.SwiftConnecting(profile.name, pct))
            try {
                Thread.sleep(400)
            } catch (_: InterruptedException) {
                return
            }
        }
        if (s.swiftPsiphon && !(swift.tunnelValidated && swift.warpReady)) {
            // gate timed out in psiphon mode without a validated tunnel:
            // never claim Connected with no traffic behind it - surface an
            // honest error instead (the user can retry or pick another
            // region; small psiphon regions are often just slow/cold).
            swift.onProgress = null
            swift.onStage = null
            connectionRepo.setState(ConnectionState.Error("psiphon did not become ready in time"))
            notifier.notifyError("psiphon did not become ready in time")
            swift.stop()
            Tun2Socks.stop()
            closeTun()
            stopSelf()
            return
        }
        connectionRepo.setState(ConnectionState.SwiftConnecting(profile.name, 100))
        swift.onProgress = null
        swift.onStage = null
        swift.onExited = {
            serviceScope.launch {
                if (connectedNow && !userDisconnected) {
                    android.util.Log.w(TAG, "swift core died — scheduling reconnect")
                    connectedNow = false
                    Tun2Socks.stop()
                    closeTun()
                    scheduleReconnect()
                }
            }
        }
        // The core survives tunnel drops (h2 keepalive timeout, connection
        // aborted) and reconnects on its own, but its PROCESS keeps running —
        // so onExited never fires and the UI would sit on a dead "Connected"
        // with zero traffic (user-visible 2026-11-13: Telegram died, app still
        // said Connected). Drop to the honest SwiftConnecting state until the
        // core's own reconnect re-validates the tunnel, then go back to
        // Connected. If the core gives up entirely, fall through to the full
        // scheduleReconnect path like a core death.
        swift.onTunnelEnded = {
            serviceScope.launch {
                if (!connectedNow || userDisconnected || switching) return@launch
                if (tunnelWatchdogActive) return@launch
                tunnelWatchdogActive = true
                try {
                    android.util.Log.w(TAG, "swift tunnel ended — waiting for the core's own reconnect")
                    connectionRepo.setState(
                        ConnectionState.SwiftConnecting(lastProfile?.name ?: "", 35)
                    )
                    val deadline = System.currentTimeMillis() + 180_000L
                    while (System.currentTimeMillis() < deadline) {
                        if (!connectedNow || userDisconnected || switching) return@launch
                        if (!swift.isRunning()) break
                        if (swift.tunnelRestored) break
                        delay(500)
                    }
                    if (!connectedNow || userDisconnected || switching) return@launch
                    if (swift.isRunning() && swift.tunnelRestored) {
                        android.util.Log.i(TAG, "swift tunnel restored — back to Connected")
                        connectionRepo.setState(
                            ConnectionState.Connected(lastProfile?.name ?: "", lastProfile?.id)
                        )
                    } else {
                        android.util.Log.w(TAG, "swift tunnel not restored — scheduling reconnect")
                        connectedNow = false
                        Tun2Socks.stop()
                        closeTun()
                        scheduleReconnect()
                    }
                } finally {
                    tunnelWatchdogActive = false
                }
            }
        }

        statsCollector.start()
        connectionRepo.setActiveProfile(profile)
        connectionRepo.updateStats {
            it.copy(connectedSinceEpochMs = System.currentTimeMillis())
        }
        connectionRepo.setState(ConnectionState.Connected(profile.name, profile.id))
        notifier.notifyConnected(profile.name)
        connectedNow = true
    }

    /** Seamless config change while connected: tear down, reconnect, stay up. */
    private fun switchTo(profile: Profile) {
        if (switching) return
        switching = true
        serviceScope.launch {
            try {
                android.util.Log.i(TAG, "hot-switch to: " + profile.name)
                connectionRepo.setState(ConnectionState.Disconnecting)
                statsCollector.stop(resetLiveStats = false)
                runCatching { coreController?.stopLoop() }
                coreController = null
                Tun2Socks.stop()
                swift.stop()
                closeTun()
                connectedNow = false
                connectionRepo.updateStats {
                    it.copy(connectedSinceEpochMs = null, elapsedSeconds = 0)
                }
                connectProfile(profile)
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "hot-switch failed", t)
                connectionRepo.setState(ConnectionState.Error(t.message ?: "switch failed"))
                notifier.notifyError(t.message ?: "switch failed")
                stopSelf()
            } finally {
                switching = false
            }
        }
    }

    private fun buildTunInterface(profile: Profile): ParcelFileDescriptor? {
        val s = settings.current
        val builder = Builder()
            .setMtu(s.mtu)
            .addAddress(VPN_IPV4, 30)
            .setSession("KUVAVpn — ${profile.name}")

        if (s.bypassLan) {
            BYPASS_LAN_ROUTES.forEach { (addr, prefix) -> builder.addRoute(addr, prefix) }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        if (s.ipv6Enabled) {
            builder.addAddress(VPN_IPV6, 126)
            builder.addRoute("::", 0)
        }

        s.effectiveDnsServers().filter { isIpLiteral(it) }.forEach { builder.addDnsServer(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        if (s.killSwitch && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setBlocking(true)
        }

        // per-app routing
        val apps = s.selectedApps
        if (apps.isNotEmpty()) {
            when (s.routingMode) {
                RoutingMode.BYPASS_SELECTED -> {
                    builder.addDisallowedApplication(packageName)
                    apps.forEach { pkg ->
                        try { builder.addDisallowedApplication(pkg) }
                        catch (_: PackageManager.NameNotFoundException) { }
                    }
                }
                RoutingMode.ONLY_SELECTED -> {
                    // only allowed apps route through; self is implicitly excluded
                    apps.forEach { pkg ->
                        try { builder.addAllowedApplication(pkg) }
                        catch (_: PackageManager.NameNotFoundException) { }
                    }
                }
            }
        } else {
            builder.addDisallowedApplication(packageName)
        }

        return try { builder.establish() } catch (t: Throwable) {
            android.util.Log.e(TAG, "establish failed", t)
            null
        }
    }

    // ------------------------------------------------------- auto-reconnect

    /**
     * Watches the default network. On WiFi <-> cellular switches or short
     * outages the old TUN/core sockets die silently; we tear them down and
     * rebuild the whole chain on the new network. User-initiated disconnects
     * never trigger a reconnect.
     */
    private fun registerNetworkWatcher() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // The VPN interface itself (tun0/tun1) shows up as the default
                // network ~1s after connect. Reacting to it "reconnected" the
                // VPN mid-connect: a second establish() raced the first and
                // hev refused to start on the second fd -> Connected UI with
                // zero traffic. Only physical networks may trigger reconnect.
                val caps = runCatching {
                    (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                        ?.getNetworkCapabilities(network)
                }.getOrNull()
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                    android.util.Log.i(TAG, "ignoring VPN's own network: $network")
                    return
                }
                android.util.Log.i(TAG, "network available: $network")
                scheduleReconnect()
            }
            override fun onLost(network: Network) {
                android.util.Log.i(TAG, "network lost: $network")
                handleNetworkLoss()
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "registerDefaultNetworkCallback failed", t)
        }
    }

    private fun unregisterNetworkWatcher() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.unregisterNetworkCallback(cb)
        } catch (_: Exception) { }
    }

    private fun handleNetworkLoss() {
        if (!connectedNow || userDisconnected) return
        connectedNow = false
        serviceScope.launch {
            connectionRepo.setState(ConnectionState.Connecting(lastProfile?.name ?: "", lastProfile?.id))
            statsCollector.stop()
            try { coreController?.stopLoop() } catch (_: Exception) { }
            coreController = null
            swift.stop()
            Tun2Socks.stop()
            closeTun()
        }
    }

    private fun scheduleReconnect() {
        if (userDisconnected) return
        if (isStarting || switching) return
        if (connectedNow || coreController != null) return
        if (lastProfile == null) return
        pendingReconnect?.cancel()
        pendingReconnect = serviceScope.launch {
            // debounce: both onLost(old) and onAvailable(new) fire on a switch;
            // give the stack a moment so establish() binds to the fresh network
            delay(1500)
            val profile = lastProfile ?: return@launch
            if (userDisconnected || connectedNow || coreController != null) return@launch
            android.util.Log.i(TAG, "auto-reconnect: restarting VPN on new network")
            try {
                connectProfile(profile)
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "auto-reconnect failed", t)
                connectionRepo.setState(ConnectionState.Error(t.message ?: "reconnect failed"))
            }
        }
    }

    // ------------------------------------------------------------------ stop

    private fun disconnect() {
        userDisconnected = true
        pendingReconnect?.cancel()
        pendingReconnect = null
        connectedNow = false
        serviceScope.launch {
            connectionRepo.setState(ConnectionState.Disconnecting)
            statsCollector.stop(resetLiveStats = false)
            try { coreController?.stopLoop() } catch (_: Exception) { }
            coreController = null
            swift.stop()
            Tun2Socks.stop()
            // verified ordering: stop service before closing the fd avoids a stale VPN icon
            closeTun()
            connectionRepo.updateStats {
                it.copy(connectedSinceEpochMs = null, elapsedSeconds = 0)
            }
            connectionRepo.setState(ConnectionState.Disconnected)
            notifier.notifyDisconnected()
            stopSelf()
        }
    }

    private fun closeTun() {
        try { tunInterface?.close() } catch (_: Exception) { }
        tunInterface = null
    }

    private fun isIpLiteral(s: String) =
        s.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$""")) || s.contains(":")

    companion object {
        private const val TAG = "KuvaVpnService"
        const val ACTION_DISCONNECT = "com.kuva.vpn.action.DISCONNECT"
        const val VPN_IPV4 = "26.26.26.1"
        const val VPN_IPV6 = "fdfe:dcba:9876::1"

        /** Route list that excludes RFC1918 + link-local (bypass-LAN mode). */
        val BYPASS_LAN_ROUTES: List<Pair<String, Int>> = listOf(
            "0.0.0.0" to 5, "8.0.0.0" to 7, "11.0.0.0" to 8, "12.0.0.0" to 6,
            "16.0.0.0" to 4, "32.0.0.0" to 3, "64.0.0.0" to 2, "128.0.0.0" to 3,
            "160.0.0.0" to 5, "168.0.0.0" to 6, "172.0.0.0" to 12, "172.32.0.0" to 11,
            "172.64.0.0" to 10, "172.128.0.0" to 9, "173.0.0.0" to 8, "174.0.0.0" to 7,
            "176.0.0.0" to 4, "192.0.0.0" to 9, "192.128.0.0" to 11, "192.160.0.0" to 13,
            "192.169.0.0" to 16, "192.170.0.0" to 15, "192.172.0.0" to 14, "192.176.0.0" to 12,
            "192.192.0.0" to 10, "193.0.0.0" to 8, "194.0.0.0" to 7, "196.0.0.0" to 6,
            "200.0.0.0" to 5, "208.0.0.0" to 4,
        )

        fun start(context: Context) {
            val i = Intent(context, KuvaVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, KuvaVpnService::class.java).apply { action = ACTION_DISCONNECT }
            context.startService(i)
        }
    }
}
