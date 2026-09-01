package com.ownapps.app.uihider

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.ownapps.app.uihider.script.Budget
import com.ownapps.app.uihider.script.Interpreter
import com.ownapps.app.uihider.script.Parser
import com.ownapps.app.uihider.script.ScriptError
import com.ownapps.app.uihider.script.Stmt

/**
 * Advanced, scriptable view hider. Each user script is bound to a package and runs in the
 * background only while that app is foreground. Scripts read the accessibility tree, compute
 * geometry, and draw overlays / press back / press home.
 *
 * Robustness: every run is sandboxed with a [Budget] and wrapped in try/catch so a faulty or
 * runaway script can never crash or hang the accessibility service.
 */
class UiHider {

    companion object {
        const val INTENT_ACTION_REFRESH_UI_HIDER = "com.ownapps.app.refresh.uihider"
        private const val MIN_RUN_INTERVAL_MS = 80L
        private val SETTLE_RETRY_DELAYS_MS = longArrayOf(200L, 500L, 1_000L, 2_000L)
    }

    private lateinit var service: AccessibilityService
    private lateinit var overlay: UiHiderOverlayManager
    private var store: ScriptStore? = null

    private var config = UiHiderConfig()
    private var blockerScope: CoroutineScope? = null
    private var settingsJob: Job? = null
    private var settleRetryJob: Job? = null

    // A crashed accessibility service forces the user to re-enable it by hand, so nothing here may
    // ever throw uncaught. This handler swallows any residual coroutine exception so a faulty
    // script or a transient binder hiccup can only degrade an individual run — never kill us.
    private val crashShield = CoroutineExceptionHandler { _, throwable ->
        Log.e("UiHider", "Uncaught coroutine exception", throwable)
    }

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenMap: Map<String, Any?> = emptyMap()

    @Volatile private var scriptsByPackage: Map<String, List<CompiledScript>> = emptyMap()

    private var lastPackage = ""
    private var lastRunAt = 0L

    @Volatile private var lastCommands: List<DrawCommand> = emptyList()

    private class CompiledScript(val id: String, val program: List<Stmt>)

    private data class EventContext(
        val type: String,
        val packageName: String,
        val text: String?,
        val className: String?
    )

