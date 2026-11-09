package dev.ajithv.triptracker

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** A ScrollView that sizes to its content up to [maxHeightPx], then scrolls — so a long search-history list
 *  stays within a bounded, scrollable area instead of filling the screen. */
class MaxHeightScrollView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : ScrollView(ctx, attrs) {
    var maxHeightPx = 0
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val hs = if (maxHeightPx > 0) MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST) else heightSpec
        super.onMeasure(widthSpec, hs)
    }
}
