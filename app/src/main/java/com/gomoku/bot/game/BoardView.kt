package com.gomoku.bot.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 自绘棋盘：木纹背景、坐标、星位、棋子阴影与高光、最后一手标记、获胜连线。
 */
class BoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var engine: GameEngine? = null
        set(value) {
            field = value
            if (width > 0 && height > 0) {
                updateMetrics(width, height)
            }
            invalidate()
        }

    var interactive: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var onCellTap: ((Int, Int) -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val boardRect = RectF()
    private val gridRect = RectF()
    private var cellSize = 0f
    private var stoneRadius = 0f
    private var labelSpace = 0f

    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#8A6A42")
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#7A5A34")
    }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7A5A34")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A6A42")
        textAlign = Paint.Align.CENTER
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33000000")
    }
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val whiteStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#C4C2B8")
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C86B4A")
    }
    private val winLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66C86B4A")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val winRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0C86B4A")
        style = Paint.Style.STROKE
    }

    private var downX = -1
    private var downY = -1
    private var shaderRadius = 0f

    init {
        isClickable = true
        isFocusable = true
        labelSpace = dp(15f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val size = when {
            widthMode == MeasureSpec.UNSPECIFIED -> height
            heightMode == MeasureSpec.UNSPECIFIED -> width
            else -> min(width, height)
        }
        setMeasuredDimension(maxOf(size, 1), maxOf(size, 1))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMetrics(w, h)
    }

    private fun updateMetrics(w: Int, h: Int) {
        labelSpace = dp(15f)
        val outer = dp(4f)
        boardRect.set(outer, outer, w - outer, h - outer)

        val inner = RectF(
            boardRect.left + labelSpace,
            boardRect.top + labelSpace,
            boardRect.right - labelSpace,
            boardRect.bottom - labelSpace
        )
        gridRect.set(inner)

        val n = engine?.size ?: 15
        cellSize = if (n > 1) gridRect.width() / (n - 1) else gridRect.width()
        stoneRadius = cellSize * 0.46f
        if (stoneRadius <= 0f) stoneRadius = 1f

        val r = stoneRadius * 1.55f
        shaderRadius = r
        blackPaint.shader = RadialGradient(
            -stoneRadius * 0.32f, -stoneRadius * 0.36f, r,
            intArrayOf(Color.parseColor("#6E6E6E"), Color.parseColor("#232323"), Color.parseColor("#050505")),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        whitePaint.shader = RadialGradient(
            -stoneRadius * 0.32f, -stoneRadius * 0.36f, r,
            intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#F6F4EC"), Color.parseColor("#D2D0C6")),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        whiteStrokePaint.strokeWidth = dp(0.8f)
        winLinePaint.strokeWidth = cellSize * 0.16f
        winRingPaint.strokeWidth = dp(2.2f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val game = engine ?: return
        val n = game.size

        // 棋盘阴影
        canvas.drawRoundRect(
            RectF(boardRect.left, boardRect.top + dp(3f), boardRect.right, boardRect.bottom + dp(3f)),
            dp(12f), dp(12f), shadowPaint
        )
        boardPaint.shader = LinearGradient(
            0f, boardRect.top, 0f, boardRect.bottom,
            Color.parseColor("#EAC084"), Color.parseColor("#D8A55F"), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(boardRect, dp(12f), dp(12f), boardPaint)
        boardPaint.shader = null

        // 网格
        gridPaint.strokeWidth = maxOf(dp(0.7f), cellSize * 0.035f)
        for (i in 0 until n) {
            val x = gridRect.left + i * cellSize
            val y = gridRect.top + i * cellSize
            canvas.drawLine(x, gridRect.top, x, gridRect.bottom, gridPaint)
            canvas.drawLine(gridRect.left, y, gridRect.right, y, gridPaint)
        }
        borderPaint.strokeWidth = gridPaint.strokeWidth * 1.8f
        canvas.drawRect(gridRect, borderPaint)

        drawStars(canvas, n)
        drawCoordinates(canvas, n)

        drawWinLine(canvas, game)

        // 棋子
        for (y in 0 until n) {
            for (x in 0 until n) {
                val stone = game.stoneAt(x, y)
                if (stone == Stone.EMPTY) continue
                drawStone(canvas, x, y, stone)
            }
        }

        // 最后一手
        game.lastMove?.let { move ->
            val cx = gridRect.left + move.x * cellSize
            val cy = gridRect.top + move.y * cellSize
            if (game.winner == null) {
                canvas.drawCircle(cx, cy, stoneRadius * 0.24f, markPaint)
            }
        }

        drawWinRings(canvas, game)
    }

    private fun drawStars(canvas: Canvas, n: Int) {
        val points = ArrayList<Pair<Int, Int>>()
        if (n >= 13) {
            val lo = 3
            val hi = n - 4
            val mid = n / 2
            points.add(lo to lo)
            points.add(hi to lo)
            points.add(lo to hi)
            points.add(hi to hi)
            points.add(mid to mid)
            if (n >= 17) {
                points.add(lo to mid)
                points.add(hi to mid)
                points.add(mid to lo)
                points.add(mid to hi)
            }
        } else if (n >= 9) {
            points.add(n / 2 to n / 2)
        }
        val radius = maxOf(dp(1.8f), cellSize * 0.08f)
        for ((x, y) in points) {
            val cx = gridRect.left + x * cellSize
            val cy = gridRect.top + y * cellSize
            canvas.drawCircle(cx, cy, radius, starPaint)
        }
    }

    private fun drawCoordinates(canvas: Canvas, n: Int) {
        labelPaint.textSize = min(cellSize * 0.42f, dp(11f))
        labelPaint.alpha = 150
        val baselineOffset = labelPaint.textSize * 0.36f
        for (i in 0 until n) {
            val cx = gridRect.left + i * cellSize
            canvas.drawText(('A' + i).toString(), cx, gridRect.bottom + labelSpace * 0.72f, labelPaint)

            val cy = gridRect.top + i * cellSize
            canvas.drawText(
                (i + 1).toString(),
                gridRect.left - labelSpace * 0.5f,
                cy + baselineOffset,
                labelPaint
            )
        }
        labelPaint.alpha = 255
    }

    private fun drawStone(canvas: Canvas, x: Int, y: Int, stone: Stone) {
        val cx = gridRect.left + x * cellSize
        val cy = gridRect.top + y * cellSize
        val r = stoneRadius

        canvas.drawCircle(cx + r * 0.1f, cy + r * 0.14f, r, shadowPaint)
        canvas.save()
        canvas.translate(cx, cy)
        if (stone == Stone.BLACK) {
            canvas.drawCircle(0f, 0f, r, blackPaint)
        } else {
            canvas.drawCircle(0f, 0f, r, whitePaint)
            canvas.drawCircle(0f, 0f, r, whiteStrokePaint)
        }
        canvas.restore()
    }

    private fun drawWinLine(canvas: Canvas, game: GameEngine) {
        val line = game.winningLine
        if (line.size < 2) return
        val first = line.first()
        val last = line.last()
        val x1 = gridRect.left + first.x * cellSize
        val y1 = gridRect.top + first.y * cellSize
        val x2 = gridRect.left + last.x * cellSize
        val y2 = gridRect.top + last.y * cellSize
        canvas.drawLine(x1, y1, x2, y2, winLinePaint)
    }

    private fun drawWinRings(canvas: Canvas, game: GameEngine) {
        val line = game.winningLine
        if (line.isEmpty()) return
        for (move in line) {
            val cx = gridRect.left + move.x * cellSize
            val cy = gridRect.top + move.y * cellSize
            canvas.drawCircle(cx, cy, stoneRadius * 1.02f, winRingPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val game = engine ?: return false
        if (!interactive) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val hit = locate(event.x, event.y)
                downX = hit?.first ?: -1
                downY = hit?.second ?: -1
                if (hit == null) return false
                isPressed = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                val hit = locate(event.x, event.y)
                if (hit != null && hit.first == downX && hit.second == downY && game.isEmpty(hit.first, hit.second)) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onCellTap?.invoke(hit.first, hit.second)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 找到触点最近的交叉点，超出阈值返回 null。 */
    private fun locate(touchX: Float, touchY: Float): Pair<Int, Int>? {
        val game = engine ?: return null
        if (cellSize <= 0f) return null
        val col = ((touchX - gridRect.left) / cellSize).roundToInt()
        val row = ((touchY - gridRect.top) / cellSize).roundToInt()
        if (col !in 0 until game.size || row !in 0 until game.size) return null
        val cx = gridRect.left + col * cellSize
        val cy = gridRect.top + row * cellSize
        val dx = touchX - cx
        val dy = touchY - cy
        if (dx * dx + dy * dy > (cellSize * 0.62f) * (cellSize * 0.62f)) return null
        return col to row
    }

    private fun dp(value: Float): Float = value * density
}
