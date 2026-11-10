package com.kuva.vpn.core.stats

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.kuva.vpn.core.tunnel.Tun2Socks
import com.kuva.vpn.data.repository.ConnectionRepositoryImpl
import com.kuva.vpn.domain.model.LiveStats
import com.kuva.vpn.ui.notifications.VpnNotifier
import com.kuva.vpn.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Polls the native tunnel's real cumulative byte counters every second,
 * computes per-second deltas (true speed), and pushes to UI + notification.
 * All numbers are measured from the OS — there is no simulation path.
 */
@Singleton
class TrafficStatsCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connRepo: ConnectionRepositoryImpl,
    private val notifier: VpnNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var lastUp = 0L
    private var lastDown = 0L

    // Battery: every notify() wakes SystemUI. With the screen on, refreshing
    // the speed notification every 2s is plenty; with the screen off, 15s.
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var lastNotifUp = ""
    private var lastNotifDown = ""
    private var lastNotifMs = 0L

    fun start() {
        stopPreservingSessionStats()
        lastUp = 0L; lastDown = 0L
        job = scope.launch {
            while (isActive) {
                try {
                    val st = Tun2Socks.stats()
                    if (st != null) {
                        val (upTotal, downTotal) = st
                        val dUp = (upTotal - lastUp).coerceAtLeast(0)
                        val dDown = (downTotal - lastDown).coerceAtLeast(0)
                        lastUp = upTotal; lastDown = downTotal
                        connRepo.updateStats { s ->
                            s.copy(
                                downSpeedBps = dDown,
                                upSpeedBps = dUp,
                                totalDownBytes = downTotal,
                                totalUpBytes = upTotal,
                            )
                        }
                        // UI stats stay 1 Hz (in-process, cheap); the system
                        // notification is refreshed far less often to save battery.
                        val up = Formatters.speed(dUp)
                        val down = Formatters.speed(dDown)
                        val now = System.currentTimeMillis()
                        val minInterval = if (powerManager.isInteractive) 2_000L else 15_000L
                        if ((up != lastNotifUp || down != lastNotifDown) &&
                            now - lastNotifMs >= minInterval
                        ) {
                            notifier.updateSpeed(up, down)
                            lastNotifUp = up
                            lastNotifDown = down
                            lastNotifMs = now
                        }
                    }
                } catch (t: Throwable) {
                    // Throwable: native-lib init failures surface as Errors
                    Log.w("KuvaStats", "stats poll: ${t.message}")
                }
                delay(1000)
            }
        }
    }

    fun stopStreaming() {
        job?.cancel(); job = null
        lastNotifUp = ""; lastNotifDown = ""; lastNotifMs = 0L
    }

    /** Stop sampling; optionally wipe the live tiles (defaults to old behavior). */
    fun stop(resetLiveStats: Boolean = true) {
        stopStreaming()
        if (resetLiveStats) connRepo.updateStats { LiveStats() }
        lastUp = 0L; lastDown = 0L
    }

    /**
     * Called right after connect. Cancels any previous sampler WITHOUT wiping
     * the tiles -- the service has just stamped connectedSinceEpochMs.
     */
    fun stopPreservingSessionStats() {
        stopStreaming()
        lastUp = 0L; lastDown = 0L
    }
}
