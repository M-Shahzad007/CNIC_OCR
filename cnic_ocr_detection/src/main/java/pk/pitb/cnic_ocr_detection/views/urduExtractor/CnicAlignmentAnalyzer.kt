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
import pk.pitb.cnic_ocr_detection.utils.Utils
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CardGeometry
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CnicFieldZones
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2

class CnicAlignmentAnalyzer(
    private val cameraOverlay: CnicTemplateCameraOverlay,
    private val onAlignmentChanged: (Boolean, Bitmap, Bitmap) -> Unit,
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val isBusy = AtomicBoolean(false)
    private val isCaptured = AtomicBoolean(false) // Prevents processing after success
    private val ISSUE_DATE_VALUE_REGEX = Regex("""\d{1,2}[.,\-/\\]\d{1,2}[.,\-/\\]\d{4}""")
    fun analyzeImage(orientedBitmap: Bitmap) {
        if (isCaptured.get() || !isBusy.compareAndSet(false, true)) return

        // Card box computed DIRECTLY on the bitmap — same function, same math
        // the overlay uses, just applied to bitmap pixel dimensions instead
        // of View pixel dimensions. No cross-space scaling anywhere.
        val cardBoundsOnBitmap = CardGeometry.computeCardBounds(
            orientedBitmap.width.toFloat(),
            orientedBitmap.height.toFloat()
        )

        val cardBmp = cropRectF(orientedBitmap, cardBoundsOnBitmap) ?: run {
            isBusy.set(false)
            return
        }
        // Run OCR once on the full card
        recognizer.process(InputImage.fromBitmap(cardBmp, 0))
            .addOnSuccessListener { visionText ->
                var hasHeader = false
                var hasIssueDate = false
                var hasSignature = false

             /*   // Helper to check if any text element overlaps with a zone and matches condition
                fun checkZone(zone: RectF, match: (String) -> Boolean): Boolean {
                    val cardBox = RectF(0f, 0f, cardBmp.width.toFloat(), cardBmp.height.toFloat())
                    val rect = CardGeometry.toCardSpace(zone, cardBox)
                    return visionText.textBlocks.any { block ->
                        block.lines.any { line ->
                            line.elements.any { element ->
                                val box = element.boundingBox ?: return@any false
                                val overlap = RectF(box).intersect(rect)
                                overlap && match(element.text.lowercase())
                            }
                        }
                    }
                }*/
                fun checkZone(zone: RectF, match: (String) -> Boolean): Boolean {
                    val cardBox = RectF(0f, 0f, cardBmp.width.toFloat(), cardBmp.height.toFloat())
                    val rect = CardGeometry.toCardSpace(zone, cardBox)

                    // Collect all text elements that overlap with the zone
                    val collectedText = StringBuilder()
                    visionText.textBlocks.forEach { block ->
                        block.lines.forEach { line ->
                            line.elements.forEach { element ->
                                val box = element.boundingBox ?: return@forEach
                                val overlap = RectF(box).intersect(rect)
                                if (overlap) {
                                    collectedText.append(" ")
                                    collectedText.append(element.text.lowercase())
                                }
                            }
                        }
                    }

                    val zoneText = collectedText.toString().trim()
                    Log.d("DebuggerText", "Zone text for $zone: $zoneText")

                    return match(zoneText)
                }


                hasHeader = checkZone(CnicFieldZones.HEADER) {
                    val res = it.contains("pakistan") && it.contains("card")
                    Log.d("DebuggerText","Header: $it")
                    res
                }
                hasIssueDate = checkZone(CnicFieldZones.FOOTER_3) {
                    val res = it.contains("issue") && ISSUE_DATE_VALUE_REGEX.containsMatchIn(it)
                    Log.d("DebuggerText","Issue Date: $it")
                    res
                }
                hasSignature = checkZone(CnicFieldZones.SIGNATURE_TEXT) {
                    val res = it.contains("signature")
                    Log.d("DebuggerText","Signature: $it")
                    res
                }

                cameraOverlay.setFieldStatuses(
                    header = hasHeader,
                    name = true,
                    fatherName = true,
                    issueDate = hasIssueDate,
                    signature = hasSignature,
                    result = null
                )

                val allAligned = hasHeader && hasIssueDate && hasSignature
                if (allAligned) {
                    isCaptured.set(true)
                    onAlignmentChanged(true, cardBmp, cardBmp)
//                    Utils.drawCnicAnchorRectangleAsync(cardBmp) { recBmp ->
//                        onAlignmentChanged(true, recBmp, cardBmp)
//                    }
                } else {
                    isBusy.set(false)
                }
            }
            .addOnFailureListener {
                Log.e("CnicAlignmentAnalyzer", "OCR failed", it)
                isBusy.set(false)
            }

        return
  /*      // Run full OCR on the captured card to generate text anchor metrics for Normalizer
        recognizer.process(InputImage.fromBitmap(cardBmp, 0))
            .addOnSuccessListener { visionText ->
                // Run Normalizer to log orientation, tilt angle & calculate straightened bitmap
                val normalizedResult = CnicCardNormalizer.processAndNormalizeCard(
                    sourceBmp = cardBmp,
                    visionText = visionText
                )
                cameraOverlay.setFieldStatuses(
                    header = false,
                    name = false,
                    fatherName = false,
                    issueDate = false,
                    signature = false,
                    result = normalizedResult
                )
                isBusy.set(false)
            }
            .addOnFailureListener {
                Log.e("CnicAlignmentAnalyzer", "Full OCR for normalization failed", it)
                isBusy.set(false)
            }

        return*/

/*        val headerBmp = cropZone(cardBmp, CnicFieldZones.HEADER)
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
                    signature = hasSignature,
                    result = null
                )
                val allAligned = hasHeader && hasIssueDate && hasSignature
                if (allAligned) {
                    // Lock analyzer to stop analyzing further frames
                    isCaptured.set(true)
                    // Asynchronously compute anchor rectangle without blocking camera thread
                    drawCnicAnchorRectangleAsync(cardBmp) { recBmp ->
                        onAlignmentChanged(true, recBmp, cardBmp)

                    }
                } else {
                    isBusy.set(false)
                }

            }
        }

        verifyRegion("Header", headerBmp, {
            it.contains("pakistan", true) && it.contains("card", true)
        }) {
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
            {
                it.contains("issue", true) && ISSUE_DATE_VALUE_REGEX.containsMatchIn(it)

            }
        ) {
            hasIssueDate = it
            checkDone()
        }
        verifyRegion(
            "SIGNATURE",
            signatureBmp,
            { it.contains("signature", true) }) {
            hasSignature = it
            checkDone()
        }*/
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




// below is rough
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

        val needsRotation =
            kotlin.math.abs(rotationAngle) > 0.5f && kotlin.math.abs(rotationAngle) < 15f
        val (uprightBmp, transform) = if (needsRotation) {
            Utils.rotateBitmapExpanded(cardBmp, -rotationAngle)
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
