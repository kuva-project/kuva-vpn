package com.kuva.vpn.ui.mycfg

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import com.kuva.vpn.ui.theme.NeonPurple
import com.kuva.vpn.ui.theme.StatusGreen
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kuva.vpn.R
import com.kuva.vpn.data.db.SubscriptionEntity
import com.kuva.vpn.domain.model.Profile
import com.kuva.vpn.ui.freecfg.ActionIconLarge
import com.kuva.vpn.ui.freecfg.ConfigRow
import com.kuva.vpn.ui.freecfg.SwipeBothWaysRow
import com.kuva.vpn.util.Formatters

/**
 * Personal configurations + subscriptions. Fully isolated from the free repo.
 * Subscription sections are collapsible cards with ping/update/delete header
 * actions; manual configs form their own collapsible section with a ping-all
 * action. All rows support swipe-left-to-delete.
 */
@Composable
fun MyConfigsScreen(
    sharedLink: String? = null,
    viewModel: MyConfigViewModel = hiltViewModel(),
) {
    val manualProfiles by viewModel.manualProfiles.collectAsStateWithLifecycle()
    val subProfiles by viewModel.subProfiles.collectAsStateWithLifecycle()
    val subs by viewModel.subscriptions.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val connState by viewModel.connectionState.collectAsStateWithLifecycle()
    val testingSpeed by viewModel.testingSpeed.collectAsStateWithLifecycle()
    val singlePinging by viewModel.singlePinging.collectAsStateWithLifecycle()
    val pingProgress by viewModel.pingProgress.collectAsStateWithLifecycle()
    val sectionPinging by viewModel.sectionPinging.collectAsStateWithLifecycle()
    val appSettings by viewModel.settings.collectAsStateWithLifecycle()
    val connectedId = (connState as? com.kuva.vpn.domain.model.ConnectionState.Connected)?.profileId
    var showAddDialog by remember { mutableStateOf(false) }
    var showSubDialog by remember { mutableStateOf(false) }
    var editSub by remember { mutableStateOf<SubscriptionEntity?>(null) }
    val builder = remember { com.kuva.vpn.core.config.builder.ShareLinkBuilder() }
    var shareLink by remember { mutableStateOf<String?>(null) }
    var shareProfile by remember { mutableStateOf<Profile?>(null) }
    var shareTitle by remember { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }
    // color hint for the status snackbar: "ok" green / "fail" yellow / null = default look
    var bannerKind by remember { mutableStateOf<String?>(null) }
    // subscriptions whose refresh is running -> spinner on the update button
    val updatingSubIds by viewModel.updatingSubIds.collectAsState()
    val speedDoneFmt = stringResource(R.string.speed_test_done)
    val speedZeroMsg = stringResource(R.string.speed_test_zero)
    val noValidMsg = stringResource(R.string.toast_no_valid_config)
    val importedFmt = stringResource(R.string.toast_imported_count)
    val subUpdatedMsg = stringResource(R.string.toast_sub_updated)
    val subAddedMsg = stringResource(R.string.toast_sub_added)
    val subFailedMsg = stringResource(R.string.toast_sub_update_failed)
    val emptyClipMsg = stringResource(R.string.toast_empty_clipboard)
    val clipSubMsg = stringResource(R.string.clipboard_sub_added)
    LaunchedEffect(Unit) {
        viewModel.events.collect { ev ->
            when (ev) {
                is MyConfigViewModel.Event.Imported -> {
                    val m = String.format(importedFmt, ev.count)
                    bannerKind = "ok"
                    snackbarHostState.showSnackbar(m)
                    bannerKind = null
                }
                MyConfigViewModel.Event.SubUpdated -> {
                    bannerKind = "ok"
                    snackbarHostState.showSnackbar(subUpdatedMsg)
                    bannerKind = null
                }
                MyConfigViewModel.Event.SubAdded -> {
                    bannerKind = "ok"
                    snackbarHostState.showSnackbar(subAddedMsg)
                    bannerKind = null
                }
                MyConfigViewModel.Event.SubUpdateFailed -> {
                    bannerKind = "fail"
                    snackbarHostState.showSnackbar(subFailedMsg)
                    bannerKind = null
                }
                MyConfigViewModel.Event.NothingValid -> {
                    bannerKind = null
                    snackbarHostState.showSnackbar(noValidMsg)
                }
                MyConfigViewModel.Event.EmptyClipboard -> {
                    bannerKind = null
                    snackbarHostState.showSnackbar(emptyClipMsg)
                }
                MyConfigViewModel.Event.ClipboardSub -> {
                    bannerKind = null
                    snackbarHostState.showSnackbar(clipSubMsg)
                }
                is MyConfigViewModel.Event.SpeedResult -> {
                    bannerKind = null
                    snackbarHostState.showSnackbar(
                        if (ev.downKbps > 0) String.format(speedDoneFmt,
                            com.kuva.vpn.util.Formatters.speed((ev.downKbps * 1000).toLong()))
                        else speedZeroMsg
                    )
                    bannerKind = null
                }
            }
        }
    }

    // undo banner: subscription delete stays 6 s, single config delete 4 s;
    // Undo restores it (sub + its configs together), otherwise the stash is dropped
    val undoCfgFmt = stringResource(R.string.undo_deleted_config)
    val undoSubFmt = stringResource(R.string.undo_deleted_sub)
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(Unit) {
        viewModel.undoEvents.collect { ev ->
            val msg = when (ev) {
                MyConfigViewModel.UndoEvent.DeletedOne -> undoCfgFmt
                MyConfigViewModel.UndoEvent.DeletedSub -> undoSubFmt
            }
            snackbarHostState.currentSnackbarData?.dismiss()
            bannerKind = "undo"
            val r = kotlinx.coroutines.withTimeoutOrNull(
                if (ev == MyConfigViewModel.UndoEvent.DeletedSub) 6000L else 4000L
            ) {
                snackbarHostState.showSnackbar(
                    msg, actionLabel = undoLabel, duration = SnackbarDuration.Indefinite)
            }
            if (r == SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.discardDelete()
        }
    }

    // real QR scanner: opens the camera, scans, feeds result into importFromLink
    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents?.trim() ?: return@rememberLauncherForActivityResult
        if (text.startsWith("http")) viewModel.addSubscriptionFromQr(text)
        else viewModel.importFromLink(text)
    }
    fun launchQrScan() {
        val opts = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan config / subscription QR")
            .setBeepEnabled(true)
            .setOrientationLocked(true)
        qrLauncher.launch(opts)
    }

    LaunchedEffect(sharedLink) {
        if (sharedLink != null) viewModel.importFromLink(sharedLink)
    }

    Scaffold(snackbarHost = { StatusSnackbarHost(snackbarHostState, bannerKind) }, containerColor = Color.Transparent) { innerPad ->
    Column(Modifier.fillMaxSize().padding(innerPad).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.nav_mine),
                style = MaterialTheme.typography.headlineMedium,
                // page title: right-aligned ONLY in Persian, left in English
                modifier = Modifier.weight(1f),
                textAlign = if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "fa")
                    androidx.compose.ui.text.style.TextAlign.End
                else androidx.compose.ui.text.style.TextAlign.Start,
            )
        }
        Text(
            stringResource(R.string.my_configs_protected),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        // same style/size/font as the free-configs screen buttons
        // ghost neon buttons — same style as the Free Configs screen (purple = primary app color)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var addMenuOpen by remember { mutableStateOf(false) }
            Button(
                onClick = { addMenuOpen = true },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = NeonPurple,
                ),
                border = BorderStroke(2.dp, Brush.linearGradient(listOf(NeonPurple, Color(0xFF7C4DFF)))),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.add_manual), color = NeonPurple, fontWeight = FontWeight.Bold, maxLines = 1)
                DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_config), color = Color.White) },
                        onClick = { addMenuOpen = false; showAddDialog = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_sub), color = Color.White) },
                        onClick = { addMenuOpen = false; showSubDialog = true },
                    )
                }
            }
            Button(
                onClick = viewModel::importClipboard,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = NeonPurple,
                ),
                border = BorderStroke(2.dp, Brush.linearGradient(listOf(NeonPurple, Color(0xFF7C4DFF)))),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.import_clipboard), color = NeonPurple, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Button(
                onClick = { launchQrScan() },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = NeonPurple,
                ),
                border = BorderStroke(2.dp, Brush.linearGradient(listOf(NeonPurple, Color(0xFF7C4DFF)))),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.import_qr), color = NeonPurple, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (manualProfiles.isEmpty() && subProfiles.isEmpty() && subs.isEmpty()) {
                item {
                    // real empty state: faint neon QR icon + guide line
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.QrCode2,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = NeonPurple.copy(alpha = 0.45f),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.no_personal),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ---------- manual configs FIRST (always visible, even when empty) ----------
            item(key = "manual-header") {
                ManualSection(
                    profiles = manualProfiles,
                    selectedId = selectedId,
                    connectedId = connectedId,
                    testingIds = testingSpeed,
                    pingingAll = sectionPinging == -1L,
                    rowPinging = singlePinging,
                    onSelect = { viewModel.select(it) },
                    onDelete = { viewModel.delete(it) },
                    onTestSpeed = { viewModel.testSpeed(it) },
                    onFavorite = { id, fav -> viewModel.toggleFavorite(id, fav) },
                    onPingAll = { viewModel.testManualPings() },
                    onPingRow = { viewModel.pingSingle(it) },
                    onShareRow = { p -> shareTitle = p.name; shareProfile = p; shareLink = builder.build(p) },
                )
            }

            // ---------- subscription sections (collapsible, ping in header) ----------
            items(subs, key = { "sub-${it.id}" }) { sub ->
                SubscriptionSection(
                    sub = sub,
                    owned = subProfiles.filter { it.subscriptionId == sub.id },
                    selectedId = selectedId,
                    connectedId = connectedId,
                    testingIds = testingSpeed,
                    pingingSub = sectionPinging == sub.id,
                    rowPinging = singlePinging,
                    onSelect = { viewModel.select(it) },
                    onDelete = { viewModel.delete(it) },
                    onTestSpeed = { viewModel.testSpeed(it) },
                    onFavorite = { id, fav -> viewModel.toggleFavorite(id, fav) },
                    onPingAll = { viewModel.testSubPings(sub.id) },
                    onPingRow = { viewModel.pingSingle(it) },
                    onUpdate = { viewModel.refreshSubscription(sub.id) },
                    updating = sub.id in updatingSubIds,
                    onDeleteSub = { viewModel.deleteSubscription(sub.id) },
                    onEditSub = { editSub = sub },
                    onExportSub = {
                        shareTitle = sub.name
                        viewModel.subUrl(sub.id) { url -> if (url != null) shareLink = url }
                    },
                    onTogglePin = { viewModel.setSubPinned(sub.id, !sub.pinned) },
                    onShareRow = { p -> shareTitle = p.name; shareProfile = p; shareLink = builder.build(p) },
                )
            }

            item(key = "bottom-space") { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showAddDialog) AddConfigDialog(
        onDismiss = { showAddDialog = false },
        onConfirm = { link -> viewModel.importFromLink(link); showAddDialog = false },
    )
    if (showSubDialog) AddSubscriptionDialog(
        onDismiss = { showSubDialog = false },
        onConfirm = { name, url -> viewModel.addSubscription(name, url); showSubDialog = false },
    )
    editSub?.let { sub ->
        EditSubDialog(
            sub = sub,
            currentUrl = viewModel.subUrlOf(sub),
            onDismiss = { editSub = null },
            onConfirm = { name, url ->
                viewModel.updateSubscription(sub.id, name, url)
                editSub = null
            },
        )
    }
    shareLink?.let { link ->
        com.kuva.vpn.ui.share.QrShareDialog(
            link = link,
            title = shareTitle,
            onDismiss = {
                shareLink = null
                shareProfile = null
            },
            exportJson = shareProfile?.let { p ->
                {
                    com.kuva.vpn.core.config.XrayConfigBuilder.build(
                        p,
                        com.kuva.vpn.core.config.XrayConfigBuilder.Options(
                            socksPort = appSettings.socksPort,
                            httpPort = appSettings.socksPort + 1,
                            dnsServers = appSettings.effectiveDnsServers(),
                            bypassLan = appSettings.bypassLan,
                        )
                    )
                }
            },
        )
    }
    }

}

