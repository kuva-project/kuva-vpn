@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.kuva.vpn.ui.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kuva.vpn.R
import com.kuva.vpn.ui.common.CountryFlag
import com.kuva.vpn.domain.model.ConnectionState
import com.kuva.vpn.util.Formatters
import com.kuva.vpn.ui.theme.Gold
import com.kuva.vpn.ui.theme.GoldLight
import com.kuva.vpn.ui.theme.StatusGray
import com.kuva.vpn.ui.theme.StatusGreen
import com.kuva.vpn.ui.theme.StatusOrange
import com.kuva.vpn.ui.theme.StatusRed
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState

// bright neon blue for the engine-switch active state
private val NeonSwitchBlue = Color(0xFF33CCFF)

/**
 * Main dashboard, modeled on the reference screenshots: centered power button,
 * status pill, live stat cards, active-config info card.
 */
@Composable
fun DashboardScreen(
    onToggleLanguage: () -> Unit = {},
    isFa: Boolean = false,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val exitIp by viewModel.exitIp.collectAsStateWithLifecycle()
    val directExitIp by viewModel.directExitIp.collectAsStateWithLifecycle()
    val selected by viewModel.selectedProfile.collectAsStateWithLifecycle()
    val swiftPsiphon by viewModel.swiftPsiphon.collectAsStateWithLifecycle()
    val swiftPsiphonCountry by viewModel.swiftPsiphonCountry.collectAsStateWithLifecycle()

    // back on the dashboard opens the exit popup: Exit / Keep in background / Cancel
    var showExitDialog by remember { mutableStateOf(false) }
    var showPsiphonCountrySheet by remember { mutableStateOf(false) }

    val exitContext = androidx.compose.ui.platform.LocalContext.current

    // update banner: KuvaApplication checks GitHub once per app start; when a
    // newer release exists, show the banner for 8 seconds
    val appUpdateAvailable by (exitContext.applicationContext as com.kuva.vpn.KuvaApplication)
        .updateAvailable.collectAsState()
    var showUpdateBanner by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(appUpdateAvailable) {
        if (appUpdateAvailable) {
            showUpdateBanner = true
            kotlinx.coroutines.delay(8000)   // visible ~8s, then fades out
            showUpdateBanner = false
        }
    }
    androidx.activity.compose.BackHandler { showExitDialog = true }

    if (showPsiphonCountrySheet) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showPsiphonCountrySheet = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 8.dp)
            ) {
                Text(
                    stringResource(R.string.swift_psiphon_country),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                // fixed-height scrollable country box: the bottom sheet is
                // otherwise clipped to the screen and the 28-row list got
                // cut off around "IE"; this box scrolls to the very top and
                // the very bottom of the list
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                listOf("", "AT", "AU", "BE", "CA", "CH", "CZ", "DE", "DK", "ES", "FI", "FR", "GB", "ID", "IE", "IN", "IT", "JP", "KR", "LT", "NL", "NO", "PL", "RO", "RS", "SE", "SG", "US").forEach { cc ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.setSwiftPsiphonCountryWithRestart(cc)
                                showPsiphonCountrySheet = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (cc.isBlank()) "\uD83C\uDF0D" else flagOf(cc),
                            style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(12.dp))
                        Text(psiphonCountryName(cc, isFa), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.weight(1f))
                        if (swiftPsiphonCountry == cc) {
                            Text("\u2713", color = NeonSwitchBlue, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (showExitDialog) {
        // ---- 3D embossed exit dialog (user request) ----
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showExitDialog = false },
            containerColor = Color(0xFF17141F),
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.exit_title)) },
            text = { Text(stringResource(R.string.exit_question)) },
            confirmButton = {},
            dismissButton = {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 1) KEEP IN BACKGROUND — green, full width, on top
                    Box(modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.material3.Button(
                            onClick = {
                                showExitDialog = false
                                // keep the app running: move the task to the background
                                (exitContext as? android.app.Activity)?.moveTaskToBack(true)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(6.dp, RoundedCornerShape(16.dp))
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0xFF2E7D46), Color(0xFF164227))
                                    )
                                ),
                            shape = RoundedCornerShape(16.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = Color.White,
                            ),
                        ) {
                            Text(
                                stringResource(R.string.exit_background),
                                color = Color(0xFF39FF14),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    // 2) EXIT — red, right button, below keep-in-background
                    // 3) CANCEL — orange, left button, same row as exit
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            androidx.compose.material3.Button(
                                onClick = {
                                    showExitDialog = false
                                    // stop the tunnel if running, then close the app task
                                    viewModel.requestDisconnect()
                                    (exitContext as? android.app.Activity)?.finishAffinity()
                                    // fully end the process: a fresh process re-runs
                                    // Application.onCreate, which resets the engine to Xray
                                    android.os.Process.killProcess(android.os.Process.myPid())
                                    kotlin.system.exitProcess(0)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .shadow(6.dp, RoundedCornerShape(16.dp))
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color(0xFF8F1D1D), Color(0xFF4D0F0F))
                                        )
                                    ),
                                shape = RoundedCornerShape(16.dp),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = Color.Transparent,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text(
                                    stringResource(R.string.exit_exit),
                                    color = Color(0xFFFF1744),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            androidx.compose.material3.Button(
                                onClick = { showExitDialog = false },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .shadow(6.dp, RoundedCornerShape(16.dp))
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color(0xFFB96A00), Color(0xFF6E4000))
                                        )
                                    ),
                                shape = RoundedCornerShape(16.dp),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = Color.Transparent,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text(
                                    stringResource(R.string.cancel),
                                    color = Color(0xFFFFC800),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            },
        )
    }

    // pinned header (logo + name + language + status) never scrolls away
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        // ---------- fixed header ----------
        // layout locked to LTR so the header never mirrors when the app
        // language switches to Persian — identical arrangement in fa/en
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.logo_main),
                contentDescription = null,
                modifier = Modifier.size(88.dp),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                "KUVA VPN",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = GoldLight,
            )
            Spacer(Modifier.weight(1f))
            // StatusPill removed from the header: the connected/disconnected
            // state was suspected of shifting the language icon size; the
            // state text still lives under the power button
            // language toggle sticker (fa <-> en) — user-designed icon.
            // plain clickable Box: IconButton clips its content to the touch
            // target and was eating the squircle corners + shrinking the
            // artwork; here the PNG renders 1:1, identical in fa/en
            Box(
                modifier = Modifier
                    .size(56.dp)                       // touch target
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,             // no ripple clipping over the artwork
                        onClick = onToggleLanguage,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(if (isFa) R.drawable.ic_lang_en else R.drawable.ic_lang_fa),
                    contentDescription = if (isFa) "English" else "فارسی",
                    modifier = Modifier.size(48.dp),   // visual size: between the two old looks
                )
            }
        }
        }  // end LTR header provider

        // update-available banner (auto-dismisses after 8s)
        androidx.compose.animation.AnimatedVisibility(visible = showUpdateBanner) {
            UpdateBanner()
        }

        Spacer(Modifier.height(12.dp))

        // ---------- scrollable body ----------
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        // ---------- power button ----------
        PowerButton(state = state, onClick = viewModel::toggle)

        Spacer(Modifier.height(16.dp))
        Text(
            text = when (state) {
                is ConnectionState.Connected -> stringResource(R.string.state_connected)
                is ConnectionState.Connecting -> stringResource(R.string.state_connecting)
                is ConnectionState.SwiftConnecting -> stringResource(R.string.state_swift_scanning)
                is ConnectionState.Disconnecting -> stringResource(R.string.state_disconnecting)
                is ConnectionState.Error -> stringResource(R.string.state_error)
                else -> stringResource(R.string.state_disconnected)
            },
            style = MaterialTheme.typography.titleMedium,
            color = when (state) {
                is ConnectionState.Connected -> StatusGreen
                is ConnectionState.Connecting -> StatusOrange
                is ConnectionState.SwiftConnecting -> StatusOrange
                is ConnectionState.Error -> StatusRed
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (state is ConnectionState.Error) {
            Text(
                (state as ConnectionState.Error).message,
                style = MaterialTheme.typography.labelSmall,
                color = StatusRed,
                textAlign = TextAlign.Center,
            )
        }

        // Swift scan feedback: "searching for route…" + thin progress bar.
        // Xray's plain Connecting… stays exactly as it was.
        if (state is ConnectionState.SwiftConnecting) {
            val pct = (state as ConnectionState.SwiftConnecting).progressPct
            val stageKey = viewModel.swiftStage.collectAsState().value
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(4.dp),
                color = StatusOrange,
                trackColor = StatusOrange.copy(alpha = 0.15f),
            )
            // Real per-stage label under the bar — driven by the core's own
            // log events, so it always matches what the bar shows.
            if (stageKey != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = swiftStageLabel(stageKey),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------- engine switch: labels + sliding toggle ----------
        // LTR so Xray Core is always LEFT and Kuva Swift always RIGHT,
        // even on Persian (RTL) layout
        val engineMode = viewModel.engineMode.collectAsState().value
        Spacer(Modifier.height(14.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    stringResource(R.string.engine_xray),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (engineMode == "xray") FontWeight.Bold else FontWeight.SemiBold,
                    color = if (engineMode == "xray") NeonSwitchBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                EngineSwitch(
                    swift = engineMode == "swift",
                    onToggle = { viewModel.setEngineMode(if (engineMode == "swift") "xray" else "swift") },
                )
                Text(
                    stringResource(R.string.engine_swift),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (engineMode == "swift") FontWeight.Bold else FontWeight.SemiBold,
                    color = if (engineMode == "swift") NeonSwitchBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // exit tiles stay blank until the next verified lookup after connecting
        if (state !is ConnectionState.Connected && exitIp != null) {
            SideEffect { viewModel.clearExitIp() }
        }

        Spacer(Modifier.height(28.dp))

        // ---------- live stats grid ----------
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard(Modifier.weight(1f), stringResource(R.string.duration),
                Formatters.duration(stats.elapsedSeconds * 1000))
            StatCard(Modifier.weight(1f), stringResource(R.string.ping),
                Formatters.ping(stats.livePingMs ?: stats.pingMs))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard(Modifier.weight(1f), stringResource(R.string.download),
                Formatters.speed(stats.downSpeedBps), sub = Formatters.bytes(stats.totalDownBytes))
            StatCard(Modifier.weight(1f), stringResource(R.string.upload),
                Formatters.speed(stats.upSpeedBps), sub = Formatters.bytes(stats.totalUpBytes))
        }

        Spacer(Modifier.height(20.dp))

        // ---------- psiphon quick card: swift mode only, one line tall ----------
        if (engineMode == "swift") {
            PsiphonCard(
                on = swiftPsiphon,
                country = swiftPsiphonCountry,
                isFa = isFa,
                onToggle = { viewModel.setSwiftPsiphonWithRestart(!swiftPsiphon) },
                onPickCountry = { showPsiphonCountrySheet = true },
            )
            Spacer(Modifier.height(10.dp))
        }

        // ---------- exit tile FIRST: tunnel exit when connected, direct IP otherwise ----------
        val connectedNow = state is ConnectionState.Connected
        val exitInfo = if (connectedNow) exitIp else directExitIp
        ExitTile(
            ip = exitInfo?.ip,
            countryIso = exitInfo?.let { it.countryCode ?: isoFromCountryName(it.country) },
            location = exitInfo?.let { info ->
                listOfNotNull(info.country, info.city).joinToString(", ").ifEmpty { null }
            },
        )

        Spacer(Modifier.height(10.dp))

        // ---------- active config card ----------
        // Swift mode has no config to show — a dedicated card replaces it
        if (engineMode == "swift") {
            SwiftCard(psiphonOn = swiftPsiphon, isFa = isFa)
        } else {
            ConfigCard(
                name = selected?.name,
                protocol = selected?.protocol?.name,
                address = selected?.displayAddress,
            )
        }

        Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusPill(state: ConnectionState) {
    val (label, color) = when (state) {
        is ConnectionState.Connected -> stringResource(R.string.state_connected) to StatusGreen
        is ConnectionState.Connecting -> stringResource(R.string.state_connecting) to StatusOrange
        else -> stringResource(R.string.state_disconnected) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PowerButton(state: ConnectionState, onClick: () -> Unit) {
    val busy = state.isBusy
    val connected = state is ConnectionState.Connected
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.92f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulseScale",
    )

    // neon palette: gray = idle, yellow = connecting, green = connected,
    // red = error only (user request: red must signal a fault, not idle)
    val baseColor = when {
        connected -> StatusGreen
        state is ConnectionState.Connecting -> StatusOrange
        state is ConnectionState.Error -> StatusRed
        else -> StatusGray
    }
    val glowColor = baseColor

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(210.dp),
    ) {
        // outer neon glow ring (breathing, always visible)
        Box(
            Modifier
                .size(210.dp * pulse)
                .clip(CircleShape)
                .background(glowColor.copy(alpha = 0.10f))
        )
        Box(
            Modifier
                .size(178.dp)
                .shadow(18.dp, CircleShape, spotColor = glowColor.copy(alpha = 0.9f))
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(glowColor.copy(alpha = 0.95f), glowColor.copy(alpha = 0.65f))
                    )
                )
                .clickable(enabled = !busy, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(color = Color(0xFF101010), modifier = Modifier.size(54.dp))
            } else {
                // power glyph
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(6.dp, 34.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF101010))
                            .align(Alignment.CenterHorizontally)
                    )
                    Spacer(Modifier.size(2.dp))
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .padding(0.dp)
                    )
                }
                PowerGlyph(color = Color(0xFF101010))
            }
        }
    }
}

