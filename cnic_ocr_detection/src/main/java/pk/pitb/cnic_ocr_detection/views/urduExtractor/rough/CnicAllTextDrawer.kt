package pk.pitb.cnic_ocr_detection.views.urduExtractor.rough

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class CnicAllTextDrawer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun drawAllTextBoundingBoxes(
        cnicBitmap: Bitmap,
        onComplete: (Bitmap) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val image = InputImage.fromBitmap(cnicBitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val annotatedBitmap = annotateImage(cnicBitmap, visionText)
                onComplete(annotatedBitmap)
            }
            .addOnFailureListener { e -> onFailure(e) }
    }

    private fun annotateImage(originalBitmap: Bitmap, visionText: Text): Bitmap {
        val annotatedBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotatedBitmap)

        val strokeWidth = (originalBitmap.width * 0.004f).coerceAtLeast(3f)

        val paintRed = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            isAntiAlias = true
        }

        val paintGreen = Paint().apply {
            color = Color.GREEN
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

        // 1. Draw RED boxes on all English lines & store key anchor Rects
        // 1. Precise check for actual English text lines
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                line.boundingBox?.let { rect ->
                    canvas.drawRect(rect, paintRed)

                    val cleanText = line.text.lowercase().replace(" ", "").trim()

                    when {
                        cleanText.contains("father") -> if (fatherNameLabelRect == null) fatherNameLabelRect = rect
                        cleanText.contains("gender") || cleanText.contains("country") -> if (genderLabelRect == null) genderLabelRect = rect
                        cleanText.contains("holder") || cleanText.contains("signature") -> if (signatureLabelRect == null) signatureLabelRect = rect
                        // Exclude ALL top header strings explicitly
                        cleanText == "name" || cleanText.contains("pakistan") || cleanText.contains("islamic") || cleanText.contains("republic") || cleanText.contains("identity") -> {
                            // Ignore top headers
                        }
                        else -> {
                            // Only assign if Y-position is strictly below the 'Name' label level (> 20% height)
                            if (englishNameRect == null && rect.top > imgHeight * 0.20 && rect.top < imgHeight * 0.45) {
                                englishNameRect = rect
                            } else if (englishFatherNameRect == null && rect.top > imgHeight * 0.40 && rect.top < imgHeight * 0.60) {
                                englishFatherNameRect = rect
                            }
                        }
                    }
                }
            }
        }

        val verticalPadding = (imgHeight * 0.005).toInt() // Small safety margin above headers

        // 2. Draw GREEN Box for Urdu Name (Sandwiched between English Name bottom & Father Name header top)
        if (englishNameRect != null && fatherNameLabelRect != null) {
            val urduTop = englishNameRect!!.bottom + verticalPadding
            val urduBottom = fatherNameLabelRect!!.top - verticalPadding

            if (urduBottom > urduTop) {
                val urduLeft = englishNameRect!!.left
                val urduRight = (englishNameRect!!.right + (imgWidth * 0.15)).toInt().coerceAtMost(imgWidth)

                canvas.drawRect(Rect(urduLeft, urduTop, urduRight, urduBottom), paintGreen)
            }
        }

        // 3. Draw GREEN Box for Urdu Father Name (Sandwiched between English Father Name bottom & Gender header top)
        if (englishFatherNameRect != null && genderLabelRect != null) {
            val urduTop = englishFatherNameRect!!.bottom + verticalPadding
            val urduBottom = genderLabelRect!!.top - verticalPadding

            if (urduBottom > urduTop) {
                val urduLeft = englishFatherNameRect!!.left
                val urduRight = (englishFatherNameRect!!.right + (imgWidth * 0.15)).toInt().coerceAtMost(imgWidth)

                canvas.drawRect(Rect(urduLeft, urduTop, urduRight, urduBottom), paintGreen)
            }
        }

        // 4. Draw GREEN Box for Signature Region
        val sigTop = signatureLabelRect?.let { it.top - (imgHeight * 0.16).toInt() }
            ?: (imgHeight * 0.72).toInt()
        val sigLeft = (imgWidth * 0.70).toInt()
        val sigRight = (imgWidth * 0.90).toInt()
        val sigBottom = sigTop + (imgHeight * 0.15).toInt()

        canvas.drawRect(Rect(sigLeft, sigTop, sigRight, sigBottom), paintGreen)

        return annotatedBitmap
    }
}