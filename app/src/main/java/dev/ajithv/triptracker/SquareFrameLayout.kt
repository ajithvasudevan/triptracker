package dev.ajithv.triptracker

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** A FrameLayout that is always as tall as it is wide — grid thumbnails stay square at any column count. */
class SquareFrameLayout @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null)
    : FrameLayout(ctx, attrs) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, widthSpec)
    }
}
