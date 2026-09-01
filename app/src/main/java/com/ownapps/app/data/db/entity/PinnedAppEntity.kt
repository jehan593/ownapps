package com.ownapps.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A user-pinned app, shown in its own section at the top of the All Apps list. */
@Entity(tableName = "pinned_app")
data class PinnedAppEntity(
    @PrimaryKey val packageName: String,
    val pinnedAtEpochMillis: Long,
    val position: Int
)
