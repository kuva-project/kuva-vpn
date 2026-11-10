package com.kuva.vpn.ui.freecfg

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.ui.composed
import androidx.compose.material3.TabPosition
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kuva.vpn.R
import com.kuva.vpn.domain.model.Profile
import com.kuva.vpn.util.Formatters
import kotlin.math.roundToInt

/**
 * Free configs screen with two panes:
 *  - SEARCH (workshop): find configs, ping them, move the alive ones to the
 *    vault (dead ones are deleted in the same action). Rows are display-only.
 *  - VAULT: the real storage. Tap = select for the dashboard, per-row
 *    share/speed/delete buttons, swipe left = delete, swipe right = ping.
 */
@Composable
fun FreeConfigsScreen(viewModel: FreeConfigViewModel = hiltViewModel()) {
    val searchProfiles by viewModel.searchProfiles.collectAsStateWithLifecycle()
    val vaultProfiles by viewModel.vaultProfiles.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val connState by viewModel.connectionState.collectAsStateWithLifecycle()
    val testingSpeed by viewModel.testingSpeed.collectAsStateWithLifecycle()
    val singlePinging by viewModel.singlePinging.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val speedDoneFmt = stringResource(R.string.speed_test_done)
    val speedZeroMsg = stringResource(R.string.speed_test_zero)
    LaunchedEffect(Unit) {
        viewModel.speedEvents.collect { ev ->
            when (ev) {
                is FreeConfigViewModel.SpeedEvent.Done ->
                    snackbarHostState.showSnackbar(
                        if (ev.downKbps > 0) String.format(speedDoneFmt,
                            com.kuva.vpn.util.Formatters.speed((ev.downKbps * 1000).toLong()))
                        else speedZeroMsg
                    )
            }
        }
    }
    // undo snackbar: delete is held in memory; Undo restores it, otherwise dropped
    val undoFmt = stringResource(R.string.undo_deleted_config)
    val undoLabel = stringResource(R.string.undo)
    val lifeOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val isForeground = lifeOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    if (isForeground) LaunchedEffect(Unit) {
        viewModel.undoEvents.collect { ev ->
            when (ev) {
                FreeConfigViewModel.UndoEvent.DeletedOne -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val r = snackbarHostState.showSnackbar(
                        undoFmt, actionLabel = undoLabel, duration = SnackbarDuration.Indefinite)
                    if (r == SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.discardUndo()
                }
            }
        }
    }
    val connectedId = (connState as? com.kuva.vpn.domain.model.ConnectionState.Connected)?.profileId
    val builder = remember { com.kuva.vpn.core.config.builder.ShareLinkBuilder() }
    var shareLink by remember { mutableStateOf<String?>(null) }
    var showSources by remember { mutableStateOf(false) }
    val tab by viewModel.tab.collectAsStateWithLifecycle()

    if (showSources) {
        SourcesDialog(viewModel = viewModel, onDismiss = { showSources = false })
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }, containerColor = Color.Transparent) { innerPad ->
    Column(Modifier.fillMaxSize().padding(innerPad).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.nav_free),
            style = MaterialTheme.typography.headlineMedium,
            // page title: right-aligned ONLY in Persian, left in English
            modifier = Modifier.fillMaxWidth(),
            textAlign = if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "fa")
                androidx.compose.ui.text.style.TextAlign.End
            else androidx.compose.ui.text.style.TextAlign.Start,
        )
        Spacer(Modifier.height(8.dp))

        // ---- pane switch (neon orange labels + indicator) ----
        val neon = Color(0xFFFF7A1A)
        TabRow(
            selectedTabIndex = tab,
            containerColor = Color.Transparent,
            divider = {},
            indicator = { tabPositions ->
                Box(
                    Modifier
                        .tabIndicatorOffsetCompat(tabPositions[tab])
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(neon),
                )
            },
        ) {
            Tab(
                selected = tab == 0,
                onClick = { viewModel.setTab(0) },
                text = { Text(
                    "${stringResource(R.string.tab_search)} (${searchProfiles.size})",
                    color = if (tab == 0) neon else neon.copy(alpha = 0.55f),
                ) },
            )
            Tab(
                selected = tab == 1,
                onClick = { viewModel.setTab(1) },
                text = { Text(
                    "${stringResource(R.string.tab_vault)} (${vaultProfiles.size})",
                    color = if (tab == 1) neon else neon.copy(alpha = 0.55f),
                ) },
            )
        }
        Spacer(Modifier.height(8.dp))

        // ---- live progress line, shown ONLY while search/ping is running ----
        if (progress.running) {
            if (progress.phase == "ping") {
                // part 1: plain remaining text; part 2: (alive green / dead red)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.progress_pinging, progress.total - progress.done),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "  (",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${progress.alive}",   // alive — green, label not needed
                        style = MaterialTheme.typography.labelSmall,
                        color = com.kuva.vpn.ui.theme.StatusGreen,
                    )
                    Text(
                        " / ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${progress.dead}",    // dead — red, label not needed
                        style = MaterialTheme.typography.labelSmall,
                        color = com.kuva.vpn.ui.theme.StatusRed,
                    )
                    Text(
                        ")",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.search_found, progress.found),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        // list states: after a ping (or search) run ends, jump to the top so
        // the fastest config is visible without manual scrolling
        val searchListState = rememberLazyListState()
        val vaultListState = rememberLazyListState()
        LaunchedEffect(progress.running) {
            if (!progress.running) {
                searchListState.scrollToItem(0)
                vaultListState.scrollToItem(0)
            }
        }

        // run flags for BOTH panes: the vault ping button also turns green
        // with a spinner while its run is active (user request #4)
        val searching = progress.running && progress.phase == "search"
        val pinging = progress.running && progress.phase == "ping"
        // owner-aware: only the pane that STARTED the ping run shows the
        // green Stop state; the other pane's ping button is disabled so two
        // ping runs can never execute in parallel (they would slow each other)
        val myPing = pinging && progress.runOwner == "search"
        val vaultPing = pinging && progress.runOwner == "vault"
        if (tab == 0) {
            // ================= SEARCH PANE =================
            // ---- action area: two tidy rows, one job per button ----
            // row 1: search | sources | clear list
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    // pressing Search opens the source picker popup;
                    // its confirm button starts the actual search
                    onClick = { showSources = true },
                    enabled = !progress.running,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB026FF),   // neon purple
                        contentColor = Color(0xFF101010),
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    if (searching) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.searching), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                    } else {
                        Text(stringResource(R.string.search_free), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                OutlinedButton(
                    onClick = { viewModel.clearResults() },
                    enabled = !progress.running,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.clear_results), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            // row 2: ping (tap again = stop) | move alive to vault
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { if (myPing) viewModel.cancelPing() else viewModel.testSearchPings() },
                    // disabled while ANY other run (search / vault / mine) is alive
                    enabled = !searching && (!progress.running || progress.runOwner == "search"),
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    colors = if (myPing) ButtonDefaults.buttonColors(
                        containerColor = com.kuva.vpn.ui.theme.StatusGreen,  // green while running
                        contentColor = Color(0xFF101010),
                    ) else ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    if (myPing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                            color = Color(0xFF101010),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.stop_ping), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                    } else {
                        Text(stringResource(R.string.test_ping), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                OutlinedButton(
                    onClick = viewModel::transferToVault,
                    enabled = !progress.running,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(stringResource(R.string.transfer_alive), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }

            Spacer(Modifier.height(8.dp))
            LazyColumn(
                state = searchListState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (searchProfiles.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.no_free),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 32.dp),
                        )
                    }
                }
                items(searchProfiles, key = { it.id }) { p ->
                    // display-only row: no buttons, no swipe (user request)
                    SearchRow(profile = p)
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        } else {
            // ================= VAULT PANE =================
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { if (vaultPing) viewModel.cancelPing() else viewModel.testVaultPings() },
                    // disabled while ANY other run (search / vault / mine) is alive
                    enabled = !searching && (!progress.running || progress.runOwner == "vault"),
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    colors = if (vaultPing) ButtonDefaults.buttonColors(
                        containerColor = com.kuva.vpn.ui.theme.StatusGreen,  // green while running
                        contentColor = Color(0xFF101010),
                    ) else ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    if (vaultPing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                            color = Color(0xFF101010),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.stop_ping), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                    } else {
                        Text(stringResource(R.string.ping), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                OutlinedButton(
                    onClick = viewModel::deleteDeadVault,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.delete_dead), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }

            Spacer(Modifier.height(8.dp))
            LazyColumn(
                state = vaultListState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (vaultProfiles.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.vault_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 32.dp),
                        )
                    }
                }
                items(vaultProfiles, key = { it.id }) { p ->
                    SwipeBothWaysRow(
                        onDelete = { viewModel.delete(p.id) },
                        onPing = { viewModel.pingSingle(p.id) },
                    ) {
                        ConfigRow(
                            profile = p,
                            selected = p.id == selectedId,
                            connected = p.id == connectedId,
                            testing = p.id in testingSpeed,
                            pinging = p.id in singlePinging,
                            onClick = { viewModel.select(p.id) },
                            onDelete = { viewModel.delete(p.id) },
                            onTestSpeed = { viewModel.testSpeed(p.id) },
                            onFavorite = { viewModel.toggleFavorite(p.id, !p.isFavorite) },
                            onShare = { shareLink = builder.build(p) },
                        )
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    shareLink?.let { link ->
        com.kuva.vpn.ui.share.QrShareDialog(
            link = link,
            title = stringResource(R.string.share_config),
            onDismiss = { shareLink = null },
        )
    }
}

}

