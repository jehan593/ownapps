package com.ownapps.app.data.repository

import com.ownapps.app.data.db.dao.AppSuspendStateDao
import com.ownapps.app.data.db.entity.AppSuspendStateEntity
import kotlinx.coroutines.flow.Flow

class AppSuspendStateRepository(private val dao: AppSuspendStateDao) {

    fun observeAllSuspended(): Flow<List<AppSuspendStateEntity>> = dao.observeAllSuspended()

    suspend fun getSuspendedPackages(): Set<String> =
        dao.getAllSuspended().map { it.packageName }.toSet()

    suspend fun markSuspended(packageName: String) {
        dao.upsert(
            AppSuspendStateEntity(
                packageName = packageName,
                isSuspended = true,
                lastChangedAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun markUnsuspended(packageName: String) {
        dao.upsert(
            AppSuspendStateEntity(
                packageName = packageName,
                isSuspended = false,
                lastChangedAtEpochMillis = System.currentTimeMillis()
            )
        )
    }
}