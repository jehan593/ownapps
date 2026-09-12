package com.ownapps.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.ownapps.app.data.db.entity.AppSuspendStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppSuspendStateDao {
    @Query("SELECT * FROM app_suspend_state WHERE isSuspended = 1")
    suspend fun getAllSuspended(): List<AppSuspendStateEntity>

    @Query("SELECT * FROM app_suspend_state WHERE isSuspended = 1")
    fun observeAllSuspended(): Flow<List<AppSuspendStateEntity>>

    @Query("SELECT * FROM app_suspend_state")
    suspend fun getAll(): List<AppSuspendStateEntity>

    @Upsert
    suspend fun upsert(entity: AppSuspendStateEntity)

    /** Single-transaction bulk write so a reconcile publishes one consistent snapshot. */
    @Transaction
    @Upsert
    suspend fun upsertAll(entities: List<AppSuspendStateEntity>)
}