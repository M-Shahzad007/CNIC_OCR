package pk.pitb.cnic_ocr_detection.views.urduExtractor

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
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CardGeometry
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CnicFieldZones
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2

class CnicAlignmentAnalyzer(
    private val cameraOverlay: CnicCameraOverlay,
    private val onAlignmentChanged: (Boolean, Bitmap, Bitmap) -> Unit,
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val isBusy = AtomicBoolean(false)
    private val isCaptured = AtomicBoolean(false) // Prevents processing after success
    fun analyzeImage(orientedBitmap: Bitmap) {
        if (isCaptured.get() || !isBusy.compareAndSet(false, true)) return

        // Card box computed DIRECTLY on the bitmap — same function, same math
        // the overlay uses, just applied to bitmap pixel dimensions instead
        // of View pixel dimensions. No cross-space scaling anywhere.
        val cardBoundsOnBitmap = CardGeometry.computeCardBounds(
            orientedBitmap.width.toFloat(),
            orientedBitmap.height.toFloat()
        )

        val cardBmp = cropRectF(orientedBitmap, cardBoundsOnBitmap)

        if (cardBmp == null) {
            isBusy.set(false)
            return
        }

        val headerBmp = cropZone(cardBmp, CnicFieldZones.HEADER)
//        val nameBmp = cropZone(cardBmp, CnicFieldZones.NAME_LABEL)
//        val fatherNameBmp = cropZone(cardBmp, CnicFieldZones.FATHER_LABEL)
        val issueDateBmp = cropZone(cardBmp, CnicFieldZones.FOOTER_3)
        val signatureBmp = cropZone(cardBmp, CnicFieldZones.SIGNATURE_TEXT)

        var hasHeader = false
        var hasName = false
        var hasFatherName = false
        var hasIssueDate = false
        var hasSignature = false
        var completed = 0
        val totalRegions = 3
        val checkDone = {
            completed++
            if (completed == totalRegions) {
                cameraOverlay.setFieldStatuses(
                    header = hasHeader,
                    name = true,
                    fatherName = true,
                    issueDate = hasIssueDate,
                    signature = hasSignature
                )
                val allAligned = hasHeader && hasIssueDate && hasSignature
                if (allAligned){
                    // Lock analyzer to stop analyzing further frames
                    isCaptured.set(true)
                    // Asynchronously compute anchor rectangle without blocking camera thread
                    drawCnicAnchorRectangleAsync(cardBmp) { recBmp ->
                        onAlignmentChanged(true, recBmp, cardBmp)
                    }
                }else{
                    isBusy.set(false)
                }

            }
        }

        verifyRegion("Header", headerBmp, {
            it.contains("pakistan",true) && it.contains("card",true)
        }
        ) {
            hasHeader = it
            checkDone()
        }
//        verifyRegion("NAME", nameBmp, { it.contains("name",true) }) {
//            hasName = it
//            checkDone()
//        }
//        verifyRegion("FATHER", fatherNameBmp, { it.contains("father",true) }) {
//            hasFatherName = it
//            checkDone()
//        }
        verifyRegion(
            "Issue Date",
            issueDateBmp,
            { it.contains("issue",true) }) {
            hasIssueDate = it
            checkDone()
        }
        verifyRegion(
            "SIGNATURE",
            signatureBmp,
            { it.contains("signature",true) }) {
            hasSignature = it
            checkDone()
        }
    }


    private fun cropRectF(source: Bitmap, r: RectF): Bitmap? {
        val left = r.left.toInt().coerceIn(0, source.width - 1)
        val top = r.top.toInt().coerceIn(0, source.height - 1)
        val width = r.width().toInt().coerceAtMost(source.width - left)
        val height = r.height().toInt().coerceAtMost(source.height - top)
        if (width <= 0 || height <= 0) return null
        return Bitmap.createBitmap(source, left, top, width, height)
    }

    private fun cropZone(bitmap: Bitmap, zone: RectF): Bitmap? {
        val cardBox = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        val rect = CardGeometry.toCardSpace(zone, cardBox)
        return cropRectF(bitmap, rect)
    }

    private fun verifyRegion(
        tag: String,
        bmp: Bitmap?,
        targetMatch: (String) -> Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        if (bmp == null) {
            onResult(false)
            return
        }
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { visionText ->
                val clean = visionText.text.lowercase().replace(" ", "").replace("\n", "").trim()
                Log.d(
                    "CnicAlignmentAnalyzer",
                    "[$tag] ${bmp.width}x${bmp.height} raw='${visionText.text}' clean='$clean'"
                )
                onResult(targetMatch(clean))
                // onResult(clean.contains("name"))
            }
            .addOnFailureListener {
                Log.e("CnicAlignmentAnalyzer", "OCR failed", it)
                onResult(false)
            }
    }


    private fun drawCnicAnchorRectangleAsync(
        cardBmp: Bitmap,
        onComplete: (Bitmap) -> Unit
    ) {
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
    private fun rotateBitmapExpanded(source: Bitmap, angleDegrees: Float): Pair<Bitmap, Matrix> {
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

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val text = element.text.lowercase().trim()
                    if (pakistanElement == null && (text.contains("pakistan"))) {
                        pakistanElement = element
                    }
                    if (issueWordRect == null && (text.contains("issue") || text.contains("date"))) {
                        issueWordRect = element.boundingBox
                    }
                    if (signatureWordRect == null && text.contains("signature")) {
                        signatureWordRect = element.boundingBox
                    }
                }
            }
        }

        var rotationAngle = 0f
        val corners = pakistanElement?.cornerPoints
        if (corners != null && corners.size == 4) {
            val deltaX = (corners[1].x - corners[0].x).toDouble()
            val deltaY = (corners[1].y - corners[0].y).toDouble()
            rotationAngle = Math.toDegrees(atan2(deltaY, deltaX)).toFloat()
        }

        val needsRotation = kotlin.math.abs(rotationAngle) > 0.5f && kotlin.math.abs(rotationAngle) < 15f
        val (uprightBmp, transform) = if (needsRotation) {
            rotateBitmapExpanded(cardBmp, -rotationAngle)
        } else {
            cardBmp to Matrix() // identity
        }

        fun mapRect(rect: Rect?): RectF? {
            rect ?: return null
            val rf = RectF(rect)
            transform.mapRect(rf)
            return rf
        }

        val pRectF = mapRect(pakistanElement?.boundingBox)
        val issueRectF = mapRect(issueWordRect)
        val sigRectF = mapRect(signatureWordRect)

        if (pRectF != null && issueRectF != null && sigRectF != null) {
            val paddingPx = uprightBmp.width * 0.02f // 2% of card width
            val left = pRectF.left - paddingPx
            val top = pRectF.top - paddingPx
            val right = maxOf(issueRectF.right, sigRectF.right) + paddingPx
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


    private fun processAndStraightenCard(cardBmp: Bitmap, visionText: Text): Bitmap {
        var pakistanElement: Text.Element? = null
        var issueWordRect: Rect? = null
        var signatureWordRect: Rect? = null

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val text = element.text.lowercase().trim()
                    if (pakistanElement == null && (text.contains("pakistan") || text.startsWith("p"))) {
                        pakistanElement = element
                    }
                    if (issueWordRect == null && (text.contains("issue") || text.contains("date"))) {
                        issueWordRect = element.boundingBox
                    }
                    if (signatureWordRect == null && text.contains("signature")) {
                        signatureWordRect = element.boundingBox
                    }
                }
            }
        }

        var rotationAngle = 0f
        val corners = pakistanElement?.cornerPoints
        if (corners != null && corners.size == 4) {
            val deltaX = (corners[1].x - corners[0].x).toDouble()
            val deltaY = (corners[1].y - corners[0].y).toDouble()
            rotationAngle = Math.toDegrees(atan2(deltaY, deltaX)).toFloat()
        }

        val needsRotation = kotlin.math.abs(rotationAngle) > 0.5f && kotlin.math.abs(rotationAngle) < 15f
        val (uprightBmp, transform) = if (needsRotation) {
            rotateBitmapExpanded(cardBmp, -rotationAngle)
        } else {
            cardBmp to Matrix() // identity
        }

        val annotated = uprightBmp.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotated)
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.GREEN
            isAntiAlias = true
        }

        // Map every anchor rect through the SAME transform used for the bitmap
        fun mapRect(rect: Rect?): RectF? {
            rect ?: return null
            val rf = RectF(rect)
            transform.mapRect(rf)
            return rf
        }

        val pRectF = mapRect(pakistanElement?.boundingBox)
        val issueRectF = mapRect(issueWordRect)
        val sigRectF = mapRect(signatureWordRect)

        if (pRectF != null && issueRectF != null && sigRectF != null) {
            // small safety padding so text glyphs (descenders, dots) aren't clipped
            val padding = 8f

            val left = pRectF.left
            val top = pRectF.top
            val bottom = maxOf(issueRectF.bottom, sigRectF.bottom) + padding
            val right = maxOf(issueRectF.right, sigRectF.right)

            val dynamicRect = RectF(
                left.coerceIn(0f, annotated.width.toFloat()),
                top.coerceIn(0f, annotated.height.toFloat()),
                right.coerceIn(left + 10f, annotated.width.toFloat()),
                bottom.coerceIn(top + 10f, annotated.height.toFloat())
            )

            canvas.drawRect(dynamicRect, paint)
        } else {
            paint.color = Color.RED
            canvas.drawRect(RectF(10f, 10f, annotated.width - 10f, annotated.height - 10f), paint)
        }

        return annotated
    }

    private fun rotateBitmap(source: Bitmap, angleDegrees: Float): Bitmap {
        val matrix = Matrix().apply {
            postRotate(angleDegrees, source.width / 2f, source.height / 2f)
        }
        return Bitmap.createBitmap(
            source, 0, 0, source.width, source.height, matrix, true
        )
    }

}
