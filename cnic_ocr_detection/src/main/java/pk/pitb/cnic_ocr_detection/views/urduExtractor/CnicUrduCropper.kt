package pk.pitb.cnic_ocr_detection.views.urduExtractor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import android.graphics.Matrix
import android.util.Log

data class CroppedUrduField(
    val fieldName: String,
    val croppedBitmap: Bitmap
)

data class UrduExtractionResult(
    var annotatedFullImage: Bitmap,
    var extractedEnglishText: Text? = null ,
    var cropsUrdu: List<CroppedUrduField>
)

class CnicUrduCropper {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val TAG = "CNIC_Orientation_Test"

 /*   fun processCnicImage(
        cnicBitmap: Bitmap,
        onSuccess: (UrduExtractionResult) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val image = InputImage.fromBitmap(cnicBitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                detectTextRotationAngle(visionText)
                val result = extractAndAnnotate(cnicBitmap, visionText)
                onSuccess(result)
            }
            .addOnFailureListener { e -> onFailure(e) }
    }*/

    fun processCnicImage(
        cnicBitmap: Bitmap,
        onSuccess: (UrduExtractionResult) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val image = InputImage.fromBitmap(cnicBitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val detectedAngle = detectTextRotationAngle(visionText)

                val correctionDegrees = when (detectedAngle) {
                    90f -> 270f
                    180f -> 180f
                    270f -> 90f
                    else -> 0f
                }

                val uprightBitmap = rotateBitmap(cnicBitmap, correctionDegrees)

                val finalBitmap = if (correctionDegrees == 0f && isCardUpsideDown(visionText, cnicBitmap.height)) {
                    rotateBitmap(uprightBitmap, 180f)
                } else {
                    uprightBitmap
                }

                // Process final upright image to get aligned text coordinates
                val uprightInput = InputImage.fromBitmap(finalBitmap, 0)
                recognizer.process(uprightInput)
                    .addOnSuccessListener { alignedVisionText ->
                        // Crop card using fresh bounding boxes
                        val cardBitmap = cropCnicCardByAnchors(finalBitmap, alignedVisionText) ?: finalBitmap

                        // If cropped, re-run ML Kit on the cropped card for precise extraction coordinates
                        val cardInput = InputImage.fromBitmap(cardBitmap, 0)
                        recognizer.process(cardInput)
                            .addOnSuccessListener { croppedVisionText ->
                                val result = extractAndAnnotate(cardBitmap, croppedVisionText)
                                onSuccess(result)
                            }
                            .addOnFailureListener { e -> onFailure(e) }
                    }
                    .addOnFailureListener { e -> onFailure(e) }
            }
            .addOnFailureListener { e -> onFailure(e) }
    }

    private fun extractAndAnnotate(originalBitmap: Bitmap, visionText: Text): UrduExtractionResult {
        val annotatedBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotatedBitmap)

