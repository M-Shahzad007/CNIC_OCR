package pk.pitb.cnic_ocr_detection.views.urduExtractor.helper

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

class OcrProcessingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null

    private var scanProgress = 0f

    private var scanAnimator: ValueAnimator? = null

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    private val imageOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(45, 0, 0, 0)
    }

    private val cardBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        maskFilter = BlurMaskFilter(
            18f,
            BlurMaskFilter.Blur.NORMAL
        )
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(
            Typeface.DEFAULT,
            Typeface.BOLD
        )
    }

    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = 15f
        textAlign = Paint.Align.CENTER
    }

    private val imageRect = RectF()

    private val cornerRadius = 24f

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        visibility = GONE
    }

    fun setBitmap(newBitmap: Bitmap?) {
        bitmap = newBitmap
        invalidate()
    }

    fun startProcessing() {
        visibility = VISIBLE

        scanAnimator?.cancel()

        scanProgress = 0f

        scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {

            duration = 1800L

            repeatCount = ValueAnimator.REVERSE

            interpolator = LinearInterpolator()

            repeatMode =  ValueAnimator.REVERSE
            addUpdateListener { animator ->

                scanProgress =
                    animator.animatedValue as Float

                invalidate()
            }

            start()
        }
    }

    fun stopProcessing() {

        scanAnimator?.cancel()
        scanAnimator = null

        bitmap = null
        scanProgress = 0f

        visibility = GONE

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {

        super.onDraw(canvas)

        canvas.drawColor(Color.BLACK)

        val currentBitmap = bitmap

        if (currentBitmap == null) {

            drawProcessingText(
                canvas,
                "",
                ""
            )

            return
        }

        /*
         * CNIC display area
         *
         * Wider and taller than before.
         */
        val horizontalPadding = width * 0.05f

        val left = horizontalPadding
        val right = width - horizontalPadding

        val imageWidth = right - left

        /*
         * Keep a reasonable CNIC aspect ratio.
         *
         * Pakistani CNIC is approximately landscape.
         */
        val aspectRatio =
            currentBitmap.width.toFloat() /
                    currentBitmap.height.toFloat()

        val imageHeight =
            imageWidth / aspectRatio

        /*
         * Center the CNIC vertically.
         */
        val centerY = height * 0.43f

        val top =
            centerY - imageHeight / 2f

        val bottom =
            centerY + imageHeight / 2f

        imageRect.set(
            left,
            top,
            right,
            bottom
        )

        /*
         * Background behind image.
         */
        canvas.drawRoundRect(
            imageRect,
            cornerRadius,
            cornerRadius,
            cardBackgroundPaint
        )

        /*
         * Clip image to rounded rectangle.
         */
        canvas.save()

        val clipPath = Path().apply {

            addRoundRect(
                imageRect,
                cornerRadius,
                cornerRadius,
                Path.Direction.CW
            )
        }

        canvas.clipPath(clipPath)

        drawBitmapInsideRect(
            canvas,
            currentBitmap,
            imageRect
        )

        canvas.restore()

        /*
         * Slight dark overlay.
         */
        canvas.drawRoundRect(
            imageRect,
            cornerRadius,
            cornerRadius,
            imageOverlayPaint
        )

        /*
         * Scan line is restricted to the CNIC.
         */
        drawScanLine(
            canvas,
            imageRect
        )

        /*
         * Processing text.
         */
        drawProcessingText(
            canvas,
            "",
            ""
        )
    }
    private fun drawBitmapInsideRect(
        canvas: Canvas,
        bitmap: Bitmap,
        destination: RectF
    ) {

        val source = Rect(
            0,
            0,
            bitmap.width,
            bitmap.height
        )

        val scale = min(
            destination.width() / bitmap.width.toFloat(),
            destination.height() / bitmap.height.toFloat()
        )

        val scaledWidth = bitmap.width * scale

        val scaledHeight = bitmap.height * scale

        val left =
            destination.centerX() - scaledWidth / 2f

        val top =
            destination.centerY() - scaledHeight / 2f

        val destinationRect = RectF(
            left,
            top,
            left + scaledWidth,
            top + scaledHeight
        )

        canvas.drawBitmap(
            bitmap,
            source,
            destinationRect,
            imagePaint
        )
    }

    private fun drawScanLine(
        canvas: Canvas,
        rect: RectF
    ) {

        /*
         * The line moves horizontally across the CNIC.
         */
        val x =
            rect.left +
                    rect.width() * scanProgress

        /*
         * Wide blurred glow
         */
        val glowRect = RectF(
            x - 16f,
            rect.top,
            x + 16f,
            rect.bottom
        )

        canvas.drawRect(
            glowRect,
            glowPaint
        )

        /*
         * Bright white core
         */
        val coreRect = RectF(
            x - 2.5f,
            rect.top,
            x + 2.5f,
            rect.bottom
        )

        canvas.drawRect(
            coreRect,
            corePaint
        )
    }

    private fun drawProcessingText(
        canvas: Canvas,
        title: String,
        subtitle: String
    ) {

        val centerX = width / 2f

        val titleY = height * 0.76f

        val subtitleY = titleY + 34f

        canvas.drawText(
            title,
            centerX,
            titleY,
            titlePaint
        )

        canvas.drawText(
            subtitle,
            centerX,
            subtitleY,
            subtitlePaint
        )
    }

    override fun onDetachedFromWindow() {

        scanAnimator?.cancel()
        scanAnimator = null

        super.onDetachedFromWindow()
    }
}