package com.kuva.vpn.ui.mycfg

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    val speedDoneFmt = stringResource(R.string.speed_test_done)
    val speedZeroMsg = stringResource(R.string.speed_test_zero)
    val noValidMsg = stringResource(R.string.toast_no_valid_config)
    val importedFmt = stringResource(R.string.toast_imported_count)
    val emptyClipMsg = stringResource(R.string.toast_empty_clipboard)
    val clipSubMsg = stringResource(R.string.clipboard_sub_added)
    LaunchedEffect(Unit) {
        viewModel.events.collect { ev ->
            when (ev) {
                is MyConfigViewModel.Event.Imported ->
                    snackbarHostState.showSnackbar(String.format(importedFmt, ev.count))
                MyConfigViewModel.Event.NothingValid ->
                    snackbarHostState.showSnackbar(noValidMsg)
                MyConfigViewModel.Event.EmptyClipboard ->
                    snackbarHostState.showSnackbar(emptyClipMsg)
                MyConfigViewModel.Event.ClipboardSub ->
                    snackbarHostState.showSnackbar(clipSubMsg)
                is MyConfigViewModel.Event.SpeedResult ->
                    snackbarHostState.showSnackbar(
                        if (ev.downKbps > 0) String.format(speedDoneFmt,
                            com.kuva.vpn.util.Formatters.speed((ev.downKbps * 1000).toLong()))
                        else speedZeroMsg
                    )
            }
        }
    }

    // undo snackbar: deleted config/subscription is held in memory for 5+ s;
    // Undo restores it (sub + its configs together), otherwise the stash is dropped
    val undoCfgFmt = stringResource(R.string.undo_deleted_config)
    val undoSubFmt = stringResource(R.string.undo_deleted_sub)
    val undoLabel = stringResource(R.string.undo)
    val lifeOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val isForeground = lifeOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    if (isForeground) LaunchedEffect(Unit) {
        viewModel.undoEvents.collect { ev ->
            val msg = when (ev) {
                MyConfigViewModel.UndoEvent.DeletedOne -> undoCfgFmt
                MyConfigViewModel.UndoEvent.DeletedSub -> undoSubFmt
            }
            snackbarHostState.currentSnackbarData?.dismiss()
            val r = snackbarHostState.showSnackbar(
                msg, actionLabel = undoLabel, duration = SnackbarDuration.Indefinite)
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

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }, containerColor = Color.Transparent) { innerPad ->
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var addMenuOpen by remember { mutableStateOf(false) }
            Button(
                onClick = { addMenuOpen = true },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.add_manual), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
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
            OutlinedButton(
                onClick = viewModel::importClipboard,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.import_clipboard), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            OutlinedButton(
                onClick = { launchQrScan() },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.import_qr), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (manualProfiles.isEmpty() && subProfiles.isEmpty() && subs.isEmpty()) {
                item {
                    Text(stringResource(R.string.no_personal),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 32.dp))
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
) {
    var expanded by remember(sub.id) { mutableStateOf(false) }
    var confirmRemove by remember(sub.id) { mutableStateOf(false) }
    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.22f)),
    ) {
        // distinct compact header (user request #2): thinner than config rows
        Column(Modifier.padding(vertical = 6.dp)) {
            // ---- header row: arrow first, then info, then compact right icons ----
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                // expand/collapse indicator, BEFORE the header name (user request)
                Text(if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(sub.name, fontWeight = FontWeight.Bold, maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium)
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
                // refresh subscription
                IconButton(onClick = onUpdate, modifier = Modifier.size(38.dp)) {
                    ActionIconLarge(R.drawable.ic_action_update)
                }
                // three-dot options: pin / edit / export / remove (like the reference menu)
                var menuOpen by remember { mutableStateOf(false) }
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
                // expiry: infinity symbol when no expiry reported
                val expireSec = sub.expireEpochSec ?: 0L
                val daysLeft = if (expireSec == 0L) Long.MAX_VALUE
                    else (expireSec - System.currentTimeMillis() / 1000) / 86400
                val expText = if (expireSec == 0L) {
                    "∞"
                } else if (daysLeft < 0) {
                    stringResource(R.string.sub_expired)
                } else {
                    stringResource(R.string.sub_expires_soon, "$daysLeft d")
                }
                Text(stringResource(R.string.sub_expiry_label, expText),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (expireSec != 0L && daysLeft < 7) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant)
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
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // expand/collapse indicator, BEFORE the header name (user request)
            Text(if (expanded) "▲" else "▼",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.section_manual_configs), fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium)
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
        }
    }
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
