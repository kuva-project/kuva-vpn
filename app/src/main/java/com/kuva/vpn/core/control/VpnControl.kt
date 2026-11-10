package com.kuva.vpn.core.control

import android.app.Activity
import android.content.Context
import android.net.VpnService
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.kuva.vpn.core.service.KuvaVpnService
import com.kuva.vpn.domain.model.ConnectionState
import com.kuva.vpn.domain.repository.ConnectionRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI-facing control of the real VPN tunnel. Handles the Android VPN consent
 * flow via the Activity Result API and forwards to the service. UI state is
 * never faked: everything on screen comes from ConnectionRepository.
 */
@Singleton
class VpnControl @Inject constructor(
    private val connRepo: ConnectionRepository,
) {
    // The consent launcher needs an Activity: we keep the one attached from
    // MainActivity. NEVER cast an app-level Context to Activity (that was the
    // cause of the connect-button crash).
    private var attachedActivity: ComponentActivity? = null
    private var consentLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>? = null

    /** Call once from MainActivity.onCreate BEFORE any toggle. */
    fun attach(activity: ComponentActivity) {
        attachedActivity = activity
        consentLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                KuvaVpnService.start(activity)
            }
        }
    }

    fun toggle(context: Context) {
        when (connRepo.state.value) {
            is ConnectionState.Connected,
            is ConnectionState.Connecting -> requestStop(context)
            else -> requestStart(context)
        }
    }

    fun requestStart(context: Context) {
        val prepare = VpnService.prepare(context)
        if (prepare == null) {
            // consent already granted — start directly
            KuvaVpnService.start(context)
            return
        }
        val act = attachedActivity
        if (act != null && !act.isFinishing && !act.isDestroyed && consentLauncher != null) {
            consentLauncher!!.launch(prepare)
        } else {
            // no activity available to show the consent dialog — try starting;
            // the system will show its own notification if still not granted
            KuvaVpnService.start(context)
        }
    }

    fun requestStop(context: Context) = KuvaVpnService.stop(context)
}
