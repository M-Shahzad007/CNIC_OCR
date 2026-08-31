package pk.pitb.cnic_ocr_detection.views.urduExtractor

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class CnicAlignmentAnalyzer(
    private val overlay: CnicCameraOverlay,
    private val onAlignmentChanged: (Boolean) -> Unit
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val isBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    @OptIn(ExperimentalGetImage::class)
    fun analyzeImage(imageProxy: ImageProxy) {

        if (!isBusy.compareAndSet(false, true)) {
            imageProxy.close() // drop this frame, previous one still processing
            return
        }

        val rawBitmap = imageProxy.toBitmap()
        val rotation = imageProxy.imageInfo.rotationDegrees

        imageProxy.close() // Close proxy immediately to keep UI/Camera pipeline smooth

        // Apply rotation matrix
        val orientedBitmap = if (rotation != 0) {
            val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        } else {
            rawBitmap
        }
        Log.d(
            "CnicDebug",
            "overlay: ${overlay.width}x${overlay.height} aspect=${overlay.width.toFloat() / overlay.height}"
        )
        Log.d(
            "CnicDebug",
            "analysis frame: ${orientedBitmap.width}x${orientedBitmap.height} aspect=${orientedBitmap.width.toFloat() / orientedBitmap.height}"
        )

        if (overlay.width == 0 || overlay.height == 0) {
            isBusy.set(false) // ⚠️ Release lock on early return
            return
        }

        // if (overlay.width == 0 || overlay.height == 0) return

        // 1. First crop the full card out of the frame using cardBounds scale
        val scaleX = orientedBitmap.width.toFloat() / overlay.width.toFloat()
        val scaleY = orientedBitmap.height.toFloat() / overlay.height.toFloat()

        val cardBmp = cropAbsolute(orientedBitmap, overlay.cardBounds, scaleX, scaleY) /*?: return*/
        if (cardBmp == null) {
            isBusy.set(false) // ⚠️ Release lock on early return
            return
        }
        // 2. Crop individual regions directly relative to normalized percentage inside cardBmp
        //   val headerBmp = cropNormalized(cardBmp, 0.22f, 0.03f, 0.82f, 0.15f)
        //   val nameBmp = cropNormalized(cardBmp, 0.25f, 0.18f, 0.65f, 0.26f)
        val headerBmp = cropZone(cardBmp, CnicFieldZones.HEADER)
        val nameBmp = cropZone(cardBmp, CnicFieldZones.NAME_LABEL)
        val fatherNameBmp = cropZone(cardBmp, CnicFieldZones.FATHER_LABEL)
        val signatureBmp = cropZone(cardBmp, CnicFieldZones.SIGNATURE_TEXT)

        /*   val fatherNameBmp = cropNormalized(cardBmp, 0.25f, 0.38f, 0.65f, 0.46f)
           val signatureBmp = cropNormalized(cardBmp, 0.68f, 0.72f, 0.96f, 0.96f)
        */   // 3. Verify extracted text in regions
        verifyCroppedRegions(
            headerBmp,
            nameBmp,
            fatherNameBmp,
            signatureBmp
        )
    }

    private fun cropAbsolute(source: Bitmap, bounds: RectF, scaleX: Float, scaleY: Float): Bitmap? {
        val left = (bounds.left * scaleX).toInt().coerceIn(0, source.width - 1)
        val top = (bounds.top * scaleY).toInt().coerceIn(0, source.height - 1)
        val width = (bounds.width() * scaleX).toInt().coerceAtMost(source.width - left)
        val height = (bounds.height() * scaleY).toInt().coerceAtMost(source.height - top)

        if (width <= 0 || height <= 0) return null
        return Bitmap.createBitmap(source, left, top, width, height)
    }

    private fun cropZone(bitmap: Bitmap, zone: RectF): Bitmap? {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val x = (bw * zone.left).toInt().coerceIn(0, bitmap.width - 1)
        val y = (bh * zone.top).toInt().coerceIn(0, bitmap.height - 1)
        val w = (bw * (zone.right - zone.left)).toInt().coerceAtMost(bitmap.width - x)
        val h = (bh * (zone.bottom - zone.top)).toInt().coerceAtMost(bitmap.height - y)
        return if (w <= 0 || h <= 0) null else Bitmap.createBitmap(bitmap, x, y, w, h)
    }

    private fun verifyCroppedRegions(
        headerBmp: Bitmap?,
        nameBmp: Bitmap?,
        fatherNameBmp: Bitmap?,
        signatureBmp: Bitmap?
    ) {
        var hasHeader = false
        var hasName = false
        var hasFatherName = false
        var hasSignature = false

        fun processRegion(
            tag: String,
            bmp: Bitmap?,
            targetMatch: (String) -> Boolean,
            onResult: (Boolean) -> Unit,
            onComplete: () -> Unit
        ) {
            if (bmp == null) {
                onResult(false)
                onComplete()
                return
            }
            val inputImage = InputImage.fromBitmap(bmp, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val cleanText =
                        visionText.text.lowercase().replace(" ", "").replace("\n", "").trim()
                    /*if (cleanText.isNotEmpty()){
                        Log.d("CnicAlignmentAnalyzer", "${bmp.width} x ${bmp.height} - [$tag] cleanText: '$cleanText'")
                    }*/
                    onResult(targetMatch(cleanText))
                }
                .addOnFailureListener {
                    onResult(false)
                }
                .addOnCompleteListener {
                    onComplete()
                }
        }

        var completedCount = 0
        val checkCompletion = {
            completedCount++
            if (completedCount == 1) {
                val isFullyAligned = /*hasHeader &&*/ hasName /*&& hasFatherName && hasSignature*/
                //  if (hasHeader) Log.d("CnicAlignmentAnalyzer", "hasHeader - $hasHeader")
                if (hasName) Log.d("CnicAlignmentAnalyzer", "hasName - $hasName")
                if (hasFatherName) Log.d("CnicAlignmentAnalyzer", "hasFatherName - $hasFatherName")
                if (hasSignature) Log.d("CnicAlignmentAnalyzer", "hasSignature - $hasSignature")
                //  overlay.setAlignmentStatus(isFullyAligned)
                overlay.setFieldStatuses(
                    header = hasHeader,
                    name = hasName,
                    fatherName = hasFatherName,
                    signature = hasSignature
                )
                onAlignmentChanged(isFullyAligned)

                // ✅ Release lock here when all regions for the frame are finished
                isBusy.set(false)
            }
        }

        /* processRegion(
             tag = "HEADER",
             bmp = headerBmp,
             targetMatch = { text -> text.contains("pakistan") || text.contains("identity") || text.contains("card") || text.contains("republic") },
             onResult = { hasHeader = it },
             onComplete = checkCompletion
         )*/
        processRegion(
            tag = "NAME",
            bmp = nameBmp,
            targetMatch = { text ->
                Log.d("CnicAlignmentAnalyzer", "NAME - $text")
                text.contains("name")
            },
            onResult = { hasName = it },
            onComplete = checkCompletion
        )
 /*       processRegion(
            tag = "FATHER_NAME",
            bmp = fatherNameBmp,
            targetMatch = { text ->
                Log.d("CnicAlignmentAnalyzer", "FATHER_NAME - $text")
                text.contains("father") || text.contains("fathername")
            },
            onResult = { hasFatherName = it },
            onComplete = checkCompletion
        )
        processRegion(
            tag = "SIGNATURE",
            bmp = signatureBmp,
            targetMatch = { text ->
                Log.d("CnicAlignmentAnalyzer", "SIGNATURE - $text")
                *//* text.contains("holder") ||*//* text.contains("signature")
            },
            onResult = { hasSignature = it },
            onComplete = checkCompletion
        )*/
    }
}