package com.ownapps.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ownapps.app.ui.components.SecondaryActionButton
import com.ownapps.app.ui.rememberAppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UiHiderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = rememberAppContainer()
    val viewModel: UiHiderViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                UiHiderViewModel(container.settingsRepository, context.applicationContext)
            }
        }
    )
    val uiState by viewModel.uiState.collectAsState()

    // The picker needs a notification so it can stay available over another app.
    val requestNotificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.launchNodePicker()
    }

    fun startNodePicker() {
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> viewModel.launchNodePicker()
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED -> viewModel.launchNodePicker()
            else -> requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshServiceState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var editorState by remember { mutableStateOf<ScriptEditorState?>(null) }
    var pendingDelete by remember { mutableStateOf<UiHiderScriptItem?>(null) }

    AnimatedContent(
        targetState = editorState,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val direction = if (targetState != null) {
                AnimatedContentTransitionScope.SlideDirection.Start
            } else {
                AnimatedContentTransitionScope.SlideDirection.End
            }
            (slideIntoContainer(direction, tween(300)) + fadeIn(tween(300))) togetherWith
                (slideOutOfContainer(direction, tween(300)) + fadeOut(tween(300)))
        },
        label = "Script editor"
    ) { state ->
        if (state != null) {
            ScriptEditorScreen(
                state = state,
                validate = viewModel::validateSource,
                onBack = { editorState = null },
                onSave = { packageName, label, source ->
                    viewModel.upsertCustomScript(state.existingId, packageName, label, source)
                    editorState = null
                }
            )
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("UI Hider") },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            Switch(
                                checked = uiState.isActive,
                                onCheckedChange = { viewModel.setActive(it) },
                                modifier = Modifier.padding(end = 20.dp)
                            )
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
                    if (uiState.isActive && !uiState.serviceEnabled) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "Enable the accessibility service to make overlays work.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Open accessibility settings") }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    SecondaryActionButton(
                        onClick = ::startNodePicker,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState.serviceEnabled
                    ) {
                        Icon(Icons.Filled.GpsFixed, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Pick an element with the Node Picker")
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Scripts", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { editorState = ScriptEditorState(existingId = null) }) {
                            Icon(Icons.Filled.Add, contentDescription = "Add script")
                        }
                    }
                    Text(
                        "Each script works on one app while it's open.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(Modifier.height(8.dp))

                    for (script in uiState.scripts) {
                        ScriptRow(
                            script = script,
                            onToggleEnabled = { enabled -> viewModel.toggleCustomScript(script.id, enabled) },
                            onEdit = { editorState = scriptEditorState(script) },
                            onDelete = { pendingDelete = script }
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }

    pendingDelete?.let { script ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete script?") },
            text = { Text("${script.label} will be removed.") },
            shape = RoundedCornerShape(12.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        viewModel.deleteCustomScript(script.id)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

private data class ScriptEditorState(
    val existingId: String?,
    val packageName: String = "",
    val label: String = "",
    val source: String = ""
)

private fun scriptEditorState(script: UiHiderScriptItem): ScriptEditorState =
    ScriptEditorState(
        existingId = script.id,
        packageName = script.packageName,
        label = script.label,
        source = script.source
    )

@Composable
private fun ScriptRow(
    script: UiHiderScriptItem,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onEdit() }
            ) {
                Text(script.label, style = MaterialTheme.typography.titleSmall)
                Text(script.packageName, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
            Switch(
                checked = script.isEnabled,
                onCheckedChange = onToggleEnabled
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScriptEditorScreen(
    state: ScriptEditorState,
    validate: (String) -> String?,
    onBack: () -> Unit,
    onSave: (packageName: String, label: String, source: String) -> Unit
) {
    BackHandler(onBack = onBack)
    var packageName by remember { mutableStateOf(state.packageName) }
    var label by remember { mutableStateOf(state.label) }
    var sourceValue by remember { mutableStateOf(TextFieldValue(state.source)) }
    var error by remember { mutableStateOf<String?>(null) }
    val scriptHighlight = rememberNordScriptHighlight()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.existingId == null) "New script" else "Edit script") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            val validationError = validate(sourceValue.text)
                            if (validationError != null) {
                                error = validationError
                            } else if (packageName.isBlank()) {
                                error = "An app package is required."
                            } else {
                                onSave(packageName, label, sourceValue.text)
                            }
                        },
                        modifier = Modifier.padding(end = 12.dp)
                    ) { Text("Save") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = packageName,
                onValueChange = { packageName = it },
                label = { Text("App package", maxLines = 1) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label", maxLines = 1) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            ScriptEditorField(
                value = sourceValue,
                onValueChange = {
                    sourceValue = it
                    error = null
                },
                highlight = scriptHighlight,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            if (error != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * The script editor: an outlined box that fills the height it's given and scrolls its own text
 * internally. The caret is explicitly brought into view on every layout (against this box's own
 * scroll — the only scrollable ancestor), so the line being edited stays above the keyboard no
 * matter where you type: mid-script or at the end.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScriptEditorField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    highlight: VisualTransformation,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val scrollState = rememberScrollState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .background(colors.surface, shape)
            .border(1.dp, colors.outline, shape)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                "Script",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                visualTransformation = highlight,
                onTextLayout = { layout ->
                    scope.launch {
                        bringIntoViewRequester.bringIntoView(
                            layout.getCursorRect(value.selection.start)
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .bringIntoViewRequester(bringIntoViewRequester)
            )
        }
    }
}
