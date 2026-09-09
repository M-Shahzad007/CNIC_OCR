package pk.pitb.cnic_ocr_detection.views.urduExtractor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CardGeometry
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CnicCardNormalizer
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CnicFieldZones
import pk.pitb.cnic_ocr_detection.views.urduExtractor.CnicAnalyzer.CnicGuidance

class CnicCameraOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class CaptureMode { AUTO, MANUAL }

    var captureMode: CaptureMode = CaptureMode.AUTO

    var onModeChangedListener: ((CaptureMode) -> Unit)? = null
    var onManualCaptureClickListener: (() -> Unit)? = null

    // Mode Toggle Button Geometry
    private val toggleRect = RectF()
    private val captureBtnRect = RectF()

    private val toggleBgPaint = Paint().apply {
        isAntiAlias = true
        color = Color.parseColor("#99000000")
    }

    private val toggleActiveBgPaint = Paint().apply {
        isAntiAlias = true
        color = Color.parseColor("#00E5A0")
    }

    private val toggleTextPaint = Paint().apply {
        isAntiAlias = true
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val manualCaptureBtnPaint = Paint().apply {
        isAntiAlias = true
        color = Color.parseColor("#00E5A0")
        style = Paint.Style.FILL
    }



    val cardBounds = RectF()

    private var isAllAligned = false
    var stopProcessing = false
    var isManualCaptureClicked = false
    private var guidance: CnicGuidance = CnicGuidance.SEARCHING   // NEW

    private var detectedCorners: List<PointF>? = null

    private val detectedEdgePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#00E5A0") // Accent green highlight
        isAntiAlias = true
     //   pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f) // Animated/Dashed outline
    }


    // ---------- Palette ----------
    private val colorAccent = Color.parseColor("#00E5A0")      // success / aligned green-teal
    private val colorWarn =
        Color.parseColor("#FF5A5A")        // pending — bright coral-red, high contrast
    private val colorIdle = Color.parseColor("#FFFFFF")        // idle white
    private val colorScrim = Color.parseColor("#B0000000")

    // ---------- Paints ----------
    private val backgroundPaint = Paint().apply { color = colorScrim }
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    private val cardOutlinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#40FFFFFF")
        isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
        color = colorIdle
    }

    private val scanLinePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val statusPillBgPaint = Paint().apply {
        isAntiAlias = true
        color = Color.parseColor("#CC101418")
    }
    private val statusTextPaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
        textSize = 34f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val labelPaint = Paint().apply {
        isAntiAlias = true
        color = Color.parseColor("#E6FFFFFF")
        textSize = 24f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private val shineGlowPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
    }
    private val shineCorePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val cornerLen = 56f
    private val cornerRadius = 20f

    // ---------- Animation ----------
    private var scanProgress = 0f       // 0..1 sweep position
    private var pulse = 0f              // 0..1 breathing value for "aligned" glow

    private val scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800
        repeatCount = ValueAnimator.INFINITE
        //    repeatMode = ValueAnimator.RESTART
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
//        addUpdateListener {
//            scanProgress = it.animatedValue as Float
//            if (!anyFieldValid) postInvalidateOnAnimation()
//        }

        addUpdateListener {
            scanProgress = it.animatedValue as Float
            if (isAllAligned) postInvalidateOnAnimation()
        }
    }

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener {
            pulse = it.animatedValue as Float
            /* if (anyFieldValid) */postInvalidateOnAnimation()
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        scanAnimator.start()
        pulseAnimator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scanAnimator.cancel()
        pulseAnimator.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cardBounds.set(CardGeometry.computeCardBounds(w.toFloat(), h.toFloat()))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Scrim with clear cutout for the card
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        canvas.drawRoundRect(cardBounds, 24f, 24f, clearPaint)

        // 2. Faint full outline so the frame reads even before alignment
        canvas.drawRoundRect(cardBounds, 24f, 24f, cardOutlinePaint)

        // 3. Corner brackets — the primary alignment cue
        val activeColor = if (isAllAligned) colorAccent else colorIdle
        cornerPaint.color = activeColor
        if (isAllAligned) {
            // soft glow via increasing alpha-blended stroke width on pulse
            cornerPaint.alpha = (200 + pulse * 55).toInt()
        } else {
            cornerPaint.alpha = 255
        }
        drawCorners(canvas, cardBounds, cornerPaint)

        // 4. Scan line sweep while searching for alignment
//        if (!anyFieldValid) {
//            drawScanLine(canvas, cardBounds)
//        }
        if (captureMode == CaptureMode.AUTO){
            if (isAllAligned && !stopProcessing) {
                drawShineSweep(canvas, cardBounds)
            } else if (!isAllAligned) {
                drawScanLine(canvas, cardBounds)
            }
        }else{
            if (isManualCaptureClicked && !stopProcessing) {
                drawShineSweep(canvas, cardBounds)
            } else if (!isManualCaptureClicked && !stopProcessing) {
                drawScanLine(canvas, cardBounds)
            }
        }
        // 6. Status pill under the card
        drawStatusPill(canvas)
        // Draw detected OpenCV edges if available
        drawDetectedEdges(canvas)

        // Draw top Mode Switcher
        drawModeToggle(canvas)

        // Draw Manual Capture Button if in MANUAL mode
        if (captureMode == CaptureMode.MANUAL) {
            drawManualCaptureButton(canvas)
        }

    }

    private fun drawCorners(canvas: Canvas, r: RectF, paint: Paint) {
        // Top-left
        canvas.drawPath(Path().apply {
            moveTo(r.left, r.top + cornerLen)
            lineTo(r.left, r.top + cornerRadius)
            quadTo(r.left, r.top, r.left + cornerRadius, r.top)
            lineTo(r.left + cornerLen, r.top)
        }, paint)
        // Top-right
        canvas.drawPath(Path().apply {
            moveTo(r.right - cornerLen, r.top)
            lineTo(r.right - cornerRadius, r.top)
            quadTo(r.right, r.top, r.right, r.top + cornerRadius)
            lineTo(r.right, r.top + cornerLen)
        }, paint)
        // Bottom-left
        canvas.drawPath(Path().apply {
            moveTo(r.left, r.bottom - cornerLen)
            lineTo(r.left, r.bottom - cornerRadius)
            quadTo(r.left, r.bottom, r.left + cornerRadius, r.bottom)
            lineTo(r.left + cornerLen, r.bottom)
        }, paint)
        // Bottom-right
        canvas.drawPath(Path().apply {
            moveTo(r.right - cornerLen, r.bottom)
            lineTo(r.right - cornerRadius, r.bottom)
            quadTo(r.right, r.bottom, r.right, r.bottom - cornerRadius)
            lineTo(r.right, r.bottom - cornerLen)
        }, paint)
    }

    private fun drawScanLine(canvas: Canvas, r: RectF) {
        val y = r.top + r.height() * scanProgress
        val shader = LinearGradient(
            r.left, y, r.right, y,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#9900E5A0"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        scanLinePaint.shader = shader
        // canvas.drawRect(r.left, y - 1.5f, r.right, y + 1.5f, scanLinePaint)
        canvas.drawRect(r.left, y - 4f, r.right, y + 4f, scanLinePaint)
    }

    private fun drawDetectedEdges(canvas: Canvas) {
        val corners = detectedCorners ?: return
        if (corners.size != 4) return

        val path = Path().apply {
            val tlX = cardBounds.left + (corners[0].x * cardBounds.width())
            val tlY = cardBounds.top + (corners[0].y * cardBounds.height())

            val trX = cardBounds.left + (corners[1].x * cardBounds.width())
            val trY = cardBounds.top + (corners[1].y * cardBounds.height())

            val brX = cardBounds.left + (corners[2].x * cardBounds.width())
            val brY = cardBounds.top + (corners[2].y * cardBounds.height())

            val blX = cardBounds.left + (corners[3].x * cardBounds.width())
            val blY = cardBounds.top + (corners[3].y * cardBounds.height())

            moveTo(tlX, tlY)
            lineTo(trX, trY)
            lineTo(brX, brY)
            lineTo(blX, blY)
            close()
        }

        canvas.drawPath(path, detectedEdgePaint)
    }

    private fun drawShineSweep(canvas: Canvas, r: RectF) {
        val x = r.left + r.width() * scanProgress

        // Soft glow, wide and blurred
        val glowShader = LinearGradient(
            x, r.top, x, r.bottom,
            intArrayOf(Color.TRANSPARENT, Color.WHITE, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        shineGlowPaint.shader = null
        shineGlowPaint.color = Color.WHITE
        canvas.drawRect(x - 10f, r.top, x + 10f, r.bottom, shineGlowPaint)

        // Bright, bold, fully-opaque core line on top of the glow
        canvas.drawRect(
            x - 2.5f,
            r.top,
            x + 2.5f,
            r.bottom,
            shineCorePaint.apply { color = Color.WHITE })
    }

    private fun drawStatusPill(canvas: Canvas) {
        val message = when (guidance) {
            CnicGuidance.SEARCHING -> "Position your CNIC inside the frame "
            CnicGuidance.TILTED_LEFT -> "Tilted — rotate card left ↺ "
            CnicGuidance.TILTED_RIGHT -> "Tilted — rotate card right ↻ "
            CnicGuidance.MOVE_CLOSER -> "Move closer & hold steady"
            CnicGuidance.HOLD_STILL -> if (captureMode== CaptureMode.AUTO)  "Perfect — hold still" else "Take Picture"
            CnicAnalyzer.CnicGuidance.CAPTURED -> "Captured! "
        }

        val padH = 28f
        val padV = 16f
        val textWidth = statusTextPaint.measureText(message)
        val pillLeft = cardBounds.centerX() - textWidth / 2 - padH
        val pillRight = cardBounds.centerX() + textWidth / 2 + padH
        val pillTop = cardBounds.bottom + 28f
        val pillBottom =
            pillTop + statusTextPaint.textSize + padV * 2 - statusTextPaint.textSize / 2

        val pillRect = RectF(pillLeft, pillTop, pillRight, pillBottom)
        canvas.drawRoundRect(
            pillRect,
            pillRect.height() / 2,
            pillRect.height() / 2,
            statusPillBgPaint
        )

        statusTextPaint.color = when (guidance) {
            CnicGuidance.HOLD_STILL, CnicGuidance.CAPTURED -> colorAccent
            CnicGuidance.TILTED_LEFT, CnicGuidance.TILTED_RIGHT -> colorWarn
            else -> Color.WHITE
        }
        val textY = pillRect.centerY() - (statusTextPaint.descent() + statusTextPaint.ascent()) / 2
        canvas.drawText(message, cardBounds.centerX(), textY, statusTextPaint)
    }

    private fun drawModeToggle(canvas: Canvas) {
        val toggleWidth = 320f
        val toggleHeight = 80f
        val topMargin = 60f

        toggleRect.set(
            (width - toggleWidth) / 2f,
            topMargin,
            (width + toggleWidth) / 2f,
            topMargin + toggleHeight
        )

        // Outer background pill
        canvas.drawRoundRect(toggleRect, toggleHeight / 2, toggleHeight / 2, toggleBgPaint)

        val halfWidth = toggleWidth / 2f
        val activeRect = if (captureMode == CaptureMode.AUTO) {
            RectF(toggleRect.left + 4f, toggleRect.top + 4f, toggleRect.left + halfWidth - 2f, toggleRect.bottom - 4f)
        } else {
            RectF(toggleRect.left + halfWidth + 2f, toggleRect.top + 4f, toggleRect.right - 4f, toggleRect.bottom - 4f)
        }

        // Active tab background indicator
        canvas.drawRoundRect(activeRect, (toggleHeight - 8f) / 2, (toggleHeight - 8f) / 2, toggleActiveBgPaint)

        // Draw Text "Auto"
        toggleTextPaint.color = if (captureMode == CaptureMode.AUTO) Color.BLACK else Color.WHITE
        val textY = toggleRect.centerY() - (toggleTextPaint.descent() + toggleTextPaint.ascent()) / 2
        canvas.drawText("Auto", toggleRect.left + halfWidth / 2, textY, toggleTextPaint)

        // Draw Text "Manual"
        toggleTextPaint.color = if (captureMode == CaptureMode.MANUAL) Color.BLACK else Color.WHITE
        canvas.drawText("Manual", toggleRect.right - halfWidth / 2, textY, toggleTextPaint)
    }

    private fun drawManualCaptureButton(canvas: Canvas) {
        val btnRadius = 75f

        // Calculate horizontal space between right edge of card bounding box and right edge of screen
        val availableWidth = width - cardBounds.right

        // If there's enough room on the right, position it horizontally centered in that margin.
        // Fallback to absolute placement if card bounds are near screen edge.
       // val btnCenterX =if (availableWidth > btnRadius * 2 + 16f) {
        val btnCenterX =if (availableWidth > btnRadius + 16f) {
            cardBounds.right + (availableWidth / 2f)
        } else {
            width - btnRadius - 24f
        }

        val btnCenterY = cardBounds.centerY()

        captureBtnRect.set(
            btnCenterX - btnRadius,
            btnCenterY - btnRadius,
            btnCenterX + btnRadius,
            btnCenterY + btnRadius
        )

        // Outer glow/shadow for high visibility against live camera background
        val outerRingPaint = Paint().apply {
            isAntiAlias = true
            color = Color.parseColor("#40000000")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(btnCenterX, btnCenterY, btnRadius + 6f, outerRingPaint)

        // Main shutter background
        canvas.drawCircle(btnCenterX, btnCenterY, btnRadius, manualCaptureBtnPaint)

        // White inner ring accent
        val innerCirclePaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        canvas.drawCircle(btnCenterX, btnCenterY, btnRadius - 10f, innerCirclePaint)

        // Solid center core
        val centerDotPaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(btnCenterX, btnCenterY, btnRadius - 22f, centerDotPaint)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_DOWN) {
            // Check click on mode switcher toggle
            if (toggleRect.contains(event.x, event.y)) {
                captureMode = if (captureMode == CaptureMode.AUTO) CaptureMode.MANUAL else CaptureMode.AUTO
                onModeChangedListener?.invoke(captureMode)
                postInvalidate()
                return true
            }

            // Expand hit area slightly (padding of 20px) for easier tapping
            val touchPadding = 20f
            val expandedCaptureRect = RectF(
                captureBtnRect.left - touchPadding,
                captureBtnRect.top - touchPadding,
                captureBtnRect.right + touchPadding,
                captureBtnRect.bottom + touchPadding
            )

            // Check click on right-side manual capture button
            if (captureMode == CaptureMode.MANUAL && expandedCaptureRect.contains(event.x, event.y)) {
                isManualCaptureClicked = true
                onManualCaptureClickListener?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun isManualMode(): Boolean = captureMode == CaptureMode.MANUAL
    fun stopOverlayRendering(stop: Boolean) {
        stopProcessing = stop
    }
    // Function to set corners from Analyzer
    fun updateDetectedCorners(corners: List<PointF>?) {
        this.detectedCorners = corners
        Log.d("openCVEdges","${corners}")
        postInvalidate()
    }

    fun setFieldStatuses(
        isAllAligned: Boolean, stopProcessing: Boolean,
        guidance: CnicGuidance = if (isAllAligned) CnicGuidance.HOLD_STILL else CnicGuidance.SEARCHING
    ) {
        this.isAllAligned = isAllAligned
        this.stopProcessing = stopProcessing
        this.guidance = guidance
        postInvalidate()
    }


    /**
     * Resets all validation flags, animation states, and debug information
     * back to their initial default states for a new capture session/retake.
     */
    fun resetOverlay() {
        isAllAligned = false
        isManualCaptureClicked = false
        stopProcessing = false
        guidance = CnicGuidance.SEARCHING
        detectedCorners = null
        // Reset scan animation loop
        scanProgress = 0f
        pulse = 0f

        // Ensure animators are running if they were stopped/paused
        if (!scanAnimator.isRunning) scanAnimator.start()
        if (!pulseAnimator.isRunning) pulseAnimator.start()

        // Request redrawing of the view back to the initial state
        postInvalidate()
    }
}