package com.kuva.vpn.domain.usecase

import com.kuva.vpn.core.diagnostics.ConfigTester
import com.kuva.vpn.data.db.SubscriptionDao
import com.kuva.vpn.core.discovery.ConfigDiscoveryEngine
import com.kuva.vpn.core.discovery.DefaultSources
import com.kuva.vpn.core.discovery.FreeConfigSource
import com.kuva.vpn.domain.model.Profile
import com.kuva.vpn.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Free-config repository operations. Hard rules enforced here:
 *  - existing configs are NEVER deleted or overwritten by discovery
 *  - duplicates are skipped by fingerprint
 *  - "delete dead" only removes configs confirmed unreachable after retries
 */
@Singleton
class FreeConfigUseCase @Inject constructor(
    private val repo: ProfileRepository,
    private val discovery: ConfigDiscoveryEngine,
    private val tester: ConfigTester,
    private val subDao: SubscriptionDao,   // for undo-aware subscription delete/restore
) {
    // ---------- discovery ----------
    data class DiscoveryProgress(
        val running: Boolean = false,
        val phase: String = "",           // "search" | "ping"
        val done: Int = 0,
        val total: Int = 0,
        val currentSource: String = "",
        val found: Int = 0,
        val valid: Int = 0,
        val newImported: Int = 0,
        val base: Int = 0,               // configs already in the list when the run started
        val runOwner: String = "",       // which pane started the run: "search"|"vault"|"mine"
        val alive: Int = 0,              // configs that got a ping (ms >= 0) this run
        val pingingSubId: Long? = null,  // which subscription is being pinged (mine run)
        val dead: Int = 0,               // configs that failed (ms < 0) this run
        val errors: List<String> = emptyList(),
        val finished: Boolean = false,
        val cancelling: Boolean = false,
    )

    private val _progress = MutableStateFlow(DiscoveryProgress())
    val progress: StateFlow<DiscoveryProgress> = _progress.asStateFlow()

    /** Target number of UNIQUE configs to add per search run. */
    private val targetCount = 50

    /** Cooperative cancellation flag, checked between sources and during ping. */
    @Volatile
    private var cancelRequested = false

    /** Minimum configs wanted from EACH selected source (when it has them). */
    private val MIN_PER_SOURCE = 8

    /**
     * User pressed Stop: keep everything found so far, stop fetching more.
     * Found configs are imported and ping-tested right away.
     */
    fun cancelSearch() {
        cancelRequested = true
        _progress.value = _progress.value.copy(cancelling = true)
    }

    /**
     * Default public sources. Can be extended — the UI passes extra sources
     * parsed from a user-supplied JSON source list.
     */
    /**
     * Numbered catalog for the Sources popup. Numbers are STABLE and are what
     * the UI shows next to every config found (Profile.source = "[n]").
     * Tested-first sources (4, 5, 6, 8) pre-verify configs before publishing,
     * which is why the alive ratio from them is far higher than raw ones.
     */
    data class SourceDef(
        val number: Int,
        val label: String,
        val description: String,
        val source: FreeConfigSource,
    )

    fun sourceCatalog(): List<SourceDef> = listOf(
        SourceDef(1, "Aggregator", "mahdibland - merged daily, raw mix",
            DefaultSources.RawUrl("[1]", "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt")),
        SourceDef(2, "Pawdroid", "GitHub sub - big curated list (base64)",
            DefaultSources.SubUrl("[2]", "https://raw.githubusercontent.com/Pawdroid/Free-servers/main/sub")),
        SourceDef(3, "V2rayCollector", "GitHub raw mix - untested",
            DefaultSources.RawUrl("[3]", "https://raw.githubusercontent.com/MhdiTaheri/V2rayCollector/main/sub/mix")),
        SourceDef(4, "Au1rxx", "HTTP-tested hourly, top ~150 only",
            DefaultSources.SubUrl("[4]", "https://raw.githubusercontent.com/Au1rxx/free-vpn-subscriptions/main/output/v2ray-base64.txt")),
        SourceDef(5, "NodesRadar", "Tested every 6h, stability-ranked",
            DefaultSources.SubUrl("[5]", "https://radarhub.top/v2ray.txt")),
        SourceDef(6, "HamedCode", "TCP-checked every 4h before publish",
            DefaultSources.RawUrl("[6]", "https://raw.githubusercontent.com/hamedcode/port-based-v2ray-configs/main/sub/port_443.txt")),
        SourceDef(7, "0xRadikal", "Top 100, refreshed every 15m",
            DefaultSources.SubUrl("[7]", "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/top100.txt")),
        SourceDef(8, "FreeFq", "GitHub sub - classic list (base64)",
            DefaultSources.SubUrl("[8]", "https://raw.githubusercontent.com/freefq/free/master/v2")),
    )

    /** Every catalog source (used when the user has not picked any set). */
    fun defaultSources(): List<FreeConfigSource> = sourceCatalog().map { it.source }

    suspend fun searchFreeConfigs(
        extraSources: List<FreeConfigSource> = emptyList(),
        enabled: List<FreeConfigSource>? = null,   // null = all catalog sources
    ) {
        if (_progress.value.running) return
        cancelRequested = false
        _progress.value = DiscoveryProgress(
            running = true, phase = "search",
            // live denominator = list size at start + found during this run
            base = 0,   // live search count = configs found THIS run only (pool excluded)

        )
        try {
            // IMPORTANT: do NOT pre-load / compare against the repo during the
            // search itself — that compare on every config caused UI lag.
            // Dedupe happens ONCE at the end (below), in memory.
            val fingerprints = emptySet<String>()

            // fetch sources one by one until target unique configs reached
            // (or the user presses Stop) — configs found so far are kept
            val sources = (enabled ?: defaultSources()) + extraSources
            val collected = LinkedHashMap<String, Profile>()  // fingerprint -> profile
            val results = mutableListOf<ConfigDiscoveryEngine.SourceResult>()
            var lastFoundUi = 0L
            val onTick: (Int) -> Unit = { n ->
                // live counter, throttled to max 4 updates/sec so the UI
                // stays smooth even when thousands of links are scanned
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastFoundUi >= 250 || n >= targetCount) {
                    lastFoundUi = now
                    _progress.value = _progress.value.copy(found = n)
                }
            }
            // PASS 1 — per-source quota: every selected source must contribute
            // at least MIN_PER_SOURCE configs, so one giant source cannot eat
            // the whole 100-config budget before the others are even fetched.
            // In quota mode the global cap is suspended inside the engine.
            sources.forEachIndexed { idx, source ->
                if (cancelRequested) return@forEachIndexed
                val r = discovery.discoverSource(
                    source = source,
                    existingFingerprints = fingerprints,
                    into = collected,
                    maxTotal = targetCount,
                    isCancelled = { cancelRequested },
                    onFound = onTick,
                    minFromSource = if (sources.size > 1) MIN_PER_SOURCE else 0,
                )
                results.add(r)
                if (!cancelRequested) {
                    _progress.value = _progress.value.copy(
                        done = idx + 1, total = sources.size, currentSource = source.name, found = collected.size
                    )
                }
            }
            // PASS 2 — fill the remainder up to targetCount with whatever is
            // still available (quota pass delivers exactly 8 per source).
            if (!cancelRequested && collected.size < targetCount) {
                sources.forEachIndexed { idx, source ->
                    if (cancelRequested || collected.size >= targetCount) return@forEachIndexed
                    discovery.discoverSource(
                        source = source,
                        existingFingerprints = fingerprints,
                        into = collected,
                        maxTotal = targetCount,
                        isCancelled = { cancelRequested },
                        onFound = onTick,
                    )
                    _progress.value = _progress.value.copy(found = collected.size)
                }
            }

            // ---- final dedupe (once, after search ends) ----
            // 1) in-memory: drop exact duplicates among what was found
            val uniq = LinkedHashMap<String, Profile>()
            collected.values.forEach { p ->
                val fp = p.fingerprintHash ?: Profile.fingerprintOf(p)
                if (fp !in uniq) uniq[fp] = p
            }
            // 2) against repo: for each found config, if an identical row already
            //    exists, keep the existing row (do not add a twin).
            val existingByFp = repo.all(Profile.Scope.FREE)
                .associateBy { it.fingerprintHash }
            val toAdd = uniq.values.filter { p ->
                val fp = p.fingerprintHash ?: Profile.fingerprintOf(p)
                !existingByFp.containsKey(fp)  // twin exists — skip
            }
            // ONE batched insert: a single Room transaction fires the list
            // Flow exactly once instead of once per row (scroll lag).
            val added = if (toAdd.isEmpty()) 0 else repo.upsertAll(toAdd)
            _progress.value = _progress.value.copy(newImported = added)

            _progress.value = _progress.value.copy(
                running = false, finished = true, cancelling = false,
                valid = results.sumOf { it.valid },
                errors = results.mapNotNull { r -> r.error?.let { "${r.source}: $it" } },
            )
        } catch (e: Exception) {
            // total failure: repo untouched
            _progress.value = _progress.value.copy(
                running = false, finished = true,
                errors = listOf(e.message ?: "discovery failed"),
            )
        }
    }

    // ---------- management ----------
    suspend fun testAllPings(
        scope: Profile.Scope = Profile.Scope.FREE,
        subscriptionId: Long? = null,
        manualOnly: Boolean = false,
        vaultOnly: Boolean = false,
        searchOnly: Boolean = false,   // workshop pane only (inVault = 0)
    ) {
        val profiles = repo.all(scope).let { list ->
            when {
                subscriptionId != null -> list.filter { it.subscriptionId == subscriptionId }
                vaultOnly -> list.filter { it.inVault }
                searchOnly -> list.filter { !it.inVault }
                manualOnly -> list.filter { it.subscriptionId == null }
                else -> list
            }
        }
        // Stale numbers must not linger during the new run: wipe them in ONE
        // update so every row reads "untested" immediately.
        cancelRequested = false
        repo.clearPingFor(profiles.map { it.id })
        var i = 0
        var lastUi = 0L
        // Results are persisted in ~1-second batches, not one UPDATE per
        // ping: each UPDATE re-fired the list Flow and made scrolling lag
        // badly during search/ping runs.
        val pending = LinkedHashMap<Long, Int>()
        var aliveCount = 0
        var deadCount = 0
        var lastCommit = 0L
        _progress.value = _progress.value.copy(
            running = true, phase = "ping", total = profiles.size, done = 0,
            // which surface started this run — the OTHER pane's ping button
            // disables itself while this run is alive (no parallel pings)
            runOwner = when {
                vaultOnly -> "vault"
                searchOnly -> "search"
                else -> "mine"
            },
            pingingSubId = subscriptionId,
        )
        tester.testPingBatch(profiles, shouldCancel = { cancelRequested }) { id, ms ->
            pending[id] = ms
            i++
            val now = android.os.SystemClock.elapsedRealtime()
            if (pending.isNotEmpty() && now - lastCommit >= 1000) {
                lastCommit = now
                val batch = pending.toList()
                pending.clear()
                repo.markTestedBatch(batch)
            }
            if (ms >= 0) aliveCount++ else deadCount++
            if (now - lastUi >= 250 || i == profiles.size) {
                lastUi = now
                _progress.value = _progress.value.copy(done = i, alive = aliveCount, dead = deadCount)
            }
        }
        if (pending.isNotEmpty()) repo.markTestedBatch(pending.toList())
        _progress.value = _progress.value.copy(running = false, finished = true)
    }

    suspend fun testSpeed(id: Long, limitMB: Int): Pair<Double, Double> {
        val p = repo.getById(id) ?: return Pair(0.0, 0.0)
        runCatching { tester.initSpeedEnv() }
        val (down, up) = tester.testSpeed(p, limitMB)
        // always persist, even 0.0 (shows the test ran and failed through proxy)
        repo.markSpeed(id, down, up)
        return Pair(down, up)
    }

    suspend fun deleteDeadConfigs(): Int {
        // Deletes every non-favorite free config whose last test failed or
        // timed out. Configs already showing "timeout" in the list were tested
        // recently — re-verifying them all again made the button feel dead, so
        // we trust the stored verdict and delete immediately (favorites never
        // touched). Configs never tested (null ping) get ONE quick verify round.
        val all = repo.all(Profile.Scope.FREE)
        val confirmedDead = all.filter { !it.isFavorite && (it.pingMs ?: 0) < 0 }
        // ONE delete query for the whole batch — sub-second even for hundreds.
        if (confirmedDead.isNotEmpty())
            repo.deleteAll(confirmedDead.map { it.id }, Profile.Scope.FREE)

        var verifiedDead = 0
        val untested = all.filter { !it.isFavorite && it.pingMs == null }
        if (untested.isNotEmpty()) {
            val verdict = java.util.concurrent.ConcurrentHashMap<Long, Int>()
            tester.testPingBatch(untested) { id, ms -> verdict[id] = ms }
            val deadIds = verdict.filterValues { it < 0 }.keys.toList()
            if (deadIds.isNotEmpty()) repo.deleteAll(deadIds, Profile.Scope.FREE)
            verifiedDead = deadIds.size
            val alive = verdict.filterValues { it >= 0 }
            if (alive.isNotEmpty()) repo.markTestedBatch(alive.map { it.toPair() })
        }
        return confirmedDead.size + verifiedDead
    }

    /**
     * Move-to-vault action (search pane button): delete dead configs, then
     * transfer the alive ones to the vault. The selected (connected) config
     * KEEPS its connection — tagging it into the vault does not touch the
     * live tunnel. @return Pair(deadDeleted, movedCount)
     */
    suspend fun transferToVault(): Pair<Int, Int> {
        val all = repo.all(Profile.Scope.FREE).filter { !it.inVault }
        val alive = all.filter { (it.pingMs ?: 0) > 0 }
        val dead = all.filter { !it.isFavorite && (it.pingMs ?: 0) < 0 }
        if (dead.isNotEmpty()) repo.deleteAll(dead.map { it.id }, Profile.Scope.FREE)
        if (alive.isNotEmpty()) repo.moveToVault(alive.map { it.id })
        // favorites that were never pinged: keep them, send them to the vault too
        val untestedFav = all.filter { it.isFavorite && it.pingMs == null }
        if (untestedFav.isNotEmpty()) repo.moveToVault(untestedFav.map { it.id })
        // drop the search-result tag so "clear list" can never reach vault rows
        val movedIds = alive.map { it.id } + untestedFav.map { it.id }
        if (movedIds.isNotEmpty()) repo.markNotFromSearch(movedIds)
        return dead.size to (alive.size + untestedFav.size)
    }

    /** Single ping (vault swipe-right). @return ms, or -1 if dead. */
    suspend fun testSingle(p: Profile): Int = tester.testPing(p)

    // ---------- undo support (5 s snackbar) ----------
    /** Most recently deleted free config, held for the Undo snackbar. */
    private var lastDeletedFree: Profile? = null

    /** Delete ONE free config, keeping a copy for undo. */
    suspend fun deleteFreeWithUndo(id: Long): Boolean {
        val p = repo.getById(id) ?: return false
        lastDeletedFree = p
        repo.delete(id, Profile.Scope.FREE)
        return true
    }

    /** Restore the last deleted free config (Undo on the snackbar). */
    suspend fun undoDeleteFree(): Boolean {
        val p = lastDeletedFree ?: return false
        lastDeletedFree = null
        repo.upsert(p)   // upsert keeps the same row id (stable order by id)
        return true
    }

    /** Most recently deleted PERSONAL config (manual or from a sub). */
    private var lastDeletedPersonal: Profile? = null

    /** Delete ONE personal config, keeping a copy for undo. */
    suspend fun deletePersonalWithUndo(id: Long): Boolean {
        val p = repo.getById(id) ?: return false
        lastDeletedPersonal = p
        repo.delete(id, Profile.Scope.PERSONAL)
        return true
    }

    /** Restore the last deleted personal config (Undo on the snackbar). */
    suspend fun undoDeletePersonal(): Boolean {
        val p = lastDeletedPersonal ?: return false
        lastDeletedPersonal = null
        repo.upsert(p)
        return true
    }

    /**
     * Delete a whole subscription + its configs, keeping copies for undo.
     * Restoring re-inserts the sub row (new id) then repoints all configs.
     */
    private var lastDeletedSub: Pair<com.kuva.vpn.data.db.SubscriptionEntity, List<Profile>>? = null

    suspend fun deleteSubWithUndo(id: Long): Boolean {
        val sub = subDao.getById(id) ?: return false
        val owned = repo.all(Profile.Scope.PERSONAL).filter { it.subscriptionId == id }
        // deselect the connected config first when it belongs to this sub:
        // repo.delete protects it and it would survive as an orphan row
        val selectedId = repo.getSelected()?.id
        if (selectedId != null && owned.any { it.id == selectedId }) {
            repo.select(null)
        }
        owned.forEach { repo.delete(it.id, Profile.Scope.PERSONAL) }
        subDao.delete(id)
        lastDeletedSub = sub to owned
        return true
    }

    suspend fun undoDeleteSub(): Boolean {
        val (sub, owned) = lastDeletedSub ?: return false
        lastDeletedSub = null
        val newId = subDao.insert(sub.copy(id = 0))
        owned.forEach { repo.upsert(it.copy(subscriptionId = newId)) }
        return true
    }

    /** Drop the undo stash without restoring (snackbar timed out). */
    fun discardFreeUndo() { lastDeletedFree = null }
    fun discardPersonalUndo() { lastDeletedPersonal = null }
    fun discardSubUndo() { lastDeletedSub = null }

    /**
     * Dead-config delete for the VAULT pane ONLY: search-pane rows are never
     * touched here (they are managed by the transfer button). Favorites and
     * the connected config are never deleted; untested rows get one verify
     * round first.
     */
    suspend fun deleteDeadVault(): Int {
        val all = repo.all(Profile.Scope.FREE).filter { it.inVault }
        val confirmedDead = all.filter { !it.isFavorite && (it.pingMs ?: 0) < 0 }
        if (confirmedDead.isNotEmpty())
            repo.deleteAll(confirmedDead.map { it.id }, Profile.Scope.FREE)

        var verifiedDead = 0
        val untested = all.filter { !it.isFavorite && it.pingMs == null }
        if (untested.isNotEmpty()) {
            val verdict = java.util.concurrent.ConcurrentHashMap<Long, Int>()
            tester.testPingBatch(untested) { id, ms -> verdict[id] = ms }
            val deadIds = verdict.filterValues { it < 0 }.keys.toList()
            if (deadIds.isNotEmpty()) repo.deleteAll(deadIds, Profile.Scope.FREE)
            verifiedDead = deadIds.size
            val alive = verdict.filterValues { it >= 0 }
            if (alive.isNotEmpty()) repo.markTestedBatch(alive.map { it.toPair() })
        }
        return confirmedDead.size + verifiedDead
    }

    suspend fun clearSearchResults(): Int {
        // un-tag previous search results; they stay in the repo (add-only policy)
        return repo.clearSearchResults()
    }
}
