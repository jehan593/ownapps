package com.ownapps.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshServiceState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // When the Node Picker hands back a selector (to the clipboard), the user pastes it into a
    // new script's find(...) call; there is nothing to pre-fill here.
    var editorState by remember { mutableStateOf<ScriptEditorState?>(null) }

    if (editorState != null) {
        val state = editorState!!
        ScriptEditorScreen(
            state = state,
            validate = viewModel::validateSource,
            onBack = { editorState = null },
            onSave = { packageName, label, source ->
                viewModel.upsertCustomScript(state.existingId, packageName, label, source)
                editorState = null
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("UI Hider") },
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Hide distracting UI", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Hide annoying buttons and pop-ups in your apps.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = uiState.isActive,
                            onCheckedChange = { viewModel.setActive(it) }
                        )
                    }
                    if (uiState.isActive && !uiState.serviceEnabled) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Enable the accessibility service to make overlays work.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Open accessibility settings") }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { viewModel.launchNodePicker() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.GpsFixed, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text(if (uiState.serviceEnabled) "Pick an element with the Node Picker" else "Enable service to use the Node Picker")
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
                    onDelete = { viewModel.deleteCustomScript(script.id) }
                )
                Spacer(Modifier.height(6.dp))
            }
        }
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
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
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
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
                    TextButton(
                        onClick = {
                            val validationError = validate(sourceValue.text)
                            if (validationError != null) {
                                error = validationError
                            } else if (packageName.isBlank()) {
                                error = "An app package is required."
                            } else {
                                onSave(packageName, label, sourceValue.text)
                            }
                        }
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
                label = { Text("App package (e.g. com.whatsapp)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                singleLine = true,
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
