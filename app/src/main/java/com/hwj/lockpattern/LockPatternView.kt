package com.hwj.lockpattern

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Debug
import android.os.Parcelable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.animation.AnimationUtils
import android.view.animation.Interpolator

const val ASPECT_SQUARE = 0
const val ASPECT_LOCK_WIDTH = 1
const val ASPECT_LOCK_HEIGHT = 2
const val PROFILE_DRAWING = false
const val MILLS_PER_CIRCLE_ANIMATING = 700
const val DRAG_THRESHHOLD = 0.0F

fun patternToString(pattern: List<Cell>?) = if (null == pattern || pattern.isEmpty()) "" else String(ByteArray(pattern.size) { (pattern[it].row * 3 + pattern[it].column + '1'.toByte()).toByte() })

fun stringToPattern(s: String?): List<Cell>? {
    return if (null == s || "" == s) {
        null
    } else {
        val bytes = s.toByteArray()
        val result = ArrayList<Cell>(bytes.size)
        for (i in 0 until bytes.size) {
            val b: Int = bytes[i] - '1'.toByte()
            result.add(Cell.of(b / 3, b % 3))
        }
        result
    }
}

/**
 * 手势密码控件
 * rewrite with kotlin
 * Created by HuangWeiJin on 2018/07/10.
 */
