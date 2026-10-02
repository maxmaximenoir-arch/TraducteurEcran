package com.traducteur.ecran

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Gère la bulle flottante et le calque transparent qui affiche les traductions. */
class OverlayController(
    private val ctx: Context,
    private val onToggle: () -> Unit,
    private val onClose: () -> Unit
) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val dp = ctx.resources.displayMetrics.density
    private var layer: FrameLayout? = null
    private var bubble: ImageView? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams

    private fun params(w: Int, h: Int, extraFlags: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        extraFlags or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        // Calque plein écran qui laisse passer les doigts (défilement normal).
        // alpha 0.8 = maximum autorisé par Android pour laisser passer les touches.
        val l = FrameLayout(ctx)
        wm.addView(
            l,
            params(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            ).apply { alpha = 0.8f }
        )
        layer = l

        // Bulle flottante, sur le bord droit.
        val size = (52 * dp).toInt()
        val b = ImageView(ctx).apply { setImageResource(R.drawable.ic_launcher) }
        val metrics = ctx.resources.displayMetrics
        bubbleParams = params(size, size, 0).apply {
            x = metrics.widthPixels - size - (6 * dp).toInt()
            y = (metrics.heightPixels * 0.35f).toInt()
        }
        b.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0; var startY = 0
            var touchX = 0f; var touchY = 0f
            var moved = false; var downAt = 0L

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = bubbleParams.x; startY = bubbleParams.y
                        touchX = e.rawX; touchY = e.rawY
                        moved = false; downAt = e.eventTime
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - touchX
                        val dy = e.rawY - touchY
                        if (abs(dx) > 10 * dp || abs(dy) > 10 * dp) moved = true
                        if (moved) {
                            bubbleParams.x = startX + dx.toInt()
                            bubbleParams.y = startY + dy.toInt()
                            wm.updateViewLayout(b, bubbleParams)
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) {
                        if (e.eventTime - downAt > 800) onClose() else onToggle()
                    }
                }
                return true
            }
        })
        wm.addView(b, bubbleParams)
        bubble = b
    }

    fun setActive(active: Boolean) {
        bubble?.alpha = if (active) 1f else 0.4f
    }

    fun bubbleRect(): Rect? {
        val b = bubble ?: return null
        val loc = IntArray(2)
        b.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + b.width, loc[1] + b.height)
    }

    /** Pose chaque traduction pile sur la bulle anglaise d'origine. */
    fun showLabels(blocks: List<TranslatedBlock>) {
        val l = layer ?: return
        l.removeAllViews()
        val origin = IntArray(2)
        l.getLocationOnScreen(origin)
        val screenW = if (l.width > 0) l.width else ctx.resources.displayMetrics.widthPixels
        val pad = (6 * dp).toInt()
        val margin = (4 * dp).toInt()

        for (b in blocks) {
            val w = min(screenW - 2 * margin, max(b.rect.width() + 2 * pad, (120 * dp).toInt()))
            val h = max((b.rect.height() * 1.25f).toInt() + pad, (34 * dp).toInt())

            val tv = TextView(ctx).apply {
                text = b.text
                setTextColor(Color.parseColor("#111111"))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(pad, pad / 2, pad, pad / 2)
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = 10 * dp
                    setStroke(max(1, dp.toInt()), Color.parseColor("#33000000"))
                }
                // Taille du texte ajustée automatiquement pour tenir dans la bulle.
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this, 8, 16, 1, TypedValue.COMPLEX_UNIT_SP
                )
            }
            val left = max(margin, min(b.rect.centerX() - origin[0] - w / 2, screenW - w - margin))
            val top = max(0, b.rect.centerY() - origin[1] - h / 2)
            l.addView(tv, FrameLayout.LayoutParams(w, h).apply {
                leftMargin = left
                topMargin = top
            })
        }
    }

    fun clearLabels() {
        layer?.removeAllViews()
    }

    fun remove() {
        try { layer?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { bubble?.let { wm.removeView(it) } } catch (_: Exception) {}
        layer = null
        bubble = null
    }
}
