package com.ownapps.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ownapps.app.data.db.entity.PinnedAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PinnedAppDao {
    @Query("SELECT * FROM pinned_app ORDER BY position ASC, pinnedAtEpochMillis ASC")
    fun observeAll(): Flow<List<PinnedAppEntity>>

    @Query("SELECT COUNT(*) FROM pinned_app WHERE packageName = :packageName")
    suspend fun isPinned(packageName: String): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM pinned_app")
    suspend fun maxPosition(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PinnedAppEntity)

    @Update
    suspend fun update(entity: PinnedAppEntity)

    @Delete
    suspend fun delete(entity: PinnedAppEntity)
}
