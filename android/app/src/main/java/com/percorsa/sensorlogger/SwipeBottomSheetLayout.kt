package com.percorsa.sensorlogger

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout

/** LinearLayout that intercepts vertical drags while preserving child clicks. */
class SwipeBottomSheetLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var onVerticalSwipe: ((expand: Boolean) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.rawY - downY) > touchSlop) {
                    dragging = true
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && dragging) {
            onVerticalSwipe?.invoke(event.rawY < downY)
            dragging = false
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) dragging = false
        return true
    }
}
