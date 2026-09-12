package com.ownapps.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ownapps.app.ui.rememberAppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = rememberAppContainer()
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    container.packageController,
                    context.applicationContext
                )
            }
        }
    )
    val uiState by viewModel.uiState.collectAsState()
    val gap = Modifier.height(12.dp)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshShizukuState()
                viewModel.refreshUiHiderServiceState()
                viewModel.refreshBatteryState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            PermissionCard(
                title = "Shizuku",
                description = "Required to disable apps and run the firewall.",
                granted = uiState.shizukuReady,
                action = {
                    when {
                        !uiState.shizukuServiceReady ->
                            Text("Shizuku isn't running. Install and start it to enable app control.")
                        !uiState.shizukuPermissionGranted ->
                            Button(onClick = { viewModel.requestShizukuPermission() }) {
                                Text("Grant Shizuku permission")
                            }
                    }
                }
            )

            Spacer(gap)

            PermissionCard(
                title = "Accessibility",
                description = "Used by the UI Hider to overlay and hide distracting elements.",
                granted = uiState.uiHiderServiceEnabled,
                action = {
                    if (!uiState.uiHiderServiceEnabled) {
                        Button(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        ) {
                            Text("Turn on accessibility")
                        }
                    }
                }
            )

            Spacer(gap)

            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.requestBatteryOptimizationExemption() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Battery optimization", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = if (uiState.batteryOptimizationExempt) {
                                "Exempt — OwnApps keeps working in the background."
                            } else {
                                "Optimized — the system may stop OwnApps in the background."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (uiState.batteryOptimizationExempt) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Exempt",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Button(onClick = { viewModel.requestBatteryOptimizationExemption() }) {
                            Text("Ignore")
                        }
                    }
                }
            }

            Spacer(gap)
        }
    }
}

/** Shared card container for every Settings section — same rounded corners and surface background
 *  as the app-row cards in the All Apps list. */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), content = content)
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    action: @Composable () -> Unit
) {
    SettingsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (granted) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Granted",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (!granted) {
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}