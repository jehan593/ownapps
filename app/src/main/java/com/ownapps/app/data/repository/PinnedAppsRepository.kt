package com.ownapps.app.data.repository

import com.ownapps.app.data.db.dao.PinnedAppDao
import com.ownapps.app.data.db.entity.PinnedAppEntity
import kotlinx.coroutines.flow.Flow

/** Persists which apps the user has pinned to the top of the All Apps list, and their manual order. */
class PinnedAppsRepository(private val dao: PinnedAppDao) {

    fun observePinned(): Flow<List<PinnedAppEntity>> = dao.observeAll()

    suspend fun togglePin(packageName: String) {
        if (dao.isPinned(packageName) > 0) {
            dao.delete(PinnedAppEntity(packageName, 0L, 0))
        } else {
            dao.insert(
                PinnedAppEntity(
                    packageName,
                    System.currentTimeMillis(),
                    dao.maxPosition() + 1
                )
            )
        }
    }

    /** Persist a new manual order (a full list of the pinned package names, top to bottom). */
    suspend fun reorder(orderedPackageNames: List<String>) {
        orderedPackageNames.forEachIndexed { index, packageName ->
            dao.update(PinnedAppEntity(packageName, System.currentTimeMillis(), index))
        }
    }
}
