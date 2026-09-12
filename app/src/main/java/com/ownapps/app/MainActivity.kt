package com.ownapps.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.ownapps.app.ui.navigation.OwnAppsNavHost
import com.ownapps.app.ui.theme.OwnAppsTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            OwnAppsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OwnAppsNavHost()
                }
            }
        }
    }
}