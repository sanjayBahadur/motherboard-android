package com.motherboard.focus.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class WarningOverlay(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { dismiss() }

    private var overlayRoot: FrameLayout? = null
    private var windowManager: WindowManager? = null

    private fun getWindowManager(): WindowManager {
        if (windowManager == null) {
            windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        return windowManager!!
    }

    /**
     * Show a warning pill with "Shorts: X / limit" for 2 seconds.
     * If called again before the previous pill is dismissed, the old pill is removed
     * and the timer restarts — so the pill stays visible as long as the user keeps scrolling.
     */
    fun show(count: Int, limit: Int) {
        // Cancel any pending dismiss
        handler.removeCallbacks(dismissRunnable)

        // Remove existing overlay if present
        overlayRoot?.let {
            try { getWindowManager().removeView(it) } catch (_: Exception) {}
        }

        // Determine severity color
        val ratio = count.toFloat() / limit.toFloat()
        val bgColor = when {
            ratio >= 0.9f -> Color.parseColor("#FF5722")     // Red (Ember80 variant)
            ratio >= 0.5f -> Color.parseColor("#FF9800")     // Orange (Sand80 variant)
            else -> Color.parseColor("#4CAF50")               // Green (Evergreen80 variant)
        }

        // Build the pill TextView
        val pill = TextView(service).apply {
            text = "Shorts: $count / $limit"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(
                dpToPx(20f),
                dpToPx(10f),
                dpToPx(20f),
                dpToPx(10f)
            )
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dpToPx(24f).toFloat()
            }
            elevation = dpToPx(6f).toFloat()
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        // Wrap in FrameLayout
        overlayRoot = FrameLayout(service).apply {
            addView(pill, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        // Layout params: trusted overlay, non-interactive, top-center
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpToPx(60f) // Below status bar
        }

        try {
            // Fade in
            overlayRoot?.alpha = 0f
            getWindowManager().addView(overlayRoot, params)
            overlayRoot?.animate()?.alpha(1f)?.setDuration(200)?.start()

            // Auto-dismiss after 2 seconds
            handler.postDelayed(dismissRunnable, 2000)
        } catch (e: Exception) {
            // If addView fails (e.g., permission issue), silently clean up
            overlayRoot = null
        }
    }

    /**
     * Dismiss the warning pill with a brief fade-out animation.
     * Safe to call multiple times — no-op if already dismissed.
     */
    fun dismiss() {
        handler.removeCallbacks(dismissRunnable)
        val view = overlayRoot ?: return
        overlayRoot = null

        view.animate()
            .alpha(0f)
            .translationY(-dpToPx(20f).toFloat())
            .setDuration(200)
            .withEndAction {
                try { getWindowManager().removeView(view) } catch (_: Exception) {}
            }
            .start()
    }

    /**
     * Clean up all resources. Called from service onDestroy.
     */
    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        overlayRoot?.let {
            try { getWindowManager().removeView(it) } catch (_: Exception) {}
        }
        overlayRoot = null
        windowManager = null
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            service.resources.displayMetrics
        ).toInt()
    }
}
