package com.kuva.vpn.ui

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kuva.vpn.core.control.VpnControl
import com.kuva.vpn.data.settings.SettingsRepository
import com.kuva.vpn.domain.model.ConnectionState
import com.kuva.vpn.ui.navigation.KuvaNavHost
import com.kuva.vpn.ui.theme.KUVAVpnTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * FragmentActivity (not ComponentActivity) so the embedded zxing QR scanner
 * fragment works. Theme + locale applied before setContent.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var vpnControl: VpnControl
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        // Hilt injects @Inject fields inside super.onCreate(), so it MUST run first
        super.onCreate(savedInstanceState)

        // apply persisted theme before rendering
        val mode = runBlocking { settings.settings.first().themeMode }
        com.kuva.vpn.KuvaApplication.applyTheme(mode)
        enableEdgeToEdge()
        vpnControl.attach(this)

        // Android 13+ gates notifications behind a runtime permission; without
        // it the persistent VPN notification (speed + Stop action) is invisible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001
            )
        }

        // incoming share links (vless:// ... ) are forwarded to the nav layer
        val sharedText = extractSharedText(intent?.data?.toString())

        setContent {
            KUVAVpnTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    KuvaNavHost(sharedLink = sharedText)
                }
            }
        }
    }

    private fun extractSharedText(data: String?): String? =
        data?.takeIf { it.startsWith("vless://") || it.startsWith("vmess://") ||
            it.startsWith("trojan://") || it.startsWith("ss://") || it.startsWith("socks") }
}
