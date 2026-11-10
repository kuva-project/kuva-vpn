package com.kuva.vpn.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kuva.vpn.data.settings.SettingsRepository
import com.kuva.vpn.domain.repository.ProfileRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Auto-connect on device startup — only if the user enabled it. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var profileRepo: ProfileRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val s = settings.settings.first()
                if (s.autoConnectBoot && profileRepo.getSelected() != null) {
                    KuvaVpnService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
