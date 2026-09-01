package com.ownapps.app.uihider

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ownapps.app.R

/**
 * The on screen surface of the Node Picker. It manages three accessibility overlay windows:
 *  - a full screen, touchable capture layer that reports where the user taps,
 *  - a non touchable highlight rectangle drawn over the currently selected node,
 *  - a touchable control panel with the navigation buttons and node info.
 *
 * Windows are stacked by add order, so the capture layer is added first (bottom), the highlight
 * sits above it but stays non touchable so taps fall through to capture, and the panel is added
 * last so its buttons receive their own touches. All WindowManager work runs on the main thread.
 */
class NodePickerOverlay(
    private val service: AccessibilityService,
    private val listener: Listener
) {

    interface Listener {
        fun onTap(x: Int, y: Int)
        fun onUp()
        fun onDeeper()
        fun onToggleInfo()
        fun onCopy()
        fun onClose()
    }

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var captureView: View? = null
    private var highlightView: View? = null
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var contentContainer: LinearLayout? = null
    private var collapsedBar: View? = null
    private var selectorText: TextView? = null
    private var infoText: TextView? = null
    private var infoScroll: ScrollView? = null

    private var atTop = false

    var isShowing = false
        private set

    val isInfoVisible: Boolean
        get() = infoScroll?.visibility == View.VISIBLE

    fun show() {
        handler.post {
            if (isShowing) return@post
            try {
                addCapture()
                addHighlight()
                addPanel()
                isShowing = true
            } catch (e: Exception) {
                Log.e("NodePicker", "Failed to show picker overlay", e)
                hide()
            }
        }
    }

    fun hide() {
        handler.post {
            removeView(panelView); panelView = null
            removeView(highlightView); highlightView = null
            removeView(captureView); captureView = null
            panelParams = null
            contentContainer = null
            collapsedBar = null
            selectorText = null
            infoText = null
            infoScroll = null
            isShowing = false
        }
    }

    fun highlight(bounds: Rect) {
        handler.post {
            val view = highlightView ?: return@post
            try {
                windowManager.updateViewLayout(view, highlightParams(bounds))
            } catch (e: Exception) {
                Log.e("NodePicker", "Failed to move highlight", e)
            }
        }
    }

    fun setSelector(text: String) {
        handler.post { selectorText?.text = text }
    }

    fun setInfo(text: String) {
        handler.post {
            infoText?.text = text
            infoScroll?.scrollTo(0, 0)
        }
    }

    fun setInfoVisible(visible: Boolean) {
        handler.post { infoScroll?.visibility = if (visible) View.VISIBLE else View.GONE }
    }

    private fun addCapture() {
        val view = View(service).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    listener.onTap(event.rawX.toInt(), event.rawY.toInt())
                }
                true
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(view, params)
        captureView = view
    }

    private fun addHighlight() {
        val accent = Color.rgb(0x4C, 0x8B, 0xF5)
        val view = View(service).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(60, 0x4C, 0x8B, 0xF5))
                setStroke(dp(2), accent)
            }
        }
        windowManager.addView(view, highlightParams(Rect()))
        highlightView = view
    }

    private fun addPanel() {
        val pad = dp(12)
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(0x1C, 0x1C, 0x1E))
                cornerRadius = dp(18).toFloat()
            }
        }

        val collapsed = button(service.getString(R.string.node_picker_show)) { setCollapsed(false) }.apply {
            visibility = View.GONE
        }
        panel.addView(collapsed, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        collapsedBar = collapsed

        val content = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        content.addView(TextView(service).apply {
            text = service.getString(R.string.node_picker_hint)
            setTextColor(Color.argb(180, 255, 255, 255))
            textSize = 12f
        })

        selectorText = TextView(service).apply {
            setTextColor(Color.rgb(0x9E, 0xC1, 0xFF))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, dp(6))
        }
        content.addView(selectorText)

        infoText = TextView(service).apply {
            setTextColor(Color.argb(230, 255, 255, 255))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(4), 0, dp(4))
        }
        infoScroll = ScrollView(service).apply {
            visibility = View.GONE
            addView(infoText)
        }
        content.addView(infoScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(200)
        ))

        content.addView(buttonRow(
            button(service.getString(R.string.node_picker_up)) { listener.onUp() },
            button(service.getString(R.string.node_picker_deeper)) { listener.onDeeper() },
            button(service.getString(R.string.node_picker_move)) { toggleSide() }
        ))
        content.addView(buttonRow(
            button(service.getString(R.string.node_picker_info)) { listener.onToggleInfo() },
            button(service.getString(R.string.node_picker_copy)) { listener.onCopy() },
            button(service.getString(R.string.node_picker_hide)) { setCollapsed(true) },
            button(service.getString(R.string.node_picker_close)) { listener.onClose() }
        ))

        panel.addView(content)
        contentContainer = content

        val params = panelParams()
        windowManager.addView(panel, params)
        panelView = panel
        panelParams = params
    }

    private fun setCollapsed(collapsed: Boolean) {
        contentContainer?.visibility = if (collapsed) View.GONE else View.VISIBLE
        collapsedBar?.visibility = if (collapsed) View.VISIBLE else View.GONE
    }

    private fun toggleSide() {
        atTop = !atTop
        val params = panelParams ?: return
        params.gravity = (if (atTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        try {
            windowManager.updateViewLayout(panelView, params)
        } catch (e: Exception) {
            Log.e("NodePicker", "Failed to move panel", e)
        }
    }

    private fun buttonRow(vararg buttons: Button): LinearLayout {
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        val m = dp(3)
        for (b in buttons) {
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(m, 0, m, 0)
            row.addView(b, lp)
        }
        return row
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(service).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Color.rgb(0x3A, 0x3A, 0x3C))
            cornerRadius = dp(10).toFloat()
        }
        minHeight = dp(40)
        setPadding(dp(2), 0, dp(2), 0)
        setOnClickListener { onClick() }
    }

    private fun panelParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }

    private fun highlightParams(bounds: Rect): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            bounds.width().coerceAtLeast(0),
            bounds.height().coerceAtLeast(0),
            bounds.left,
            bounds.top,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun removeView(view: View?) {
        view ?: return
        try {
            if (view.parent != null) windowManager.removeView(view)
        } catch (_: Exception) {}
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()
}
