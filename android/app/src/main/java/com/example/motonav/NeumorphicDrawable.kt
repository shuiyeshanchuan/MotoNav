package com.example.motonav

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Native Android counterpart of the app's Soft UI tokens.  Software drawing is
 * intentional: Paint shadow layers are otherwise ignored by hardware Canvas on
 * several Android versions.
 */
class NeumorphicDrawable(
    context: Context,
    private val radiusDp: Float = 18f,
    private val recessed: Boolean = false,
    private val insetDp: Float = if (recessed) 2f else 7f
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val radius get() = radiusDp * density
    private val inset get() = insetDp * density
    private val offset get() = 5f * density
    private val blur get() = 10f * density
    private val rect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SURFACE }
    private var alphaValue = 255
    private var relief = if (recessed) 0f else 1f

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        if (rect.width() <= 0f || rect.height() <= 0f) return
        paint.alpha = alphaValue
        if (relief <= 0.5f) {
            paint.color = DEPTH_SURFACE
            paint.clearShadowLayer()
            canvas.drawRoundRect(rect, radius, radius, paint)
            return
        }
        paint.color = SURFACE
        paint.setShadowLayer(blur * relief, offset * relief, offset * relief, DARK_SHADOW)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.setShadowLayer(blur * relief, -offset * relief, -offset * relief, LIGHT_SHADOW)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.clearShadowLayer()
    }

    fun animatePressed(pressed: Boolean) {
        if (recessed) return
        val target = if (pressed) 0f else 1f
        ValueAnimator.ofFloat(relief, target).apply {
            duration = if (pressed) 160L else 300L
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
        val SURFACE: Int = Color.parseColor("#E0E5EC")
        val DEPTH_SURFACE: Int = Color.parseColor("#D1D9E6")
        val DARK_SHADOW: Int = Color.parseColor("#B8BCC2")
        val LIGHT_SHADOW: Int = Color.WHITE
    }
}

object Neumorphic {
    fun raised(context: Context, radiusDp: Float = 18f) = NeumorphicDrawable(context, radiusDp)
    fun recessed(context: Context, radiusDp: Float = 14f) = NeumorphicDrawable(context, radiusDp, recessed = true)

    fun applyRaised(view: View, radiusDp: Float = 18f, interactive: Boolean = false,
                    softwareLayer: Boolean = true) {
        val drawable = raised(view.context, radiusDp)
        // A software layer is useful for Paint shadow layers on small controls,
        // but a long settings card can exceed Android's bitmap cache limit.
        if (softwareLayer) view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        view.background = drawable
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
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        view.background = recessed(view.context, radiusDp)
    }
}
