package dev.zanderp.opencfmoto

import kotlin.math.max
import kotlin.math.min

/** Position is a fraction of the available travel, so the crop survives resize and fullscreen. */
internal data class TftPhotoCrop(val x: Float = .5f, val y: Float = .5f, val zoom: Float = 1f) {
    data class Frame(val left: Float, val top: Float, val width: Float, val height: Float)

    fun frame(imageWidth: Int, imageHeight: Int, fill: Boolean): Frame {
        val scaleX = WIDTH / imageWidth
        val scaleY = HEIGHT / imageHeight
        val scale = if (fill) max(scaleX, scaleY) * zoom else min(scaleX, scaleY)
        val width = imageWidth * scale
        val height = imageHeight * scale
        return Frame((WIDTH - width) * x, (HEIGHT - height) * y, width, height)
    }

    fun drag(dx: Float, dy: Float, imageWidth: Int, imageHeight: Int): TftPhotoCrop {
        val frame = frame(imageWidth, imageHeight, fill = true)
        val extraWidth = frame.width - WIDTH
        val extraHeight = frame.height - HEIGHT
        return copy(
            x = if (extraWidth > .01f) (x - dx / extraWidth).coerceIn(0f, 1f) else x,
            y = if (extraHeight > .01f) (y - dy / extraHeight).coerceIn(0f, 1f) else y,
        )
    }

    companion object {
        const val WIDTH = 640f
        const val HEIGHT = 384f
        const val MAX_ZOOM = 3f

        fun fromPosition(position: Int) = when (position) {
            1 -> TftPhotoCrop(y = 0f)
            2 -> TftPhotoCrop(y = 1f)
            3 -> TftPhotoCrop(x = 0f)
            4 -> TftPhotoCrop(x = 1f)
            else -> TftPhotoCrop()
        }
    }
}