    /** [configFlow] is the DataStore-backed UiHiderConfig flow; the blocker recompiles on change. */
    fun setupBlocker(service: AccessibilityService, configFlow: Flow<UiHiderConfig>) {
        this.service = service
        overlay = UiHiderOverlayManager(service)
        if (store == null) store = ScriptStore(java.io.File(service.filesDir, "uihider_store.json"))
        val metrics = service.resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenMap = mapOf("width" to screenWidth.toDouble(), "height" to screenHeight.toDouble())

        blockerScope?.cancel()
        blockerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashShield)
        settingsJob = blockerScope?.launch(Dispatchers.IO) {
            try {
                configFlow.collectLatest { newConfig ->
                    config = newConfig
                    recompile()
                }
            } catch (t: Throwable) {
                // Collecting should never die: if the flow or recompile throws, keep backing off
                // rather than crash the process (which would disable the accessibility service).
                Log.e("UiHider", "Config collector failed", t)
                delay(2_000L)
            }
        }
    }

    fun setupReceivers() {
        val filter = IntentFilter(INTENT_ACTION_REFRESH_UI_HIDER)
        service.registerReceiver(refreshReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    fun removeReceivers() {
        try { service.unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
        settingsJob?.cancel()
        settleRetryJob?.cancel()
        blockerScope?.cancel()
        blockerScope = null
        clearOverlays()
        store?.close()
        store = null
    }

    private fun recompile() {
        val newMap = HashMap<String, MutableList<CompiledScript>>()
        if (config.isActive) {
            for (script in config.allScripts()) {
                if (!script.isEnabled || script.packageName.isBlank() || script.source.isBlank()) continue
                try {
                    val program = Parser.parse(script.source)
                    newMap.getOrPut(script.packageName) { ArrayList() }
                        .add(CompiledScript(script.id.ifEmpty { script.packageName }, program))
                } catch (e: ScriptError) {
                    Log.w("UiHider", "Compile error in '${script.label}': ${e.message}")
                }
            }
        }
        scriptsByPackage = newMap
        if (!config.isActive) {
            settleRetryJob?.cancel()
            clearOverlays()
        } else {
            scheduleCurrentWindowRetries()
        }
    }

    fun doUiHiderCheck(event: AccessibilityEvent?) {
        if (event == null || !config.isActive) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == service.packageName) return

        val scripts = scriptsByPackage[pkg]
        if (scripts.isNullOrEmpty()) {
            if (lastPackage != pkg) {
                settleRetryJob?.cancel()
                clearOverlays()
                lastPackage = pkg
            }
            return
        }
        val packageChanged = lastPackage != pkg
        lastPackage = pkg

        val now = SystemClock.uptimeMillis()
        val isWindowChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!isWindowChange && now - lastRunAt < MIN_RUN_INTERVAL_MS) return
        lastRunAt = now

        val eventContext = EventContext(
            type = eventTypeName(event.eventType),
            packageName = pkg,
            text = event.text.joinToString(" ").takeIf { it.isNotEmpty() },
            className = event.className?.toString()
        )
        scheduleRun(pkg, scripts, eventContext)
        if (packageChanged || isWindowChange) {
            scheduleSettleRetries(pkg, eventContext)
        }
    }

    /** Snapshot the active window on the accessibility thread, then evaluate scripts off it so the
     *  interpreter never blocks (or ANRs) the service's main thread. */
    private fun scheduleRun(pkg: String, scripts: List<CompiledScript>, event: EventContext) {
        val scope = blockerScope ?: return
        val root = try {
            service.rootInActiveWindow
        } catch (t: Throwable) {
            Log.w("UiHider", "Failed to read active window: ${t.message}")
            null
        } ?: return
        scope.launch { runScripts(root, pkg, scripts, event) }
    }

    @Synchronized
    private fun runScripts(
        root: AccessibilityNodeInfo,
        pkg: String,
        scripts: List<CompiledScript>,
        event: EventContext
    ) {
        try {
            if (root.packageName?.toString() != pkg) return

            val commands = ArrayList<DrawCommand>()
            val globals = buildGlobals(event)
            val store = store ?: return
            for (compiled in scripts) {
                val budget = Budget()
                val runtime = UiHiderRuntime(service, root, budget, globals, compiled.id, store)
                try {
                    Interpreter(runtime, budget).run(compiled.program)
                    for (cmd in runtime.drawCommands) {
                        commands.add(cmd.copy(key = "${compiled.id}::${cmd.key}"))
                    }
                } catch (e: ScriptError) {
                    Log.w("UiHider", "Runtime error in script '${compiled.id}': ${e.message}")
                } finally {
                    if (runtime.output.isNotEmpty()) {
                        Log.i("UiHider", "[${compiled.id}] ${runtime.output.toString().trimEnd()}")
                    }
                    runtime.finish()
                }
            }
            if (commands != lastCommands) {
                overlay.apply(commands)
                lastCommands = commands
            }
        } catch (t: Throwable) {
            Log.e("UiHider", "Error running scripts for $pkg", t)
        } finally {
            @Suppress("DEPRECATION") root.recycle()
        }
    }

    private fun scheduleCurrentWindowRetries() {
        val scope = blockerScope ?: return
        val root = try {
            service.rootInActiveWindow
        } catch (t: Throwable) {
            Log.e("UiHider", "Error reading the active window", t)
            return
        } ?: return
        val pkg = try { root.packageName?.toString() } finally {
            @Suppress("DEPRECATION") root.recycle()
        } ?: return

        if (!scriptsByPackage[pkg].isNullOrEmpty()) {
            scheduleSettleRetries(
                pkg,
                EventContext("content", pkg, text = null, className = null)
            )
        }
    }

    private fun scheduleSettleRetries(pkg: String, event: EventContext) {
        val scope = blockerScope ?: return
        settleRetryJob?.cancel()
        settleRetryJob = scope.launch {
            for (delayMs in SETTLE_RETRY_DELAYS_MS) {
                delay(delayMs)
                val scripts = scriptsByPackage[pkg] ?: return@launch
                // rootInActiveWindow must be read on the main thread; evaluation runs here.
                val root = kotlinx.coroutines.withContext(Dispatchers.Main) {
                    try { service.rootInActiveWindow } catch (t: Throwable) { null }
                } ?: return@launch
                runScripts(root, pkg, scripts, event)
            }
        }
    }

    private fun clearOverlays() {
        overlay.clearAll()
        lastCommands = emptyList()
    }

    private fun buildGlobals(event: EventContext): Map<String, Any?> = mapOf(
        "app" to event.packageName,
        "screen" to screenMap,
        "event" to mapOf(
            "type" to event.type,
            "package" to event.packageName,
            "text" to event.text,
            "class" to event.className
        )
    )

    fun clearAndReset() {
        settleRetryJob?.cancel()
        clearOverlays()
    }

    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window_state"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "content"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "scrolled"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "clicked"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "selected"
        else -> "other"
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == INTENT_ACTION_REFRESH_UI_HIDER) {
                clearOverlays()
            }
        }
    }
}
