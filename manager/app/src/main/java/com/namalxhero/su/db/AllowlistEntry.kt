package com.namalxhero.su.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** How long a grant lasts. Mirrors SU_GRANT_* in su_uapi.h. */
enum class GrantType { PERMANENT, SESSION }

@Entity(tableName = "allowlist")
data class AllowlistEntry(
    @PrimaryKey val uid: Int,
    val packageName: String?,
    val label: String,
    val grantType: GrantType,
    val grantedAtMillis: Long,
)
