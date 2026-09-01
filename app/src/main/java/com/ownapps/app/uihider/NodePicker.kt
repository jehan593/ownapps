package com.ownapps.app.uihider

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.ownapps.app.R

/**
 * Lets a user inspect the live accessibility tree of whatever app is in the foreground. It runs
 * inside the [UiHiderService] because only the accessibility service can read the node tree and
 * draw accessibility overlays.
 *
 * Flow: the [NodePickerService] foreground notification broadcasts [ACTION_OPEN] when tapped, which
 * shows the picker overlay. The user taps an element to select the deepest node under their finger,
 * then walks the tree with the panel buttons (up to a parent, deeper into the first child). "Info"
 * prints the full node details plus a selector path, so it can be pasted into a UIHider script.
 *
 * The current selection is tracked as a list of child indices from the root rather than a held
 * [AccessibilityNodeInfo]. Navigation edits that path and re-resolves the node against a fresh
 * `rootInActiveWindow`, so the highlight always tracks the real node and a stale reference can never
 * crash the service.
 */
class NodePicker(private val service: AccessibilityService) : NodePickerOverlay.Listener {

    companion object {
        const val ACTION_OPEN = "com.ownapps.app.nodepicker.OPEN"
        const val ACTION_STOP = "com.ownapps.app.nodepicker.STOP"
        private const val MAX_VISIT = 6000
    }

    private val main = Handler(Looper.getMainLooper())

    private var overlay: NodePickerOverlay? = null
    // Child indices from the root to the selected node; empty list is the root, null is no selection.
    private var path: List<Int>? = null
    private var visitCount = 0

