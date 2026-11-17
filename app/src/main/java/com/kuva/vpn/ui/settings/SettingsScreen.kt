package com.kuva.vpn.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kuva.vpn.R
import com.kuva.vpn.data.settings.RoutingMode
import com.kuva.vpn.ui.apps.AppPickerScreen
import java.io.File
import com.kuva.vpn.ui.theme.NeonPurple
import com.kuva.vpn.ui.theme.PurpleGradientEnd
import com.kuva.vpn.ui.theme.StatusRed
import com.kuva.vpn.ui.theme.NeonPurpleLight
import com.kuva.vpn.ui.theme.NeonPurpleSoft

/**
 * Settings, redesigned as grouped cards (appearance, split tunneling, DNS,
 * connection, security, advanced, about). Every row edits its value in
 * place; numeric rows open a small edit dialog.
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val s by viewModel.settings.collectAsStateWithLifecycle()
    var showAppPicker by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showGeoInfo by remember { mutableStateOf(false) }
    var geoSourcePick by remember { mutableStateOf("") }
    var showPsiphonModePick by remember { mutableStateOf(false) }
    var showSwiftLog by remember { mutableStateOf(false) }
    var swiftLog by remember { mutableStateOf<String?>(null) }
    var showXrayLog by remember { mutableStateOf(false) }
    var showLogPicker by remember { mutableStateOf(false) }
    var xrayLog by remember { mutableStateOf<String?>(null) }
    val geoState by viewModel.geoState.collectAsStateWithLifecycle()
    val geoMessage by viewModel.geoMessage.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val batteryRestricted = viewModel.batteryRestricted.value

    // the user tapped the update banner on the dashboard: it landed here with
    // the auto-check flag set — run the update check immediately, clear flag
    val appUpdateFlag = androidx.compose.ui.platform.LocalContext.current
        .applicationContext as com.kuva.vpn.KuvaApplication
    val runUpdateCheck by appUpdateFlag.runUpdateCheck.collectAsState()
    androidx.compose.runtime.LaunchedEffect(runUpdateCheck) {
        if (runUpdateCheck) {
            appUpdateFlag.runUpdateCheck.value = false
            viewModel.checkForUpdates()
        }
    }

    // refresh the battery-optimization flag every time settings opens/resumes
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.refreshBatteryRestricted()
    }
    // which numeric editor is open (null = none)
    var editing by remember { mutableStateOf<NumField?>(null) }
    // custom MASQUE SNI editor (patch 216)
    var showSniEdit by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)

        // ---------- connection engine (top of all settings) ----------
        SectionCard(stringResource(R.string.engine_mode)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("xray", "swift").forEach { m ->
                    FilterChip(
                        selected = s.engineMode == m,
                        onClick = { viewModel.setEngineMode(m) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Transparent,
                            labelColor = Color.White.copy(alpha = 0.75f),
                            selectedContainerColor = Color.Transparent,
                            selectedLabelColor = NeonPurple,
                        ),
                        border = if (s.engineMode == m)
                            BorderStroke(1.5.dp, Brush.linearGradient(listOf(NeonPurple, PurpleGradientEnd)))
                        else BorderStroke(1.5.dp, Color.White.copy(alpha = 0.15f)),
                        label = {
                            Text(
                                when (m) {
                                    "swift" -> stringResource(R.string.engine_swift)
                                    else -> stringResource(R.string.engine_xray)
                                }
                            )
                        },
                    )
                }
            }
            Text(
                text = stringResource(
                    if (s.engineMode == "swift") R.string.engine_swift_desc else R.string.engine_xray_desc
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ---------- split tunneling ----------
        SectionCard(stringResource(R.string.split_tunneling)) {
            Text(
                if (s.routingMode == RoutingMode.ONLY_SELECTED)
                    stringResource(R.string.routing_mode_only_desc)
                else
                    stringResource(R.string.routing_mode_bypass_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // full-width stacked options: bypass on top, only below
            ModeOption(
                title = stringResource(R.string.routing_mode_bypass),
                desc = stringResource(R.string.routing_mode_bypass_desc),
                selected = s.routingMode == RoutingMode.BYPASS_SELECTED,
                onClick = { viewModel.setRoutingMode(RoutingMode.BYPASS_SELECTED) },
            )
            ModeOption(
                title = stringResource(R.string.routing_mode_only),
                desc = stringResource(R.string.routing_mode_only_desc),
                selected = s.routingMode == RoutingMode.ONLY_SELECTED,
                onClick = { viewModel.setRoutingMode(RoutingMode.ONLY_SELECTED) },
            )
            ValueRow(
                title = stringResource(R.string.select_apps),
                value = stringResource(R.string.apps_count, s.selectedApps.size),
                onClick = { showAppPicker = true },
            )
            SwitchRow(stringResource(R.string.bypass_lan), null, s.bypassLan, viewModel::setBypassLan)
        }

        // ---------- DNS ----------
        SectionCard(stringResource(R.string.dns)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                listOf("cloudflare", "google", "quad9", "adguard", "custom").forEach { p ->
                    FilterChip(
                        selected = s.dnsProvider == p,
                        onClick = { viewModel.setDnsProvider(p) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Transparent,
                            labelColor = Color.White.copy(alpha = 0.75f),
                            selectedContainerColor = Color.Transparent,
                            selectedLabelColor = NeonPurple,
                        ),
                        border = if (s.dnsProvider == p)
                            BorderStroke(1.5.dp, Brush.linearGradient(listOf(NeonPurple, PurpleGradientEnd)))
                        else BorderStroke(1.5.dp, Color.White.copy(alpha = 0.15f)),
                        label = { Text(p.replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            if (s.dnsProvider == "custom") {
                var custom by remember(s.customDns) { mutableStateOf(s.customDns) }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text(stringResource(R.string.dns_custom_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                TextButton(onClick = { viewModel.setCustomDns(custom) }) {
                    Text(stringResource(R.string.save))
                }
            }
            SwitchRow(
                stringResource(R.string.dns_leak_protection),
                stringResource(R.string.dns_leak_desc),
                s.dnsLeakProtection,
                viewModel::setDnsLeak,
            )
        }

        // ---------- connection ----------
        SectionCard(stringResource(R.string.connection)) {
            SwitchRow(stringResource(R.string.auto_connect_launch), null, s.autoConnectLaunch, viewModel::setAutoLaunch)
            SwitchRow(stringResource(R.string.auto_connect_boot), null, s.autoConnectBoot, viewModel::setAutoBoot)
            SwitchRow(stringResource(R.string.auto_reconnect), null, s.autoReconnect, viewModel::setAutoReconnect)
            ValueRow(
                title = stringResource(R.string.conn_timeout),
                value = stringResource(R.string.fmt_val_sec, s.connTimeoutSec),
                onClick = { editing = NumField.TIMEOUT },
            )
            ValueRow(
                title = stringResource(R.string.retry_interval),
                value = stringResource(R.string.fmt_val_sec, s.retryIntervalSec),
                onClick = { editing = NumField.RETRY },
            )
            SwitchRow(stringResource(R.string.auto_ping), null, s.autoPingAfterImport, viewModel::setAutoPing)
            SwitchRow(
                stringResource(R.string.swift_scan),
                stringResource(R.string.swift_scan_desc),
                s.swiftScan,
                viewModel::setSwiftScan,
            )
            // psiphon on/off + exit country moved to the dashboard's quick
            // card (bottom sheet) — settings no longer duplicates them
            // server selection (manual/lowest ping/best performance) removed
            // by user request — manual profile choice is always used
        }

        // ---------- Kuva Swift (patch 216) ----------
        // anti-DPI controls in their own card, per user request; defaults
        // match the core (fragment off, noize off, SNI = core default) —
        // the always-on combination stalled tunnels on Hamrah-Aval
        SectionCard(stringResource(R.string.swift_settings)) {
            SwitchRow(
                stringResource(R.string.swift_fragment),
                stringResource(R.string.swift_fragment_desc),
                s.swiftFragment,
                viewModel::setSwiftFragment,
                enabled = s.engineMode == "swift",
            )
            SwitchRow(
                stringResource(R.string.swift_noize),
                stringResource(R.string.swift_noize_desc),
                s.swiftNoize,
                viewModel::setSwiftNoize,
                enabled = s.engineMode == "swift",
            )
            // patch 224: HTTP/3 moved back above the SNI row and is now
            // dimmed when the engine is Xray — it only ever affects the
            // Swift core, same treatment as fragment/noize (user request:
            // "کامل غیرفعال مثل فرگمنت و noize")
            SwitchRow(
                stringResource(R.string.swift_h3),
                stringResource(R.string.swift_h3_desc),
                s.swiftH3,
                viewModel::setSwiftH3,
                enabled = s.engineMode == "swift",
            )
            ValueRow(
                title = stringResource(R.string.swift_sni),
                value = s.swiftSni.ifBlank { stringResource(R.string.swift_sni_default) },
                onClick = { showSniEdit = true },
            )
            // psiphon chain direction (patch 217): how the psiphon layer
            // rides the MASQUE tunnel when the dashboard switch is on
            ValueRow(
                title = stringResource(R.string.swift_psiphon_mode),
                value = if (s.swiftPsiphonMode == "reverse")
                    stringResource(R.string.psiphon_mode_reverse)
                else
                    stringResource(R.string.psiphon_mode_chain),
                onClick = { showPsiphonModePick = true },
            )
        }

        // ---------- security ----------
        SectionCard(stringResource(R.string.security)) {
            SwitchRow(
                stringResource(R.string.kill_switch),
                stringResource(R.string.kill_switch_desc),
                s.killSwitch,
                viewModel::setKillSwitch,
            )
            if (batteryRestricted) {
                ValueRow(
                    stringResource(R.string.battery_optimization),
                    stringResource(R.string.battery_optimization_desc),
                    onClick = { viewModel.openBatteryOptimizationSettings() },
                )
            }
            SwitchRow(
                stringResource(R.string.bypass_iran),
                stringResource(R.string.bypass_iran_desc),
                s.bypassIran,
                viewModel::setBypassIran,
            )
            SwitchRow(stringResource(R.string.ipv6), null, s.ipv6Enabled, viewModel::setIpv6)
            SwitchRow(stringResource(R.string.stats_enabled), null, s.statsEnabled, viewModel::setStats)
            SwitchRow(stringResource(R.string.notif_show_speed), null, s.showNotifSpeed, viewModel::setShowNotifSpeed)
        }

        // ---------- advanced ----------
        SectionCard(stringResource(R.string.advanced)) {
            Text(
                stringResource(R.string.advanced_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ValueRow(
                title = stringResource(R.string.mtu),
                value = s.mtu.toString(),
                onClick = { editing = NumField.MTU },
            )
            ValueRow(
                title = stringResource(R.string.socks_port),
                value = s.socksPort.toString(),
                onClick = { editing = NumField.SOCKS_PORT },
            )
            ValueRow(
                title = stringResource(R.string.speed_test_limit),
                value = stringResource(R.string.fmt_val_mb, s.speedTestLimitMB),
                onClick = { editing = NumField.SPEED_LIMIT },
            )
        }

        // ---------- about ----------
        SectionCard(stringResource(R.string.about), initiallyExpanded = true) {
            // ---------- in-app update ----------
            when (val u = updateState) {
                is SettingsViewModel.UpdateState.Idle -> Unit
                is SettingsViewModel.UpdateState.Checking -> ValueRow(
                    stringResource(R.string.update_check),
                    stringResource(R.string.update_checking),
                    onClick = {},
                )
                is SettingsViewModel.UpdateState.UpToDate -> ValueRow(
                    stringResource(R.string.update_check),
                    stringResource(
                        R.string.update_latest,
                        com.kuva.vpn.BuildConfig.VERSION_NAME,
                    ),
                    onClick = { viewModel.dismissUpdateState() },
                )
                is SettingsViewModel.UpdateState.Available -> {
                    ValueRow(
                        stringResource(R.string.update_available, u.version),
                        stringResource(R.string.update_download),
                        onClick = {
                            viewModel.downloadAndInstall(u.version, u.assetName, u.assetUrl)
                        },
                    )
                    Text(
                        stringResource(R.string.update_install_prompt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                is SettingsViewModel.UpdateState.Downloading -> ValueRow(
                    stringResource(R.string.update_download),
                    stringResource(R.string.update_downloading, "${u.percent}%"),
                    onClick = {},
                )
                is SettingsViewModel.UpdateState.InstallPrompted -> ValueRow(
                    stringResource(R.string.update_check),
                    stringResource(R.string.update_install_prompt),
                    onClick = { viewModel.dismissUpdateState() },
                )
                is SettingsViewModel.UpdateState.PermissionNeeded -> ValueRow(
                    stringResource(R.string.update_check),
                    stringResource(R.string.update_need_permission),
                    onClick = { viewModel.openInstallPermissionSettings() },
                )
                is SettingsViewModel.UpdateState.Failed -> ValueRow(
                    stringResource(R.string.update_check),
                    stringResource(R.string.update_failed, u.message),
                    onClick = { viewModel.dismissUpdateState() },
                )
            }
            // action row only while idle: while checking/downloading the state
            // row above already stands in its place (no duplicated title)
            if (updateState is SettingsViewModel.UpdateState.Idle) {
                ValueRow(
                    stringResource(R.string.update_check),
                    "",
                    onClick = { viewModel.checkForUpdates() },
                )
            }
            ValueRow(
                stringResource(R.string.geo_assets),
                stringResource(R.string.geo_check_short),
                onClick = {
                    geoSourcePick = s.geoSource
                    showGeoInfo = true
                },
            )
            geoMessage?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            ValueRow(stringResource(R.string.privacy), "") { showPrivacy = true }
            // license line, same linear format as the Kuva Swift
            // attribution below (no clickable row needed)
            Text(
                "Xray-core — open-source under MPL-2.0.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Text(
                stringResource(R.string.about_swift_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ValueRow(stringResource(R.string.version), com.kuva.vpn.BuildConfig.VERSION_NAME, onClick = {})
            ValueRow(stringResource(R.string.telegram_channel), "@kuvaproject", {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://t.me/kuvaproject")
                        )
                    )
                }
            })
            ValueRow(stringResource(R.string.github_repo), "github.com/kuva-project/kuva-vpn", {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/kuva-project/kuva-vpn")
                        )
                    )
                }
            })
            ValueRow(stringResource(R.string.view_logs), "", {
                showLogPicker = true
            })
            ValueRow(stringResource(R.string.bug_report), "", {
                val ok = buildAndShareBugReport(context)
                if (!ok) {
                    android.widget.Toast.makeText(
                        context, context.getString(R.string.bug_report_failed),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            })
        }

        // ---------- danger zone ----------
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { showResetConfirm = true }) {
            Text(stringResource(R.string.reset_settings), color = StatusRed,
                fontSize = 17.sp)
        }
        TextButton(onClick = viewModel::clearCache) {
            Text(stringResource(R.string.clear_cache), color = StatusRed,
                fontSize = 17.sp)
        }
        Spacer(Modifier.height(32.dp))
    }

    // ---------- dialogs ----------
    if (showAppPicker) {
        AppPickerScreen(
            selected = s.selectedApps,
            onConfirm = {
                viewModel.setSelectedApps(it)
                showAppPicker = false
            },
            onDismiss = { showAppPicker = false },
        )
    }
    if (showGeoInfo) {
        AlertDialog(
            onDismissRequest = { showGeoInfo = false },
            title = { Text(stringResource(R.string.geo_assets)) },
            text = {
                Column {
                    Text(
                        "geoip:  " + geoState.geoip.ifBlank { "bundled" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "geosite: " + geoState.geosite.ifBlank { "bundled" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (geoState.source.isNotBlank()) {
                        Text(
                            "source: " + geoState.source,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    // selectable source repo (radio list, like v2rayNG's
                    // asset sources): picked with "Update", remembered in
                    // DataStore so later updates reuse it
                    Text(
                        stringResource(R.string.geo_source),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    com.kuva.vpn.core.engine.GeoUpdater.SOURCES.forEach { src ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { geoSourcePick = src }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = geoSourcePick == src,
                                onClick = { geoSourcePick = src },
                            )
                            Text(src, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.geo_assets_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showGeoInfo = false
                    if (geoSourcePick != s.geoSource) {
                        viewModel.setGeoSource(geoSourcePick)
                    }
                    viewModel.updateGeo()
                }) { Text(stringResource(R.string.geo_update)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showGeoInfo = false
                    viewModel.resetGeo()
                }) { Text(stringResource(R.string.geo_reset)) }
            },
        )
    }

    if (showLogPicker) {
        AlertDialog(
            onDismissRequest = { showLogPicker = false },
            title = { Text(stringResource(R.string.view_logs)) },
            text = {
                Column {
                    TextButton(onClick = {
                        showLogPicker = false
                        showXrayLog = true
                    }) { Text(stringResource(R.string.log_pick_xray)) }
                    TextButton(onClick = {
                        showLogPicker = false
                        showSwiftLog = true
                    }) { Text(stringResource(R.string.log_pick_swift)) }
                }
            },
            confirmButton = {},
        )
    }
    if (showXrayLog) {
        LogViewerDialog(
            title = stringResource(R.string.view_xray_log),
            emptyText = stringResource(R.string.xray_log_empty),
            log = xrayLog,
            onLoad = { xrayLog = viewModel.xrayLogText() },
            onShare = { shareLogOrToast(context, viewModel.coreLogSnapshot(), R.string.no_core_log) },
            onDismiss = { showXrayLog = false; xrayLog = null },
        )
    }
    if (showSwiftLog) {
        LogViewerDialog(
            title = stringResource(R.string.view_swift_log),
            emptyText = stringResource(R.string.swift_log_empty),
            log = swiftLog,
            onLoad = { swiftLog = viewModel.swiftLogText() },
            onShare = { shareLogOrToast(context, viewModel.swiftLogSnapshot(), R.string.no_swift_log) },
            onDismiss = { showSwiftLog = false; swiftLog = null },
        )
    }
    if (showPrivacy) {
        PrivacyPolicyDialog(onClose = { showPrivacy = false })
    }
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.reset_settings)) },
            text = { Text(stringResource(R.string.reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetSettings()
                    showResetConfirm = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
    editing?.let { field ->
        val row = when (field) {
            NumField.TIMEOUT -> PreferenceRow(
                stringResource(R.string.conn_timeout), s.connTimeoutSec, 5..120, R.string.fmt_val_sec,
            )
            NumField.RETRY -> PreferenceRow(
                stringResource(R.string.retry_interval), s.retryIntervalSec, 1..60, R.string.fmt_val_sec,
            )
            NumField.MTU -> PreferenceRow(
                stringResource(R.string.mtu), s.mtu, 1280..16000, R.string.edit_value,
            )
            NumField.SOCKS_PORT -> PreferenceRow(
                stringResource(R.string.socks_port), s.socksPort, 1024..65535, R.string.edit_value,
            )
            NumField.SPEED_LIMIT -> PreferenceRow(
                stringResource(R.string.speed_test_limit), s.speedTestLimitMB, 1..500, R.string.fmt_val_mb,
            )
        }
        NumberEditDialog(
            title = row.title,
            initial = row.value,
            range = row.range,
            unit = stringResource(row.unitRes, row.value),
            onConfirm = {
                when (field) {
                    NumField.TIMEOUT -> viewModel.setConnTimeout(it)
                    NumField.RETRY -> viewModel.setRetryInterval(it)
                    NumField.MTU -> viewModel.setMtu(it)
                    NumField.SOCKS_PORT -> viewModel.setSocksPort(it)
                    NumField.SPEED_LIMIT -> viewModel.setSpeedLimit(it)
                }
            },
            onDismiss = { editing = null },
        )
    }
    if (showPsiphonModePick) {
        AlertDialog(
            onDismissRequest = { showPsiphonModePick = false },
            title = { Text(stringResource(R.string.swift_psiphon_mode)) },
            text = {
                Column {
                    listOf(
                        "chain" to stringResource(R.string.psiphon_mode_chain),
                        "reverse" to stringResource(R.string.psiphon_mode_reverse),
                    ).forEach { (mode, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showPsiphonModePick = false; viewModel.setSwiftPsiphonMode(mode) }
                                .padding(vertical = 6.dp),
                        ) {
                            RadioButton(
                                selected = s.swiftPsiphonMode == mode,
                                onClick = { showPsiphonModePick = false; viewModel.setSwiftPsiphonMode(mode) },
                            )
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Text(
                        stringResource(
                            if (s.swiftPsiphonMode == "reverse") R.string.psiphon_mode_reverse_desc
                            else R.string.psiphon_mode_chain_desc
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPsiphonModePick = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (showSniEdit) {
        TextEditDialog(
            title = stringResource(R.string.swift_sni),
            initial = s.swiftSni,
            placeholder = "www.cloudflare.com",
            hint = stringResource(R.string.swift_sni_hint),
            onConfirm = { viewModel.setSwiftSni(it) },
            onDismiss = { showSniEdit = false },
        )
    }
}

/** Identifies which numeric setting is being edited. */
private enum class NumField { TIMEOUT, RETRY, MTU, SOCKS_PORT, SPEED_LIMIT }

