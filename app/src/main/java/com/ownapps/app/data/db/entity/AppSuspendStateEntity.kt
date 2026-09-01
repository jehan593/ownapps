package com.ownapps.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A disable persists until the user explicitly enables it again — no automatic expiry. */
@Entity(tableName = "app_suspend_state")
data class AppSuspendStateEntity(
    @PrimaryKey val packageName: String,
    val isSuspended: Boolean,
    val lastChangedAtEpochMillis: Long
)