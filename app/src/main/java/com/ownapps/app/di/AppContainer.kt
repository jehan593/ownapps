package com.ownapps.app.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ownapps.app.data.db.AppDatabase
import com.ownapps.app.data.pm.InstalledAppsRepository
import com.ownapps.app.data.repository.AppSuspendStateRepository
import com.ownapps.app.data.repository.FirewallPinnedAppsRepository
import com.ownapps.app.data.repository.FirewallRulesRepository
import com.ownapps.app.data.repository.PinnedAppsRepository
import com.ownapps.app.data.repository.SettingsRepository
import com.ownapps.app.enforcement.FirewallBlocker
import com.ownapps.app.enforcement.FirewallBootRestorer
import com.ownapps.app.enforcement.FirewallController
import com.ownapps.app.enforcement.PackageBlocker
import com.ownapps.app.enforcement.PackageController
import com.ownapps.app.enforcement.ShizukuFirewallController
import com.ownapps.app.enforcement.ShizukuPackageController

private val Context.dataStore by preferencesDataStore(name = "ownapps_settings")

interface AppContainer {
    val database: AppDatabase
    val settingsRepository: SettingsRepository
    val suspendStateRepository: AppSuspendStateRepository
    val packageController: PackageController
    val packageBlocker: PackageBlocker
    val installedAppsRepository: InstalledAppsRepository
    val pinnedAppsRepository: PinnedAppsRepository
    val firewallController: FirewallController
    val firewallBlocker: FirewallBlocker
    val firewallRulesRepository: FirewallRulesRepository
    val firewallPinnedAppsRepository: FirewallPinnedAppsRepository
    val firewallBootRestorer: FirewallBootRestorer
}

class DefaultAppContainer(private val context: Context) : AppContainer {

    override val database: AppDatabase by lazy {
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(context.dataStore)
    }

    override val suspendStateRepository: AppSuspendStateRepository by lazy {
        AppSuspendStateRepository(database.appSuspendStateDao())
    }

    override val packageController: PackageController by lazy {
        ShizukuPackageController()
    }

    override val packageBlocker: PackageBlocker by lazy {
        PackageBlocker(packageController, suspendStateRepository)
    }

    override val installedAppsRepository: InstalledAppsRepository by lazy {
        InstalledAppsRepository(context.packageManager, context.packageName)
    }

    override val pinnedAppsRepository: PinnedAppsRepository by lazy {
        PinnedAppsRepository(database.pinnedAppDao())
    }

    override val firewallController: FirewallController by lazy {
        ShizukuFirewallController()
    }

    override val firewallBlocker: FirewallBlocker by lazy {
        FirewallBlocker(firewallController, firewallRulesRepository)
    }

    override val firewallRulesRepository: FirewallRulesRepository by lazy {
        FirewallRulesRepository(database.firewallRuleDao())
    }

    override val firewallPinnedAppsRepository: FirewallPinnedAppsRepository by lazy {
        FirewallPinnedAppsRepository(database.firewallPinnedAppDao())
    }

    override val firewallBootRestorer: FirewallBootRestorer by lazy {
        FirewallBootRestorer(firewallController, firewallRulesRepository, settingsRepository)
    }
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pinned_app ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
        // Backfill position from the previous pin order (oldest first).
        db.execSQL(
            """
            UPDATE pinned_app SET position = (
                SELECT COUNT(*) FROM pinned_app AS prior
                WHERE prior.pinnedAtEpochMillis < pinned_app.pinnedAtEpochMillis
                   OR (prior.pinnedAtEpochMillis = pinned_app.pinnedAtEpochMillis AND prior.packageName < pinned_app.packageName)
            )
            """
        )
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS firewall_rule (
                packageName TEXT NOT NULL,
                isBlocked INTEGER NOT NULL,
                lastChangedAtEpochMillis INTEGER NOT NULL,
                PRIMARY KEY(packageName)
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS firewall_pinned_app (
                packageName TEXT NOT NULL,
                pinnedAtEpochMillis INTEGER NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY(packageName)
            )
            """
        )
    }
}