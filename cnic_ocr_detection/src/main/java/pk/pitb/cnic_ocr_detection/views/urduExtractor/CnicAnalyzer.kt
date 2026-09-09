package pk.pitb.cnic_ocr_detection.views.urduExtractor

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CardGeometry
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.OpenCVCardTransformer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class CnicAnalyzer(
    private val cameraOverlay: CnicCameraOverlay,
    private val onAlignmentChanged: (Boolean, Bitmap, Bitmap, List<PointF>?) -> Unit,
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val isBusy = AtomicBoolean(false)
    var isManualMode: Boolean = cameraOverlay.isManualMode()
    private val isCaptured = AtomicBoolean(false)

    // --- Performance Optimization Parameters ---
    private var lastAnalysisTime = 0L
    private val ANALYSIS_INTERVAL_MS = 180L // Throttles OCR processing to ~5 FPS for high UI fluidity
    private var lastEmittedCorners: List<PointF>? = null

    fun analyzeImage(orientedBitmap: Bitmap) {
        val currentTime = SystemClock.elapsedRealtime()

        // 1. Skip frame processing if within throttle threshold
        if (currentTime - lastAnalysisTime < ANALYSIS_INTERVAL_MS) {
            return
        }

        if (isCaptured.get() || !isBusy.compareAndSet(false, true)) return
        lastAnalysisTime = currentTime

        val cardBoundsOnBitmap = CardGeometry.computeCardBounds(
            orientedBitmap.width.toFloat(),
            orientedBitmap.height.toFloat()
        )

        val cardBmp = cropRectF(orientedBitmap, cardBoundsOnBitmap) ?: run {
            isBusy.set(false)
            return
        }

        // MANUAL MODE BRANCH
        if (isManualMode) {
            cameraOverlay.setFieldStatuses(
                isAllAligned = true,
                stopProcessing = false,
                guidance = CnicGuidance.HOLD_STILL
            )
            isBusy.set(false)
            return
        }

        verifyRegion(
            tag = "Card",
            bmp = cardBmp,
            targetMatch = { text ->
                val isCardValid = text.contains("pakistan", true)
                        && text.contains("card", true)
                        && text.contains("name", true)
                        && text.contains("father", true)
                        && text.contains("gender", true)
                        && text.contains("stay", true)
                        && text.contains("number", true)
                        && text.contains("birth", true)
                        && text.contains("date", true)
                        && text.contains("issue", true)
                        && text.contains("expiry", true)
                        && text.contains("signature", true)
                isCardValid
            },
            onCornerResult = { textCorners ->
                // 2. Smooth corner updates to prevent flickering UI redraws
                if (shouldUpdateOverlay(textCorners, lastEmittedCorners)) {
                    lastEmittedCorners = textCorners
                    cameraOverlay.updateDetectedCorners(textCorners)
                }
            },
            onResult = { isMatched, msg ->
                if (isMatched) {
                    isCaptured.set(true)

                    // 3. Perform OpenCV flattening ONLY when card is matched and captured
                    val result = OpenCVCardTransformer.detectAndFlatten(cardBmp)
                    val finalCorners = result.relativeCorners ?: lastEmittedCorners

                    onAlignmentChanged(true, result.transformedBitmap, cardBmp, finalCorners)
                } else {
                    isBusy.set(false)
                }
            }
        )
    }

    /**
     * Prevents triggering unnecessary UI redraws if corner movement is minimal (< 1.5% displacement)
     */
    private fun shouldUpdateOverlay(newCorners: List<PointF>?, oldCorners: List<PointF>?): Boolean {
        if (newCorners == null && oldCorners == null) return false
        if (newCorners == null || oldCorners == null) return true
        if (newCorners.size != oldCorners.size) return true

        for (i in newCorners.indices) {
            val dist = hypot(
                (newCorners[i].x - oldCorners[i].x).toDouble(),
                (newCorners[i].y - oldCorners[i].y).toDouble()
            )
            if (dist > 0.015) { // 1.5% distance threshold
                return true
            }
        }
        return false
    }

    /**
     * Triggered directly when the user taps the Manual Capture button on screen.
     */
    fun triggerManualCapture(orientedBitmap: Bitmap) {
        if (isCaptured.get()) return

        val cardBoundsOnBitmap = CardGeometry.computeCardBounds(
            orientedBitmap.width.toFloat(),
            orientedBitmap.height.toFloat()
        )

        val cardBmp = cropRectF(orientedBitmap, cardBoundsOnBitmap) ?: return

        // Flatten image on demand during manual capture
        val result = OpenCVCardTransformer.detectAndFlatten(cardBmp)

        isCaptured.set(true)
        onAlignmentChanged(true, result.transformedBitmap, cardBmp, result.relativeCorners ?: lastEmittedCorners)
    }

    private fun cropRectF(source: Bitmap, r: RectF): Bitmap? {
        val left = r.left.toInt().coerceIn(0, source.width - 1)
        val top = r.top.toInt().coerceIn(0, source.height - 1)
        val width = r.width().toInt().coerceAtMost(source.width - left)
        val height = r.height().toInt().coerceAtMost(source.height - top)
        if (width <= 0 || height <= 0) return null
        return Bitmap.createBitmap(source, left, top, width, height)
    }

    private fun verifyRegion(
        tag: String,
        bmp: Bitmap,
        targetMatch: (String) -> Boolean,
        onResult: (Boolean, String) -> Unit,
        onCornerResult: (List<PointF>?) -> Unit,
    ) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { visionText ->
                val points = estimateCardCornersFromText(visionText, bmp.width.toFloat(), bmp.height.toFloat())
                onCornerResult(points)

                val alignment = checkCardAlignment(visionText)
                when (alignment.direction) {
                    RotationDirection.NONE -> {
                        val clean = visionText.text.lowercase().replace(" ", "").replace("\n", "").trim()
                        val matched = targetMatch(clean)
                        cameraOverlay.setFieldStatuses(
                            isAllAligned = matched,
                            stopProcessing = false,
                            guidance = if (matched) CnicGuidance.HOLD_STILL else CnicGuidance.MOVE_CLOSER
                        )
                        onResult(matched, "Card is straight")
                    }

                    RotationDirection.ROTATE_LEFT -> {
                        cameraOverlay.setFieldStatuses(false, false, CnicGuidance.TILTED_LEFT)
                        onResult(false, "Tilted ${alignment.skewAngle}° — rotate left")
                    }

                    RotationDirection.ROTATE_RIGHT -> {
                        cameraOverlay.setFieldStatuses(false, false, CnicGuidance.TILTED_RIGHT)
                        onResult(false, "Tilted ${alignment.skewAngle}° — rotate right")
                    }

                    RotationDirection.UNKNOWN -> {
                        cameraOverlay.setFieldStatuses(false, false, CnicGuidance.SEARCHING)
                        onResult(false, "Not enough text detected")
                    }
                }
            }
            .addOnFailureListener {
                Log.e("CnicAnalyzer", "OCR failed", it)
                cameraOverlay.setFieldStatuses(false, false, CnicGuidance.SEARCHING)
                onResult(false, "OCR failed")
            }
    }

    fun estimateCardCornersFromText(
        visionText: Text,
        imageWidth: Float,
        imageHeight: Float
    ): List<PointF>? {
        var topPPoint: PointF? = null
        var topCardRightPoint: PointF? = null
        var signatureBottomRight: PointF? = null

        val dateRegex = Regex("""\d{1,2}[.,\-/\\]\d{1,2}[.,\-/\\]\d{4}""")
        val foundDateBottomRights = mutableListOf<PointF>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text.lowercase().trim()

                if (lineText.contains("pakistan")) {
                    for (element in line.elements) {
                        val elemText = element.text.lowercase().trim()
                        val corners = element.cornerPoints ?: continue
                        if (corners.size != 4) continue

                        if (elemText.contains("pakistan") && topPPoint == null) {
                            topPPoint = PointF(corners[0].x.toFloat(), corners[0].y.toFloat())
                        }
                        if (elemText.contains("card")) {
                            topCardRightPoint = PointF(corners[1].x.toFloat(), corners[1].y.toFloat())
                        }
                    }
                }

                if (lineText.contains("signature") || lineText.contains("holder")) {
                    for (element in line.elements) {
                        val elemText = element.text.lowercase().trim()
                        val corners = element.cornerPoints ?: continue
                        if (corners.size == 4 && elemText.contains("signature")) {
                            signatureBottomRight = PointF(corners[2].x.toFloat(), corners[2].y.toFloat())
                        }
                    }
                }

                if (dateRegex.containsMatchIn(lineText)) {
                    line.cornerPoints?.getOrNull(2)?.let { point ->
                        foundDateBottomRights.add(PointF(point.x.toFloat(), point.y.toFloat()))
                    }
                }
            }
        }

        val pPoint = topPPoint ?: return null
        val headerRightX = topCardRightPoint?.x ?: (pPoint.x + (imageWidth * 0.65f))
        val bottomPoint = signatureBottomRight
            ?: foundDateBottomRights.maxByOrNull { it.y }
            ?: PointF(headerRightX, pPoint.y + (imageHeight * 0.85f))

        val headerWidth = abs(headerRightX - pPoint.x)
        val totalTextHeight = abs(bottomPoint.y - pPoint.y)

        val leftPadding = headerWidth * 0.35f
        val rightPadding = headerWidth * 0.12f
        val topPadding = totalTextHeight * 0.06f
        val bottomPadding = totalTextHeight * 0.06f

        val rawLeft = (pPoint.x - leftPadding).coerceAtLeast(0f)
        val rawTop = (pPoint.y - topPadding).coerceAtLeast(0f)
        val rawRight = (bottomPoint.x + rightPadding).coerceAtMost(imageWidth)
        val rawBottom = (bottomPoint.y + bottomPadding).coerceAtMost(imageHeight)

        val relLeft = rawLeft / imageWidth
        val relTop = rawTop / imageHeight
        val relRight = rawRight / imageWidth
        val relBottom = rawBottom / imageHeight

        return listOf(
            PointF(relLeft, relTop),
            PointF(relRight, relTop),
            PointF(relRight, relBottom),
            PointF(relLeft, relBottom)
        )
    }

    data class AlignmentResult(
        val isAligned: Boolean,
        val skewAngle: Float,
        val direction: RotationDirection,
        val correctionAngle: Float,
        val sampleCount: Int
    )

    enum class RotationDirection {
        NONE, ROTATE_LEFT, ROTATE_RIGHT, UNKNOWN
    }

    fun checkCardAlignment(
        visionText: Text,
        alignedThresholdDegrees: Float = 500f,
        maxTrustedAngle: Float = 20f
    ): AlignmentResult {
        val angleSamples = mutableListOf<Pair<Float, Float>>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val text = element.text.lowercase().trim()
                    val isAngleAnchor = text.contains("pakistan") ||
                            text.contains("national") ||
                            text.contains("identity") ||
                            text.contains("name") ||
                            text.contains("father") ||
                            text.contains("gender")

                    if (!isAngleAnchor) continue

                    val corners = element.cornerPoints
                    if (corners != null && corners.size == 4) {
                        val deltaX = (corners[1].x - corners[0].x).toDouble()
                        val deltaY = (corners[1].y - corners[0].y).toDouble()
                        val angle = Math.toDegrees(atan2(deltaY, deltaX)).toFloat()
                        val weight = kotlin.math.abs(deltaX).toFloat()

                        if (kotlin.math.abs(angle) < maxTrustedAngle && weight > 0f) {
                            angleSamples.add(angle to weight)
                        }
                    }
                }
            }
        }

        if (angleSamples.isEmpty()) {
            return AlignmentResult(false, 0f, RotationDirection.UNKNOWN, 0f, 0)
        }

        val totalWeight = angleSamples.sumOf { it.second.toDouble() }
        val avgAngle = (angleSamples.sumOf { (it.first * it.second).toDouble() } / totalWeight).toFloat()
        val aligned = kotlin.math.abs(avgAngle) <= alignedThresholdDegrees

        val direction = when {
            aligned -> RotationDirection.NONE
            avgAngle > 0f -> RotationDirection.ROTATE_LEFT
            else -> RotationDirection.ROTATE_RIGHT
        }

        val correction = if (aligned) 0f else -avgAngle

        return AlignmentResult(aligned, avgAngle, direction, correction, angleSamples.size)
    }

    enum class CnicGuidance {
        SEARCHING, TILTED_LEFT, TILTED_RIGHT, MOVE_CLOSER, HOLD_STILL, CAPTURED
    }
}