        // Paint configuration for Urdu crop regions (Green)
        val paintGreen = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = (originalBitmap.width * 0.005f).coerceAtLeast(4f)
            isAntiAlias = true
        }
        // Paint configuration for English text detection (Red)
        val paintRed = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            isAntiAlias = true
        }

        var englishNameRect: Rect? = null
        var fatherNameLabelRect: Rect? = null
        var englishFatherNameRect: Rect? = null
        var genderLabelRect: Rect? = null
        var signatureLabelRect: Rect? = null

        val imgWidth = originalBitmap.width
        val imgHeight = originalBitmap.height

        // 1. Locate reference anchor boxes using ML Kit lines
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                line.boundingBox?.let { rect ->
                    // Draw Red bounding box around detected English text
                    canvas.drawRect(rect, paintRed)
                    val cleanText = line.text.lowercase().replace(" ", "").trim()

                    when {
                        cleanText.contains("father") -> if (fatherNameLabelRect == null) fatherNameLabelRect = rect
                        cleanText.contains("gender") || cleanText.contains("country") -> if (genderLabelRect == null) genderLabelRect = rect
                        cleanText.contains("holder") || cleanText.contains("signature") -> if (signatureLabelRect == null) signatureLabelRect = rect

                        // Filter out card headers
                        cleanText == "name" || cleanText.contains("pakistan") || cleanText.contains("islamic") || cleanText.contains("republic") || cleanText.contains("identity") -> {
                            // Ignored headers
                        }
                        else -> {
                            // Detect English Name value (below headers, above middle)
                            if (englishNameRect == null && rect.top > imgHeight * 0.20 && rect.top < imgHeight * 0.45) {
                                englishNameRect = rect
                            }
                            // Detect English Father Name value (middle section)
                            else if (englishFatherNameRect == null && rect.top > imgHeight * 0.40 && rect.top < imgHeight * 0.60) {
                                englishFatherNameRect = rect
                            }
                        }
                    }
                }
            }
        }

        val croppedList = mutableListOf<CroppedUrduField>()
        val verticalPadding = (imgHeight * 0.005).toInt()

        // 2. Crop Urdu Name (Between English Name bottom & Father Name header top)
        if (englishNameRect != null && fatherNameLabelRect != null) {
            val urduTop = englishNameRect!!.bottom + verticalPadding
            val urduBottom = fatherNameLabelRect!!.top - verticalPadding

            if (urduBottom > urduTop) {
                val urduLeft = englishNameRect!!.left
                val urduRight = (englishNameRect!!.right + (imgWidth * 0.30)).toInt().coerceAtMost(imgWidth)

                val urduNameRect = Rect(urduLeft, urduTop, urduRight, urduBottom)

                canvas.drawRect(urduNameRect, paintGreen)
                cropSafe(originalBitmap, urduNameRect)?.let {
                    croppedList.add(CroppedUrduField("Urdu Name", it))
                }
            }
        }

        // 3. Crop Urdu Father Name (Between English Father Name bottom & Gender header top)
        if (englishFatherNameRect != null && genderLabelRect != null) {
            val urduTop = englishFatherNameRect!!.bottom + verticalPadding
            val urduBottom = genderLabelRect!!.top - verticalPadding

            if (urduBottom > urduTop) {
                val urduLeft = englishFatherNameRect!!.left
                val urduRight = (englishFatherNameRect!!.right + (imgWidth * 0.30)).toInt().coerceAtMost(imgWidth)

                val urduFatherRect = Rect(urduLeft, urduTop, urduRight, urduBottom)

                canvas.drawRect(urduFatherRect, paintGreen)
                cropSafe(originalBitmap, urduFatherRect)?.let {
                    croppedList.add(CroppedUrduField("Urdu Father Name", it))
                }
            }
        }

        // 4. Draw GREEN Box for Signature Region
        val sigTop = signatureLabelRect?.let { it.top - (imgHeight * 0.16).toInt() }
            ?: (imgHeight * 0.72).toInt()
        val sigLeft = (imgWidth * 0.70).toInt()
        val sigRight = (imgWidth * 0.90).toInt()
        val sigBottom = sigTop + (imgHeight * 0.15).toInt()

        canvas.drawRect(Rect(sigLeft, sigTop, sigRight, sigBottom), paintGreen)

        return UrduExtractionResult(annotatedFullImage = annotatedBitmap, extractedEnglishText = visionText, cropsUrdu = croppedList)
    }

    private fun cropSafe(original: Bitmap, rect: Rect): Bitmap? {
        val x = rect.left.coerceIn(0, original.width - 1)
        val y = rect.top.coerceIn(0, original.height - 1)
        val w = rect.width().coerceAtMost(original.width - x)
        val h = rect.height().coerceAtMost(original.height - y)

        if (w <= 0 || h <= 0) return null
        return Bitmap.createBitmap(original, x, y, w, h)
    }

    // Utility

    private fun cropCnicCardByAnchors(bitmap: Bitmap, visionText: Text): Bitmap? {
        var minX = bitmap.width
        var minY = bitmap.height
        var maxX = 0
        var maxY = 0
        var foundLine = false

        val allLines = visionText.textBlocks.flatMap { it.lines }
        if (allLines.isEmpty()) return null

        for (line in allLines) {
            line.boundingBox?.let { box ->
                foundLine = true
                if (box.left < minX) minX = box.left
                if (box.top < minY) minY = box.top
                if (box.right > maxX) maxX = box.right
                if (box.bottom > maxY) maxY = box.bottom
            }
        }

        if (!foundLine) return null

        // Padding around detected text bounds (6% horizontal, 5% vertical)
        val padX = (bitmap.width * 0.06).toInt()
        val padY = (bitmap.height * 0.05).toInt()

        val cardRect = Rect(
            (minX - padX).coerceAtLeast(0),
            (minY - padY).coerceAtLeast(0),
            (maxX + padX).coerceAtMost(bitmap.width),
            (maxY + padY).coerceAtMost(bitmap.height)
        )

        return cropSafe(bitmap, cardRect)
    }
    fun detectTextRotationAngle(visionText: Text): Float {
        val angles = mutableListOf<Float>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                angles.add(line.angle)
                Log.d(TAG, "[Text Line Angle]: ${line.text} - ( ${line.angle})\n")
            }
        }

        if (angles.isEmpty()) {
            Log.d(TAG, "[Method 1 - Text Line Angle] No lines detected. Defaulting angle to 0°")
            return 0f
        }

        val averageAngle = angles.average().toFloat()
        val normalizedAngle = when {
            averageAngle in 45f..135f -> 90f
            averageAngle in 135f..225f || averageAngle in -225f..-135f -> 180f
            averageAngle in 225f..315f || averageAngle in -135f..-45f -> 270f
            else -> 0f
        }

        Log.d(TAG, "[Method 1 - Text Line Angle] Raw Avg Angle: ${"%.2f".format(averageAngle)}° -> Normalized: ${normalizedAngle.toInt()}°")
        return normalizedAngle
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun isCardUpsideDown(visionText: Text, imageHeight: Int): Boolean {
        for (block in visionText.textBlocks) {
            val cleanText = block.text.lowercase()
            if (cleanText.contains("pakistan") || cleanText.contains("identity")) {
                block.boundingBox?.let { rect ->
                    if (rect.top > imageHeight * 0.5) return true
                }
            }
        }
        return false
    }

}