package com.kuva.vpn.ui.mycfg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kuva.vpn.core.config.parser.ShareLinkParser
import com.kuva.vpn.domain.model.Profile
import com.kuva.vpn.domain.repository.ProfileRepository
import com.kuva.vpn.domain.usecase.FreeConfigUseCase
import com.kuva.vpn.domain.usecase.SubscriptionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.kuva.vpn.core.diagnostics.ConfigTester
import javax.inject.Inject

@HiltViewModel
class MyConfigViewModel @Inject constructor(
    private val repo: ProfileRepository,
    private val subUseCase: SubscriptionUseCase,
    private val parser: ShareLinkParser,
    private val freeUseCase: FreeConfigUseCase,
    private val connRepo: com.kuva.vpn.domain.repository.ConnectionRepository,
    private val tester: com.kuva.vpn.core.diagnostics.ConfigTester,
    private val settingsRepo: com.kuva.vpn.data.settings.SettingsRepository,
) : ViewModel() {

    /** current settings snapshot flow (used by config JSON export) */
    val settings: StateFlow<com.kuva.vpn.data.settings.AppSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly,
            com.kuva.vpn.data.settings.AppSettings())

    /** raw tunnel state; screens resolve the connected profile by name */
    val connectionState: StateFlow<com.kuva.vpn.domain.model.ConnectionState> = connRepo.state


    /** ALWAYS stable insertion order (by id) — pinging one section must
     *  never reshuffle rows of the other sections. The DB returns ping-sorted
     *  rows on every ping update; re-sorting by id here means ping writes
     *  change values only, never the order the user sees. */
    private fun stableOrder(flow: kotlinx.coroutines.flow.Flow<List<Profile>>): kotlinx.coroutines.flow.Flow<List<Profile>> =
        kotlinx.coroutines.flow.combine(flow, freeUseCase.progress) { list, _ ->
            // ping-sorted inside each section: favorites first, then alive
            // (ascending ms), dead, untested. sortedWith is STABLE — rows with
            // equal sort keys keep their relative order, so pinging ONE
            // subscription only reorders that section and leaves the others
            // untouched.
            list.sortedWith(
                compareBy(
                    { !it.isFavorite },
                    { it.pingMs == null },
                    { it.pingMs != null && it.pingMs < 0 },
                    { it.pingMs ?: 0 },
                )
            )
        }

    val profiles: StateFlow<List<Profile>> = stableOrder(repo.observe(Profile.Scope.PERSONAL))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** configs that came from a subscription */
    val subProfiles: StateFlow<List<Profile>> = stableOrder(repo.observe(Profile.Scope.PERSONAL))
        .map { list -> list.filter { it.subscriptionId != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** manually added configs — never mixed with subscription ones */
    val manualProfiles: StateFlow<List<Profile>> = stableOrder(repo.observe(Profile.Scope.PERSONAL))
        .map { list -> list.filter { it.subscriptionId == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val subscriptions: StateFlow<List<com.kuva.vpn.data.db.SubscriptionEntity>> =
        subUseCase.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedId: StateFlow<Long?> = repo.observeSelected()
        .map { it?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** One-shot UI events (snackbar/toast). Collected by the screen. */
    sealed interface Event {
        data class Imported(val count: Int) : Event
        data object NothingValid : Event
        data object EmptyClipboard : Event
        data object ClipboardSub : Event
        data class SpeedResult(val id: Long, val downKbps: Double) : Event
    }
    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: kotlinx.coroutines.flow.SharedFlow<Event> = _events

    /** Decrypted subscription URL for the QR dialog (async: Keystore on IO). */
    fun subUrl(id: Long, onReady: (String?) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val sub = subscriptions.value.firstOrNull { it.id == id }
            val url = sub?.let { subUseCase.urlOf(it) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onReady(url)
            }
        }
    }

    /** Import share link(s) as PERSONAL configs. */
    fun importFromLink(text: String) {
        viewModelScope.launch {
            val parsed = parser.parse(text)
            if (parsed.isEmpty()) {
                _events.emit(Event.NothingValid)
                return@launch
            }
            parsed.forEach { p ->
                val safeName = p.name.trim().ifEmpty {
                    "${p.protocol.name.lowercase()}-${p.address}:${p.port}"
                }
                repo.upsert(p.copy(
                    scope = Profile.Scope.PERSONAL,
                    name = safeName,
                    source = "manual",
                    fingerprintHash = Profile.fingerprintOf(p),
                ))
            }
            _events.emit(Event.Imported(parsed.size))
        }
    }

    /**
     * SMART clipboard: detects what kind of text is on the clipboard and
     * routes it like the QR scanner does — a subscription URL (http/https,
     * no vless/vmess/ss/trojan scheme) becomes a SEPARATE subscription
     * entry, anything else is parsed as config share links and lands in
     * Manual Configs.
     */
    fun importClipboard() {
        val text = com.kuva.vpn.util.Clipboards.getText()
        if (text.isNullOrBlank()) {
            viewModelScope.launch { _events.emit(Event.EmptyClipboard) }
            return
        }
        val trimmed = text.trim()
        val isSubLink = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        val isConfigLink = listOf("vless://", "vmess://", "trojan://", "ss://", "socks://", "http2://")
            .any { trimmed.startsWith(it) }
        when {
            isSubLink && !isConfigLink -> {
                // name it from the host, then fetch immediately (same as QR sub import)
                val host = runCatching { java.net.URI(trimmed).host }.getOrNull()
                addSubscription(host ?: "Clipboard sub", trimmed)
                viewModelScope.launch { _events.emit(Event.ClipboardSub) }
            }
            isConfigLink -> importFromLink(trimmed)
            // multi-line pastes: try to parse every line as config links
            trimmed.lines().any { l -> listOf("vless://", "vmess://", "trojan://", "ss://").any { l.trim().startsWith(it) } } ->
                importFromLink(trimmed.lines().joinToString("\n"))
            // a pasted Xray JSON config (full export from v2rayNG/v2Box or a
            // single outbound) — the parser understands whole-text JSON, so
            // just hand it over; works even when the JSON is surrounded by
            // other text as long as it contains an outbounds array
            trimmed.startsWith("{") && trimmed.contains("\"outbounds\"") ->
                importFromLink(trimmed)
            else -> viewModelScope.launch { _events.emit(Event.NothingValid) }
        }
    }

    fun scanQr() {
        // QR scanning launches the zxing scanner from the screen (activity contract);
        // result text lands in importFromLink via the screen callback.
    }

    fun importFile() { /* file picker handled by screen; result -> importFromLink */ }

    fun addSubscription(name: String, url: String) = viewModelScope.launch {
        if (url.startsWith("http")) subUseCase.add(name, url)
    }

    /** QR scan on a subscription link: name it from the host, fetch immediately. */
    fun addSubscriptionFromQr(url: String) = viewModelScope.launch {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "QR sub"
        subUseCase.add(host, url)
    }

    fun refreshSubscription(id: Long) = viewModelScope.launch { subUseCase.refresh(id) }

    /** pin / unpin from the three-dot menu */
    fun setSubPinned(id: Long, pinned: Boolean) = viewModelScope.launch { subUseCase.setPinned(id, pinned) }

    /** Edit Sub dialog: save new name (and URL when changed), then refetch */
    fun updateSubscription(id: Long, name: String, url: String?) = viewModelScope.launch {
        subUseCase.refresh(id, newUrl = url?.trim()?.takeIf { it.isNotEmpty() && it != "-" && it.startsWith("http") })
        subUseCase.rename(id, name)
    }

    fun select(id: Long) = viewModelScope.launch { repo.select(id) }

    /** delete one personal config with undo support */
    fun delete(id: Long) = viewModelScope.launch {
        val ok = freeUseCase.deletePersonalWithUndo(id)
        if (ok) _undoEvents.emit(UndoEvent.DeletedOne)
    }

    /** delete a whole subscription + its configs, undoable for 5 s */
    fun deleteSubscription(id: Long) = viewModelScope.launch {
        val ok = freeUseCase.deleteSubWithUndo(id)
        if (ok) _undoEvents.emit(UndoEvent.DeletedSub)
    }

    /** undo the last delete (config or subscription) */
    fun undoDelete() = viewModelScope.launch {
        if (!freeUseCase.undoDeleteSub()) freeUseCase.undoDeletePersonal()
    }

    sealed interface UndoEvent { data object DeletedOne : UndoEvent; data object DeletedSub : UndoEvent }
    private val _undoEvents = kotlinx.coroutines.flow.MutableSharedFlow<UndoEvent>(extraBufferCapacity = 8)
    val undoEvents: kotlinx.coroutines.flow.SharedFlow<UndoEvent> = _undoEvents

    /** rows currently running a single (swipe) ping — spinner replaces the ping icon */
    val singlePinging = kotlinx.coroutines.flow.MutableStateFlow<Set<Long>>(emptySet())

    /** live ping-run progress (kept for diagnostics) */
    val pingProgress: StateFlow<FreeConfigUseCase.DiscoveryProgress> = freeUseCase.progress

    /** id of the section whose HEADER ping is running: manual = -1,
     *  subscription = its id, null = idle. Drives the header spinners
     *  reliably (independent of the shared use-case progress object). */
    private val _sectionPinging = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    val sectionPinging: kotlinx.coroutines.flow.StateFlow<Long?> = _sectionPinging

    /** snackbar timed out without undo — free the stash */
    fun discardDelete() { freeUseCase.discardSubUndo(); freeUseCase.discardPersonalUndo() }

    /** Swipe-right single ping in My Configs (manual + subscription rows). */
    fun pingSingle(id: Long) = viewModelScope.launch {
        val p = repo.getById(id) ?: return@launch
        val ms = freeUseCase.testSingle(p)
        repo.markTested(id, ms)
    }
    /** decrypted subscription URL for the Edit Sub dialog */
    fun subUrlOf(sub: com.kuva.vpn.data.db.SubscriptionEntity): String? = subUseCase.urlOf(sub)

    /** ids whose speed test is currently running (screen shows a spinner) */
    val testingSpeed = kotlinx.coroutines.flow.MutableStateFlow<Set<Long>>(emptySet())

    fun testSpeed(id: Long) {
        if (id in testingSpeed.value) return
        testingSpeed.value = testingSpeed.value + id
        viewModelScope.launch {
            try {
                val (down, _) = freeUseCase.testSpeed(id, 10)
                _events.emit(Event.SpeedResult(id, down))
            } finally {
                testingSpeed.value = testingSpeed.value - id
            }
        }
    }
    /** ping every config that belongs to one subscription */
    fun testSubPings(subId: Long) = viewModelScope.launch {
        _sectionPinging.value = subId
        try { freeUseCase.testAllPings(Profile.Scope.PERSONAL, subscriptionId = subId) }
        finally { _sectionPinging.value = null }
    }
    /** ping every manually added config */
    fun testManualPings() = viewModelScope.launch {
        _sectionPinging.value = -1L
        try { freeUseCase.testAllPings(Profile.Scope.PERSONAL, manualOnly = true) }
        finally { _sectionPinging.value = null }
    }
    fun toggleFavorite(id: Long, fav: Boolean) =
        viewModelScope.launch { repo.setFavorite(id, fav, Profile.Scope.PERSONAL) }
}
