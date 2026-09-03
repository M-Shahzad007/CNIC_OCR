package pk.pitb.cnic_ocr_detection.views

import android.annotation.SuppressLint
import android.graphics.Bitmap
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
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import pk.pitb.cnic_ocr_detection.OCRManager
import pk.pitb.cnic_ocr_detection.R
import pk.pitb.cnic_ocr_detection.utils.compressBitmapToByteArray
import pk.pitb.cnic_ocr_detection.utils.toUprightViewportBitmap
import pk.pitb.cnic_ocr_detection.views.urduExtractor.CnicAlignmentAnalyzer
import pk.pitb.cnic_ocr_detection.views.urduExtractor.CnicCameraOverlay
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CardOcrResult
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CnicFieldParser
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CnicFields
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CnicLineExtractor
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.LineCrop
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.UTRNetRecognizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IdCardOcrPreviewActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var cameraOverlay: CnicCameraOverlay
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null

    // Auto-capture parameters
    private var isCapturing = false
    private val autoCaptureHandler = Handler(Looper.getMainLooper())
    private var autoCaptureRunnable: Runnable? = null

    var imageByteArray: ByteArray? = null
    private lateinit var lineExtractor: CnicLineExtractor


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

        val glyphsText = assets.open("UrduGlyphs.txt").bufferedReader().use { it.readText() }
        val urduRecognizer = UTRNetRecognizer(this, glyphsText)
        lineExtractor = CnicLineExtractor(urduRecognizer)


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
//            val alignmentAnalyzer = CnicAlignmentAnalyzerold(
//                overlay = cameraOverlay,
//                onAlignmentChanged = { isAligned ->
//                    handleAutoCaptureLogic(isAligned)
//                }
//            )
            val alignmentAnalyzer = CnicAlignmentAnalyzer(
                cameraOverlay,
                onAlignmentChanged = { isAligned, validationAnotatedMap, cardBmp ->
                    imageByteArray = compressBitmapToByteArray(cardBmp)
                    handleAutoCaptureLogic(
                        isAligned,
                        validationAnotatedMap,
                        cardBmp
                    )
                }
            )

