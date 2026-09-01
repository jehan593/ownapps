package com.ownapps.app.data.repository

import com.ownapps.app.data.db.dao.FirewallRuleDao
import com.ownapps.app.data.db.entity.FirewallRuleEntity
import kotlinx.coroutines.flow.Flow

/** Persists which packages have their internet access blocked by the firewall. */
class FirewallRulesRepository(private val dao: FirewallRuleDao) {

    fun observeAllBlocked(): Flow<List<FirewallRuleEntity>> = dao.observeAllBlocked()

    suspend fun getBlockedPackages(): Set<String> =
        dao.getAllBlocked().map { it.packageName }.toSet()

    suspend fun markBlocked(packageName: String) {
        dao.upsert(
            FirewallRuleEntity(
                packageName = packageName,
                isBlocked = true,
                lastChangedAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun markUnblocked(packageName: String) {
        dao.upsert(
            FirewallRuleEntity(
                packageName = packageName,
                isBlocked = false,
                lastChangedAtEpochMillis = System.currentTimeMillis()
            )
        )
    }
}