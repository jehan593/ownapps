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

object Routes {
    const val APP_LIST = "app_list"
    const val SETTINGS = "settings"
    const val UI_HIDER = "ui_hider"
    const val FIREWALL = "firewall"
}

// Navigation-compose defaults to an abrupt cut between screens (and a known source of misplaced
// post-pop taps). Set real transitions once so every destination slides and fades.
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
                onOpenFirewall = { navController.navigate(Routes.FIREWALL) },
                onOpenUiHider = { navController.navigate(Routes.UI_HIDER) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.UI_HIDER) {
            UiHiderScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.FIREWALL) {
            FirewallScreen(onBack = { navController.popBackStack() })
        }
    }
}