/** Small holder for the dialog config. */
private data class PreferenceRow(val title: String, val value: Int, val range: IntRange, val unitRes: Int)

/** Free-text editor (patch 216: custom MASQUE SNI). Blank confirm = reset. */
@Composable
private fun TextEditDialog(
    title: String,
    initial: String,
    placeholder: String,
    hint: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(placeholder) },
                supportingText = { Text(hint) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(text)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun NumberEditDialog(
    title: String,
    initial: Int,
    range: IntRange,
    unit: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in range
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() } },
                singleLine = true,
                suffix = { if (unit.isNotEmpty()) Text(unit) },
                isError = !valid,
                supportingText = {
                    if (!valid) {
                        Text(stringResource(R.string.invalid_value_hint, range.first, range.last))
                    }
                },
            )
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    parsed?.let { onConfirm(it) }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

/**
 * Icon-only log-dialog button: outlined pill in the light neon purple
 * accent (variant 3 from the mockup). Icon paints the whole label, so
 * text can never clip or wrap.
 */
@Composable
private fun LogActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 44.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            tint = NeonPurpleLight,  // light neon purple accent
        )
    }
}

/**
 * Shared log viewer dialog (Xray and Swift): monospace tail of the log with
 * Share / Refresh / Close. `onLoad` runs on first show and on Refresh.
 */
