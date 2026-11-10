package com.kuva.vpn.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Personal subscriptions only — never mixed with the free repository. */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val urlEnc: String,               // encrypted at rest
    val lastUpdated: Long? = null,
    val configCount: Int = 0,
    val lastError: String? = null,
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expireEpochSec: Long? = null,
    val autoUpdate: Boolean = true,
    val pinned: Boolean = false,        // pinned subs sort to the top of the list
)

@Entity(tableName = "connection_history")
data class ConnectionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileName: String,
    val serverAddress: String,
    val protocol: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val totalDownBytes: Long = 0,
    val totalUpBytes: Long = 0,
    val exitIp: String? = null,
    val country: String? = null,
)
