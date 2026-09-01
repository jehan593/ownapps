package com.ownapps.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
fun SettingsScreen(onBack: () -> Unit, onOpenUiHider: () -> Unit) {
    val context = LocalContext.current
    val container = rememberAppContainer()
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    container.settingsRepository,
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
            if (!uiState.shizukuReady) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Shizuku is needed to disable apps.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = when {
                                !uiState.shizukuServiceReady ->
                                    "Shizuku isn't running. Install and start it."
                                !uiState.shizukuPermissionGranted ->
                                    "Shizuku is running, but permission was not granted."
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        if (uiState.shizukuServiceReady && !uiState.shizukuPermissionGranted) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { viewModel.requestShizukuPermission() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Grant Shizuku permission")
                            }
                        }
                    }
                }
            }

            Spacer(gap)
            HorizontalDivider()
            Spacer(gap)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("UI Hider", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Hide distracting buttons and pop-ups in your apps.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = uiState.uiHiderEnabled,
                    onCheckedChange = { viewModel.setUiHiderEnabled(it) }
                )
            }
            if (uiState.uiHiderEnabled && !uiState.uiHiderServiceEnabled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Enable the accessibility service to make overlays work.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedButton(
                    onClick = {
                        viewModel.setUiHiderEnabled(true)
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open accessibility settings")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenUiHider,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Manage UI Hider scripts")
            }

            Spacer(gap)
            HorizontalDivider()
            Spacer(gap)
        }
    }
}
