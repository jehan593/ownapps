package com.ownapps.app.enforcement

import com.ownapps.app.data.repository.FirewallRulesRepository

/**
 * Shared firewall actions used by any screen that offers a per-app block toggle. Unlike the app
 * disabler, a network block must not be claimed before it actually landed — a blocked flag that
 * isn't enforced would silently mislead ("app looks firewalled but has internet"). So the local
 * rule row is only mirrored after the privileged backend command succeeds.
 */
class FirewallBlocker(
    private val firewallController: FirewallController,
    private val firewallRulesRepository: FirewallRulesRepository
) {
    /** True when [packageName] was blocked at the system level (and the local row says so). */
    suspend fun block(packageName: String): Boolean {
        if (!firewallController.block(packageName)) return false
        firewallRulesRepository.markBlocked(packageName)
        return true
    }

    /** True when [packageName]'s network was re-allowed at the system level. */
    suspend fun unblock(packageName: String): Boolean {
        if (!firewallController.unblock(packageName)) return false
        firewallRulesRepository.markUnblocked(packageName)
        return true
    }

    /** Blocks every package in [packageNames]. Returns true when all of them landed. */
    suspend fun blockAll(packageNames: Collection<String>): Boolean {
        var ok = true
        for (packageName in packageNames) {
            if (!block(packageName)) ok = false
        }
        return ok
    }

    /** Unblocks every package in [packageNames]. Returns true when all of them landed. */
    suspend fun unblockAll(packageNames: Collection<String>): Boolean {
        var ok = true
        for (packageName in packageNames) {
            if (!unblock(packageName)) ok = false
        }
        return ok
    }
}