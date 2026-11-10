package com.kuva.vpn.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for both free and personal profiles. The `scope` column is the
 * hard partition boundary: every DAO mutation filters on it, so free-repo
 * operations (refresh, delete-dead, clear-search) can never touch personal rows.
 */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scope: String,                 // FREE | PERSONAL
    val name: String,
    val protocol: String,
    val address: String,
    val port: Int,
    // encrypted at rest via EncryptedFieldConverter
    val uuidEnc: String? = null,
    val method: String? = null,
    val alterId: Int = 0,
    val flow: String? = null,
    val encryption: String? = null,
    val network: String = "tcp",
    val security: String = "none",
    val sni: String? = null,
    val host: String? = null,
    val path: String? = null,
    val serviceName: String? = null,
    val alpn: String? = null,
    val fingerprint: String? = null,
    val publicKeyEnc: String? = null,
    val shortId: String? = null,
    val spiderX: String? = null,
    val allowInsecure: Boolean = false,
    // metadata
    val source: String? = null,
    val discoveredAt: Long,
    val fingerprintHash: String,
    val pingMs: Int? = null,
    val speedDownKbps: Double? = null,
    val speedUpKbps: Double? = null,
    val lastTestedAt: Long? = null,
    val country: String? = null,
    val isFavorite: Boolean = false,
    val isSelected: Boolean = false,
    val isFromSearch: Boolean = false,
    val inVault: Boolean = false,
    val subscriptionId: Long? = null,
    val rawJsonEnc: String? = null,
)