@Composable
private fun PowerGlyph(color: Color) {
    // simple power icon drawn with canvas
    androidx.compose.foundation.Canvas(modifier = Modifier.size(70.dp)) {
        val stroke = 7.dp.toPx()
        val r = (size.minDimension - stroke) / 2
        val arcRect = androidx.compose.ui.geometry.Rect(
            center.x - r, center.y - r + stroke * 0.6f, center.x + r, center.y + r + stroke * 0.6f
        )
        drawArc(
            color = color,
            startAngle = -60f, sweepAngle = 300f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(arcRect.left, arcRect.top),
            size = androidx.compose.ui.geometry.Size(arcRect.width, arcRect.height),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(center.x, center.y - r - stroke * 0.4f),
            end = androidx.compose.ui.geometry.Offset(center.x, center.y + stroke * 0.2f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    sub: String? = null,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            // frosted-glass tile: brighter than background, slight translucency
            containerColor = Color.White.copy(alpha = 0.10f)
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        // centered text: stat tiles only (duration / ping / download / upload)
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center)
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun SwiftCard(psiphonOn: Boolean, isFa: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.10f)
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(stringResource(R.string.engine_swift), fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.swift_card_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            InfoRow(stringResource(R.string.swift_row_engine), if (isFa) "ماسک" else "MASQUE")
            // route label reflects the psiphon state: plain WARP, or
            // WARP arrow Psiphon when the psiphon layer is on
            InfoRow(
                stringResource(R.string.swift_row_route),
                if (isFa) {
                    // RTL: the arrow points left in Persian, right in English
                    if (psiphonOn) "وارپ ← سایفون" else "وارپ"
                } else {
                    if (psiphonOn) "WARP → Psiphon" else "WARP"
                },
            )
        }
    }
}

@Composable
private fun UpdateBanner() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1B1726))
            .border(1.dp, Gold.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color(0xFFFF3B30)),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                stringResource(R.string.update_banner_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = GoldLight,
            )
            Text(
                stringResource(R.string.update_banner_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EngineSwitch(swift: Boolean, onToggle: () -> Unit) {
    // gestures live across recompositions: always call the LATEST onToggle,
    // otherwise the closure captures the engine mode of the first frame
    // and every tap repeats that first decision
    val currentOnToggle by rememberUpdatedState(onToggle)
    val transition = animateFloatAsState(
        targetValue = if (swift) 1f else 0f,
        animationSpec = tween(220),
        label = "engineKnob",
    )
    val knob = 22.dp
    // 58 track - 4 left pad - knob - 4 right pad = 28 travel
    val travel = 28.dp
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .width(58.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.10f))
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(listOf(Gold, GoldLight)),
                shape = RoundedCornerShape(50),
            )
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { currentOnToggle() },
                ) { _, _ -> 0f }   // direction is decided on release: one gesture = one switch
            }
            .pointerInput(Unit) {
                detectTapGestures { currentOnToggle() }
            },
    ) {
        Box(
            modifier = Modifier
                .offset(x = 4.dp + travel * transition.value)
                .size(knob)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(NeonSwitchBlue, NeonSwitchBlue.copy(alpha = 0.75f))
                    )
                ),
        )
    }
}

