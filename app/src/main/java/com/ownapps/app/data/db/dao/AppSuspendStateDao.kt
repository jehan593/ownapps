package com.ownapps.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.ownapps.app.data.db.entity.AppSuspendStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppSuspendStateDao {
    @Query("SELECT * FROM app_suspend_state WHERE isSuspended = 1")
    suspend fun getAllSuspended(): List<AppSuspendStateEntity>

    @Query("SELECT * FROM app_suspend_state WHERE isSuspended = 1")
    fun observeAllSuspended(): Flow<List<AppSuspendStateEntity>>

    @Upsert
    suspend fun upsert(entity: AppSuspendStateEntity)
}