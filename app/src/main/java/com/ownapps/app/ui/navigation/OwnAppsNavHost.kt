package com.ownapps.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ownapps.app.ui.applist.AppListScreen
import com.ownapps.app.ui.firewall.FirewallScreen
import com.ownapps.app.ui.settings.SettingsScreen
import com.ownapps.app.ui.settings.UiHiderScreen

/** Intent extra set on the launching Intent to make the app open straight on the All Apps list.
 *  (Used by the "Pin All-apps shortcut" launcher shortcut.) */
const val EXTRA_OPEN_ALL_APPS = "com.ownapps.app.OPEN_ALL_APPS"

object Routes {
    const val APP_LIST = "app_list"
    const val SETTINGS = "settings"
    const val UI_HIDER = "ui_hider"
    const val FIREWALL = "firewall"
}

// Navigation-compose defaults every destination to EnterTransition.None/ExitTransition.None
// when nothing is set here, which (a) reads as an abrupt cut between screens and (b) is a known
// source of the outgoing screen's first post-pop tap landing on the wrong composable frame.
// Setting real transitions once at the NavHost level (applies to every composable() in the
// graph) fixes both.
private const val NAV_TRANSITION_MILLIS = 300

@Composable
fun OwnAppsNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.APP_LIST
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(NAV_TRANSITION_MILLIS)) +
                fadeIn(tween(NAV_TRANSITION_MILLIS))
        },
        exitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(NAV_TRANSITION_MILLIS)) +
                fadeOut(tween(NAV_TRANSITION_MILLIS))
        },
        popEnterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(NAV_TRANSITION_MILLIS)) +
                fadeIn(tween(NAV_TRANSITION_MILLIS))
        },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(NAV_TRANSITION_MILLIS)) +
                fadeOut(tween(NAV_TRANSITION_MILLIS))
        }
    ) {
        composable(Routes.APP_LIST) {
            AppListScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenFirewall = { navController.navigate(Routes.FIREWALL) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenUiHider = { navController.navigate(Routes.UI_HIDER) }
            )
        }
        composable(Routes.UI_HIDER) {
            UiHiderScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.FIREWALL) {
            FirewallScreen(onBack = { navController.popBackStack() })
        }
    }
}