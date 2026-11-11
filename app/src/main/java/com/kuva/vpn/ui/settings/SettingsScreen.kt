package com.kuva.vpn.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kuva.vpn.R
import com.kuva.vpn.data.settings.RoutingMode
import com.kuva.vpn.ui.apps.AppPickerScreen

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
    val geoState by viewModel.geoState.collectAsStateWithLifecycle()
    val geoMessage by viewModel.geoMessage.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val batteryRestricted = viewModel.batteryRestricted.value

    // refresh the battery-optimization flag every time settings opens/resumes
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.refreshBatteryRestricted()
    }
    // which numeric editor is open (null = none)
    var editing by remember { mutableStateOf<NumField?>(null) }

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
            Spacer(Modifier.height(6.dp))
            CardTitle(stringResource(R.string.auto_select_mode))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("manual", "lowest_ping", "best_performance").forEach { m ->
                    FilterChip(
                        selected = s.autoSelectMode == m,
                        onClick = { viewModel.setAutoSelect(m) },
                        label = {
                            Text(
                                when (m) {
                                    "lowest_ping" -> stringResource(R.string.mode_lowest_ping)
                                    "best_performance" -> stringResource(R.string.mode_best_perf)
                                    else -> stringResource(R.string.mode_manual)
                                }
                            )
                        },
                    )
                }
            }
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
        SectionCard(stringResource(R.string.about)) {
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
                geoState.source.ifBlank {
                    geoState.geosite.ifBlank { "bundled" }
                },
                onClick = { showGeoInfo = true },
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
            ValueRow(
                stringResource(R.string.licenses),
                "Xray-core MPL-2.0 • hev-socks5-tunnel MPL-2.0",
                viewModel::openLicenses,
            )
            Text(
                stringResource(R.string.about_swift_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ValueRow(stringResource(R.string.version), com.kuva.vpn.BuildConfig.VERSION_NAME, onClick = {})
            ValueRow(stringResource(R.string.clear_cache), "", viewModel::clearCache)
            ValueRow(stringResource(R.string.share_core_log), "", {
                val uri = viewModel.coreLogSnapshot()
                if (uri != null) {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, null))
                } else {
                    android.widget.Toast.makeText(
                        context, R.string.no_core_log, android.widget.Toast.LENGTH_SHORT).show()
                }
            })
            ValueRow(stringResource(R.string.share_swift_log), "", {
                val uri = viewModel.swiftLogSnapshot()
                if (uri != null) {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, null))
                } else {
                    android.widget.Toast.makeText(
                        context, R.string.no_swift_log, android.widget.Toast.LENGTH_SHORT).show()
                }
            })
        }

        // ---------- danger zone ----------
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { showResetConfirm = true }) {
            Text(stringResource(R.string.reset_settings), color = MaterialTheme.colorScheme.error)
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
                    Text(
                        stringResource(R.string.geo_assets_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showGeoInfo = false
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
}

/** Identifies which numeric setting is being edited. */
private enum class NumField { TIMEOUT, RETRY, MTU, SOCKS_PORT, SPEED_LIMIT }

/** Small holder for the dialog config. */
private data class PreferenceRow(val title: String, val value: Int, val range: IntRange, val unitRes: Int)

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

/** Grouped settings card with a small muted title at the top. */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    // collapsed by default: only the title is visible; tapping the title row
    // expands the card contents, tapping again collapses them
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
    )
    val titleColor = Color(0xFFD08FFF)   // lighter neon purple accent
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
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = titleColor,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(chevronRotation),
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
            containerColor = if (selected) Color(0xFFB026FF).copy(alpha = 0.18f)
            else Color.White.copy(alpha = 0.06f),
        ),
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
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
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
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFFB026FF),   // neon purple
                checkedThumbColor = Color(0xFF101010),
                checkedBorderColor = Color(0xFFB026FF),
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
