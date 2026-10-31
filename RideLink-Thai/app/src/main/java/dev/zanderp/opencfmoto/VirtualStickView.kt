package dev.zanderp.opencfmoto

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import dev.zanderp.opencfmoto.aa.AaInput
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Dedicated Android Auto Virtual Stick & 5-Way Tactile Controller for non-touch TFT clusters
 * (Zontes 150X / 350D / 368G).
 *
 * Interaction modes supported simultaneously:
 *  - **Analog Stick Drag & Hold**: Drag puck in 4 directions (`KEY_UP`, `KEY_DOWN`, `KEY_LEFT`,
 *    `KEY_RIGHT`) with immediate firing + 230ms auto-repeat while held.
 *  - **D-Pad Quadrant Tap**: Tap any outer directional arrow directly for single-step focus move.
 *  - **Center Puck Tap**: Tap the center `"OK"` puck to confirm (`KEY_ENTER`).
 */
class VirtualStickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onDirection: ((keycode: Int) -> Unit)? = null
    var onCenterTap: (() -> Unit)? = null

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var activeDirection: Int = 0
    private var firedDirectionThisGesture = false
    private var centerPressed = false

    private var knobOffsetX = 0f
    private var knobOffsetY = 0f
    private var springAnimator: ValueAnimator? = null

    private val density = resources.displayMetrics.density

    private val outerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#141E33")
    }

    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        color = Color.parseColor("#4538BDF8")
    }

    private val guideRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#2294A3B8")
    }

    private val activeArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#34D399")
    }

    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#94A3B8")
    }

    private val chevronActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.0f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#34D399")
    }

    private val puckFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1E293B")
    }

    private val puckStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        color = Color.parseColor("#38BDF8")
    }

    private val okTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val arcRect = RectF()
    private val chevronPath = Path()

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val dir = activeDirection
            if (dir != 0) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onDirection?.invoke(dir)
                repeatHandler.postDelayed(this, 230L)
            }
        }
    }

    init {
        isClickable = true
        isHapticFeedbackEnabled = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * 0.5f
        val cy = height * 0.5f
        val radius = min(width, height) * 0.46f
        if (radius <= 0f) return

        canvas.drawCircle(cx, cy, radius, outerBgPaint)
        canvas.drawCircle(cx, cy, radius, outerRingPaint)
        canvas.drawCircle(cx, cy, radius * 0.60f, guideRingPaint)

        if (activeDirection != 0) {
            val inset = 4f * density
            arcRect.set(cx - radius + inset, cy - radius + inset, cx + radius - inset, cy + radius - inset)
            val startAngle = when (activeDirection) {
                AaInput.KEY_UP -> 235f
                AaInput.KEY_RIGHT -> 325f
                AaInput.KEY_DOWN -> 55f
                AaInput.KEY_LEFT -> 145f
                else -> 0f
            }
            canvas.drawArc(arcRect, startAngle, 70f, false, activeArcPaint)
        }

        val chevDist = radius * 0.77f
        val chevSize = 7.5f * density
        drawChevron(canvas, cx, cy - chevDist, 0f, chevSize, activeDirection == AaInput.KEY_UP)
        drawChevron(canvas, cx + chevDist, cy, 90f, chevSize, activeDirection == AaInput.KEY_RIGHT)
        drawChevron(canvas, cx, cy + chevDist, 180f, chevSize, activeDirection == AaInput.KEY_DOWN)
        drawChevron(canvas, cx - chevDist, cy, 270f, chevSize, activeDirection == AaInput.KEY_LEFT)

        val maxTravel = radius * 0.44f
        val px = cx + knobOffsetX * maxTravel
        val py = cy + knobOffsetY * maxTravel
        val puckRadius = radius * 0.34f

        puckFillPaint.shader = RadialGradient(
            px - puckRadius * 0.25f,
            py - puckRadius * 0.25f,
            puckRadius * 1.2f,
            if (centerPressed || activeDirection != 0) Color.parseColor("#1E3A5F") else Color.parseColor("#24344D"),
            Color.parseColor("#0F172A"),
            Shader.TileMode.CLAMP
        )
        puckStrokePaint.color = if (activeDirection != 0 || centerPressed) {
            Color.parseColor("#34D399")
        } else {
            Color.parseColor("#38BDF8")
        }

        canvas.drawCircle(px, py, puckRadius, puckFillPaint)
        canvas.drawCircle(px, py, puckRadius, puckStrokePaint)

        val textY = py - (okTextPaint.descent() + okTextPaint.ascent()) * 0.5f
        canvas.drawText("OK", px, textY, okTextPaint)
    }

    private fun drawChevron(
        canvas: Canvas,
        x: Float,
        y: Float,
        rotationDeg: Float,
        size: Float,
        active: Boolean,
    ) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rotationDeg)
        chevronPath.reset()
        chevronPath.moveTo(-size, size * 0.45f)
        chevronPath.lineTo(0f, -size * 0.55f)
        chevronPath.lineTo(size, size * 0.45f)
        canvas.drawPath(chevronPath, if (active) chevronActivePaint else chevronPaint)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width * 0.5f
        val cy = height * 0.5f
        val radius = min(width, height) * 0.46f
        if (radius <= 0f) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                springAnimator?.cancel()
                firedDirectionThisGesture = false
                updateStickFromTouch(event.x - cx, event.y - cy, radius, isDown = true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                updateStickFromTouch(event.x - cx, event.y - cy, radius, isDown = false)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                repeatHandler.removeCallbacks(repeatRunnable)
                if (event.actionMasked == MotionEvent.ACTION_UP && !firedDirectionThisGesture && centerPressed) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onCenterTap?.invoke()
                }
                activeDirection = 0
                centerPressed = false
                animateReturnToCenter()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateStickFromTouch(dx: Float, dy: Float, radius: Float, isDown: Boolean) {
        val dist = hypot(dx, dy)
        val maxDrag = radius * 0.65f
        val clamped = min(dist, maxDrag)
        val angle = atan2(dy, dx)

        knobOffsetX = if (maxDrag > 0f) (cos(angle) * clamped) / maxDrag else 0f
        knobOffsetY = if (maxDrag > 0f) (sin(angle) * clamped) / maxDrag else 0f

        val norm = if (radius > 0f) dist / radius else 0f
        val deadzone = 0.34f

        if (norm < deadzone) {
            if (isDown) centerPressed = true
            if (activeDirection != 0) {
                activeDirection = 0
                repeatHandler.removeCallbacks(repeatRunnable)
            }
            invalidate()
            return
        }

        centerPressed = false
        val newDir = if (abs(dx) >= abs(dy)) {
            if (dx > 0) AaInput.KEY_RIGHT else AaInput.KEY_LEFT
        } else {
            if (dy > 0) AaInput.KEY_DOWN else AaInput.KEY_UP
        }

        if (newDir != activeDirection) {
            activeDirection = newDir
            firedDirectionThisGesture = true
            repeatHandler.removeCallbacks(repeatRunnable)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onDirection?.invoke(newDir)
            repeatHandler.postDelayed(repeatRunnable, 300L)
        }
        invalidate()
    }

    private fun animateReturnToCenter() {
        val startX = knobOffsetX
        val startY = knobOffsetY
        if (abs(startX) < 0.01f && abs(startY) < 0.01f) {
            knobOffsetX = 0f
            knobOffsetY = 0f
            invalidate()
            return
        }
        springAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 180L
            interpolator = OvershootInterpolator(1.4f)
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                knobOffsetX = startX * f
                knobOffsetY = startY * f
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        repeatHandler.removeCallbacks(repeatRunnable)
        springAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
