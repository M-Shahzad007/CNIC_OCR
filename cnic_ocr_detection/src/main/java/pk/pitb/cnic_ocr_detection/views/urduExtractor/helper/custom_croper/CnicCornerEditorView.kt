package pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.custom_croper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class CnicCornerEditorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var sourceBitmap: Bitmap? = null

    // 4 Corner points relative to image size (normalized 0.0f..1.0f)
    // Order: [0]=Top-Left, [1]=Top-Right, [2]=Bottom-Right, [3]=Bottom-Left
    val normalizedCorners = ArrayList<PointF>(4)

    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val imageBounds = RectF()

    private var activeCornerIndex = -1
    private val touchRadiusPx = 60f

    // Rotation & Flip State
    var rotationDegrees = 0f
        private set
    var isFlippedHorizontal = false
        private set
    var isFlippedVertical = false
        private set

    // Paints
    private val edgePaint = Paint().apply {
        color = Color.parseColor("#00E5A0")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val cornerOuterPaint = Paint().apply {
        color = Color.parseColor("#00E5A0")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val cornerInnerPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /**
     * Set up editor with bitmap and optionally initial detected corners.
     */
    fun setImageAndCorners(bitmap: Bitmap, initialCorners: List<PointF>?) {
        this.sourceBitmap = bitmap
        normalizedCorners.clear()

        if (initialCorners != null && initialCorners.size == 4) {
            normalizedCorners.addAll(initialCorners.map { PointF(it.x, it.y) })
        } else {
            // Default fallbacks: slight inset rectangle
            normalizedCorners.add(PointF(0.1f, 0.1f)) // TL
            normalizedCorners.add(PointF(0.9f, 0.1f)) // TR
            normalizedCorners.add(PointF(0.9f, 0.9f)) // BR
            normalizedCorners.add(PointF(0.1f, 0.9f)) // BL
        }

        resetTransformations()
        requestLayout()
        invalidate()
    }

    fun rotate90() {
        rotationDegrees = (rotationDegrees + 90f) % 360f
        invalidate()
    }

    fun flipHorizontal() {
        isFlippedHorizontal = !isFlippedHorizontal
        invalidate()
    }

    fun flipVertical() {
        isFlippedVertical = !isFlippedVertical
        invalidate()
    }

    fun resetTransformations() {
        rotationDegrees = 0f
        isFlippedHorizontal = false
        isFlippedVertical = false
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalculateMatrix()
    }

    private fun recalculateMatrix() {
        val bmp = sourceBitmap ?: return
        if (width == 0 || height == 0) return

        val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())

        // Calculate image aspect ratio bounds
        val scale = Math.min(
            viewRect.width() / bmp.width.toFloat(),
            viewRect.height() / bmp.height.toFloat()
        )

        val drawableWidth = bmp.width * scale
        val drawableHeight = bmp.height * scale
        val left = (viewRect.width() - drawableWidth) / 2f
        val top = (viewRect.height() - drawableHeight) / 2f

        imageBounds.set(left, top, left + drawableWidth, top + drawableHeight)

        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(left, top)
        imageMatrix.invert(inverseMatrix)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = sourceBitmap ?: return

        canvas.save()

        // Apply rotations/flips to Canvas drawing
        canvas.rotate(rotationDegrees, width / 2f, height / 2f)
        val scaleX = if (isFlippedHorizontal) -1f else 1f
        val scaleY = if (isFlippedVertical) -1f else 1f
        canvas.scale(scaleX, scaleY, width / 2f, height / 2f)

        // 1. Draw transformed base image
        canvas.drawBitmap(bmp, imageMatrix, null)

        // 2. Map normalized corners to view screen points
        val screenPoints = getScreenPoints()

        // 3. Draw polygon edges connecting corners
        val path = Path().apply {
            moveTo(screenPoints[0].x, screenPoints[0].y)
            lineTo(screenPoints[1].x, screenPoints[1].y)
            lineTo(screenPoints[2].x, screenPoints[2].y)
            lineTo(screenPoints[3].x, screenPoints[3].y)
            close()
        }
        canvas.drawPath(path, edgePaint)

        // 4. Draw touchable handle knobs
        for (pt in screenPoints) {
            canvas.drawCircle(pt.x, pt.y, 28f, cornerOuterPaint)
            canvas.drawCircle(pt.x, pt.y, 14f, cornerInnerPaint)
        }

        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (sourceBitmap == null) return false

        // Invert touch event to account for View rotation/flipping
        val touchPt = floatArrayOf(event.x, event.y)
        val invertMatrix = Matrix()

        invertMatrix.postRotate(-rotationDegrees, width / 2f, height / 2f)
        val scaleX = if (isFlippedHorizontal) -1f else 1f
        val scaleY = if (isFlippedVertical) -1f else 1f
        invertMatrix.postScale(scaleX, scaleY, width / 2f, height / 2f)
        invertMatrix.mapPoints(touchPt)

        val touchX = touchPt[0]
        val touchY = touchPt[1]

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val screenPts = getScreenPoints()
                activeCornerIndex = -1
                var minDistance = Float.MAX_VALUE

                for (i in 0..3) {
                    val dist = hypot(screenPts[i].x - touchX, screenPts[i].y - touchY)
                    if (dist < touchRadiusPx && dist < minDistance) {
                        minDistance = dist
                        activeCornerIndex = i
                    }
                }
                return activeCornerIndex != -1
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeCornerIndex != -1) {
                    // Convert screen touch back to image space relative (0.0 .. 1.0)
                    val clampedX = touchX.coerceIn(imageBounds.left, imageBounds.right)
                    val clampedY = touchY.coerceIn(imageBounds.top, imageBounds.bottom)

                    val normX = (clampedX - imageBounds.left) / imageBounds.width()
                    val normY = (clampedY - imageBounds.top) / imageBounds.height()

                    normalizedCorners[activeCornerIndex].set(normX, normY)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeCornerIndex = -1
            }
        }
        return true
    }

    private fun getScreenPoints(): List<PointF> {
        return normalizedCorners.map { norm ->
            PointF(
                imageBounds.left + (norm.x * imageBounds.width()),
                imageBounds.top + (norm.y * imageBounds.height())
            )
        }
    }

    /**
     * Converts normalized corners to actual Bitmap Pixel coordinates.
     */
    fun getPixelCorners(): List<PointF> {
        val bmp = sourceBitmap ?: return emptyList()
        return normalizedCorners.map { norm ->
            PointF(norm.x * bmp.width, norm.y * bmp.height)
        }
    }
}