@Composable
private fun LogViewerDialog(
    title: String,
    emptyText: String,
    log: String?,
    onLoad: suspend () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // first open (log == null) triggers the load; Refresh relaunches it
    LaunchedEffect(log) { if (log == null) onLoad() }
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollState()
    // auto-scroll to the END of the log: the latest lines are what matter
    LaunchedEffect(log, scroll.maxValue) {
        scroll.scrollTo(scroll.maxValue)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f))
                // X close icon at the top of the dialog
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.close),
                    )
                }
            }
        },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(340.dp),
            ) {
                when {
                    log == null -> Text(
                        emptyText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            log,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.verticalScroll(scroll),
                        )
                    }
                }
            }
        },
        confirmButton = {
            // buttons wrap their own text, centered in one row —
            // each label is always fully visible on a single line
            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Share: box with up arrow (user-chosen IosShare icon)
                LogActionButton(
                    Icons.Filled.IosShare,
                    stringResource(R.string.swift_log_share),
                    onClick = onShare,
                )
                LogActionButton(
                    Icons.Filled.Refresh,
                    stringResource(R.string.swift_log_refresh),
                    onClick = { scope.launch { onLoad() } },
                )
                // Copy replaces the old Close button: one tap copies the
                // whole log text; the X icon above closes the dialog
                LogActionButton(
                    Icons.Filled.ContentCopy,
                    stringResource(R.string.log_copy),
                    onClick = { clipboard.setText(AnnotatedString(log ?: "")) },
                )
            }
        },
    )
}

