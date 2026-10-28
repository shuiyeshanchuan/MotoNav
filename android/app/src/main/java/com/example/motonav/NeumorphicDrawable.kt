package com.example.motonav

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils

/** Light tactile surfaces with clear boundaries and no bitmap shadow layers. */
class NeumorphicDrawable(
    context: Context,
    private val radiusDp: Float = 18f,
    private val recessed: Boolean = false
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val radius get() = radiusDp * density
    private val rect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SURFACE }
    private var alphaValue = 255
    private var relief = if (recessed) 0f else 1f
    private var pressAnimator: ValueAnimator? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        val edge = density
        rect.set(b.left + edge, b.top + edge, b.right - edge, b.bottom - edge)
        if (rect.width() <= 0f || rect.height() <= 0f) return
        paint.alpha = alphaValue
        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.blendARGB(DEPTH_SURFACE, SURFACE, relief)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = BORDER
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    fun animatePressed(pressed: Boolean) {
        if (recessed) return
        val target = if (pressed) 0f else 1f
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(relief, target).apply {
            duration = if (pressed) 90L else 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener { relief = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    override fun setAlpha(alpha: Int) { alphaValue = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        val SURFACE: Int = NavStyle.CARD
        val DEPTH_SURFACE: Int = NavStyle.CARD2
        val BORDER: Int = NavStyle.STROKE
    }
}

object Neumorphic {
    fun raised(context: Context, radiusDp: Float = 18f) = NeumorphicDrawable(context, radiusDp)
    fun recessed(context: Context, radiusDp: Float = 14f) = NeumorphicDrawable(context, radiusDp, recessed = true)

    @Suppress("UNUSED_PARAMETER")
    fun applyRaised(view: View, radiusDp: Float = 18f, interactive: Boolean = false,
                    softwareLayer: Boolean = true) {
        val drawable = raised(view.context, radiusDp)
        view.background = drawable
        if (!interactive) view.elevation = view.resources.displayMetrics.density * 1.5f
        if (interactive) {
            view.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> drawable.animatePressed(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drawable.animatePressed(false)
                }
                false
            }
        }
    }

    fun applyRecessed(view: View, radiusDp: Float = 14f) {
        view.background = recessed(view.context, radiusDp)
    }
}
