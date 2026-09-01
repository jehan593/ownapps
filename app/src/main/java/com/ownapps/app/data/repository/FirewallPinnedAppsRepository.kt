package com.ownapps.app.data.repository

import com.ownapps.app.data.db.dao.FirewallPinnedAppDao
import com.ownapps.app.data.db.entity.FirewallPinnedAppEntity
import kotlinx.coroutines.flow.Flow

/** Persists which apps the user has pinned to the top of the Firewall list, and their manual order. */
class FirewallPinnedAppsRepository(private val dao: FirewallPinnedAppDao) {

    fun observePinned(): Flow<List<FirewallPinnedAppEntity>> = dao.observeAll()

    suspend fun togglePin(packageName: String) {
        if (dao.isPinned(packageName) > 0) {
            dao.delete(FirewallPinnedAppEntity(packageName, 0L, 0))
        } else {
            dao.insert(
                FirewallPinnedAppEntity(
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
            dao.update(FirewallPinnedAppEntity(packageName, System.currentTimeMillis(), index))
        }
    }
}