@Composable
private fun ConfigCard(
    name: String?,
    protocol: String?,
    address: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.10f)
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            if (name == null) {
                Text(stringResource(R.string.no_server), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.no_server_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.height(10.dp))
                InfoRow(stringResource(R.string.protocol), protocol ?: "—")
                InfoRow(stringResource(R.string.address), address ?: "—")
            }
        }
    }
}

@Composable
private fun ExitTile(
    ip: String?,
    countryIso: String?,
    location: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.10f)
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            InfoRow(stringResource(R.string.exit_ip), ip ?: stringResource(R.string.unavailable))
            // same layout as InfoRow (90dp label), with the flag sized to
            // the text line height and drawn BEFORE the value
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            ) {
                Text(stringResource(R.string.location),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(90.dp))
                // bundled flag (offline) — no flagcdn.com round-trip
                CountryFlag(
                    iso = countryIso,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    location ?: stringResource(R.string.no_location),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
        }
    }
}


/** Full country name → ISO-3166 alpha-2, for endpoints that return no code. */
@Composable
private fun PsiphonCard(
    on: Boolean,
    country: String,
    isFa: Boolean,
    onToggle: () -> Unit,
    onPickCountry: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.10f)
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        // two equal halves: the location chip starts at the card's center
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.psiphon_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                // on/off sits right after the label with a 3-space gap,
                // preceded by a small status dot in the same color
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (on) Color(0xFF4CAF50) else Color(0xFFE53935))
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(if (on) R.string.psiphon_on else R.string.psiphon_off),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (on) Color(0xFF4CAF50) else Color(0xFFE53935),
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggle,
                    ),
                )
            }
            // second half begins exactly at the card center — a 3-space
            // gap inside it pushes the location chip off the midline
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(12.dp))
                Text(
                    if (country.isBlank()) "\uD83C\uDF0D " + stringResource(R.string.psiphon_auto)
                    else flagOf(country) + " " + psiphonCountryName(country, isFa),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(onClick = onPickCountry),
                )
            }
        }
    }
}

