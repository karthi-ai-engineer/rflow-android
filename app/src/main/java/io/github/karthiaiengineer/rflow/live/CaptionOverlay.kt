package io.github.karthiaiengineer.rflow.live

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The floating caption bar over other apps while Rflow translates what the phone plays ("Display over other apps"):
 * the last finished line small, the one being said large. Dragged with a finger; ✕ stops the translation. Plain views,
 * not Compose: a service has no activity to host one.
 */
class CaptionOverlay(private val context: Context, private val onClose: () -> Unit) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    private val before = TextView(context).apply {
        setTextColor(Color.parseColor("#A6ACBA")); textSize = 13f; maxLines = 2
    }
    private val now = TextView(context).apply {
        setTextColor(Color.parseColor("#ECEEF3")); textSize = 18f; maxLines = 3
    }
    private val close = TextView(context).apply {
        text = "✕"; textSize = 16f; setTextColor(Color.parseColor("#A6ACBA"))
        setPadding(dp(10f), 0, 0, 0)
        setOnClickListener { onClose() }
        contentDescription = "Stop translating"
    }
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply {
            cornerRadius = dp(16f).toFloat(); setColor(Color.parseColor("#F0191C22")); setStroke(dp(1f), Color.parseColor("#33FFFFFF"))
        }
        setPadding(dp(14f), dp(10f), dp(12f), dp(12f))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(before); addView(now)
        }
        addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(close)
    }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        // The screen's width less a margin each side (a MATCH_PARENT window with a margin runs off the right edge).
        width = context.resources.displayMetrics.widthPixels - 2 * dp(12f)
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = dp(96f)
    }
    private var shown = false

    @SuppressLint("ClickableViewAccessibility")
    fun show() = main.post {
        var startY = 0
        var touchY = 0f
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startY = params.y; touchY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    params.y = (startY - (e.rawY - touchY)).toInt().coerceAtLeast(0)
                    windows.updateViewLayout(root, params); true
                }
                else -> false
            }
        }
        now.text = "Listening…"
        windows.addView(root, params)
        shown = true
    }

    fun refresh() = main.post {
        if (!shown) return@post
        val ui = LiveHub.ui.value
        val last = ui.lines.lastOrNull()?.line?.translation.orEmpty()
        val current = ui.current[0]
        before.text = last
        before.visibility = if (last.isEmpty()) View.GONE else View.VISIBLE
        now.text = current?.second?.ifEmpty { null } ?: if (last.isEmpty()) ui.problem.ifEmpty { ui.status } else "…"
    }

    fun hide() = main.post {
        if (shown) runCatching { windows.removeView(root) }
        shown = false
    }
}