// ---------------------------------------------------------------- subscription

@Composable
private fun SubscriptionSection(
    sub: SubscriptionEntity,
    owned: List<Profile>,
    selectedId: Long?,
    connectedId: Long? = null,
    testingIds: Set<Long> = emptySet(),
    pingingSub: Boolean = false,        // batch ping of THIS subscription is running
    rowPinging: Set<Long> = emptySet(), // rows running a single swipe ping
    onSelect: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onTestSpeed: (Long) -> Unit,
    onFavorite: (Long, Boolean) -> Unit,
    onPingAll: () -> Unit,
    onPingRow: (Long) -> Unit = {},
    onUpdate: () -> Unit,
    onDeleteSub: () -> Unit,
    onEditSub: () -> Unit = {},
    onExportSub: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onShareRow: (Profile) -> Unit = {},
    updating: Boolean = false,           // refresh fetch running -> spinner on the update button
) {
    var expanded by remember(sub.id) { mutableStateOf(false) }
    var confirmRemove by remember(sub.id) { mutableStateOf(false) }
    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.22f)),
    ) {
        // EDGE-BAR (removable): Box wrapper so the strip can span the card height
        Box(Modifier.fillMaxWidth()) {
        // EDGE-BAR strip (delete this Block to revert)
        Box(
            Modifier
                .matchParentSize()
                .clip(MaterialTheme.shapes.medium)
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .padding(vertical = 6.dp, horizontal = 0.dp)
                    .width(3.dp)
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFFB026FF), Color(0xFF7C4DFF))),
                        RoundedCornerShape(2.dp)
                    )
            )
        }
        // distinct compact header (user request #2): thinner than config rows
        Column(Modifier.padding(vertical = 6.dp)) {
            // ---- header row: arrow first, then info, then compact right icons ----
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                // expand/collapse indicator, BEFORE the header name (user request)
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                val chevRot by animateFloatAsState(
                    // expanded: point down; collapsed: point toward the text
                    // start edge — right in English, left in Persian
                    targetValue = if (expanded) 0f else if (rtl) 90f else -90f,
                    animationSpec = tween(200),
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp).rotate(chevRot),
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(sub.name, fontWeight = FontWeight.Bold, maxLines = 1,
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 16.sp)  // header size (user request)
                        // green pulsing dot: this subscription hosts the connected config
                        if (owned.any { it.id == connectedId }) {
                            Spacer(Modifier.width(8.dp))
                            ConnectedDot()
                        }
                    }
                    Text(stringResource(R.string.sub_count, owned.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // ping all configs of this sub (spinner while the batch runs)
                IconButton(onClick = onPingAll, modifier = Modifier.size(38.dp)) {
                    if (pingingSub) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Image(painterResource(R.drawable.ic_action_ping),
                        contentDescription = null, modifier = Modifier.size(26.dp))
                }
                // refresh subscription (spinner while the fetch runs)
                IconButton(
                    onClick = onUpdate,
                    modifier = Modifier.size(38.dp),
                    enabled = !updating,
                ) {
                    if (updating) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    else ActionIconLarge(R.drawable.ic_action_update)
                }
                // three-dot options: pin / edit / export / remove (like the reference menu)
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more_options),
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (sub.pinned) R.string.unpin_sub else R.string.pin_sub), color = Color.White) },
                            leadingIcon = { Image(painterResource(R.drawable.ic_pin_white), contentDescription = null, modifier = Modifier.size(26.dp)) },
                            onClick = { menuOpen = false; onTogglePin() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit_sub), color = Color.White) },
                            leadingIcon = { Image(painterResource(R.drawable.ic_edit_white), contentDescription = null, modifier = Modifier.size(26.dp)) },
                            onClick = { menuOpen = false; onEditSub() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_sub), color = Color.White) },
                            leadingIcon = { Image(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(26.dp)) },
                            onClick = { menuOpen = false; onExportSub() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.remove_sub), color = Color.White) },
                            leadingIcon = { Image(painterResource(R.drawable.ic_action_delete), contentDescription = null, modifier = Modifier.size(26.dp)) },
                            onClick = { menuOpen = false; confirmRemove = true },
                        )
                    }
                }
            }
            // ---- metadata: ALWAYS visible; only the arrow shows drawer state ----
            Column(Modifier.padding(horizontal = 10.dp)) {
                sub.lastUpdated?.let {
                    val t = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(it))
                    Text(stringResource(R.string.sub_last_update, t),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // traffic: used / total; infinite when no quota reported
                val up = sub.uploadBytes ?: 0L
                val down = sub.downloadBytes ?: 0L
                val used = up + down
                val traffic = if (sub.totalBytes == null || sub.totalBytes == 0L) {
                    stringResource(R.string.sub_traffic_infinite, Formatters.bytes(used))
                } else {
                    "${Formatters.bytes(used)} / ${Formatters.bytes(sub.totalBytes)}"
                }
                Text(traffic, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // usage bar only when the subscription reports a real quota —
                // infinite subs keep the plain text (no misleading bar);
                // gold past 90% as a "quota almost gone" warning
                val quotaTotal = sub.totalBytes ?: 0L
                if (quotaTotal > 0L) {
                    val frac = (used.toFloat() / quotaTotal.toFloat()).coerceIn(0f, 1f)
                    // neon gradient bar: purple→cyan normally, sharp neon yellow past 90%
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(frac)
                                .background(
                                    if (frac >= 0.9f)
                                        Brush.horizontalGradient(listOf(Color(0xFFFFEA00), Color(0xFFFFC400)))
                                    else
                                        Brush.horizontalGradient(listOf(Color(0xFFB026FF), Color(0xFF00E5FF))),
                                    RoundedCornerShape(50)
                                )
                        )
                    }
                }
                // expiry: "label: infinity" when no expiry reported;
                // with a real expiry show only the expiry date (no label)
                val expireSec = sub.expireEpochSec ?: 0L
                val daysLeft = if (expireSec == 0L) Long.MAX_VALUE
                    else (expireSec - System.currentTimeMillis() / 1000) / 86400
                val expText = if (expireSec == 0L) {
                    "∞"
                } else if (daysLeft < 0) {
                    stringResource(R.string.sub_expired)
                } else {
                    // the actual expiry date instead of remaining days
                    java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault())
                        .format(java.util.Date(expireSec * 1000))
                }
                // ONE label ("Expiry:" / "انقضا:") always, followed by
                // the value: infinity, the expiry date, or "expired"
                val expColor = if (expireSec != 0L && daysLeft < 7) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                // label and value as TWO Texts in one Row — the bidi
                // algorithm can no longer push the infinity glyph to
                // its own line in Persian
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sub_expiry_label_only),
                        style = MaterialTheme.typography.labelSmall, softWrap = false, color = expColor)
                    Spacer(Modifier.width(4.dp))
                    Text(expText,
                        style = MaterialTheme.typography.labelSmall, softWrap = false, color = expColor)
                }
                sub.lastError?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                if (owned.isEmpty()) {
                    Text(stringResource(R.string.sub_empty),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        } // EDGE-BAR Box wrapper (delete with the strip Block above)
    }
    // destructive actions always ask first
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.remove_sub)) },
            text = { Text(sub.name) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onDeleteSub() }) {
                    Text(stringResource(R.string.remove_sub), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // ---- configs of this subscription, swipe-to-delete ----
    if (expanded) {
        Spacer(Modifier.height(4.dp))
        owned.forEach { p ->
            SwipeBothWaysRow(
                onDelete = { onDelete(p.id) },
                onPing = { onPingRow(p.id) },
            ) {
                ConfigRow(
                    profile = p,
                    selected = p.id == selectedId,
                    connected = p.id == connectedId,
                    onClick = { onSelect(p.id) },
                    onDelete = { onDelete(p.id) },
                    onTestSpeed = { onTestSpeed(p.id) },
                    onFavorite = { onFavorite(p.id, !p.isFavorite) },
                    onShare = { onShareRow(p) },
                    testing = p.id in testingIds,
                    pinging = p.id in rowPinging,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

// -------------------------------------------------------------------- manual

@Composable
private fun ManualSection(
    profiles: List<Profile>,
    selectedId: Long?,
    connectedId: Long? = null,
    testingIds: Set<Long> = emptySet(),
    pingingAll: Boolean = false,        // batch ping of the manual section is running
    rowPinging: Set<Long> = emptySet(), // rows running a single swipe ping
    onSelect: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onTestSpeed: (Long) -> Unit,
    onFavorite: (Long, Boolean) -> Unit,
    onPingAll: () -> Unit,
    onPingRow: (Long) -> Unit = {},
    onShareRow: (Profile) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(true) }
    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.22f)),
    ) {
        // EDGE-BAR (removable): Box wrapper so the strip can span the card height
        Box(Modifier.fillMaxWidth()) {
        // EDGE-BAR strip (delete this Block to revert)
        Box(
            Modifier
                .matchParentSize()
                .clip(MaterialTheme.shapes.medium)
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .padding(vertical = 6.dp, horizontal = 0.dp)
                    .width(3.dp)
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFFB026FF), Color(0xFF7C4DFF))),
                        RoundedCornerShape(2.dp)
                    )
            )
        }
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // expand/collapse indicator, BEFORE the header name (user request)
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            val chevRot by animateFloatAsState(
                // expanded: point down; collapsed: point toward the text
                // start edge — right in English, left in Persian
                targetValue = if (expanded) 0f else if (rtl) 90f else -90f,
                animationSpec = tween(200),
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp).rotate(chevRot),
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.section_manual_configs), fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 16.sp)  // same size as the subscription headers
                    // green pulsing dot: this section hosts the connected config
                    if (profiles.any { it.id == connectedId }) {
                        Spacer(Modifier.width(8.dp))
                        ConnectedDot()
                    }
                }
                Text(stringResource(R.string.sub_count, profiles.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // ping all manual configs (spinner while the batch runs)
            IconButton(onClick = onPingAll, modifier = Modifier.size(38.dp)) {
                if (pingingAll) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                else Image(painterResource(R.drawable.ic_action_ping),
                    contentDescription = null, modifier = Modifier.size(26.dp))
            }
        }
        } // EDGE-BAR Box wrapper (delete with the strip Block above)
    }
    if (expanded) {
        Spacer(Modifier.height(4.dp))
        if (profiles.isEmpty()) {
            Text(
                stringResource(R.string.no_personal),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        }
        profiles.forEach { p ->
            SwipeBothWaysRow(
                onDelete = { onDelete(p.id) },
                onPing = { onPingRow(p.id) },
            ) {
                ConfigRow(
                    profile = p,
                    selected = p.id == selectedId,
                    connected = p.id == connectedId,
                    onClick = { onSelect(p.id) },
                    onDelete = { onDelete(p.id) },
                    onTestSpeed = { onTestSpeed(p.id) },
                    onFavorite = { onFavorite(p.id, !p.isFavorite) },
                    onShare = { onShareRow(p) },
                    testing = p.id in testingIds,
                    pinging = p.id in rowPinging,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** Small pulsing green dot for section headers hosting the connected config. */
@Composable
private fun ConnectedDot() {
    val pulse by rememberInfiniteTransition(label = "dotPulse").animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "dotAlpha",
    )
    Box(
        Modifier
            .size(10.dp)
            .background(StatusGreen.copy(alpha = 0.35f + 0.65f * pulse), CircleShape)
    )
}

// ------------------------------------------------------------------- dialogs

@Composable
private fun AddConfigDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_config)) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("vless://  vmess://  trojan://  ss://") },
                modifier = Modifier.fillMaxWidth(), minLines = 3,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun AddSubscriptionDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_sub)) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.manual_name)) })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = url, onValueChange = { url = it },
                    label = { Text("https://…") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, url) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Edit an existing subscription: rename and/or replace the link ("-"/blank keeps the link). */