/** Shares a log file URI, or toasts when there is nothing to share yet. */
private fun shareLogOrToast(
    context: android.content.Context,
    uri: android.net.Uri?,
    noLogRes: Int,
) {
    if (uri != null) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, null))
    } else {
        android.widget.Toast.makeText(
            context, noLogRes, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** Grouped settings card with a small muted title at the top. */
@Composable
private fun SectionCard(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    // collapsed by default: only the title is visible; tapping the title row
    // expands the card contents, tapping again collapses them
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val chevronRotation by animateFloatAsState(
        // expanded: point down; collapsed: point toward the text start
        // edge — right in English, left in Persian
        targetValue = if (expanded) 0f else if (rtl) 90f else -90f,
        animationSpec = tween(200),
    )
    val titleColor = NeonPurpleSoft   // lighter neon purple accent
    Spacer(Modifier.height(16.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = titleColor,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(chevronRotation),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = titleColor,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                content()
            }
        }
    }
}

/** Full-width routing-mode option row with radio indicator. */
@Composable
private fun ModeOption(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) NeonPurple.copy(alpha = 0.18f)
            else Color.White.copy(alpha = 0.06f),
        ),
        border = if (selected)
            BorderStroke(1.5.dp, Brush.linearGradient(listOf(NeonPurple, PurpleGradientEnd)))
        else null,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