private fun flagOf(cc: String): String = try {
    cc.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
} catch (_: Exception) { "" }

private fun psiphonCountryName(cc: String, fa: Boolean = false): String = when (cc) {
    "" -> if (fa) "خودکار" else "Auto"
    "AT" -> if (fa) "اتریش" else "Austria"
    "AU" -> if (fa) "استرالیا" else "Australia"
    "BE" -> if (fa) "بلژیک" else "Belgium"
    "CA" -> if (fa) "کانادا" else "Canada"
    "CH" -> if (fa) "سوئیس" else "Switzerland"
    "CZ" -> if (fa) "چک" else "Czechia"
    "DE" -> if (fa) "آلمان" else "Germany"
    "DK" -> if (fa) "دانمارک" else "Denmark"
    "ES" -> if (fa) "اسپانیا" else "Spain"
    "FI" -> if (fa) "فنلاند" else "Finland"
    "FR" -> if (fa) "فرانسه" else "France"
    "GB" -> if (fa) "بریتانیا" else "United Kingdom"
    "ID" -> if (fa) "اندونزی" else "Indonesia"
    "IE" -> if (fa) "ایرلند" else "Ireland"
    "IN" -> if (fa) "هند" else "India"
    "IT" -> if (fa) "ایتالیا" else "Italy"
    "JP" -> if (fa) "ژاپن" else "Japan"
    "KR" -> if (fa) "کره‌ی جنوبی" else "South Korea"
    "LT" -> if (fa) "لیتوانی" else "Lithuania"
    "NL" -> if (fa) "هلند" else "Netherlands"
    "NO" -> if (fa) "نروژ" else "Norway"
    "PL" -> if (fa) "لهستان" else "Poland"
    "RO" -> if (fa) "رومانی" else "Romania"
    "RS" -> if (fa) "صربستان" else "Serbia"
    "SE" -> if (fa) "سوئد" else "Sweden"
    "SG" -> if (fa) "سنگاپور" else "Singapore"
    "US" -> if (fa) "ایالات متحده" else "United States"
    else -> cc
}

