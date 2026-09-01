package com.ownapps.app.ui.applist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ownapps.app.ui.components.AppRowWithBlock
import com.ownapps.app.ui.rememberAppContainer
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * A dedicated screen (not a section buried in Settings) so the search field can sit right below
 * the top bar with the results list filling the rest of the screen — putting search at the
 * bottom of a long scrolling settings page meant the keyboard covered the filtered results.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(onOpenSettings: () -> Unit, onOpenFirewall: () -> Unit) {
    val context = LocalContext.current
    val container = rememberAppContainer()
    val scope = rememberCoroutineScope()

    val viewModel: AppListViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                AppListViewModel(
                    container.installedAppsRepository,
                    container.suspendStateRepository,
                    container.pinnedAppsRepository,
                    container.packageBlocker
                )
            }
        }
    )
    val uiState by viewModel.uiState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(searchQuery, uiState.apps) {
        if (searchQuery.isBlank()) {
            uiState.apps
        } else {
            uiState.apps.filter { it.label.contains(searchQuery, ignoreCase = true) }
        }
    }
    val pinnedApps = remember(filteredApps) {
        filteredApps.filter { it.isPinned }.sortedBy { it.pinPosition }
    }
    val otherApps = remember(filteredApps) { filteredApps.filterNot { it.isPinned } }
    // Pinned rows live in a reorder-only local snapshot so drag-and-drop can animate moves without
    // fighting the DB-backed Flow re-emitting mid-drag. Resync is intentionally *set* based so a
    // pure reorder (same package set) never snaps the order back — that keeps drags smooth. Row
    // data is rendered via [displayPinned], which joins the live [pinnedApps] rows (current
    // isSuspended/label) onto this drag order, so enable toggles stay responsive.
    val orderedPinned = remember { mutableStateListOf<AppListRow>().apply { addAll(pinnedApps) } }
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
        // Pinned rows sit at LazyColumn indices 1..pinnedCount (index 0 is the section header).
        val fromPos = (from.index - PINNED_ITEM_OFFSET).coerceIn(0, orderedPinned.lastIndex)
        val toPos = (to.index - PINNED_ITEM_OFFSET).coerceIn(0, orderedPinned.lastIndex)
        if (fromPos != toPos) {
            orderedPinned.add(toPos, orderedPinned.removeAt(fromPos))
        }
    }

    // Re-query the installed app list every time this screen becomes visible, so the list reflects
    // newly installed/uninstalled apps and any state changes made elsewhere.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { viewModel.refresh() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("All apps") },
                actions = {
                    IconButton(onClick = onOpenFirewall) {
                        Icon(Icons.Filled.Shield, contentDescription = "Firewall")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                singleLine = true,
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
            )
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
                                    onClick = { viewModel.disableAllPinned() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    )
                                ) {
                                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Disable all")
                                }
                                Button(
                                    onClick = { viewModel.enableAllPinned() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Enable all")
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
                                        AppRowWithBlock(
                                            icon = app.icon,
                                            label = app.label,
                                            isDisabled = app.isSuspended,
                                            canDisable = uiState.canDisable,
                                            onToggleEnabled = { viewModel.toggleEnabled(app.packageName, !app.isSuspended) },
                                            onOpen = {
                                                scope.launch {
                                                    if (app.isSuspended) {
                                                        viewModel.enable(app.packageName)
                                                    }
                                                    launchApp(context, container, app.packageName)
                                                }
                                            },
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
                }

                items(otherApps, key = { it.packageName }) { app ->
                    AppRowWithBlock(
                        icon = app.icon,
                        label = app.label,
                        isDisabled = app.isSuspended,
                        canDisable = uiState.canDisable,
                        onToggleEnabled = { viewModel.toggleEnabled(app.packageName, !app.isSuspended) },
                        onOpen = {
                            scope.launch {
                                if (app.isSuspended) {
                                    viewModel.enable(app.packageName)
                                }
                                launchApp(context, container, app.packageName)
                            }
                        },
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

private fun launchApp(
    context: android.content.Context,
    container: com.ownapps.app.di.AppContainer,
    packageName: String
) {
    val intent = container.installedAppsRepository.getLaunchIntent(packageName) ?: return
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { /* App may have no visible launcher activity or was disabled. */ }
}

// Pinned rows sit at LazyColumn indices 1..pinnedCount (index 0 is the section header), so
// mapping a reorderable from/to index back to a pinned-list position subtracts this offset.
private const val PINNED_ITEM_OFFSET = 1