//            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
//                latestFrameBitmap = imageProxy.toBitmap()
//                alignmentAnalyzer.analyzeImage(imageProxy)
//            }
            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val bitmap = imageProxy.toUprightViewportBitmap()
                imageProxy.close()
                alignmentAnalyzer.analyzeImage(bitmap)
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
                    val focusPoint =
                        factory.createPoint(previewView.width / 2f, previewView.height / 2f)
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

    private fun handleAutoCaptureLogic(
        isAligned: Boolean,
        validationAnotatedMap: Bitmap,
        cardBmp: Bitmap
    ) {
        runOnUiThread {
            if (isAligned && !isCapturing) {
                if (autoCaptureRunnable == null) {
                    autoCaptureRunnable = Runnable {
                        if (!isCapturing) {
                            triggerFrameCapture(validationAnotatedMap, cardBmp)
                        }
                    }
                    // Trigger capture when target anchors stay aligned for 600ms
                    autoCaptureHandler.postDelayed(autoCaptureRunnable!!, 50)
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

    private fun triggerFrameCapture(validationAnotatedMap: Bitmap, cardBmp: Bitmap) {
        if (isCapturing) return
        isCapturing = true
        try {
            stopCamera()
            lineExtractor.extract(
                //  cardBmp = cardBmp,
                cardBmp = validationAnotatedMap,
                onResult = { result: CardOcrResult ->
                    val fields = CnicFieldParser.parse(result)
                    showLineExtractionDialog(result, fields,validationAnotatedMap)
                },
                onFailure = { e ->
                    Log.e("IdCardOcrPreview", "Line extraction failed", e)
                    Toast.makeText(
                        this,
                        "Extraction failed: ${e.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                    isCapturing = false
                    startCamera()
                }
            )
        }catch (e: Exception){
            e.printStackTrace()
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
        lineExtractor.shutdown()
    }

    private fun showLineExtractionDialog(
        result: CardOcrResult,
        fields: CnicFields,
        validationAnotatedMap: Bitmap
    ) {
        val context = this
        val density = resources.displayMetrics.density
        val scrollView = android.widget.ScrollView(context).apply {
            setPadding(
                (12 * density).toInt(),
                (12 * density).toInt(),
                (12 * density).toInt(),
                (12 * density).toInt()
            )
        }
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        fun addKeyValue(label: String, value: String?) {
            container.addView(android.widget.TextView(context).apply {
                text = "$label: ${value ?: "—"}"
                textSize = 13f
                setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
            })
        }

        fun sectionLabel(text: String) = android.widget.TextView(context).apply {
            this.text = text
            setTypeface(null, android.graphics.Typeface.BOLD)
            textSize = 14f
            setPadding(0, (12 * density).toInt(), 0, (6 * density).toInt())
        }

        fun addCropRow(crop: LineCrop, heightDp: Int = 50) {
            val row = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(
                    (8 * density).toInt(),
                    (8 * density).toInt(),
                    (8 * density).toInt(),
                    (8 * density).toInt()
                )
                setBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * density).toInt() }
            }
            row.addView(android.widget.TextView(context).apply {
                text = "${crop.label}: ${crop.recognizedText}"
                textSize = 11f
                setTextColor(android.graphics.Color.DKGRAY)
            })
            row.addView(ImageView(context).apply {
                setImageBitmap(crop.bitmap)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    (heightDp * density).toInt()
                )
            })
            container.addView(row)
        }

        // --- 1. Top Section: Validation Annotated Map ---
        container.addView(sectionLabel("VALIDATION ANNOTATED MAP"))
        container.addView(ImageView(context).apply {
            setImageBitmap(validationAnotatedMap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * density).toInt() }
        })

        container.addView(sectionLabel("EXTRACTED FIELDS"))
        addKeyValue("Name", fields.name)
        addKeyValue("Name (Urdu)", fields.nameUrdu)
        addKeyValue("Father Name", fields.fatherName)
        addKeyValue("Father Name (Urdu)", fields.fatherNameUrdu)
        addKeyValue("Gender", fields.gender)
        addKeyValue("Country of Stay", fields.countryOfStay)
        addKeyValue("Identity Number", fields.identityNumber)
        addKeyValue("Date of Birth", fields.dateOfBirth)
        addKeyValue("Date of Issue", fields.dateOfIssue)
        addKeyValue("Date of Expiry", fields.dateOfExpiry)

        // --- Add Debug Blocks Section ---
        if (fields.debugBlocks.isNotEmpty()) {
            container.addView(sectionLabel("DEBUG BLOCKS"))
            fields.debugBlocks.forEach { (blockName, rawText) ->
                addKeyValue(blockName, rawText.ifEmpty { "N/A" })
            }
        }

        Log.d("resultData","$fields")

        container.addView(sectionLabel("ANNOTATED CARD (all lines + Urdu regions)"))
        container.addView(ImageView(context).apply {
            setImageBitmap(result.annotatedBitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * density).toInt() }
        })

        result.urduNameCrop?.let {
            container.addView(sectionLabel("URDU NAME"))
            addCropRow(it, heightDp = 60)
        }
        result.urduFatherNameCrop?.let {
            container.addView(sectionLabel("URDU FATHER NAME"))
            addCropRow(it, heightDp = 60)
        }

        container.addView(sectionLabel("ALL DETECTED LINES (${result.lineCrops.size})"))
        result.lineCrops.forEach { addCropRow(it) }

        scrollView.addView(container)

        AlertDialog.Builder(context)
            .setTitle("CNIC OCR Extraction")
            .setView(scrollView)
            .setCancelable(false)
            .setPositiveButton("Accept") { dialog, _ ->

                OCRManager.ocrDetectionResult.onOcrDetection(
                    fields,
                    imageByteArray,
                )

                dialog.dismiss();
                finish()
            }
            .setNegativeButton("Retake") { dialog, _ ->
                dialog.dismiss()
                isCapturing = false
                startCamera()
            }
            .show()
    }

}