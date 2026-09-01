package com.ownapps.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ownapps.app.OwnAppsApplication
import com.ownapps.app.di.AppContainer

@Composable
fun rememberAppContainer(): AppContainer {
    val context = LocalContext.current
    return remember { (context.applicationContext as OwnAppsApplication).container }
}