    /** True while the picker overlay is up, so the host can suspend background scripts. */
    @Volatile var isActive = false
        private set

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers() {
        val filter = IntentFilter().apply {
            addAction(ACTION_OPEN)
            addAction(ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            service.registerReceiver(receiver, filter)
        }
    }

    fun removeReceivers() {
        try { service.unregisterReceiver(receiver) } catch (_: Exception) {}
        stopPicker()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_OPEN -> startPicker()
                ACTION_STOP -> stopPicker()
            }
        }
    }

    private fun startPicker() {
        main.post {
            if (overlay == null) {
                path = null
                isActive = true
                overlay = NodePickerOverlay(service, this).also { it.show() }
            }
        }
    }

    private fun stopPicker() {
        main.post {
            path = null
            isActive = false
            overlay?.hide()
            overlay = null
        }
    }

    override fun onTap(x: Int, y: Int) {
        val root = service.rootInActiveWindow ?: return
        try {
            visitCount = 0
            path = pathTo(root, x, y, emptyList())
            refreshSelection()
        } catch (t: Throwable) {
            Log.e("NodePicker", "Failed to select node", t)
        } finally {
            recycle(root)
        }
    }

    override fun onUp() {
        val current = path ?: return
        if (current.isEmpty()) return
        path = current.dropLast(1)
        refreshSelection()
    }

    override fun onDeeper() {
        val current = path ?: return
        val root = service.rootInActiveWindow ?: return
        try {
            val node = resolve(root, current) ?: return
            val hasChild = node.childCount > 0
            recycle(node)
            if (hasChild) {
                path = current + 0
                refreshSelectionWith(root)
            }
        } catch (t: Throwable) {
            Log.e("NodePicker", "Failed to go deeper", t)
        } finally {
            recycle(root)
        }
    }

    override fun onToggleInfo() {
        val overlay = overlay ?: return
        val show = !overlay.isInfoVisible
        overlay.setInfoVisible(show)
        if (show) withCurrentNode { overlay.setInfo(buildInfo(it)) }
    }

    override fun onCopy() {
        withCurrentNode { node ->
            try {
                val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("selector", bestSelector(node)))
                toast(service.getString(R.string.node_picker_copied))
            } catch (e: Exception) {
                Log.e("NodePicker", "Failed to copy selector", e)
            }
        }
    }

    override fun onClose() {
        try { service.stopService(Intent(service, NodePickerService::class.java)) } catch (_: Exception) {}
        stopPicker()
    }

    /** Re-resolve the current path against a fresh root and refresh the overlay. */
    private fun refreshSelection() {
        val root = service.rootInActiveWindow ?: return
        try {
            refreshSelectionWith(root)
        } finally {
            recycle(root)
        }
    }

    private fun refreshSelectionWith(root: AccessibilityNodeInfo) {
        val p = path ?: return
        val node = resolve(root, p) ?: return
        try {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            overlay?.let {
                it.highlight(bounds)
                it.setSelector(bestSelector(node))
                if (it.isInfoVisible) it.setInfo(buildInfo(node))
            }
        } finally {
            recycle(node)
        }
    }

    /** Resolve [node] at the current path against a fresh root, run [block], then recycle. */
    private inline fun withCurrentNode(block: (AccessibilityNodeInfo) -> Unit) {
        val p = path ?: return
        val root = service.rootInActiveWindow ?: return
        try {
            val node = resolve(root, p) ?: return
            try { block(node) } finally { recycle(node) }
        } finally {
            recycle(root)
        }
    }

    /** Walk [path] from [root] by child index, returning an owned node or null if it no longer exists. */
    private fun resolve(root: AccessibilityNodeInfo, path: List<Int>): AccessibilityNodeInfo? {
        var cur = obtain(root)
        for (index in path) {
            val child = try { cur.getChild(index) } catch (_: Exception) { null }
            recycle(cur)
            if (child == null) return null
            cur = child
        }
        return cur
    }

    /** Path to the deepest node whose bounds contain (x, y), preferring later (upper) siblings. */
    private fun pathTo(node: AccessibilityNodeInfo, x: Int, y: Int, prefix: List<Int>): List<Int>? {
        if (visitCount++ > MAX_VISIT) return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        var best: List<Int>? = if (bounds.contains(x, y)) prefix else null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childBest = pathTo(child, x, y, prefix + i)
            recycle(child)
            if (childBest != null) best = childBest
        }
        return best
    }

    /** Most specific single selector for [node]: a view id, else a description, else a path. */
    private fun bestSelector(node: AccessibilityNodeInfo): String {
        node.viewIdResourceName?.let { if (it.isNotEmpty()) return "id:$it" }
        node.contentDescription?.toString()?.let { if (it.isNotEmpty()) return "desc:$it" }
        return buildPath(node)
    }

    /**
     * Class index path from the root down to [node], e.g. `path:FrameLayout[0]>RecyclerView[1]`.
     * Matches [NodeFinder.walkPath] semantics: the root itself is excluded, and each index counts
     * only siblings of the same class. Returned in the format usable inside `find(path="...")`.
     */
    private fun buildPath(node: AccessibilityNodeInfo): String {
        val segments = ArrayList<String>()
        var cur: AccessibilityNodeInfo? = obtain(node)
        try {
            while (cur != null) {
                val parent = cur.parent
                if (parent == null) { recycle(cur); break }
                val className = cur.className?.toString() ?: "?"
                var index = 0
                for (i in 0 until parent.childCount) {
                    val sibling = parent.getChild(i) ?: continue
                    val isCurrent = sibling == cur
                    val sameClass = sibling.className?.toString() == className
                    recycle(sibling)
                    if (isCurrent) break
                    if (sameClass) index++
                }
                segments.add(0, "$className[$index]")
                recycle(cur)
                cur = parent
            }
        } catch (e: Exception) {
            cur?.let { recycle(it) }
            Log.e("NodePicker", "Failed to build path", e)
        }
        return "path:" + segments.joinToString(">")
    }

    private fun buildInfo(node: AccessibilityNodeInfo): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return buildString {
            appendLine("class: ${node.className}")
            appendLine("id: ${node.viewIdResourceName ?: "none"}")
            appendLine("text: ${node.text ?: "none"}")
            appendLine("desc: ${node.contentDescription ?: "none"}")
            appendLine("bounds: [${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
            appendLine("clickable: ${node.isClickable}  scrollable: ${node.isScrollable}")
            appendLine("checkable: ${node.isCheckable}  enabled: ${node.isEnabled}")
            appendLine("focusable: ${node.isFocusable}  children: ${node.childCount}")
            appendLine("package: ${node.packageName}")
            appendLine()
            appendLine("— selectors —")
            node.viewIdResourceName?.let { if (it.isNotEmpty()) appendLine("id:$it") }
            node.contentDescription?.toString()?.let { if (it.isNotEmpty()) appendLine("desc:$it") }
            node.text?.toString()?.let { if (it.isNotEmpty()) appendLine("text:$it") }
            append(buildPath(node))
        }
    }

    private fun toast(message: String) {
        main.post { Toast.makeText(service, message, Toast.LENGTH_SHORT).show() }
    }

    private fun obtain(node: AccessibilityNodeInfo): AccessibilityNodeInfo =
        @Suppress("DEPRECATION") AccessibilityNodeInfo.obtain(node)

    private fun recycle(node: AccessibilityNodeInfo) {
        try { @Suppress("DEPRECATION") node.recycle() } catch (_: Exception) {}
    }
}
