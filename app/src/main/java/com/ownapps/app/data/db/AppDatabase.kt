package com.ownapps.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ownapps.app.data.db.dao.AppSuspendStateDao
import com.ownapps.app.data.db.dao.FirewallPinnedAppDao
import com.ownapps.app.data.db.dao.FirewallRuleDao
import com.ownapps.app.data.db.dao.PinnedAppDao
import com.ownapps.app.data.db.entity.AppSuspendStateEntity
import com.ownapps.app.data.db.entity.FirewallPinnedAppEntity
import com.ownapps.app.data.db.entity.FirewallRuleEntity
import com.ownapps.app.data.db.entity.PinnedAppEntity

@Database(
    entities = [
        AppSuspendStateEntity::class,
        PinnedAppEntity::class,
        FirewallRuleEntity::class,
        FirewallPinnedAppEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appSuspendStateDao(): AppSuspendStateDao
    abstract fun pinnedAppDao(): PinnedAppDao
    abstract fun firewallRuleDao(): FirewallRuleDao
    abstract fun firewallPinnedAppDao(): FirewallPinnedAppDao

    companion object {
        const val DATABASE_NAME = "ownapps.db"
    }
}