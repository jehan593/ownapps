package com.ownapps.app.ui.applist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.ownapps.app.ui.firewall.FirewallViewModel
import com.ownapps.app.ui.firewall.findActivity
import com.ownapps.app.ui.rememberAppContainer
import kotlinx.coroutines.delay
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
fun AppListScreen(onOpenSettings: () -> Unit, onOpenFirewall: () -> Unit, onOpenUiHider: () -> Unit) {
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
    // Warm the activity-scoped Firewall ViewModel so its first visit renders with rows already
    // cached instead of the list popping in mid-transition. Same owner and factory as
    // FirewallScreen, so this is the identical instance that screen reads.
    val firewallViewModel: FirewallViewModel = viewModel(
        viewModelStoreOwner = checkNotNull(LocalContext.current.findActivity()),
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
    LaunchedEffect(Unit) { firewallViewModel.refresh() }
    val uiState by viewModel.uiState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    // Package of a disabled app whose row was tapped: ask before enabling + launching.
    var pendingOpen by remember { mutableStateOf<String?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
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
    // Pinned rows live in a local snapshot so drag-to-reorder stays smooth while the DB Flow
    // re-emits. Reconciling in composition (not post-frame) keeps pin/unpin moves continuous.
    val orderedPinned = remember { mutableStateListOf<AppListRow>() }
    if (orderedPinned.map { it.packageName }.toSet() != pinnedApps.map { it.packageName }.toSet()) {
        orderedPinned.clear()
        orderedPinned.addAll(pinnedApps)
    }
    val displayPinned by remember(orderedPinned, pinnedApps) {
        derivedStateOf {
            val byName = pinnedApps.associateBy { it.packageName }
            orderedPinned.mapNotNull { byName[it.packageName] }
        }
    }
    val lazyListState = rememberLazyListState()
    // Restoring the list after clearing search should show the pinned section again.
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) lazyListState.scrollToItem(0)
    }
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
                title = { Text("OwnApps") },
                actions = {
                    IconButton(onClick = onOpenUiHider) {
                        Icon(Icons.Filled.VisibilityOff, contentDescription = "UI Hider")
                    }
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
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    isRefreshing = true
                    val start = System.currentTimeMillis()
                    viewModel.refreshAll()
                    // Keep the indicator up for a minimum so a fast refresh still reads as one.
                    delay((MIN_PULL_REFRESH_MILLIS - (System.currentTimeMillis() - start)).coerceAtLeast(0))
                    isRefreshing = false
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
            if (uiState.permissionNeeded) {
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Grant Shizuku permission to manage apps.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.requestPermission() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Grant Shizuku permission")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                }
            )
            if (uiState.isLoading && uiState.apps.isEmpty()) {
                // First load only. The list and its toggle states are populated together (see
                // AppListViewModel.maybeEmit), so a spinner is shown instead of an empty list that
                // would otherwise pop in and snap switches into place a frame later.
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), state = lazyListState) {
                if (pinnedApps.isNotEmpty() && searchQuery.isBlank()) {
                    item(key = "pinned_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Pinned",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                // ON = every pinned app is enabled, mirroring the per-row switches.
                                checked = pinnedApps.all { !it.isSuspended },
                                onCheckedChange = { enableAll ->
                                    scope.launch {
                                        if (enableAll) viewModel.enableAllPinned()
                                        else viewModel.disableAllPinned()
                                    }
                                },
                                // Enabling is always allowed; disabling needs the privileged backend.
                                enabled = if (pinnedApps.all { !it.isSuspended }) uiState.canDisable else true,
                                // The row switches hide inside cards whose 16dp outer + 16dp inner
                                // padding seats their trailing edge 16dp further in; pad this header
                                // switch the same amount so its toggle lines up with theirs.
                                modifier = Modifier.padding(end = 16.dp)
                            )
                        }
                    }
                }
                items(displayPinned, key = { it.packageName }) { app ->
                    ReorderableItem(
                        reorderableState,
                        key = app.packageName,
                        // The library's default Modifier.animateItem() fades rows in on
                        // composition; keep only the placement (pin/unpin) motion.
                        animateItemModifier = Modifier.animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = null
                        )
                    ) { isDragging ->
                        val elevation by animateDpAsState(
                            if (isDragging) 6.dp else 0.dp,
                            label = "dragElevation"
                        )
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            // Pinned cards sit on a slightly lighter surface than the normal ones so
                            // the pinned section reads as a distinct group.
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = elevation)
                        ) {
                            AppRowWithBlock(
                                icon = app.icon,
                                label = app.label,
                                isDisabled = app.isSuspended,
                                canDisable = uiState.canDisable,
                                onToggleEnabled = { viewModel.toggleEnabled(app.packageName, !app.isSuspended) },
                                onOpen = {
                                    if (app.isSuspended) pendingOpen = app.packageName
                                    else scope.launch { launchApp(context, container, app.packageName) }
                                },
                                isPinned = true,
                                onTogglePin = { viewModel.togglePin(app.packageName) },
                                reorderGripModifier = Modifier
                                    .draggableHandle(
                                        onDragStopped = {
                                            viewModel.reorderPinned(orderedPinned.map { it.packageName })
                                        }
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                item(key = "all_header") {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                items(otherApps, key = { it.packageName }) { app ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = null
                            ),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        AppRowWithBlock(
                            icon = app.icon,
                            label = app.label,
                            isDisabled = app.isSuspended,
                            canDisable = uiState.canDisable,
                            onToggleEnabled = { viewModel.toggleEnabled(app.packageName, !app.isSuspended) },
                            onOpen = {
                                if (app.isSuspended) pendingOpen = app.packageName
                                else scope.launch { launchApp(context, container, app.packageName) }
                            },
                            isPinned = app.isPinned,
                            onTogglePin = { viewModel.togglePin(app.packageName) }
                        )
                    }
                }
            }
            }
            }
        }
    }
    pendingOpen?.let { packageName ->
        val app = uiState.apps.firstOrNull { it.packageName == packageName }
        AlertDialog(
            onDismissRequest = { pendingOpen = null },
            title = { Text("Enable and open?") },
            text = { Text("${app?.label ?: packageName} is disabled.") },
            shape = RoundedCornerShape(12.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            confirmButton = {
                Button(
                    onClick = {
                        pendingOpen = null
                        scope.launch {
                            viewModel.enable(packageName)
                            launchApp(context, container, packageName)
                        }
                    }
                ) { Text("Enable") }
            },
            dismissButton = {
                TextButton(onClick = { pendingOpen = null }) { Text("Cancel") }
            }
        )
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

// Minimum time the pull-to-refresh indicator stays visible, so a fast refresh is perceptible.
private const val MIN_PULL_REFRESH_MILLIS = 800L
