package com.kuva.vpn.domain.usecase

import com.kuva.vpn.data.db.ConnectionHistoryDao
import com.kuva.vpn.data.db.ConnectionHistoryEntity
import com.kuva.vpn.domain.model.ConnectionState
import com.kuva.vpn.domain.repository.ConnectionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Records real connection sessions into history. */
@Singleton
class ConnectionHistoryUseCase @Inject constructor(
    private val dao: ConnectionHistoryDao,
    private val connRepo: ConnectionRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var openSessionId: Long? = null

    fun observeRecent(): Flow<List<ConnectionHistoryEntity>> = dao.observeRecent()

    fun startTracking() {
        scope.launch {
            connRepo.state.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        openSessionId = dao.insert(
                            ConnectionHistoryEntity(
                                profileName = state.profileName ?: "?",
                                serverAddress = "",
                                protocol = "",
                                startedAt = System.currentTimeMillis(),
                            )
                        )
                    }
                    is ConnectionState.Disconnected -> {
                        openSessionId?.let { id ->
                            dao.finish(id, System.currentTimeMillis(), 0, 0, null, null)
                            openSessionId = null
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    suspend fun clearHistory() = dao.clear()
}
