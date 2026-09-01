package com.ownapps.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A per-app network block persists until the user explicitly allows networking again. */
@Entity(tableName = "firewall_rule")
data class FirewallRuleEntity(
    @PrimaryKey val packageName: String,
    val isBlocked: Boolean,
    val lastChangedAtEpochMillis: Long
)