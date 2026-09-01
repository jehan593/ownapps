package com.ownapps.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.ownapps.app.data.db.entity.FirewallRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FirewallRuleDao {
    @Query("SELECT * FROM firewall_rule WHERE isBlocked = 1")
    suspend fun getAllBlocked(): List<FirewallRuleEntity>

    @Query("SELECT * FROM firewall_rule WHERE isBlocked = 1")
    fun observeAllBlocked(): Flow<List<FirewallRuleEntity>>

    @Upsert
    suspend fun upsert(entity: FirewallRuleEntity)
}