/** Display-only row for the search pane: name, protocol/address, ping, source. */

@Composable
private fun SearchRow(profile: Profile) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.10f)),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${profile.protocol.name} • ${profile.displayAddress}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Row {
                    Text(
                        Formatters.ping(profile.pingMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            profile.pingMs == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            profile.pingMs < 0 -> MaterialTheme.colorScheme.error
                            else -> com.kuva.vpn.ui.theme.StatusGreen
                        },
                    )
                    profile.source?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Two-direction swipe row for the VAULT: left past ~96dp fires onDelete,
 * right past ~96dp fires onPing. Spring back on release either way.
 */
@Composable
fun SwipeBothWaysRow(
    onDelete: () -> Unit,
    onPing: () -> Unit,
    content: @Composable () -> Unit,
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val threshold = remember(density) { with(density) { 96.dp.toPx() } }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        // red delete hint BEHIND the row, visible while dragging left
        Box(
            Modifier
                .matchParentSize()
                .alpha(if (offsetX < 0f) 1f else 0f)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.CenterEnd,
        ) {
            ActionIcon(R.drawable.ic_action_delete)
            Spacer(Modifier.width(24.dp))
        }
        // green ping hint, visible while dragging right
        Box(
            Modifier
                .matchParentSize()
                .alpha(if (offsetX > 0f) 1f else 0f)
                .background(com.kuva.vpn.ui.theme.StatusGreen.copy(alpha = 0.25f)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Spacer(Modifier.width(24.dp))
            Image(painterResource(R.drawable.ic_action_ping), contentDescription = null,
                modifier = Modifier.size(26.dp))
        }
        Box(
            Modifier
                .offset { IntOffset(x = offsetX.roundToInt(), y = 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < -threshold) onDelete()
                            else if (offsetX > threshold) onPing()
                            offsetX = 0f
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount
                    }
                },
        ) {
            content()
        }
    }
}

