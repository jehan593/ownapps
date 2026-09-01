package com.ownapps.app.ui.firewall

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateDpAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ownapps.app.ui.components.FirewallRow
import com.ownapps.app.ui.rememberAppContainer
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * The per-app network firewall. A master switch turns Chain 3 on/off; per-app toggles block or
 * allow internet access through the Shizuku backend. Search + pin + reorder work just like the
 * All Apps list, with its own independent pinned set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirewallScreen(onBack: () -> Unit) {
    val container = rememberAppContainer()
    val scope = rememberCoroutineScope()

    val viewModel: FirewallViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                FirewallViewModel(
                    container.installedAppsRepository,
                    container.firewallRulesRepository,
                    container.firewallPinnedAppsRepository,
                    container.firewallController,
                    container.firewallBlocker,
                    container.settingsRepository
                )
            }
        }
    )
    val uiState by viewModel.uiState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(searchQuery, uiState.rows) {
        if (searchQuery.isBlank()) {
            uiState.rows
        } else {
            uiState.rows.filter { it.label.contains(searchQuery, ignoreCase = true) }
        }
    }
    val pinnedApps = remember(filteredApps) {
        filteredApps.filter { it.isPinned }.sortedBy { it.pinPosition }
    }
    val otherApps = remember(filteredApps) { filteredApps.filterNot { it.isPinned } }
    // Same refresh mechanics as the All Apps list: pinned rows live in a reorder-only local
    // snapshot so drags stay smooth while the DB-backed Flow re-emits.
    val orderedPinned = remember { mutableStateListOf<FirewallRow>().apply { addAll(pinnedApps) } }
    val displayPinned by remember(orderedPinned, pinnedApps) {
        derivedStateOf {
            val byName = pinnedApps.associateBy { it.packageName }
            orderedPinned.mapNotNull { byName[it.packageName] }
        }
    }
    LaunchedEffect(pinnedApps) {
        val current = pinnedApps.map { it.packageName }.toSet()
        if (orderedPinned.map { it.packageName }.toSet() != current) {
            orderedPinned.clear()
            orderedPinned.addAll(pinnedApps)
        }
    }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (orderedPinned.size < 2) return@rememberReorderableLazyListState
        val fromPos = (from.index - PINNED_ITEM_OFFSET).coerceIn(0, orderedPinned.lastIndex)
        val toPos = (to.index - PINNED_ITEM_OFFSET).coerceIn(0, orderedPinned.lastIndex)
        if (fromPos != toPos) {
            orderedPinned.add(toPos, orderedPinned.removeAt(fromPos))
        }
    }

    val canToggle = uiState.canControl && uiState.firewallEnabled

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { viewModel.refresh() }
                // Return to the top every time the screen becomes visible — if the activity
                // was only backgrounded the old scroll offset would otherwise survive, leaving
                // the pinned section hidden below the fold and looking like it "disappeared".
                scope.launch { lazyListState.scrollToItem(0) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Firewall") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Block internet access", style = MaterialTheme.typography.titleSmall)
                        // Master switch keeps the original semantics: ON = firewall enforcing
                        // (selected apps have no internet), OFF = everything connects normally.
                        Text(
                            text = if (uiState.firewallEnabled) {
                                "Blocked apps can't send or receive data. They reconnect when you turn this off."
                            } else {
                                "Turn on to stop selected apps from using the internet."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = uiState.firewallEnabled,
                        onCheckedChange = { viewModel.setFirewallEnabled(it) },
                        enabled = uiState.canControl && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    )
                }
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "The firewall needs Android 11 or newer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            } else if (uiState.checkedBackend && !uiState.canControl) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        !uiState.isServiceReady ->
                            "Firewall needs Shizuku (or a compatible backend like Sui) running."
                        !uiState.isPermissionGranted ->
                            "Shizuku is running, but permission was not granted."
                        else -> "The firewall backend is unavailable."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                if (uiState.isServiceReady && !uiState.isPermissionGranted) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.requestPermission() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Text("Grant Shizuku permission")
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                singleLine = true,
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
            )

            if (uiState.isLoading) {
                // First load only — prevents the list (pinned apps included) from flashing in
                // out of nowhere; the launcher-only query is fast so this is a brief spinner.
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), state = lazyListState) {
                if (pinnedApps.isNotEmpty() && searchQuery.isBlank()) {
                    item(key = "pinned_header") {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(text = "Pinned", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.blockAllPinned() },
                                    enabled = canToggle,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    )
                                ) {
                                    Icon(Icons.Filled.Block, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Block all")
                                }
                                Button(
                                    onClick = { viewModel.unblockAllPinned() },
                                    enabled = canToggle,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Unblock all")
                                }
                            }
                        }
                    }
                }
                items(displayPinned, key = { it.packageName }) { app ->
                    ReorderableItem(reorderableState, key = app.packageName) { isDragging ->
                        val elevation by animateDpAsState(
                            if (isDragging) 6.dp else 0.dp,
                            label = "dragElevation"
                        )
                        Column {
                            Surface(shadowElevation = elevation) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FirewallRow(
                                        icon = app.icon,
                                        label = app.label,
                                        isBlocked = app.isBlocked,
                                        canToggle = canToggle,
                                        onToggleBlocked = { viewModel.toggleBlocked(app.packageName, !app.isBlocked) },
                                        isPinned = true,
                                        onTogglePin = { viewModel.togglePin(app.packageName) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        Icons.Filled.DragHandle,
                                        contentDescription = "Drag to reorder",
                                        modifier = Modifier
                                            .padding(end = 16.dp)
                                            .draggableHandle(
                                                onDragStopped = {
                                                    viewModel.reorderPinned(orderedPinned.map { it.packageName })
                                                }
                                            )
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                item(key = "all_header") {
                    Text(
                        text = "All apps",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(otherApps, key = { it.packageName }) { app ->
                    FirewallRow(
                        icon = app.icon,
                        label = app.label,
                        isBlocked = app.isBlocked,
                        canToggle = canToggle,
                        onToggleBlocked = { viewModel.toggleBlocked(app.packageName, !app.isBlocked) },
                        isPinned = app.isPinned,
                        onTogglePin = { viewModel.togglePin(app.packageName) },
                        modifier = Modifier.animateItem()
                    )
                    HorizontalDivider()
                }
            }
            }
        }
    }
}

// Pinned rows sit at LazyColumn indices 1..pinnedCount (index 0 is the section header), so
// mapping a reorderable from/to index back to a pinned-list position subtracts this offset.
private const val PINNED_ITEM_OFFSET = 1