/** Inner sub-title (theme / language / auto-select mode). */
@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.45f),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) {
                Text(
                    desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onChange else null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = NeonPurple,   // neon purple
                checkedThumbColor = Color(0xFF101010),
                checkedBorderColor = NeonPurple,
            ),
        )
    }
}

@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(140.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}


/**
 * Bug-report bundle: app/device info + the last core logs, zipped into
 * cache/exports (already exposed through the FileProvider) and handed to
 * the system share sheet (Telegram, email, ...). Nothing is uploaded by
 * the app itself — the user picks the destination and sees what is sent.
 * Logs are trimmed to their last 400 KB so the zip stays small.
 */
private fun buildAndShareBugReport(context: android.content.Context): Boolean {
    return runCatching {
        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(outDir, "kuva_report.zip")
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { z ->
            fun put(name: String, text: String, maxBytes: Int = 400_000) {
                if (text.isEmpty()) return
                val bytes = text.toByteArray(Charsets.UTF_8)
                val trimmed = if (bytes.size > maxBytes)
                    bytes.copyOfRange(bytes.size - maxBytes, bytes.size)
                else bytes
                z.putNextEntry(java.util.zip.ZipEntry(name))
                z.write(trimmed)
                z.closeEntry()
            }
            val info = buildString {
                appendLine("KUVAVpn bug report")
                appendLine(
                    "time: " + java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
                    ).format(java.util.Date())
                )
                appendLine(
                    "app: " + context.packageName +
                        " v" + com.kuva.vpn.BuildConfig.VERSION_NAME +
                        " (" + com.kuva.vpn.BuildConfig.VERSION_CODE + ")"
                )
                appendLine(
                    "device: " + android.os.Build.MANUFACTURER + " " +
                        android.os.Build.MODEL +
                        " (Android " + android.os.Build.VERSION.RELEASE +
                        ", SDK " + android.os.Build.VERSION.SDK_INT + ")"
                )
            }
            put("info.txt", info)
            File(context.filesDir, "swift/swift.log").takeIf { it.exists() }
                ?.let { put("swift.log", it.readText()) }
            File(context.cacheDir, "logs/xray.log").takeIf { it.exists() }
                ?.let { put("xray.log", it.readText()) }
            // persisted uncaught-exception logs (written by the crash handler
            // in KuvaApplication) — newest first, max 3 files
            File(context.filesDir, "crash").listFiles()
                ?.sortedByDescending { it.name }
                ?.take(3)
                ?.forEach { f ->
                    runCatching { put("crashes/" + f.name, f.readText()) }
                }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", out
        )
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(send, null))
        true
    }.getOrDefault(false)
}
