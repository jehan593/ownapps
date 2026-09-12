package com.ownapps.app.data.repository

import com.ownapps.app.data.db.dao.AppSuspendStateDao
import com.ownapps.app.data.db.entity.AppSuspendStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppSuspendStateRepository(private val dao: AppSuspendStateDao) {

    /** Serializes DB writes so a reconcile can never read a torn snapshot mid-toggle and undo it. */
    private val writeMutex = Mutex()

    fun observeAllSuspended(): Flow<List<AppSuspendStateEntity>> = dao.observeAllSuspended()

    suspend fun getSuspendedPackages(): Set<String> =
        dao.getAllSuspended().map { it.packageName }.toSet()

    suspend fun markSuspended(packageName: String) = writeMutex.withLock {
        dao.upsert(
            AppSuspendStateEntity(
                packageName = packageName,
                isSuspended = true,
                lastChangedAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun markUnsuspended(packageName: String) = writeMutex.withLock {
        dao.upsert(
            AppSuspendStateEntity(
                packageName = packageName,
                isSuspended = false,
                lastChangedAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    /**
     * Brings the local mirror in line with the real on-device state, so apps disabled/enabled
     * outside OwnApps show correctly here. Corrections are written in one transaction so the flow
     * emits a single complete snapshot. Only ever called from a manual pull-to-refresh.
     *
     * @return the reconciled set of currently-suspended packages, read back from the DB.
     */
    suspend fun reconcile(actualSuspended: Map<String, Boolean>): Set<String> = writeMutex.withLock {
        val known = dao.getAll().associate { it.packageName to it.isSuspended }
        val now = System.currentTimeMillis()
        val toWrite = mutableListOf<AppSuspendStateEntity>()
        for ((packageName, suspended) in actualSuspended) {
            when {
                suspended && known[packageName] != true ->
                    toWrite += AppSuspendStateEntity(packageName, true, now)
                !suspended && known[packageName] == true ->
                    toWrite += AppSuspendStateEntity(packageName, false, now)
            }
        }
        if (toWrite.isNotEmpty()) dao.upsertAll(toWrite)
        dao.getAllSuspended().map { it.packageName }.toSet()
    }
}