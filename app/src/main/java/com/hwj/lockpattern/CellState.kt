package com.hwj.lockpattern

import android.animation.ValueAnimator

/**
 * Created by HuangWeiJin on 2018/07/16.
 */
data class CellState(var scale: Float = 1.0f, var translateY: Float = 0.0f, var alpha: Float = 1.0f, var size: Float = 0f, var lineEndX: Float = Float.MIN_VALUE, var lineEndY: Float = Float.MIN_VALUE, var lineAnimator: ValueAnimator? = null)
