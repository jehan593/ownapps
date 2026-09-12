package com.ownapps.app.ui.firewall

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateDpAsState
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ownapps.app.ui.components.FirewallRow
import com.ownapps.app.ui.rememberAppContainer
import kotlinx.coroutines.delay
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
        // Scope to the activity (not the back-stack entry) so the loaded list survives leaving
        // and re-entering the screen — re-entry then renders the rows already present instead of
        // spinning up a fresh, empty list that "pops in" every visit.
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
    val uiState by viewModel.uiState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }
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
    // Pinned rows live in a local snapshot so drag-to-reorder stays smooth while the DB Flow
    // re-emits. Reconciling in composition (not post-frame) keeps pin/unpin moves continuous.
    val orderedPinned = remember { mutableStateListOf<FirewallRow>() }
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
    // Clearing the search (cross button or deleting the text) restores the full list — bring the
    // user back to the top so the Pinned section gets visible again.
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) lazyListState.scrollToItem(0)
    }
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
                },
                actions = {
                    // Master switch (top-right): ON = enforcing (blocked apps have no internet), OFF = normal.
                    // Position comes from the persisted last-enforced state (see VM docs).
                    Switch(
                        checked = uiState.firewallEnabled,
                        onCheckedChange = { viewModel.setFirewallEnabled(it) },
                        enabled = uiState.canControl && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                        modifier = Modifier.padding(end = 12.dp)
                    )
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
                            "Shizuku is running, but permission isn't granted."
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

            if (uiState.isLoading && uiState.rows.isEmpty()) {
                // First load only — prevents the list (pinned apps included) from flashing in
                // out of nowhere; the launcher-only query is fast so this is a brief spinner.
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
                                // ON = every pinned app has internet access, mirroring the per-row
                                // switches. Same gating as the per-row switches: the master switch
                                // must be enforcing and the backend available.
                                checked = pinnedApps.all { !it.isBlocked },
                                onCheckedChange = { allowAll ->
                                    scope.launch {
                                        if (allowAll) viewModel.unblockAllPinned()
                                        else viewModel.blockAllPinned()
                                    }
                                },
                                enabled = canToggle,
                                // Matches the row switches, whose trailing edge sits 16dp further
                                // in (card outer + row inner padding).
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
                            FirewallRow(
                                icon = app.icon,
                                label = app.label,
                                isBlocked = app.isBlocked,
                                canToggle = canToggle,
                                onToggleBlocked = { viewModel.toggleBlocked(app.packageName, !app.isBlocked) },
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
                        FirewallRow(
                            icon = app.icon,
                            label = app.label,
                            isBlocked = app.isBlocked,
                            canToggle = canToggle,
                            onToggleBlocked = { viewModel.toggleBlocked(app.packageName, !app.isBlocked) },
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
}

// Pinned rows sit at LazyColumn indices 1..pinnedCount (index 0 is the section header), so
// mapping a reorderable from/to index back to a pinned-list position subtracts this offset.
private const val PINNED_ITEM_OFFSET = 1

// Minimum time the pull-to-refresh indicator stays visible, so a fast refresh is perceptible.
private const val MIN_PULL_REFRESH_MILLIS = 800L

tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}