package pk.pitb.cnic_ocr_detection.views.urduExtractor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CardGeometry
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CnicCardNormalizer
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CnicFieldZones

class CnicTemplateCameraOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    val cardBounds = RectF()

    val headerBounds = RectF()
    val nameLabelBounds = RectF()
    val fatherNameLabelBounds = RectF()

    val footerBlock1Bounds = RectF()
    val footerBlock2Bounds = RectF()
    val footerBlock3Bounds = RectF()
    val signatureBounds = RectF()
    val signatureTextBounds = RectF()

    val urduNameBounds = RectF()
    val urduFatherNameBounds = RectF()

    private var isAllAligned = false
    private var anyFieldValid = false
    var isHeaderValid = false
    var isNameValid = false
    var isFatherNameValid = false
    var isIssueDateValid = false
    var isSignatureValid = false
    var stopProcessing = false

    // ---------- Palette ----------
    private val colorAccent = Color.parseColor("#00E5A0")      // success / aligned green-teal
    private val colorWarn = Color.parseColor("#FF5A5A")        // pending — bright coral-red, high contrast
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

    private val badgeBgPaint = Paint().apply { isAntiAlias = true }
    private val badgeIconPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#101418")
    }
    private val zoneHairlinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
      //  pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
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


    // ---------- Diagnostic Data State ----------
    private var debugResult: CnicCardNormalizer.NormalizedResult? = null

    // ---------- Debug Paints ----------
    private val debugBgPaint = Paint().apply {
        isAntiAlias = true
        color = Color.parseColor("#CC0D1117") // Deep translucent dark background
    }

    private val debugTextPaint = Paint().apply {
        isAntiAlias = true
        color = Color.parseColor("#00E5A0") // Accent green for metrics
        textSize = 28f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val debugHeaderPaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
        textSize = 30f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
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
            if (!anyFieldValid || isAllAligned) postInvalidateOnAnimation()
        }
    }

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener {
            pulse = it.animatedValue as Float
            if (anyFieldValid) postInvalidateOnAnimation()
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

        headerBounds.set(CardGeometry.toCardSpace(CnicFieldZones.HEADER, cardBounds))
        nameLabelBounds.set(CardGeometry.toCardSpace(CnicFieldZones.NAME_LABEL, cardBounds))
        urduNameBounds.set(CardGeometry.toCardSpace(CnicFieldZones.NAME_URDU, cardBounds))
        fatherNameLabelBounds.set(CardGeometry.toCardSpace(CnicFieldZones.FATHER_LABEL, cardBounds))
        urduFatherNameBounds.set(CardGeometry.toCardSpace(CnicFieldZones.FATHER_URDU, cardBounds))
        footerBlock1Bounds.set(CardGeometry.toCardSpace(CnicFieldZones.FOOTER_1, cardBounds))
        footerBlock2Bounds.set(CardGeometry.toCardSpace(CnicFieldZones.FOOTER_2, cardBounds))
        footerBlock3Bounds.set(CardGeometry.toCardSpace(CnicFieldZones.FOOTER_3, cardBounds))
        signatureTextBounds.set(CardGeometry.toCardSpace(CnicFieldZones.SIGNATURE_TEXT, cardBounds))
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
        if (isAllAligned && !stopProcessing) {
            drawShineSweep(canvas, cardBounds)
        } else if (!anyFieldValid) {
            drawScanLine(canvas, cardBounds)
        }

        // 5. Field zones as subtle dashed hairlines + small corner badges (not solid boxes)
        if (!isAllAligned) {
            drawFieldZone(canvas, headerBounds, isHeaderValid, "Header")
//        drawFieldZone(canvas, nameLabelBounds, isNameValid, "Name")
//        drawFieldZone(canvas, fatherNameLabelBounds, isFatherNameValid, "Father's Name")
            drawFieldZone(canvas, signatureTextBounds, isSignatureValid, "Signature")
            drawFieldZone(canvas, footerBlock3Bounds, isIssueDateValid, "Issue date")
        }
        // 6. Status pill under the card
        drawStatusPill(canvas)

        //7
        drawDebugHUD(canvas)
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

    private val zoneLabelBgPaint = Paint().apply {
        isAntiAlias = true
        color = Color.parseColor("#B3101418")   // semi-dark, ~70% opacity
    }

    private fun drawFieldZone(canvas: Canvas, bounds: RectF, valid: Boolean, label: String) {
        val color = if (valid) colorAccent else colorWarn
        // Solid, fully-opaque outline — no low-contrast alpha blending
        zoneHairlinePaint.color = color
        canvas.drawRoundRect(bounds, 8f, 8f, zoneHairlinePaint)

        // ---- Header row: badge + label side-by-side, sitting just above the box ----
        val badgeR = 17f                       // bigger badge
        val rowGap = 10f                       // gap between row and box
        val rowCenterY = bounds.top - rowGap - badgeR
        val cx = bounds.left + badgeR

        // Background pill behind the badge + label so text stays legible over any
        // camera content (skin, paper, patterned backgrounds, etc.)
        val textWidth = labelPaint.measureText(label)
        val pillPadH = 10f
        val pillPadV = 8f
        val pillLeft = cx - badgeR - pillPadH
        val pillRight = cx + badgeR + 10f + textWidth + pillPadH
        val pillTop = rowCenterY - badgeR - pillPadV
        val pillBottom = rowCenterY + badgeR + pillPadV
        canvas.drawRoundRect(
            RectF(pillLeft, pillTop, pillRight, pillBottom),
            (pillBottom - pillTop) / 2, (pillBottom - pillTop) / 2,
            zoneLabelBgPaint
        )

        // Badge circle
        badgeBgPaint.color = color
        canvas.drawCircle(cx, rowCenterY, badgeR, badgeBgPaint)
        if (valid) {
            val path = Path().apply {
                moveTo(cx - 7f, rowCenterY)
                lineTo(cx - 2f, rowCenterY + 5.5f)
                lineTo(cx + 7f, rowCenterY - 5.5f)
            }
            canvas.drawPath(path, badgeIconPaint)
        } else {
            badgeIconPaint.style = Paint.Style.FILL
            canvas.drawCircle(cx, rowCenterY, 3.5f, badgeIconPaint)
            badgeIconPaint.style = Paint.Style.STROKE
        }

        // Label, vertically centered against the badge, right next to it
        val textY = rowCenterY - (labelPaint.descent() + labelPaint.ascent()) / 2
        canvas.drawText(label, cx + badgeR + 10f, textY, labelPaint)
    }

    private fun drawStatusPill(canvas: Canvas) {
        val fieldsOk = listOf(isHeaderValid, isIssueDateValid, isSignatureValid)
        val doneCount = fieldsOk.count { it }
        val message = when {
            isAllAligned -> "Perfect — hold still"
            doneCount == 0 -> "Position your CNIC inside the frame"
            else -> "Adjusting… $doneCount/${fieldsOk.size} fields detected"
        }

        val padH = 28f
        val padV = 16f
        val textWidth = statusTextPaint.measureText(message)
        val pillLeft = cardBounds.centerX() - textWidth / 2 - padH
        val pillRight = cardBounds.centerX() + textWidth / 2 + padH
        val pillTop = cardBounds.bottom + 28f
        val pillBottom = pillTop + statusTextPaint.textSize + padV * 2 - statusTextPaint.textSize / 2

        val pillRect = RectF(pillLeft, pillTop, pillRight, pillBottom)
        canvas.drawRoundRect(pillRect, pillRect.height() / 2, pillRect.height() / 2, statusPillBgPaint)

        statusTextPaint.color = if (isAllAligned) colorAccent else Color.WHITE
        val textY = pillRect.centerY() - (statusTextPaint.descent() + statusTextPaint.ascent()) / 2
        canvas.drawText(message, cardBounds.centerX(), textY, statusTextPaint)
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
        canvas.drawRect(x - 2.5f, r.top, x + 2.5f, r.bottom, shineCorePaint.apply { color = Color.WHITE })
    }

    private fun drawDebugHUD(canvas: Canvas) {
        val result = debugResult ?: return

        val lines = listOf(
            "--- Normalization Metrics ---",
            "Valid Orientation : ${result.isValidOrientation}",
            "Upside Down       : ${result.isUpsideDown}",
            "Tilt Angle        : ${String.format("%.2f°", result.tiltAngle)}",
            "Rotation Applied  : ${String.format("%.1f°", result.rotationApplied)}"
        )

        val padding = 24f
        val lineSpacing = 38f
        val startX = 32f
        val startY = 80f // Top padding on view screen

        // Calculate dynamic panel dimensions
        var maxTextWidth = 0f
        for (line in lines) {
            val w = debugTextPaint.measureText(line)
            if (w > maxTextWidth) maxTextWidth = w
        }

        val panelWidth = maxTextWidth + (padding * 2)
        val panelHeight = (lines.size * lineSpacing) + padding

        // Panel background
        val hudBounds = RectF(startX, startY, startX + panelWidth, startY + panelHeight)
        canvas.drawRoundRect(hudBounds, 16f, 16f, debugBgPaint)

        // Render lines of text
        var currentY = startY + padding + 20f
        for ((index, line) in lines.withIndex()) {
            val paintToUse = if (index == 0) debugHeaderPaint else debugTextPaint

            // Highlight validity with colors
            if (line.startsWith("Valid Orientation")) {
                paintToUse.color =
                    if (result.isValidOrientation) Color.parseColor("#00E5A0") else Color.parseColor(
                        "#FF5A5A"
                    )
            } else if (index != 0) {
                paintToUse.color = Color.parseColor("#00E5A0")
            }

            canvas.drawText(line, startX + padding, currentY, paintToUse)
            currentY += lineSpacing
        }
    }

    fun setAlignmentStatus(aligned: Boolean) {
        if (isAllAligned != aligned) {
            isAllAligned = aligned
            postInvalidate()
        }
    }
    fun stopOverlayRendering(stop: Boolean) {
        stopProcessing = stop
    }

    fun setFieldStatuses(header: Boolean, name: Boolean, fatherName: Boolean, issueDate: Boolean, signature: Boolean,
                         result: CnicCardNormalizer.NormalizedResult?) {
        this.isHeaderValid = header
        this.isNameValid = name
        this.isFatherNameValid = fatherName
        this.isIssueDateValid = issueDate
        this.isSignatureValid = signature
        this.isAllAligned = header && issueDate && signature
        this.anyFieldValid = header || issueDate|| signature

        this.debugResult = result
        postInvalidate()
    }

    fun updateNormalizationResult(result: CnicCardNormalizer.NormalizedResult?) {
        this.debugResult = result
        postInvalidate()
    }
}