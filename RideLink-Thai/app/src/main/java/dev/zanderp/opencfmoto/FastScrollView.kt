package dev.zanderp.opencfmoto

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView
import kotlin.math.roundToInt

/**
 * High-refresh-rate (120Hz) tuned [ScrollView] with boosted fling impulse and zero overscroll drag
 * so scrolling tall settings/calibration cards feels instant, fast, and weightless.
 */
class FastScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {

    init {
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isNestedScrollingEnabled = false
        isSmoothScrollingEnabled = true
    }

    override fun fling(velocityY: Int) {
        val boosted = (velocityY * 1.55f).roundToInt()
        super.fling(boosted)
    }
}
