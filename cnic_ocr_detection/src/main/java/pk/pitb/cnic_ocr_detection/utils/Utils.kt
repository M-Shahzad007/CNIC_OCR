package pk.pitb.cnic_ocr_detection.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.leptonica.android.AdaptiveMap
import com.googlecode.leptonica.android.Binarize
import com.googlecode.leptonica.android.Convert
import com.googlecode.leptonica.android.Enhance
import com.googlecode.leptonica.android.Pix
import com.googlecode.leptonica.android.ReadFile
import com.googlecode.leptonica.android.Rotate
import com.googlecode.leptonica.android.Skew
import com.googlecode.leptonica.android.WriteFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.atan2

class Utils {
   // private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    companion object {
        fun drawCnicAnchorRectangleAsync(
            cardBmp: Bitmap,
            onComplete: (Bitmap) -> Unit
        ) {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(InputImage.fromBitmap(cardBmp, 0))
                .addOnSuccessListener { visionText ->
                    val annotated = processAndStraightenCardCrop(cardBmp, visionText)
                    onComplete(annotated)
                }
                .addOnFailureListener { e ->
                    Log.e("CnicAlignmentAnalyzer", "Anchor OCR processing failed", e)
                    val fallbackBmp = cardBmp.copy(Bitmap.Config.ARGB_8888, true)
                    val canvas = Canvas(fallbackBmp)
                    val paint = Paint().apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 6f
                        color = Color.RED
                        isAntiAlias = true
                    }
                    canvas.drawRect(RectF(10f, 10f, cardBmp.width - 10f, cardBmp.height - 10f), paint)
                    onComplete(fallbackBmp)
                }
        }

        /**
         * Rotates the bitmap into a canvas large enough to hold the whole
         * rotated image (no corner clipping), and returns the matrix used
         * so callers can map old coordinates into the new bitmap's space.
         */
        fun rotateBitmapExpanded(source: Bitmap, angleDegrees: Float): Pair<Bitmap, Matrix> {
            val matrix = Matrix().apply {
                postRotate(angleDegrees, source.width / 2f, source.height / 2f)
            }

            // Where do the four corners of the source end up after rotation?
            val src = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
            val bounds = RectF()
            matrix.mapRect(bounds, src)

            // Shift so nothing has negative coordinates in the new bitmap
            matrix.postTranslate(-bounds.left, -bounds.top)

            val newWidth = bounds.width().toInt().coerceAtLeast(1)
            val newHeight = bounds.height().toInt().coerceAtLeast(1)

            val rotated = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(rotated)
            canvas.drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

            return rotated to matrix
        }

        private fun processAndStraightenCardCrop(
            cardBmp: Bitmap,
            visionText: Text,
//        paddingPx: Float = 24f // tune this — see note below
        ): Bitmap {
            var pakistanElement: Text.Element? = null
            var issueWordRect: Rect? = null
            var signatureWordRect: Rect? = null

            // One refinement worth considering: weighted average by line width
            val angleSamples = mutableListOf<Pair<Float, Float>>() // angle to weight

            fun addAngleSample(element: Text.Element) {
                val corners = element.cornerPoints
                if (corners != null && corners.size == 4) {
                    val deltaX = (corners[1].x - corners[0].x).toDouble()
                    val deltaY = (corners[1].y - corners[0].y).toDouble()
                    val angle = Math.toDegrees(atan2(deltaY, deltaX)).toFloat()
                    val weight = kotlin.math.abs(deltaX).toFloat() // line width as weight
                    if (kotlin.math.abs(angle) < 20f && weight > 0f) {
                        angleSamples.add(angle to weight)
                    }
                }
            }

            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    for (element in line.elements) {
                        val text = element.text.lowercase().trim()
                        if (pakistanElement == null && (text.contains("pakistan"))) {
                            pakistanElement = element
                        }
                        if (issueWordRect == null && (text.contains("date of issue"))) {
                            issueWordRect = element.boundingBox
                        }
                        if (signatureWordRect == null && text.contains("signature")) {
                            signatureWordRect = element.boundingBox
                        }

                        // Anchor words used purely for skew estimation
                        val isAngleAnchor =
                            text.contains("pakistan") ||
                                    text.contains("national") ||
                                    text.contains("identity") ||
                                    text.contains("name") ||
                                    text.contains("father") ||
                                    text.contains("gender")

                        if (isAngleAnchor) {
                            addAngleSample(element)
                        }
                    }
                }
            }
            val rotationAngle = if (angleSamples.isNotEmpty()) {
                val totalWeight = angleSamples.sumOf { it.second.toDouble() }
                angleSamples.sumOf { (it.first * it.second).toDouble() }.toFloat() / totalWeight.toFloat()
            } else {
                0f
            }

            val needsRotation =
                kotlin.math.abs(rotationAngle) > 0.5f && kotlin.math.abs(rotationAngle) < 15f
            val (uprightBmp, transform) = if (needsRotation) {
                rotateBitmapExpanded(cardBmp, -rotationAngle)
            } else {
                cardBmp to Matrix()
            }
         /*   var rotationAngle = 0f
            val corners = pakistanElement?.cornerPoints
            if (corners != null && corners.size == 4) {
                val deltaX = (corners[1].x - corners[0].x).toDouble()
                val deltaY = (corners[1].y - corners[0].y).toDouble()
                rotationAngle = Math.toDegrees(atan2(deltaY, deltaX)).toFloat()
            }

            val needsRotation =
                kotlin.math.abs(rotationAngle) > 0.5f && kotlin.math.abs(rotationAngle) < 15f
            val (uprightBmp, transform) = if (needsRotation) {
                rotateBitmapExpanded(cardBmp, -rotationAngle)
            } else {
                cardBmp to Matrix() // identity
            }*/

            fun mapRect(rect: Rect?): RectF? {
                rect ?: return null
                val rf = RectF(rect)
                transform.mapRect(rf)
                return rf
            }

            val pRectF = mapRect(pakistanElement?.boundingBox)
            val issueRectF = mapRect(issueWordRect)
            val sigRectF = mapRect(signatureWordRect)
            val sidePad = uprightBmp.width * 0.02f


            if (pRectF != null && issueRectF != null && sigRectF != null) {
                val paddingPx = uprightBmp.width * 0.02f // 2% of card width
                val left = pRectF.left - paddingPx
                val top = pRectF.top - paddingPx
                //val right = maxOf(issueRectF.right, sigRectF.right) + paddingPx
                val right = uprightBmp.width.toFloat() - sidePad   // card width already known, no need to depend on a right anchor

                val bottom = maxOf(issueRectF.bottom, sigRectF.bottom) + paddingPx

                val cropRect = RectF(
                    left.coerceIn(0f, uprightBmp.width.toFloat()),
                    top.coerceIn(0f, uprightBmp.height.toFloat()),
                    right.coerceIn(0f, uprightBmp.width.toFloat()),
                    bottom.coerceIn(0f, uprightBmp.height.toFloat())
                )

                val croppedBmp = cropRectF(uprightBmp, cropRect) ?: uprightBmp

                // Draw border so it's visually obvious what was captured
                val annotated = croppedBmp.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(annotated)
                val paint = Paint().apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                    color = Color.GREEN
                    isAntiAlias = true
                }
                canvas.drawRect(RectF(1f, 1f, annotated.width - 1f, annotated.height - 1f), paint)
                return annotated
            }

            // Fallback: no crop possible, return full straightened card with red border
            val annotated = uprightBmp.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(annotated)
            val paint = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.RED
                isAntiAlias = true
            }
            canvas.drawRect(RectF(10f, 10f, annotated.width - 10f, annotated.height - 10f), paint)
            return annotated
        }

        private fun cropRectF(source: Bitmap, r: RectF): Bitmap? {
            val left = r.left.toInt().coerceIn(0, source.width - 1)
            val top = r.top.toInt().coerceIn(0, source.height - 1)
            val width = r.width().toInt().coerceAtMost(source.width - left)
            val height = r.height().toInt().coerceAtMost(source.height - top)
            if (width <= 0 || height <= 0) return null
            return Bitmap.createBitmap(source, left, top, width, height)
        }

    }

}