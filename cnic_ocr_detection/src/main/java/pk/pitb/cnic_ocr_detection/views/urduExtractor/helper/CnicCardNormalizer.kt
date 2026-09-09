package pk.pitb.cnic_ocr_detection.views.urduExtractor.helper


import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.text.Text
import kotlin.math.abs
import kotlin.math.atan2

object CnicCardNormalizer {

    private const val TAG = "CNIC_ALIGNMENT_LOG"

    data class NormalizedResult(
        val alignedBitmap: Bitmap,
        val rotationApplied: Float,
        val isUpsideDown: Boolean,
        val tiltAngle: Float,
        val isValidOrientation: Boolean
    )

    /**
     * Checks alignment using text metrics, logs diagnostics, and deskews the bitmap.
     */
    fun processAndNormalizeCard(
        sourceBmp: Bitmap,
        visionText: Text
    ): NormalizedResult {

        var headerElement1: Text.Element? = null // "pakistan"
        var headerElement2: Text.Element? = null // "card"
        var footerElement1: Text.Element? = null // "issue"
        var footerElement2: Text.Element? = null // "signature"

        // 1. Locate Anchor Text Elements
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val txt = element.text.lowercase().trim()
                    if (headerElement1 == null && txt.contains("pakistan")) {
                        headerElement1 = element
                    }
                    if (headerElement2 == null && txt.contains("card")) {
                        headerElement2 = element
                    }
                    if (footerElement1 == null && txt.contains("issue")) {
                        footerElement1 = element
                    }
                    if (footerElement2 == null && txt.contains("signature")) {
                        footerElement2 = element
                    }
                }
            }
        }

        // Combine anchors into group lists for multi-point fallback evaluation
        val headerElements = listOfNotNull(headerElement1, headerElement2)
        val footerElements = listOfNotNull(footerElement1, footerElement2)
        val allDetectedElements = headerElements + footerElements

        // Average Y-positions across detected anchors in each group
        val avgHeaderY = if (headerElements.isNotEmpty()) headerElements.map { it.boundingBox?.top ?: 0 }.average().toFloat() else null
        val avgFooterY = if (footerElements.isNotEmpty()) footerElements.map { it.boundingBox?.top ?: 0 }.average().toFloat() else null

        // --- DIAGNOSTIC LOGGING STARTS ---
        Log.d(TAG, "==================================================")
        Log.d(TAG, "ANALYZING CARD TEXT ORIENTATION (4-POINT VALIDATION)")
        Log.d(TAG, "Bitmap Canvas Dimensions: ${sourceBmp.width}x${sourceBmp.height}")

        Log.d(TAG, "Detected Header Anchors: ${headerElements.size}/2 [Pakistan: ${headerElement1 != null}, Card: ${headerElement2 != null}]")
        Log.d(TAG, "Detected Footer Anchors: ${footerElements.size}/2 [Issue: ${footerElement1 != null}, Signature: ${footerElement2 != null}]")
        Log.d(TAG, "Average Header Y-Pos: ${avgHeaderY ?: "N/A"}")
        Log.d(TAG, "Average Footer Y-Pos: ${avgFooterY ?: "N/A"}")

        // 2. Determine if Card is Upside Down (180°)
        var isUpsideDown = false
        if (avgHeaderY != null && avgFooterY != null) {
            // Primary Check: Header Y > Footer Y implies upside down layout
            if (avgHeaderY > avgFooterY) {
                isUpsideDown = true
            }
        } else if (avgHeaderY != null) {
            // Fallback 1: Header located in lower half of image
            if (avgHeaderY > (sourceBmp.height * 0.5f)) {
                isUpsideDown = true
            }
        } else if (avgFooterY != null) {
            // Fallback 2: Footer located in upper half of image
            if (avgFooterY < (sourceBmp.height * 0.5f)) {
                isUpsideDown = true
            }
        }

        Log.d(TAG, "Orientation Status: ${if (isUpsideDown) "UPSIDE DOWN (180°)" else "RIGHT-SIDE UP (0°)"}")

        // 3. Calculate Fine Tilt Angle (Averaged across all detected corner pairs)
        var calculatedTiltAngle = 0f
        var validAngleCount = 0

        for (element in allDetectedElements) {
            val corners = element.cornerPoints
            if (corners != null && corners.size == 4) {
                val p0 = corners[0] // Top-Left of bounding element
                val p1 = corners[1] // Top-Right of bounding element

                val deltaX = (p1.x - p0.x).toDouble()
                val deltaY = (p1.y - p0.y).toDouble()

                calculatedTiltAngle += Math.toDegrees(atan2(deltaY, deltaX)).toFloat()
                validAngleCount++
            }
        }

        val tiltAngle = if (validAngleCount > 0) calculatedTiltAngle / validAngleCount else 0f

        if (validAngleCount > 0) {
            Log.d(TAG, "Calculated Tilt Angle (Averaged over $validAngleCount elements): $tiltAngle°")
        } else {
            Log.w(TAG, "Insufficient corner points found across all 4 anchor targets.")
        }

        // 4. Determine Total Required Correction Angle
        var totalCorrectionAngle = -tiltAngle
        if (isUpsideDown) {
            totalCorrectionAngle += 180f
        }

        Log.d(TAG, "Total Correction Angle to apply: $totalCorrectionAngle°")
        Log.d(TAG, "==================================================")
        // --- DIAGNOSTIC LOGGING ENDS ---

        // 5. Apply Bitmap Transformation if Skew > 1.0°
        val needsTransformation = abs(totalCorrectionAngle) > 1.0f
        val outputBmp = if (needsTransformation) {
            rotateAndExpandBitmap(sourceBmp, totalCorrectionAngle)
        } else {
            sourceBmp
        }

        // Requires at least one header anchor AND one footer anchor (or 3+ overall anchors) for strict validation
        val isValidOrientation = (headerElements.isNotEmpty() && footerElements.isNotEmpty()) || allDetectedElements.size >= 3

        return NormalizedResult(
            alignedBitmap = outputBmp,
            rotationApplied = totalCorrectionAngle,
            isUpsideDown = isUpsideDown,
            tiltAngle = tiltAngle,
            isValidOrientation = isValidOrientation
        )
    }

    private fun rotateAndExpandBitmap(source: Bitmap, angleDegrees: Float): Bitmap {
        val matrix = Matrix().apply {
            postRotate(angleDegrees, source.width / 2f, source.height / 2f)
        }
        val src = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        val bounds = RectF()
        matrix.mapRect(bounds, src)

        matrix.postTranslate(-bounds.left, -bounds.top)

        val newWidth = bounds.width().toInt().coerceAtLeast(1)
        val newHeight = bounds.height().toInt().coerceAtLeast(1)

        val rotated = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(rotated)
        canvas.drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return rotated
    }
}