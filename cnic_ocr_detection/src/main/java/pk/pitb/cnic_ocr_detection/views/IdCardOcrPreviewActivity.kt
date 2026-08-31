package pk.pitb.cnic_ocr_detection.views

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import pk.pitb.cnic_ocr_detection.R
import pk.pitb.cnic_ocr_detection.views.urduExtractor.CnicAlignmentAnalyzer
import pk.pitb.cnic_ocr_detection.views.urduExtractor.CnicCameraOverlay
import pk.pitb.cnic_ocr_detection.views.urduExtractor.CnicFieldZones
import pk.pitb.cnic_ocr_detection.views.urduExtractor.CnicUrduCropper
import pk.pitb.cnic_ocr_detection.views.urduExtractor.CroppedUrduField
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import androidx.camera.core.UseCaseGroup
class IdCardOcrPreviewActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var cameraOverlay: CnicCameraOverlay
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var latestFrameBitmap: Bitmap? = null

    // Auto-capture parameters
    private var isCapturing = false
    private val autoCaptureHandler = Handler(Looper.getMainLooper())
    private var autoCaptureRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_id_card_ocr_preview)

        // Prevent full-view padding shifts so camera & overlay match device boundaries exactly
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { _, insets ->
            insets
        }

        previewView = findViewById(R.id.previewView)
        cameraOverlay = findViewById(R.id.cameraOverlay)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Reveal overlay and start scanning
        cameraOverlay.visibility = View.VISIBLE
        //startCamera()
        previewView.post { startCamera() }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            // Initialize Alignment Analyzer
            val alignmentAnalyzer = CnicAlignmentAnalyzer(
                overlay = cameraOverlay,
                onAlignmentChanged = { isAligned ->
                    handleAutoCaptureLogic(isAligned)
                }
            )

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                latestFrameBitmap = imageProxy.toBitmap()
                alignmentAnalyzer.analyzeImage(imageProxy)
            }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

           /* try {
                cameraProvider?.unbindAll()
                val camera = cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
*/
            val viewPort = previewView.viewPort
            if (viewPort == null) {
                Log.e("IdCardOcrPreview", "previewView not laid out yet — retrying")
                previewView.post { startCamera() }
                return@addListener
            }

            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(imageAnalysis)
                .setViewPort(viewPort)
                .build()

            try {
                cameraProvider?.unbindAll()
                val camera = cameraProvider?.bindToLifecycle(this, cameraSelector, useCaseGroup)
                // Continuous Auto-Focus
                previewView.post {
                    val factory = SurfaceOrientedMeteringPointFactory(
                        previewView.width.toFloat(), previewView.height.toFloat()
                    )
                    val focusPoint = factory.createPoint(previewView.width / 2f, previewView.height / 2f)
                    val action = FocusMeteringAction.Builder(focusPoint)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                    camera?.cameraControl?.startFocusAndMetering(action)
                }
            } catch (e: Exception) {
                Log.e("IdCardOcrPreview", "Camera binding failed", e)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleAutoCaptureLogic(isAligned: Boolean) {
        runOnUiThread {
            if (isAligned && !isCapturing) {
                if (autoCaptureRunnable == null) {
                    autoCaptureRunnable = Runnable {
                        if (!isCapturing) {
                            triggerFrameCapture()
                        }
                    }
                    // Trigger capture when target anchors stay aligned for 600ms
                    autoCaptureHandler.postDelayed(autoCaptureRunnable!!, 600)
                }
            } else {
                // Reset capture handler if card moves out of alignment
                autoCaptureRunnable?.let {
                    autoCaptureHandler.removeCallbacks(it)
                    autoCaptureRunnable = null
                }
            }
        }
    }

    private fun triggerFrameCapture() {
        if (isCapturing) return
        isCapturing = true

        latestFrameBitmap?.let { rawBitmap ->
            stopCamera()

            // Transform Overlay Card Rect coordinates onto the high-res Bitmap space
            val cardBounds = cameraOverlay.cardBounds
            val scaleX = rawBitmap.width.toFloat() / cameraOverlay.width.toFloat()
            val scaleY = rawBitmap.height.toFloat() / cameraOverlay.height.toFloat()

            val cropX = (cardBounds.left * scaleX).toInt().coerceAtLeast(0)
            val cropY = (cardBounds.top * scaleY).toInt().coerceAtLeast(0)
            val cropWidth = (cardBounds.width() * scaleX).toInt().coerceAtMost(rawBitmap.width - cropX)
            val cropHeight = (cardBounds.height() * scaleY).toInt().coerceAtMost(rawBitmap.height - cropY)

            val cardCroppedBitmap = Bitmap.createBitmap(rawBitmap, cropX, cropY, cropWidth, cropHeight)
            // Extract individual sub-fields directly
            val extractedFields = extractFieldsDirectly(cardCroppedBitmap)
// Draw overlay bounding boxes on full card preview bitmap
            val annotatedCardBitmap = drawBoundingBoxesOnCard(cardCroppedBitmap)
            // Render preview dialog on UI thread
            runOnUiThread {
                showExtractedFieldsDialog(annotatedCardBitmap, extractedFields)
            }

            // Show dialog with cardCroppedBitmap on Main UI Thread
           /* runOnUiThread {
                showCroppedImageDialog(cardCroppedBitmap)
            }*/
            // Run CNIC Extraction pipeline
        /*    val cropper = CnicUrduCropper()
            cropper.processCnicImage(
                cnicBitmap = cardCroppedBitmap,
                onSuccess = { result ->
                    // Return result or handle extraction
                    Toast.makeText(this, "CNIC captured successfully!", Toast.LENGTH_SHORT).show()
                    finish()
                },
                onFailure = { error ->
                    isCapturing = false
                    Toast.makeText(this, "Capture failed: ${error.localizedMessage}", Toast.LENGTH_SHORT).show()
                    startCamera() // Restart live camera on failure
                }
            )*/
        } ?: run {
            isCapturing = false
        }
    }

    private fun stopCamera() {
        autoCaptureRunnable?.let { autoCaptureHandler.removeCallbacks(it) }
        autoCaptureRunnable = null
        cameraProvider?.unbindAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        cameraExecutor.shutdown()
    }


    private fun showCroppedImageDialog(bitmap: Bitmap) {
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }

        AlertDialog.Builder(this)
            .setTitle("Captured Card Preview")
            .setView(imageView)
            .setCancelable(false)
            .setPositiveButton("Use Image") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setNegativeButton("Retake") { dialog, _ ->
                dialog.dismiss()
                isCapturing = false
                startCamera() // Resume camera scanning
            }
            .show()
    }
    private fun extractFieldsDirectly(cardCroppedBitmap: Bitmap): List<CroppedUrduField> {
        val crops = mutableListOf<CroppedUrduField>()
        val bw = cardCroppedBitmap.width.toFloat()
        val bh = cardCroppedBitmap.height.toFloat()
        cropZone(cardCroppedBitmap, CnicFieldZones.HEADER)?.let { crops.add(CroppedUrduField("Header", it)) }
        cropZone(cardCroppedBitmap, CnicFieldZones.NAME_LABEL)?.let { crops.add(CroppedUrduField("English Name Label", it)) }
        cropZone(cardCroppedBitmap, CnicFieldZones.NAME_URDU)?.let { crops.add(CroppedUrduField("Urdu Name", it)) }
        cropZone(cardCroppedBitmap, CnicFieldZones.FATHER_LABEL)?.let { crops.add(CroppedUrduField("English Father Name Label", it)) }
        cropZone(cardCroppedBitmap, CnicFieldZones.FATHER_URDU)?.let { crops.add(CroppedUrduField("Urdu Father Name", it)) }
        cropZone(cardCroppedBitmap, CnicFieldZones.SIGNATURE_TEXT)?.let { crops.add(CroppedUrduField("Signature Text", it)) }
        return crops
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
    private fun showExtractedFieldsDialog(fullCard: Bitmap, fields: List<CroppedUrduField>) {
        val context = this
        val density = resources.displayMetrics.density

        // Scrollable container
        val scrollView = android.widget.ScrollView(context).apply {
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
        }

        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        // 1. Section Header: Full Card
        val fullCardLabel = android.widget.TextView(context).apply {
            text = "FULL CNIC CROP"
            setTypeface(null, android.graphics.Typeface.BOLD)
            textSize = 14f
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        container.addView(fullCardLabel)

        // Full Card ImageView
        val fullCardView = ImageView(context).apply {
            setImageBitmap(fullCard)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (16 * density).toInt()
            }
        }
        container.addView(fullCardView)

        // Divider
        val divider = View(context).apply {
            setBackgroundColor(android.graphics.Color.LTGRAY)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
        }
        container.addView(divider)

        // 2. Section Header: Extracted Key Fields
        val fieldsLabel = android.widget.TextView(context).apply {
            text = "EXTRACTED FIELD REGIONS"
            setTypeface(null, android.graphics.Typeface.BOLD)
            textSize = 14f
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        container.addView(fieldsLabel)

        // Render individual field crops
        fields.forEach { field ->
            val itemLayout = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                setBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (10 * density).toInt()
                }
            }

            val fieldTitle = android.widget.TextView(context).apply {
                text = field.fieldName
                setTypeface(null, android.graphics.Typeface.BOLD)
                textSize = 12f
                setTextColor(android.graphics.Color.DKGRAY)
            }

            val fieldImageView = ImageView(context).apply {
                setImageBitmap(field.croppedBitmap)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    (60 * density).toInt()
                )
            }

            itemLayout.addView(fieldTitle)
            itemLayout.addView(fieldImageView)
            container.addView(itemLayout)
        }

        scrollView.addView(container)

        AlertDialog.Builder(context)
            .setTitle("Extracted CNIC Data")
            .setView(scrollView)
            .setCancelable(false)
            .setPositiveButton("Accept") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setNegativeButton("Retake") { dialog, _ ->
                dialog.dismiss()
                isCapturing = false
                startCamera()
            }
            .show()
    }

    private fun drawBoundingBoxesOnCard(sourceCard: Bitmap): Bitmap {
        // 1. Create a mutable copy of the cropped card bitmap to draw onto
        val annotatedBitmap = sourceCard.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(annotatedBitmap)

        val width = annotatedBitmap.width.toFloat()
        val height = annotatedBitmap.height.toFloat()

        // 2. Configure paints for borders and text labels
        val boxPaint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = (1 * resources.displayMetrics.density)
            color = android.graphics.Color.GREEN
            isAntiAlias = true
        }

        val labelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.GREEN
            textSize = (8 * resources.displayMetrics.density)
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT
        }

        // 3. Define zones to render on the overlay bitmap
        val zonesToDraw = listOf(
            "Header" to CnicFieldZones.HEADER,
            "Name Label" to CnicFieldZones.NAME_LABEL,
            "Urdu Name" to CnicFieldZones.NAME_URDU,
            "Father Label" to CnicFieldZones.FATHER_LABEL,
            "Urdu Father Name" to CnicFieldZones.FATHER_URDU,
            "Signature" to CnicFieldZones.SIGNATURE_TEXT
        )

        // 4. Draw each bounding box and its corresponding label
        zonesToDraw.forEach { (label, zone) ->
            val rect = RectF(
                width * zone.left,
                height * zone.top,
                width * zone.right,
                height * zone.bottom
            )
            // Draw outline rectangle
            canvas.drawRect(rect, boxPaint)

            // Draw small label tag slightly above or inside the zone
            canvas.drawText(label, rect.left + 4f, rect.top + labelPaint.textSize, labelPaint)
        }

        return annotatedBitmap
    }
}