private fun isoFromCountryName(name: String?): String? {
    val byName = mapOf(
        "iran" to "IR", "germany" to "DE", "united states" to "US", "united kingdom" to "GB",
        "netherlands" to "NL", "france" to "FR", "turkey" to "TR", "russia" to "RU",
        "sweden" to "SE", "finland" to "FI", "switzerland" to "CH", "austria" to "AT",
        "poland" to "PL", "romania" to "RO", "moldova" to "MD", "ukraine" to "UA",
        "canada" to "CA", "japan" to "JP", "singapore" to "SG", "hong kong" to "HK",
        "armenia" to "AM", "georgia" to "GE", "azerbaijan" to "AZ", "uae" to "AE",
        "united arab emirates" to "AE", "spain" to "ES", "italy" to "IT", "belgium" to "BE",
        "denmark" to "DK", "ireland" to "IE", "india" to "IN", "norway" to "NO",
        "serbia" to "RS", "lithuania" to "LT", "australia" to "AU", "indonesia" to "ID",
        "south korea" to "KR", "korea" to "KR", "czechia" to "CZ", "czech republic" to "CZ",
    )
    val key = name?.trim()?.lowercase() ?: return null
    if (key.length == 2 && key.all { it in 'a'..'z' }) return key.uppercase()
    return byName[key]
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(90.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

/**
 * Maps a Swift progress stage key (from the core's own log events) to a
 * localized label. Keys come from SwiftCoreManager.ProgressStages; unknown
 * keys fall back to the generic scanning string.
 */
@Composable
private fun swiftStageLabel(key: String): String = when (key) {
    "prepare" -> stringResource(R.string.stage_prepare)
    "identity" -> stringResource(R.string.stage_identity)
    "check_cached" -> stringResource(R.string.stage_check_cached)
    "saved_confirmed" -> stringResource(R.string.stage_saved_confirmed)
    "finding_server" -> stringResource(R.string.stage_finding_server)
    "selecting" -> stringResource(R.string.stage_selecting)
    "tunnel" -> stringResource(R.string.stage_tunnel)
    "testing" -> stringResource(R.string.stage_testing)
    "final" -> stringResource(R.string.stage_final)
    else -> stringResource(R.string.state_swift_scanning)
}
