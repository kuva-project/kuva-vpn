package com.kuva.vpn.core.stats

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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Polls the native tunnel's real cumulative byte counters every second,
 * computes per-second deltas (true speed), and pushes to UI + notification.
 * All numbers are measured from the OS — there is no simulation path.
 */
@Singleton
class TrafficStatsCollector @Inject constructor(
    private val connRepo: ConnectionRepositoryImpl,
    private val notifier: VpnNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var lastUp = 0L
    private var lastDown = 0L

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
                        notifier.updateSpeed(Formatters.speed(dUp), Formatters.speed(dDown))
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