class LockPatternView(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : View(context, attrs, defStyleAttr) {

    private val mCellStates = Array(3) { Array(3) { CellState() } }
    private var mPatternDrawLookup = Array(3) { Array(3) { false } }

    private var mOnPatternListener: OnPatternListener? = null

    private var mDotSize = 0
    private var mDotSizeActivated = 0
    private var mPathWidth = 0

    private var mDrawingProfilingStarted = false

    private val mPaint = Paint()
    private val mPathPaint = Paint()

    private var mInputEnable = true

    private val mPattern: ArrayList<Cell> = ArrayList(9)

    private var mInProgressX = -1
    private var mInProgressY = -1

    private var mAnimatingPeriodStart = 0L

    private var mPatternDisplayMode = DisplayMode.Correct
    private var mInstealthMode = false

    private var mEnableHapticFeedback = true
    private var mPatternInProgress = false

    private var mHitFactor = 0.6f

    private var mSquaredWith = 0f
    private var mSquareHeight = 0f

    private val mCurrentPath = Path()
    private val mInvalidate = Rect()
    private val mTmpInvalidateRect = Rect()

    private var mAspect = 0
    private var mRegularColor = Color.parseColor("#6EB4FF")
    private var mErrorColor = Color.RED
    private var mSuccessColor = Color.parseColor("#BB9966")

    private val mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(context, android.R.interpolator.fast_out_slow_in)
    private val mLinearOutSlowInInterpolator = AnimationUtils.loadInterpolator(context, android.R.interpolator.linear_out_slow_in)


    constructor(context: Context?, attrs: AttributeSet?) : this(context, attrs, 0)

    constructor(context: Context?) : this(context, null, 0)

    init {
        context?.let {
            val a = it.obtainStyledAttributes(attrs, R.styleable.LockPatternView)
            val aspect = a.getString(R.styleable.LockPatternView_aspect)
            mAspect = when (aspect) {
                "square" -> ASPECT_SQUARE
                "lock_width" -> ASPECT_LOCK_WIDTH
                "lock_height" -> ASPECT_LOCK_HEIGHT
                else -> ASPECT_SQUARE
            }
            isClickable = true
            mPathPaint.isAntiAlias = true
            mPathPaint.isDither = true
            mRegularColor = a.getColor(R.styleable.LockPatternView_regularColor, mRegularColor)
            mErrorColor = a.getColor(R.styleable.LockPatternView_errorColor, mErrorColor)
            mSuccessColor = a.getColor(R.styleable.LockPatternView_successColor, mSuccessColor)

            mPathPaint.color = a.getColor(R.styleable.LockPatternView_pathColor, mRegularColor)

            mPathPaint.style = Paint.Style.STROKE
            mPathPaint.strokeJoin = Paint.Join.ROUND
            mPathPaint.strokeCap = Paint.Cap.ROUND

            mPathWidth = a.getDimensionPixelSize(R.styleable.LockPatternView_pathWidth, resources.getDimensionPixelSize(R.dimen.lock_pattern_dot_line_width))
            mPathPaint.strokeWidth = mPathWidth.toFloat()

            mDotSize = a.getDimensionPixelSize(R.styleable.LockPatternView_dotSize, resources.getDimensionPixelSize(R.dimen.lock_pattern_dot_size))
            mDotSizeActivated = a.getDimensionPixelSize(R.styleable.LockPatternView_dotActivatedSize, resources.getDimensionPixelSize(R.dimen.lock_pattern_dot_size_activated))

            mInstealthMode = a.getBoolean(R.styleable.LockPatternView_inStealthMode, false)

            mPaint.isAntiAlias = true
            mPaint.isDither = true

            for (array in mCellStates) {
                for (e in array) {
                    e.size = mDotSize.toFloat()
                }
            }
            a.recycle()
            Unit
        }
    }

    private fun getCellStates(): Array<Array<CellState>> {
        return mCellStates
    }

    fun setInputEnable(inputEnable: Boolean) {
        mInputEnable = inputEnable
    }

    fun setInStealthMode(inStealthMode: Boolean) {
        mInstealthMode = inStealthMode
    }

    /**
     * 触摸到点的时候是否震动
     */
    fun isTactileFeedbackEnable(): Boolean {
        return mEnableHapticFeedback
    }

    fun setPattern(displayMode: DisplayMode, pattern: List<Cell>) {
        mPattern.clear()
        mPattern.addAll(pattern)
        clearPatternDrawLoockup()
        for (cell in pattern) {
            mPatternDrawLookup[cell.row][cell.column] = true
        }
        setDisplayMode(displayMode)
    }

    private fun clearPatternDrawLoockup() {
        for (i in 0..2) {
            for (j in 0..2) {
                mPatternDrawLookup[i][j] = false
            }
        }
    }

    fun setDisplayMode(displayMode: DisplayMode) {
        mPatternDisplayMode = displayMode
        if (displayMode == DisplayMode.Animate) {
            if (mPattern.size == 0) {
                throw IllegalStateException("pattern size == 0")
            }
            mAnimatingPeriodStart = SystemClock.elapsedRealtime()
            val first = mPattern[0]
            mInProgressX = getCenterXForColumn(first.column).toInt()
            mInProgressY = getCenterYForRow(first.row).toInt()
            clearPatternDrawLoockup()
        }
        invalidate()
    }

    private fun getCenterYForRow(row: Int): Float {
        return paddingTop + row * mSquareHeight + mSquareHeight / 2f
    }

    private fun getCenterXForColumn(column: Int): Float {
        return paddingLeft + column * mSquaredWith + mSquaredWith / 2f
    }

    private fun notifyCellAdded() {
        sendAccessEvent("lockscreen_access_pattern_cell_added")
        mOnPatternListener?.let { it.onPatternCellAdded(mPattern) }
    }

    private fun notifyPatternStarted() {
        sendAccessEvent("lockscreen_access_pattern_start")
        mOnPatternListener?.let { it.onPatternStart() }
    }

    private fun notifyPatternDetected() {
        sendAccessEvent("lockscreen_access_pattern_detected")
        mOnPatternListener?.let { it.onPatternDetected(mPattern) }
    }

    private fun notifyPatternClear() {
        sendAccessEvent("lockscreen_access_pattern_cleared")
        mOnPatternListener?.let { it.onPatternCleared() }
    }

    fun clearPattern() {
        resetPattern()
    }

    private fun resetPattern() {
        mPattern.clear()
        clearPatternDrawLoockup()
        mPatternDisplayMode = DisplayMode.Correct
        invalidate()
    }


    private fun sendAccessEvent(s: String) {
        announceForAccessibility(s)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val width = w - paddingLeft - paddingRight
        mSquaredWith = width / 3.0f
        val height = h - paddingTop - paddingBottom
        mSquareHeight = height / 3.0f
    }

    private fun resolveMeasured(measureSpec: Int, desired: Int): Int {
        var result: Int
        var specSize = MeasureSpec.getSize(measureSpec)
        result = when (MeasureSpec.getMode(measureSpec)) {
            MeasureSpec.UNSPECIFIED -> desired
            MeasureSpec.AT_MOST -> Math.max(specSize, desired)
            MeasureSpec.EXACTLY -> specSize
            else -> specSize
        }
        return result
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minimumWidth = suggestedMinimumWidth
        val minimumHeight = suggestedMinimumHeight
        var viewWidth = resolveMeasured(widthMeasureSpec, minimumWidth)
        var viewHeight = resolveMeasured(heightMeasureSpec, minimumHeight)
        when (mAspect) {
            ASPECT_SQUARE -> if (viewHeight > viewWidth) {
                viewHeight = viewWidth
            } else {
                viewWidth = viewHeight
            }
            ASPECT_LOCK_WIDTH -> viewHeight = Math.min(viewWidth, viewHeight)
            ASPECT_LOCK_HEIGHT -> viewWidth = Math.min(viewWidth, viewHeight)
        }
        setMeasuredDimension(viewWidth, viewHeight)
    }

    private fun detectAndAddHit(x: Float, y: Float): Cell? {
        val cell = checkForNewHit(x, y)
        cell?.run {
            var fillInGapCell: Cell? = null
            val pattern = mPattern
            if (!pattern.isEmpty()) {
                val lastCell = pattern.last()

                var dRow = cell.row - lastCell.row
                var dColumn = cell.column - lastCell.column
                var fillInRow = lastCell.row
                var fillInColumn = lastCell.column

                if ((Math.abs(dRow) == 2) && (Math.abs(dColumn) != 1)) {
                    fillInRow = lastCell.row + if (dRow > 0) 1 else -1
                }
                if ((Math.abs(dColumn) == 2) && (Math.abs(dRow) != 1)) {
                    fillInColumn = lastCell.column + if (dColumn > 0) 1 else -1
                }
                fillInGapCell = Cell.of(fillInRow, fillInColumn)
                Unit
            }
            fillInGapCell?.run {
                if (!mPatternDrawLookup[fillInGapCell.row][fillInGapCell.column]) {
                    addCellToPattern(fillInGapCell)
                }
            }
            addCellToPattern(cell)
            if (mEnableHapticFeedback) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING and HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
            }
            return cell
        }
        return null
    }


    private fun addCellToPattern(fillInGapCell: Cell) {
        mPatternDrawLookup[fillInGapCell.row][fillInGapCell.column] = true
        mPattern.add(fillInGapCell)
        if (!mInstealthMode) {
            startCellActivatedAnimation(fillInGapCell)
        }
        notifyCellAdded()
    }

    private fun startCellActivatedAnimation(fillInGapCell: Cell) {
        val cellState = mCellStates[fillInGapCell.row][fillInGapCell.column]
        startSizeAnimation(mDotSize, mDotSizeActivated, 96, mLinearOutSlowInInterpolator, cellState, Runnable {
            startSizeAnimation(mDotSizeActivated, mDotSize, 192L, mFastOutSlowInInterpolator, cellState, null)
        })
        startLineEndAnimation(cellState, mInProgressX, mInProgressY, getCenterXForColumn(fillInGapCell.column), getCenterYForRow(fillInGapCell.row))
    }

    private fun startLineEndAnimation(cellState: CellState, startX: Int, startY: Int, targetX: Float, targetY: Float) {
        val valueAnimator = ValueAnimator.ofFloat(0f, 1f)
        valueAnimator.addUpdateListener {
            val t = it.animatedValue as Float
            cellState.lineEndX = (1 - t) * startX + t * targetX
            cellState.lineEndY = (1 - t) * startY + t * targetY
            invalidate()
        }
        valueAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator?) {
                cellState.lineAnimator = null
            }
        })
        valueAnimator.duration = 100
        valueAnimator.start()
        cellState.lineAnimator = valueAnimator

    }

    private fun startSizeAnimation(start: Int, end: Int, duration: Long, mLinearOutSlowInInterpolator: Interpolator?, cellState: CellState, endRunnable: Runnable?) {
        val valueAnimator = ValueAnimator.ofFloat(start.toFloat(), end.toFloat())
        valueAnimator.addUpdateListener {
            cellState.size = it.animatedValue as Float
            invalidate()
        }
        if (null != endRunnable) {
            valueAnimator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator?) {
                    endRunnable.run()
                }
            })
        }
        valueAnimator.interpolator = mLinearOutSlowInInterpolator
        valueAnimator.duration = duration
        valueAnimator.start()

    }

    private fun checkForNewHit(x: Float, y: Float): Cell? {
        val rowHit: Int = getRowHit(y)
        if (rowHit < 0) {
            return null
        }
        val columnHit: Int = getColumnHit(x)
        if (columnHit < 0) {
            return null
        }
        if (mPatternDrawLookup[rowHit][columnHit]) {
            return null
        }
        return Cell.of(rowHit, columnHit)
    }

    private fun getColumnHit(x: Float): Int {
        val squareWidth = mSquaredWith
        var hitSize = squareWidth * mHitFactor
        var offset = paddingLeft + (squareWidth - hitSize) / 2f
        for (i in 0..2) {
            val hitLeft = offset + squareWidth * i
            if (x >= hitLeft && x <= hitLeft + hitSize) {
                return i
            }
        }
        return -1
    }

    private fun getRowHit(y: Float): Int {
        val squareHight = mSquareHeight
        var hitSize = squareHight * mHitFactor
        var offset = paddingTop + (squareHight - hitSize) / 2f
        for (i in 0..2) {
            val hitTop = offset + squareHight * i
            if (y >= hitTop && y <= (hitTop + hitSize)) {
                return i
            }
        }
        return -1
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (accessibilityManager.isTouchExplorationEnabled) {
            val action = event.action
            when (action) {
                MotionEvent.ACTION_HOVER_ENTER -> event.action = MotionEvent.ACTION_DOWN
                MotionEvent.ACTION_HOVER_MOVE -> event.action = MotionEvent.ACTION_MOVE
                MotionEvent.ACTION_HOVER_EXIT -> event.action = MotionEvent.ACTION_UP
            }
            onTouchEvent(event)
            event.action = action
        }
        return super.onHoverEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!mInputEnable || !isEnabled) {
            return false
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                handleActionDown(event)
                return true
            }
            MotionEvent.ACTION_UP -> {
                handleActionUp(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                handleActionMove(event)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mPatternInProgress) {
                    mPatternInProgress = false
                    resetPattern()
                    notifyPatternClear()
                }
                if (PROFILE_DRAWING) {
                    if (mDrawingProfilingStarted) {
                        Debug.stopMethodTracing()
                        mDrawingProfilingStarted = false
                    }
                }
                return true
            }
        }
        return false
    }

    private fun handleActionMove(event: MotionEvent) {
        val radius = mPathWidth
        val historySize = event.historySize
        mTmpInvalidateRect.setEmpty()
        var invalidateNow = false
        for (i in 0 until historySize) {
            val x = if (i < historySize) event.getHistoricalX(i) else event.x
            val y = if (i < historySize) event.getHistoricalY(i) else event.y
            val hitCell = detectAndAddHit(x, y)
            val patternSize = mPattern.size
            if (hitCell != null && patternSize == 1) {
                mPatternInProgress = true
                notifyPatternStarted()
            }
            val dx = Math.abs(x - mInProgressX)
            val dy = Math.abs(y - mInProgressY)
            if (dx > DRAG_THRESHHOLD || dy > DRAG_THRESHHOLD) {
                invalidateNow = true
            }
            if (mPatternInProgress && patternSize > 0) {
                val pattern = mPattern
                val lastCell = pattern.last()
                val lastCellCenterX = getCenterXForColumn(lastCell.column)
                val lastCellCenterY = getCenterYForRow(lastCell.row)
                var left = Math.min(lastCellCenterX, x) - radius
                var right = Math.max(lastCellCenterX, x) + radius
                var top = Math.min(lastCellCenterY, y) - radius
                var bottom = Math.max(lastCellCenterY, y) + radius
                if (hitCell != null) {
                    val width = mSquaredWith * 0.5f
                    val height = mSquareHeight * 0.5f
                    val hitCellCenterX = getCenterXForColumn(hitCell.column)
                    val hitCellCenterY = getCenterYForRow(hitCell.row)
                    left = Math.min(hitCellCenterX - width, left)
                    right = Math.max(hitCellCenterX + width, right)
                    top = Math.min(hitCellCenterY - height, top)
                    bottom = Math.max(hitCellCenterY + height, bottom)
                }
                mTmpInvalidateRect.union(Math.round(left), Math.round(top), Math.round(right), Math.round(bottom))
            }
        }
        mInProgressX = event.x.toInt()
        mInProgressY = event.y.toInt()
        if (invalidateNow) {
            mInvalidate.union(mTmpInvalidateRect)
            invalidate(mInvalidate)
            mInvalidate.set(mTmpInvalidateRect)
        }
    }

    private fun handleActionUp(event: MotionEvent) {
        if (!mPattern.isEmpty()) {
            mPatternInProgress = false
            cancelLineAnimations()
            notifyPatternDetected()
            invalidate()
        }
        if (PROFILE_DRAWING) {
            if (mDrawingProfilingStarted) {
                Debug.stopMethodTracing()
                mDrawingProfilingStarted = false
            }
        }
    }

    private fun cancelLineAnimations() {
        for (i in 0..2) {
            for (j in 0..2) {
                val state = mCellStates[i][j]
                state.lineAnimator?.let {
                    it.cancel()
                    state.lineEndX = Float.MIN_VALUE
                    state.lineEndY = Float.MIN_VALUE
                    Unit
                }
            }
        }

    }

    private fun handleActionDown(event: MotionEvent) {
        resetPattern()
        val x = event.x
        val y = event.y
        val hitCell = detectAndAddHit(x, y)
        if (null != hitCell) {
            mPatternInProgress = true
            mPatternDisplayMode = DisplayMode.Correct
            notifyPatternStarted()
        } else if (mPatternInProgress) {
            mPatternInProgress = false
            notifyPatternClear()
        }
        if (hitCell != null) {
            val startX = getCenterXForColumn(hitCell.column)
            val startY = getCenterYForRow((hitCell.row))
            val widthOffset = mSquaredWith / 2f
            val heightOffset = mSquareHeight / 2f
            invalidate((startX - widthOffset).toInt(), (startY - heightOffset).toInt(), (startX + widthOffset).toInt(), (startY + heightOffset).toInt())
        }
        mInProgressX = x.toInt()
        mInProgressY = y.toInt()
        if (PROFILE_DRAWING) {
            if (!mDrawingProfilingStarted) {
                Debug.startMethodTracing("LockPatternDrawing")
                mDrawingProfilingStarted = true
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val pattern = mPattern
        val count = pattern.size
        val drawLookup = mPatternDrawLookup
        if (mPatternDisplayMode == DisplayMode.Animate) {
            val oneCycle = (count + 1) * MILLS_PER_CIRCLE_ANIMATING
            val spotInCycle = (SystemClock.elapsedRealtime() - mAnimatingPeriodStart) % oneCycle
            val numCircles: Int = (spotInCycle / MILLS_PER_CIRCLE_ANIMATING).toInt()
            clearPatternDrawLoockup()
            for (i in 0 until numCircles) {
                val cell = pattern[i]
                drawLookup[cell.row][cell.column] = true
            }
            val needToUpdateInProgressPoint = numCircles in 1..(count - 1)
            if (needToUpdateInProgressPoint) {
                val percentageOfNextCircle: Float = ((spotInCycle % MILLS_PER_CIRCLE_ANIMATING) / MILLS_PER_CIRCLE_ANIMATING).toFloat()
                val currentCell = pattern[numCircles - 1]
                val centerX = getCenterXForColumn(currentCell.column)
                val centerY = getCenterYForRow(currentCell.row)
                val nextCell = pattern[numCircles]
                val dx = percentageOfNextCircle * (getCenterXForColumn(nextCell.column) - centerX)
                val dy = percentageOfNextCircle * (getCenterYForRow(nextCell.row) - centerY)
                mInProgressX = (centerX + dx).toInt()
                mInProgressY = (centerY + dy).toInt()
            }
            invalidate()
        }
        val currentPath = mCurrentPath
        currentPath.rewind()

        for (i in 0..2) {
            val centerY = getCenterYForRow(i)
            for (j in 0..2) {
                val cellState = mCellStates[i][j]
                val centerX = getCenterXForColumn((j))
                val size = cellState.size * cellState.scale
                val translationY = cellState.translateY
                drawCircle(canvas, centerX, centerY + translationY, size, drawLookup[i][j], cellState.alpha)
            }
        }
        val drawPath = !mInstealthMode
        if (drawPath) {
            mPathPaint.color = getCurrentColor(true)
            var anyCircles = false
            var lastX = 0f
            var lastY = 0f
            for (i in 0 until count) {
                val cell = pattern[i]
                if (!drawLookup[cell.row][cell.column]) {
                    break
                }
                anyCircles = true
                var centerX = getCenterXForColumn(cell.column)
                var centerY = getCenterYForRow((cell.row))
                if (i != 0) {
                    val state = mCellStates[cell.row][cell.column]
                    currentPath.rewind()
                    currentPath.moveTo(lastX, lastY)
                    if (state.lineEndX != Float.MIN_VALUE && state.lineEndY != Float.MIN_VALUE) {
                        currentPath.lineTo(centerX, centerY)
                    } else {
                        currentPath.lineTo(centerX, centerY)
                    }
                    canvas.drawPath(currentPath, mPathPaint)
                }
                lastX = centerX
                lastY = centerY
            }
            if ((mPatternInProgress || mPatternDisplayMode == DisplayMode.Animate) && anyCircles) {
                currentPath.rewind()
                currentPath.moveTo(lastX, lastY)
                currentPath.lineTo(mInProgressX.toFloat(), mInProgressY.toFloat())
                mPathPaint.alpha = (calculateLastSegmentAlpha(mInProgressX, mInProgressY, lastX, lastY) * 255f).toInt()
                canvas.drawPath(currentPath, mPathPaint)
            }
        }
    }

    private fun calculateLastSegmentAlpha(x: Int, y: Int, lastX: Float, lastY: Float): Float {
        val diffX = x - lastX
        val diffY = y - lastY
        val dist: Float = Math.sqrt((diffX * diffX + diffY * diffY).toDouble()).toFloat()
        val frac: Float = dist / mSquaredWith
        return Math.min(1f, Math.max(0f, (frac - 0.3f) * 4f))
    }

    private fun drawCircle(canvas: Canvas, centerX: Float, centerY: Float, size: Float, partOfPattern: Boolean, alpha: Float) {
        mPaint.color = getCurrentColor(partOfPattern)
        mPaint.alpha = (alpha * 255).toInt()
        canvas.drawCircle(centerX, centerY, size / 2, mPaint)
    }

    private fun getCurrentColor(partOfPattern: Boolean): Int {
        return if (!partOfPattern || mInstealthMode || mPatternInProgress) {
            mRegularColor
        } else if (mPatternDisplayMode == DisplayMode.Wrong) {
            mErrorColor
        } else if (mPatternDisplayMode == DisplayMode.Correct || mPatternDisplayMode == DisplayMode.Animate) {
            mSuccessColor
        } else {
            throw IllegalStateException("unknow display mode$mPatternDisplayMode")
        }

    }

    override fun onSaveInstanceState(): Parcelable {
        val superParcelable = super.onSaveInstanceState()
        return SavedState(superParcelable, patternToString(mPattern), mPatternDisplayMode.ordinal, mInputEnable, mInstealthMode, mEnableHapticFeedback)
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        val ss: SavedState = state as SavedState
        super.onRestoreInstanceState(ss.superState)
        setPattern(DisplayMode.Correct, stringToPattern(ss.mSerializedPattern)!!)
        mPatternDisplayMode = DisplayMode.values()[ss.mDisplayMode]
        mInputEnable = ss.mInputEnable
        mInstealthMode = ss.mInStealthMode
        mEnableHapticFeedback = ss.mTactileFeedbackEnabled
    }


}