@Composable
private fun SourcesDialog(
    viewModel: FreeConfigViewModel,
    onDismiss: () -> Unit,
) {
    val catalog = viewModel.sourceCatalog
    val selNums = viewModel.selNums.collectAsStateWithLifecycle().value
    // SINGLE selection: only one source per search run
    var picked by remember { mutableStateOf(selNums.firstOrNull() ?: catalog.firstOrNull()?.number ?: 1) }
    var custom by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sources_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                catalog.forEach { s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { picked = s.number },
                    ) {
                        RadioButton(
                            selected = s.number == picked,
                            onClick = { picked = s.number },
                        )
                        Column {
                            Text("[${s.number}] ${s.label}", fontWeight = FontWeight.SemiBold)
                            Text(
                                s.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text(stringResource(R.string.sources_custom)) },
                    placeholder = { Text(stringResource(R.string.sources_custom_hint)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val urls = custom.split(',', '\n').map { it.trim() }.filter { it.length > 8 }
                if (urls.isNotEmpty()) {
                    // custom link(s) win over the catalog selection
                    viewModel.setSourceSelection(emptyList(), urls)
                } else {
                    viewModel.setSourceSelection(listOf(picked), emptyList())
                }
                // confirm = pick the source AND start the search right away
                viewModel.searchFree()
                onDismiss()
            }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
fun ConfigRow(
    profile: Profile,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onTestSpeed: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit = {},
    connected: Boolean = false,
    testing: Boolean = false,
    pinging: Boolean = false,   // single ping in progress: spinner instead of the ping value
) {
    // animated neon border: blue when selected, green when the tunnel is on it
    val pulse by rememberInfiniteTransition(label = "neonPulse").animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "neonAlpha",
    )
    val neonBlue = listOf(Color(0xFF00E5FF), Color(0xFF2979FF))
    val neonGreen = listOf(Color(0xFF39FF14), Color(0xFF00C853))
    val borderBrush = when {
        connected -> Brush.linearGradient(neonGreen.map { it.copy(alpha = 0.45f + 0.55f * pulse) })
        selected  -> Brush.linearGradient(neonBlue.map { it.copy(alpha = 0.45f + 0.55f * pulse) })
        else -> null
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected || connected) Color.White.copy(alpha = 0.22f)
            else Color.White.copy(alpha = 0.10f)
        ),
        shape = MaterialTheme.shapes.medium,
        border = borderBrush?.let { BorderStroke(2.dp, it) },
    ) {
        // compact row: smaller padding (user request #2)
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile.name, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pinging) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else Text(
                        Formatters.ping(profile.pingMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            profile.pingMs == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            profile.pingMs < 0 -> MaterialTheme.colorScheme.error
                            else -> com.kuva.vpn.ui.theme.StatusGreen  // neon green ms
                        },
                    )
                    profile.speedDownKbps?.let {
                        Spacer(Modifier.width(6.dp))
                        Text("↓ ${Formatters.speed((it * 1000).toLong())}",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            // right-packed action icons, smaller + closer (user request #1)
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                IconButton(onClick = onShare, modifier = Modifier.size(30.dp)) {
                    ActionIcon(R.drawable.ic_share)
                }
                IconButton(onClick = onTestSpeed, modifier = Modifier.size(30.dp)) {
                    if (testing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else ActionIcon(R.drawable.ic_action_speed)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                    ActionIcon(R.drawable.ic_action_delete)
                }
            }
        }
    }
}

/** Small raster action icon (user-designed squircle PNGs). */
@Composable
fun ActionIcon(resId: Int) {
    // plain raster icon, untouched artwork (no saturation filter)
    Image(
        painter = painterResource(resId),
        contentDescription = null,
        modifier = Modifier.size(26.dp),
    )
}

/** Material3 in this BOM lacks Modifier.tabIndicatorOffset as an extension;
 *  TabRowDefaults still exposes the offset function as a plain method. */
private fun Modifier.tabIndicatorOffsetCompat(currentTabPosition: TabPosition): Modifier =
    composed {
        val currentTabWidth by animateDpAsState(
            targetValue = currentTabPosition.width,
            animationSpec = tween(250, easing = FastOutSlowInEasing),
            label = "tabWidth",
        )
        val indicatorOffset by animateDpAsState(
            targetValue = currentTabPosition.left,
            animationSpec = tween(250, easing = FastOutSlowInEasing),
            label = "tabOffset",
        )
        this
            .wrapContentSize(Alignment.BottomStart)
            .offset(x = indicatorOffset)
            .width(currentTabWidth)
    }

/** Larger action icon for subscription headers. */
@Composable
fun ActionIconLarge(resId: Int) {
    Image(
        painter = painterResource(resId),
        contentDescription = null,
        modifier = Modifier.size(30.dp),
    )
}

/**
 * Swipe-left-to-delete wrapper using stable pointerInput gestures.
 * Dragging a row left past ~96dp fires onDelete and the row springs back.
 * (Still exported — MyConfigsScreen uses it.)
 */
@Composable
fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val threshold = remember(density) { with(density) { 96.dp.toPx() } }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        // red delete hint lives BEHIND the row; invisible until the user drags left
        Box(
            Modifier
                .matchParentSize()
                .alpha(if (offsetX < 0f) 1f else 0f)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.CenterEnd,
        ) {
            ActionIcon(R.drawable.ic_action_delete)
            Spacer(Modifier.width(24.dp))
        }
        Box(
            Modifier
                .offset { IntOffset(x = offsetX.roundToInt(), y = 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < -threshold) onDelete()
                            offsetX = 0f
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        val next = offsetX + dragAmount
                        if (next <= 0f) offsetX = next
                    }
                },
        ) {
            content()
        }
    }
}
