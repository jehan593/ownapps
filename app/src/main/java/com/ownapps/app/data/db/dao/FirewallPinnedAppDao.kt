package com.ownapps.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ownapps.app.data.db.entity.FirewallPinnedAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FirewallPinnedAppDao {
    @Query("SELECT * FROM firewall_pinned_app ORDER BY position ASC, pinnedAtEpochMillis ASC")
    fun observeAll(): Flow<List<FirewallPinnedAppEntity>>

    @Query("SELECT COUNT(*) FROM firewall_pinned_app WHERE packageName = :packageName")
    suspend fun isPinned(packageName: String): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM firewall_pinned_app")
    suspend fun maxPosition(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: FirewallPinnedAppEntity)

    @Update
    suspend fun update(entity: FirewallPinnedAppEntity)

    @Delete
    suspend fun delete(entity: FirewallPinnedAppEntity)
}