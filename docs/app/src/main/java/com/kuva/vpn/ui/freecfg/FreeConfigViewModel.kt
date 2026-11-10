package com.kuva.vpn.ui.freecfg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kuva.vpn.domain.model.Profile
import com.kuva.vpn.domain.repository.ProfileRepository
import com.kuva.vpn.domain.usecase.FreeConfigUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.kuva.vpn.core.diagnostics.ConfigTester
import javax.inject.Inject

@HiltViewModel
class FreeConfigViewModel @Inject constructor(
    private val repo: ProfileRepository,
    private val freeUseCase: FreeConfigUseCase,
    private val connRepo: com.kuva.vpn.domain.repository.ConnectionRepository,
    private val tester: com.kuva.vpn.core.diagnostics.ConfigTester,
) : ViewModel() {

    /** raw tunnel state; screens resolve the connected profile by name */
    val connectionState: StateFlow<com.kuva.vpn.domain.model.ConnectionState> = connRepo.state


    /** While a run (search/ping) is active the list keeps its insertion
     *  order — the first config stays on top; results arriving mid-run must
     *  not reshuffle rows. Sorted-by-ping resumes when the run ends. */
    private fun stableOrder(flow: kotlinx.coroutines.flow.Flow<List<Profile>>): kotlinx.coroutines.flow.Flow<List<Profile>> =
        kotlinx.coroutines.flow.combine(flow, freeUseCase.progress) { list, prog ->
            if (prog.running) {
                // insertion order while running: rows must not reshuffle mid-run
                list.sortedBy { it.id }
            } else {
                // final order: alive by ping asc, then untested, then dead
                list.sortedWith(
                    compareBy<Profile> {
                        when { it.pingMs == null -> 2; it.pingMs < 0 -> 3; else -> 0 }
                    }.thenBy { it.pingMs ?: 0 }
                )
            }
        }

    // ---------- two-pane state ----------
    private val _tab = MutableStateFlow(0)
    val tab: StateFlow<Int> = _tab.asStateFlow()
    fun setTab(t: Int) { _tab.value = t }

    /** Workshop pane: found configs, not yet transferred to the vault. */
    val searchProfiles: StateFlow<List<Profile>> = stableOrder(repo.observeSearch(Profile.Scope.FREE))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Vault ("Pool") pane: transferred configs, tap selects for the dashboard.
     * Sorted by ping ascending (lowest/fastest first); failed (-1) configs go
     * last. After each finished ping run the list re-sorts, so the top of the
     * list is always the fastest config (user request).
     */
    val vaultProfiles: StateFlow<List<Profile>> =
        repo.observeVault(Profile.Scope.FREE).map { list ->
            list.sortedWith(
                compareBy<Profile> { p -> if (p.pingMs != null && p.pingMs > 0) p.pingMs else Int.MAX_VALUE }
                    .thenBy { it.id }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun transferToVault() = viewModelScope.launch { freeUseCase.transferToVault() }

    fun testVaultPings() = viewModelScope.launch { freeUseCase.testAllPings(scope = Profile.Scope.FREE, vaultOnly = true) }

    /** Search-pane ping: workshop rows only, never the vault. */
    fun testSearchPings() = viewModelScope.launch { freeUseCase.testAllPings(scope = Profile.Scope.FREE, searchOnly = true) }

    /** Second tap on the ping button stops the run. */
    fun cancelPing() = freeUseCase.cancelSearch()

    fun deleteDeadVault() = viewModelScope.launch { freeUseCase.deleteDeadVault() }

    /** Swipe-right single ping in the vault. */
    fun pingSingle(id: Long) = viewModelScope.launch {
        val p = repo.getById(id) ?: return@launch
        singlePinging.value = singlePinging.value + id
        try {
            val ms = freeUseCase.testSingle(p)
            repo.markTested(id, ms)
        } finally {
            singlePinging.value = singlePinging.value - id
        }
    }

    /** rows currently running a single (swipe) ping — spinner replaces the ping icon */
    val singlePinging = kotlinx.coroutines.flow.MutableStateFlow<Set<Long>>(emptySet())

    /** delete with undo support (5 s snackbar) */
    fun delete(id: Long) = viewModelScope.launch {
        val ok = freeUseCase.deleteFreeWithUndo(id)
        if (ok) _undoEvents.emit(UndoEvent.DeletedOne)
    }

    /** undo the last deleted free config */
    fun undoDelete() = viewModelScope.launch { freeUseCase.undoDeleteFree() }

    /** snackbar timed out without undo — free the stash */
    fun discardUndo() = freeUseCase.discardFreeUndo()

    sealed interface UndoEvent { data object DeletedOne : UndoEvent }
    private val _undoEvents = kotlinx.coroutines.flow.MutableSharedFlow<UndoEvent>(extraBufferCapacity = 8)
    val undoEvents: kotlinx.coroutines.flow.SharedFlow<UndoEvent> = _undoEvents

    val progress: StateFlow<FreeConfigUseCase.DiscoveryProgress> = freeUseCase.progress

    val selectedId: StateFlow<Long?> = repo.observeSelected()
        .map { it?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ---------- source selection (in-memory only, resets on app restart) ----------
    // null until the user confirms the Sources dialog once; while null the
    // search uses ALL catalog sources (default = option A).
    private var activeSources: List<com.kuva.vpn.core.discovery.FreeConfigSource>? = null

    private val _selNums = MutableStateFlow((1..8).toList())
    val selNums: StateFlow<List<Int>> = _selNums.asStateFlow()

    val sourceCatalog: List<FreeConfigUseCase.SourceDef> get() = freeUseCase.sourceCatalog()

    /** Called by the Sources dialog on confirm. Numbers 1-8 = catalog sources,
     *  extra URLs become temporary SubUrl sources numbered 10 and up. */
    /** Checkbox / Select-All updates from the dialog — always applied. */
    fun updateSelNums(nums: List<Int>) { _selNums.value = nums }

    fun setSourceSelection(nums: List<Int>, customUrls: List<String>) {
        if (nums.isEmpty() && customUrls.isEmpty()) { activeSources = null; return }
        _selNums.value = nums
        val picked = freeUseCase.sourceCatalog().filter { it.number in nums }.map { it.source }
        val extra = customUrls.mapIndexed { i, u ->
            com.kuva.vpn.core.discovery.DefaultSources.SubUrl("[${10 + i}]", u)
        }
        activeSources = picked + extra
    }

    fun searchFree() = viewModelScope.launch { freeUseCase.searchFreeConfigs(enabled = activeSources) }
    fun cancelSearch() = freeUseCase.cancelSearch()
    fun testAllPings() = viewModelScope.launch { freeUseCase.testAllPings() }
    fun deleteDead() = viewModelScope.launch { freeUseCase.deleteDeadConfigs() }
    fun clearResults() = viewModelScope.launch { freeUseCase.clearSearchResults() }

    fun select(id: Long) = viewModelScope.launch { repo.select(id) }
    /** ids whose speed test is currently running (screen shows a spinner) */
    val testingSpeed = kotlinx.coroutines.flow.MutableStateFlow<Set<Long>>(emptySet())

    /** one-shot speed result events */
    sealed interface SpeedEvent { data class Done(val id: Long, val downKbps: Double) : SpeedEvent }
    private val _speedEvents = kotlinx.coroutines.flow.MutableSharedFlow<SpeedEvent>(extraBufferCapacity = 8)
    val speedEvents: kotlinx.coroutines.flow.SharedFlow<SpeedEvent> = _speedEvents

    fun testSpeed(id: Long) {
        if (id in testingSpeed.value) return
        testingSpeed.value = testingSpeed.value + id
        viewModelScope.launch {
            try {
                val (down, _) = freeUseCase.testSpeed(id, 10)
                _speedEvents.emit(SpeedEvent.Done(id, down))
            } finally {
                testingSpeed.value = testingSpeed.value - id
            }
        }
    }
    fun toggleFavorite(id: Long, fav: Boolean) =
        viewModelScope.launch { repo.setFavorite(id, fav, Profile.Scope.FREE) }
}