@Composable
private fun EditSubDialog(
    sub: SubscriptionEntity,
    currentUrl: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(sub.name) }
    var url by remember { mutableStateOf(currentUrl ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_sub)) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.manual_name)) }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = url, onValueChange = { url = it },
                    label = { Text(stringResource(R.string.sub_url)) },
                    placeholder = { Text("https://…") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, url.ifBlank { "-" }) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

// single status banner at the bottom of the screen: green success / yellow failure;
// messages without a color hint (speed test, clipboard, undo) keep the default snackbar look
@Composable
private fun StatusSnackbarHost(
    hostState: SnackbarHostState,
    kind: String?,
) {
    SnackbarHost(hostState) { data ->
        // full-area box so a wrap-content banner can sit centered at the bottom
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        if (kind == "undo") {
            // delete/undo banner: dark neutral card, 25% narrower than the colored
            // ones, white text, purple Undo action
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp)
                    .widthIn(max = 315.dp)
                    .background(
                        Brush.horizontalGradient(listOf(Color(0xFF2A2A2A), Color(0xFF1E1E1E))),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "\u2715",
                    color = Color(0xFFFF5252),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    data.visuals.message,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(10.dp))
                androidx.compose.material3.TextButton(
                    onClick = { data.performAction() },
                ) {
                    Text(
                        data.visuals.actionLabel ?: "",
                        color = Color(0xFFB026FF),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        } else if (kind == null) {
            androidx.compose.material3.Snackbar(data)
        } else {
            val ok = kind == "ok"
            val fg = if (ok) Color(0xFF003300) else Color(0xFF332600)
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp)
                    // banner hugs its text instead of stretching full width
                    .widthIn(max = 420.dp)
                    .background(
                        if (ok) Brush.horizontalGradient(listOf(Color(0xFF00E676), Color(0xFF00C853)))
                        else Brush.horizontalGradient(listOf(Color(0xFFFFEB3B), Color(0xFFFFC107))),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (ok) "\u2714" else "\u26A0",
                    color = fg,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    data.visuals.message,
                    color = fg,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        }
    }
}
