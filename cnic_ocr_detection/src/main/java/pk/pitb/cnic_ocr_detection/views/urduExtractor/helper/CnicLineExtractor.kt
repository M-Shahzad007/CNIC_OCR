package pk.pitb.cnic_ocr_detection.views.urduExtractor.helper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A single cropped region — English (from ML Kit) or Urdu (from UTRNet) — with its text. */
data class LineCrop(
    val label: String,
    val bounds: Rect,
    val bitmap: Bitmap,
    val recognizedText: String
)

data class CardOcrResult(
    val annotatedBitmap: Bitmap,       // cardBmp with EVERY detected line boxed + indexed
    val lineCrops: List<LineCrop>,     // one crop + text per detected English/machine-readable line
    val urduNameCrop: LineCrop?,       // derived gap crop + UTRNet text
    val urduFatherNameCrop: LineCrop?  // derived gap crop + UTRNet text
)

/**
 * @param urduRecognizer Pass in an already-constructed UTRNetRecognizer (model + glyphs loaded
 *                        once, e.g. in Activity.onCreate) — do NOT construct a new one per frame,
 *                        it's expensive.
 */
class CnicLineExtractor(
    private val urduRecognizer: UTRNetRecognizer
) {
    private val mlKitRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val bgExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val TAG = "CnicLineExtractor"

    private data class LineInfo(val text: String, val clean: String, val rect: Rect)

    /**
     * Runs ML Kit line detection, then UTRNet Urdu recognition on the derived gap regions,
     * off the main thread. [onResult] / [onFailure] are always invoked on the main thread.
     */
    fun extract(
        cardBmp: Bitmap,
        onResult: (CardOcrResult) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        mlKitRecognizer.process(InputImage.fromBitmap(cardBmp, 0))
            .addOnSuccessListener { visionText ->
                // UTRNet inference is heavy — move off the ML Kit callback (main) thread.
                bgExecutor.execute {
                    try {
                        val result = buildResult(cardBmp, visionText)
                        mainHandler.post { onResult(result) }
                    } catch (e: Exception) {
                        Log.e(TAG, "extract() failed", e)
                        mainHandler.post { onFailure(e) }
                    }
                }
            }
            .addOnFailureListener { e -> mainHandler.post { onFailure(e) } }
    }

    private fun buildResult(cardBmp: Bitmap, visionText: Text): CardOcrResult {
        // 1. Collect every detected line with a valid bounding box, sorted top-to-bottom.
        val lines: List<LineInfo> = visionText.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line -> line.boundingBox?.let { LineInfo(line.text, cleanText(line.text), it) } }
            .sortedBy { it.rect.top }

        lines.forEachIndexed { i, l -> Log.d(TAG, "[$i] top=${l.rect.top} text='${l.text}'") }

        // 2. Draw a box + index number around EVERY detected line for visual confirmation.
        val annotated = cardBmp.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotated)
        val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = (cardBmp.width * 0.003f).coerceAtLeast(3f)
            color = Color.BLUE
            isAntiAlias = true
        }
        val indexPaint = Paint().apply {
            color = Color.RED
            textSize = (cardBmp.width * 0.018f).coerceAtLeast(18f)
            isAntiAlias = true
        }
        lines.forEachIndexed { index, line ->
            canvas.drawRect(line.rect, boxPaint)
            canvas.drawText(
                "$index",
                line.rect.left.toFloat(),
                (line.rect.top - 6f).coerceAtLeast(indexPaint.textSize),
                indexPaint
            )
        }

        // 3. Crop each individual detected line — text is already known from ML Kit, no need
        //    to re-run any recognizer on these.
        val lineCrops = lines.mapIndexedNotNull { index, line ->
            cropSafe(cardBmp, line.rect)?.let {
                LineCrop(label = "line_$index", bounds = line.rect, bitmap = it, recognizedText = line.text)
            }
        }

        // 4. Derive Urdu Name region: skip "Name" label line, take the BOTTOM of the next
        //    line (English value) down to the TOP of "Father Name".
        val nameLabelIdx = lines.indexOfFirst { it.clean == "name" }
        val fatherLabelIdx = lines.indexOfFirst { it.clean.contains("father") }

        val urduNameRegion = buildGapRegion(
            cardBmp, lines,
            labelIdx = nameLabelIdx,
            stopIdx = fatherLabelIdx,
            label = "Urdu Name"
        )

        // 5. Derive Urdu Father Name region: skip "Father Name" label, take BOTTOM of the
        //    next line down to TOP of the next anchor (Gender / Country / Identity Number).
        val nextAnchorIdx = if (fatherLabelIdx >= 0) {
            lines.indices
                .drop(fatherLabelIdx + 2)
                .firstOrNull { idx ->
                    val c = lines[idx].clean
                    c.contains("gender") || c.contains("country") || c.contains("identity")
                } ?: (fatherLabelIdx + 2)
        } else -1

        val urduFatherRegion = buildGapRegion(
            cardBmp, lines,
            labelIdx = fatherLabelIdx,
            stopIdx = nextAnchorIdx,
            label = "Urdu Father Name"
        )

        // 6. Draw the derived Urdu gap regions in GREEN, distinct from the BLUE per-line boxes.
        val urduPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = (cardBmp.width * 0.004f).coerceAtLeast(4f)
            color = Color.GREEN
            isAntiAlias = true
        }
        urduNameRegion?.let { canvas.drawRect(it.second, urduPaint) }
        urduFatherRegion?.let { canvas.drawRect(it.second, urduPaint) }

        // 7. Run UTRNet on the two Urdu crops now (we're already on a background thread here).
        val urduNameCrop = urduNameRegion?.let { (bmp, rect) ->
            val text = safeRecognizeUrdu(bmp, "Urdu Name")
            LineCrop("Urdu Name", rect, bmp, text)
        }
        val urduFatherNameCrop = urduFatherRegion?.let { (bmp, rect) ->
            val text = safeRecognizeUrdu(bmp, "Urdu Father Name")
            LineCrop("Urdu Father Name", rect, bmp, text)
        }

        return CardOcrResult(annotated, lineCrops, urduNameCrop, urduFatherNameCrop)
    }

    private fun safeRecognizeUrdu(bmp: Bitmap, tag: String): String {
        return try {
            val text = urduRecognizer.recognizeText(bmp)
            Log.d(TAG, "[$tag] UTRNet text='$text'")
            text
        } catch (e: Exception) {
            Log.e(TAG, "[$tag] UTRNet recognition failed", e)
            ""
        }
    }

    /**
     * Returns (croppedBitmap, boundsRect) for the gap between labelIdx's value line and stopIdx's line.
     * Expanded horizontally to cover full Right-to-Left (RTL) Urdu text.
     */
    private fun buildGapRegion(
        cardBmp: Bitmap,
        lines: List<LineInfo>,
        labelIdx: Int,
        stopIdx: Int,
        label: String
    ): Pair<Bitmap, Rect>? {
        if (labelIdx < 0 || stopIdx < 0 || labelIdx + 1 >= lines.size || labelIdx + 1 >= stopIdx) {
            Log.w(TAG, "$label: cannot resolve anchors (labelIdx=$labelIdx stopIdx=$stopIdx, totalLines=${lines.size})")
            return null
        }

        val valueLine = lines[labelIdx + 1]
        val stopLine = lines[stopIdx]

        val padX = (cardBmp.width * 0.01f).toInt()
        val padY = (cardBmp.height * 0.005f).toInt()

        // 1. Left anchor: Aligned with the English label / text start
        val left = (minOf(valueLine.rect.left, stopLine.rect.left) - padX).coerceAtLeast(0)

        // 2. Right anchor: Expand up to 75% of the card width (stops right before the signature/photo margin)
        val maxRightBound = (cardBmp.width * 0.75f).toInt()
        val right = maxOf(valueLine.rect.right, maxRightBound).coerceAtMost(cardBmp.width)

        // 3. Vertical bounds between current value bottom and next anchor top
        // 3. Vertical bounds between current value bottom and next anchor top
        val top = (valueLine.rect.bottom + padY).coerceAtMost(cardBmp.height - 1)

        // Add bottom padding (e.g. 1.5% of height) to push the bottom boundary lower
        val padBottom = (cardBmp.height * 0.015f).toInt()
        val bottom = (stopLine.rect.top + padBottom).coerceAtMost(cardBmp.height).coerceAtLeast(top + 1)

//        val top = (valueLine.rect.bottom + padY).coerceAtMost(cardBmp.height - 1)
//        val bottom = (stopLine.rect.top - padY).coerceAtLeast(top + 1)

        if (bottom <= top || right <= left) {
            Log.w(TAG, "$label: degenerate gap rect ($left,$top,$right,$bottom)")
            return null
        }

        val rect = Rect(left, top, right, bottom)
        val bmp = cropSafe(cardBmp, rect) ?: return null

        return bmp to rect
    }

    private fun cleanText(text: String) = text.lowercase().replace(" ", "").replace("\n", "").trim()

    private fun cropSafe(bitmap: Bitmap, rect: Rect): Bitmap? {
        val x = rect.left.coerceIn(0, bitmap.width - 1)
        val y = rect.top.coerceIn(0, bitmap.height - 1)
        val w = rect.width().coerceAtMost(bitmap.width - x)
        val h = rect.height().coerceAtMost(bitmap.height - y)
        if (w <= 0 || h <= 0) return null
        return Bitmap.createBitmap(bitmap, x, y, w, h)
    }

    /** Call from Activity.onDestroy() to release the background thread. */
    fun shutdown() {
        bgExecutor.shutdown()
    }
}