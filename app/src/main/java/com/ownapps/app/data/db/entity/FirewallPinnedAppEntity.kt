package com.ownapps.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A user-pinned app, shown in its own section at the top of the Firewall list. Kept separate
 *  from the main list's pins so the firewall's pinned set stays independent. */
@Entity(tableName = "firewall_pinned_app")
data class FirewallPinnedAppEntity(
    @PrimaryKey val packageName: String,
    val pinnedAtEpochMillis: Long,
    val position: Int
)