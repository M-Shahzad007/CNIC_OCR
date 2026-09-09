package pk.pitb.cnic_ocr_detection.views

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope

// CanHub Cropper Imports
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.google.android.material.button.MaterialButton

import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pk.pitb.cnic_ocr_detection.OCRManager
import pk.pitb.cnic_ocr_detection.R
import pk.pitb.cnic_ocr_detection.utils.Utils
import pk.pitb.cnic_ocr_detection.utils.compressBitmapToByteArray
import pk.pitb.cnic_ocr_detection.utils.toUprightViewportBitmap
import pk.pitb.cnic_ocr_detection.views.urduExtractor.CnicAlignmentAnalyzer
import pk.pitb.cnic_ocr_detection.views.urduExtractor.CnicAnalyzer
import pk.pitb.cnic_ocr_detection.views.urduExtractor.CnicCameraOverlay
import pk.pitb.cnic_ocr_detection.views.urduExtractor.CnicTemplateCameraOverlay
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CardOcrResult
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CnicFieldParser
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CnicFields
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CnicLineExtractor
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.LineCrop
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.OcrProcessingView
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.OpenCVCardTransformer
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.UTRNetRecognizer
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.custom_croper.CnicCornerEditorView
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.custom_croper.ManualCardTransformer
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IdCardOcrPreviewActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var cameraTemplateOverlay: CnicTemplateCameraOverlay
    private lateinit var cameraOverlay: CnicCameraOverlay
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null

    // Auto-capture controls
    private var isCapturing = false
    private val autoCaptureHandler = Handler(Looper.getMainLooper())
    private var autoCaptureRunnable: Runnable? = null
    private lateinit var ocrProcessingView: OcrProcessingView
    var cardByteArray: ByteArray? = null
    var alignedCardByteArray: ByteArray? = null
    private lateinit var lineExtractor: CnicLineExtractor

    // Temp URI holder for Quick Snap camera photo
    private var tempPhotoUri: Uri? = null
    var currentOrientedBitmap: Bitmap? = null

    // 1. CanHub Cropper Launcher
    private val canHubCropLauncher = registerForActivityResult(CropImageContract()) { result ->
        // requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (result.isSuccessful) {
            result.uriContent?.let { croppedUri ->
                processImageUri(croppedUri)
            } ?: run {
                Toast.makeText(this, "Failed to retrieve cropped image", Toast.LENGTH_SHORT).show()
                showSourceSelectionDialog()
            }
        } else {
            val error = result.error
            Log.e("IdCardOcrPreview", "CanHub Cropping Failed", error)
            showSourceSelectionDialog()
        }
    }

    // 2. Full Resolution Quick Snap Camera Launcher
    private val takeFullPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        //requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (success && tempPhotoUri != null) {
            // Forward captured full-res camera image into CanHub Cropper
            startCanHubCropper(tempPhotoUri!!)
        } else {
            showSourceSelectionDialog()
        }
    }

    // ML Kit Document Scanner Activity Result Launcher
    private val mlKitScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        // requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (result.resultCode == RESULT_OK) {
            val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanningResult?.pages?.firstOrNull()?.imageUri?.let { uri ->
                processImageUri(uri)
            } ?: run {
                Toast.makeText(this, "No document captured", Toast.LENGTH_SHORT).show()
                showSourceSelectionDialog()
            }
        } else {
            showSourceSelectionDialog()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_id_card_ocr_preview)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { _, insets -> insets }

        previewView = findViewById(R.id.previewView)
        cameraTemplateOverlay = findViewById(R.id.cameraTemplateOverlay)
        cameraOverlay = findViewById(R.id.cameraOverlay)
        ocrProcessingView = findViewById(R.id.ocrProcessingView)

        cameraExecutor = Executors.newSingleThreadExecutor()

        val glyphsText = assets.open("UrduGlyphs.txt").bufferedReader().use { it.readText() }
        val urduRecognizer = UTRNetRecognizer(this, glyphsText)
        lineExtractor = CnicLineExtractor(urduRecognizer)

        showSourceSelectionDialog()
    }

    private fun showSourceSelectionDialog() {
        stopCamera()
        cameraTemplateOverlay.visibility = View.GONE
        cameraOverlay.visibility = View.GONE

        val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_source_selection, null)
        bottomSheetDialog.setContentView(dialogView)
        bottomSheetDialog.setCancelable(false)

        val optionsContainer = dialogView.findViewById<LinearLayout>(R.id.optionsContainer)
        val btnExit = dialogView.findViewById<MaterialButton>(R.id.btnExit)

        data class OptionItem(
            val title: String,
            val subtitle: String,
            val iconRes: Int,
            val action: () -> Unit
        )

        val options = listOf(
            OptionItem(
                title = "AI Scanner",
                subtitle = "Automatic document detection and cropping",
                iconRes = R.drawable.ic_document_scanner // Replace with your icon
            ) {
                startMlKitDocumentScanner()
            },
            OptionItem(
                title = "Live Scan (Template Guided)",
                subtitle = "Real-time auto capture with card alignment box",
                iconRes = R.drawable.ic_camera_template // Replace with your icon
            ) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                startLiveScan(true)
            },
            OptionItem(
                title = "Live Scan (Freeform)",
                subtitle = "Real-time auto capture without template bounds",
                iconRes = R.drawable.ic_center_focus // Replace with your icon
            ) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                cameraOverlay.resetOverlay()
                startLiveScan(false)
            },
            OptionItem(
                title = "Quick Snap & Manual Crop",
                subtitle = "Full resolution camera photo with custom cropping",
                iconRes = R.drawable.ic_crop // Replace with your icon
            ) {
                launchQuickSnapCamera()
            }
        )

        // Dynamically inflate option cards
        options.forEach { option ->
            val itemView =
                layoutInflater.inflate(R.layout.item_ocr_source_option, optionsContainer, false)
            val card =
                itemView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardOption)
            val tvTitle = itemView.findViewById<TextView>(R.id.tvTitle)
            val tvSubtitle = itemView.findViewById<TextView>(R.id.tvSubtitle)
            val ivIcon = itemView.findViewById<ImageView>(R.id.ivIcon)

            tvTitle.text = option.title
            tvSubtitle.text = option.subtitle
            ivIcon.setImageResource(option.iconRes)

            card.setOnClickListener {
                bottomSheetDialog.dismiss()
                option.action()
            }

            optionsContainer.addView(itemView)
        }

        btnExit.setOnClickListener {
            bottomSheetDialog.dismiss()
            finish()
        }

        bottomSheetDialog.show()
    }

    private fun showOcrProcessing(bitmap: Bitmap? = null) {

        ocrProcessingView.setBitmap(bitmap)

        ocrProcessingView.startProcessing()
    }

    private fun hideOcrProcessing() {

        ocrProcessingView.stopProcessing()
    }

    private fun showSourceSelectionDialog2() {
        stopCamera()
        cameraTemplateOverlay.visibility = View.GONE
        cameraOverlay.visibility = View.GONE

        val options = arrayOf(
            "AI Scanner (Auto)",
            "Live CNIC Auto-Scan (Template)",
            "Live CNIC Auto-Scan",
            "Quick Snap + Manual Crop (CanHub)"
        )

        AlertDialog.Builder(this)
            .setTitle("Select Document Source")
            .setCancelable(false)
            .setItems(options) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> startMlKitDocumentScanner()
                    1 -> {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        startLiveScan(true)
                    }

                    2 -> {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        cameraOverlay.resetOverlay()
                        startLiveScan(false)
                    }

                    3 -> {
                        // requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        launchQuickSnapCamera()
                    }
                }
            }
            .setNegativeButton("Exit") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .show()
    }

    private fun launchQuickSnapCamera() {
        try {
            val photoFile = File.createTempFile("CNIC_SNAP_", ".jpg", externalCacheDir)
            tempPhotoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.provider",
                photoFile
            )
            tempPhotoUri?.let { takeFullPhotoLauncher.launch(it) }
        } catch (e: Exception) {
            Log.e("IdCardOcrPreview", "Error creating temp photo file", e)
            Toast.makeText(this, "Could not open camera", Toast.LENGTH_SHORT).show()
            showSourceSelectionDialog()
        }
    }

    private fun startCanHubCropper(imageUri: Uri) {
        val cropOptions = CropImageContractOptions(
            uri = imageUri,
            cropImageOptions = CropImageOptions().apply {
                // 1. Disable Aspect Ratio constraints so corners move independently
                fixAspectRatio = false

                // 2. Disable Center Move (prevents dragging the box as a whole accidentally)
                centerMoveEnabled = true

                // 3. Allow free corner dragging
                canChangeCropWindow = true

                // 4. Set Rectangle Corner Handles (easiest for isolated single-corner dragging)
                cornerShape = CropImageView.CropCornerShape.RECTANGLE

                // Visual Overlay & UI Options
                guidelines = CropImageView.Guidelines.ON
                showCropOverlay = true
                activityTitle = "Crop CNIC"
                cropMenuCropButtonTitle = "Done"

                // Preserve raw RGB colors for OCR processing
                outputCompressFormat = Bitmap.CompressFormat.JPEG
                outputCompressQuality = 100
            }
        )
        canHubCropLauncher.launch(cropOptions)
    }

    private fun startMlKitDocumentScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setPageLimit(1)
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE)
            .setScannerMode(GmsDocumentScannerOptions.CAPTURE_MODE_MANUAL)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                mlKitScannerLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build()
                )
            }
            .addOnFailureListener { e ->
                Log.e("IdCardOcrPreview", "Failed to launch ML Kit Scanner", e)
                Toast.makeText(this, "ML Kit Scanner Unavailable: ${e.message}", Toast.LENGTH_SHORT)
                    .show()
                showSourceSelectionDialog()
            }
    }

    private fun startLiveScan(isTemplate: Boolean) {
        if (isTemplate) {
            cameraTemplateOverlay.visibility = View.VISIBLE

        } else {
            cameraOverlay.visibility = View.VISIBLE
        }
        previewView.post { startCamera(isTemplate) }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera(isTemplate: Boolean) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val alignmentAnalyzer = if (isTemplate) {
                CnicAlignmentAnalyzer(
                    cameraTemplateOverlay,
                    onAlignmentChanged = { isAligned, alignedCard, cardBmp ->
                        cardByteArray = compressBitmapToByteArray(cardBmp)
                        Utils.drawCnicAnchorRectangleAsync(cardBmp) { alignedCard ->
                            alignedCardByteArray = compressBitmapToByteArray(alignedCard)
                            handleAutoCaptureLogic(isAligned, isTemplate, alignedCard, cardBmp)
                        }

                    }
                )
            } else {
                CnicAnalyzer(
                    cameraOverlay,
                    onAlignmentChanged = { isAligned, alignedCard, cardBmp, detectedCorners ->

                        if (isAligned && !isCapturing) {
                            isCapturing = true
                            runOnUiThread {
                                stopCamera()
                                // 1. Display interactive editor dialog
                                showCornerEditorDialog(
                                    cardBmp,
                                    detectedCorners
                                ) { finalFlattenedCard ->
                                    // 2. Perform OCR after user presses "Crop & Process"
                                    cardByteArray = compressBitmapToByteArray(cardBmp)
                                    alignedCardByteArray =
                                        compressBitmapToByteArray(finalFlattenedCard)
                                    processBitmapForOcr(
                                        cardBmp = cardBmp,
                                        alignedCard = finalFlattenedCard,
                                        isTemplate = false
                                    )
                                }

                            }
                        }
                        /*    cardByteArray = compressBitmapToByteArray(cardBmp)
                            alignedCardByteArray = compressBitmapToByteArray(alignedCard)
                            handleAutoCaptureLogic(isAligned, isTemplate, alignedCard, cardBmp)*/

//                        Utils.drawCnicAnchorRectangleAsync(cardBmp) { alignedCard ->
//                            alignedCardByteArray = compressBitmapToByteArray(alignedCard)
//                            handleAutoCaptureLogic(isAligned, isTemplate, alignedCard, cardBmp)
//                        }

                    }
                )
            }
            when (alignmentAnalyzer) {
                is CnicAlignmentAnalyzer -> {
                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val bitmap = imageProxy.toUprightViewportBitmap()
                        imageProxy.close()
                        alignmentAnalyzer.analyzeImage(bitmap)
                    }
                }

                is CnicAnalyzer -> {
                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val bitmap = imageProxy.toUprightViewportBitmap()
                        currentOrientedBitmap = bitmap
                        imageProxy.close()
                        alignmentAnalyzer.analyzeImage(bitmap)
                    }

                    // Wire overlay switch and capture button events:
                    cameraOverlay.onModeChangedListener = { mode ->
                        alignmentAnalyzer.isManualMode =
                            (mode == CnicCameraOverlay.CaptureMode.MANUAL)
                        if (mode == CnicCameraOverlay.CaptureMode.MANUAL) {
                            cameraOverlay.setFieldStatuses(
                                isAllAligned = true,
                                stopProcessing = false,
                                guidance = CnicAnalyzer.CnicGuidance.HOLD_STILL
                            )
                        }
                    }

                    cameraOverlay.onManualCaptureClickListener = {
                        currentOrientedBitmap?.let { bmp ->
                            // Triggers instant manual capture bypass
                            alignmentAnalyzer.triggerManualCapture(bmp)
                        }
                    }

                }
            }


            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            val viewPort = previewView.viewPort
            if (viewPort == null) {
                previewView.post { startCamera(isTemplate) }
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
        isTemplate: Boolean,
        alignedCard: Bitmap,
        cardBmp: Bitmap
    ) {
        runOnUiThread {
            if (isAligned && !isCapturing) {
                if (autoCaptureRunnable == null) {
                    autoCaptureRunnable = Runnable {
                        if (!isCapturing) {
                            triggerFrameCapture(alignedCard, isTemplate, cardBmp)
                        }
                    }
                    autoCaptureHandler.postDelayed(autoCaptureRunnable!!, 50)
                }
            } else {
                autoCaptureRunnable?.let {
                    autoCaptureHandler.removeCallbacks(it)
                    autoCaptureRunnable = null
                }
            }
        }
    }

    private fun triggerFrameCapture(alignedCard: Bitmap, isTemplate: Boolean, cardBmp: Bitmap) {
        if (isCapturing) return
        isCapturing = true
        stopCamera()
        processBitmapForOcr(cardBmp = cardBmp, isTemplate = isTemplate, alignedCard = alignedCard)
    }

    private fun processImageUri(uri: Uri) {
        // Show processing UI immediately
        showOcrProcessing()

        /*
         * Give Android a chance to draw the processing screen
         * before starting image processing.
         */
        previewView.post {

            lifecycleScope.launch(Dispatchers.Default) {
                try {
                    val highResBitmap = decodeSampledBitmapFromUri(uri, 1920, 1080)
                    if (highResBitmap == null) {
                        Toast.makeText(this@IdCardOcrPreviewActivity, "Failed to decode cropped image", Toast.LENGTH_SHORT).show()
                        showSourceSelectionDialog()
                        return@launch
                    }

                    val argbBitmap = highResBitmap.copy(Bitmap.Config.ARGB_8888, true)
                    cardByteArray = compressBitmapToByteArray(argbBitmap)
                    val flatCroppedBitmap = OpenCVCardTransformer.flattenImage(argbBitmap)
                    alignedCardByteArray = compressBitmapToByteArray(flatCroppedBitmap)
                    withContext(Dispatchers.Main) {
                     // Now show the actual captured CNIC. inside the processing screen.
                        ocrProcessingView.setBitmap(
                            flatCroppedBitmap
                        )
                        processBitmapForOcr(
                            cardBmp = argbBitmap,
                            isTemplate = null,
                            alignedCard = flatCroppedBitmap,
                        )
                    }
//            Utils.drawCnicAnchorRectangleAsync(argbBitmap) { recBmp ->
//                this.alignedCardByteArray = compressBitmapToByteArray(recBmp)
//                processBitmapForOcr(cardBmp = argbBitmap, isTemplate = null, alignedCard = recBmp,)
//            }

//            processBitmapForOcr(cardBmp = argbBitmap, alignedCard = argbBitmap)
                } catch (e: Exception) {

                    withContext(Dispatchers.Main) {
                        hideOcrProcessing()
                        Toast.makeText(
                            this@IdCardOcrPreviewActivity,
                            "Unable to process image",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    /*
                       Log.e("IdCardOcrPreview", "Error decoding URI from crop result", e)
                       Toast.makeText(this, "Error processing image", Toast.LENGTH_SHORT).show()
                       showSourceSelectionDialog()*/
                }
            }
        }

    }

    private fun decodeSampledBitmapFromUri(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        return contentResolver.openInputStream(uri)?.use { inputStream ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(inputStream, null, options)

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun processBitmapForOcr(cardBmp: Bitmap, alignedCard: Bitmap, isTemplate: Boolean?) {
        try {
            lineExtractor.extract(
                cardBmp = alignedCard,
                onResult = { result: CardOcrResult ->
                    val fields = CnicFieldParser.parse(result)
                    runOnUiThread {
                        hideOcrProcessing()
                        OCRManager.ocrDetectionResult.onOcrDetection(
                            fields,
                            this.alignedCardByteArray,
                        )
                        finish()
                       // showLineExtractionDialog(result, fields, cardBmp, alignedCard)
                    }
                    if (isTemplate == true) {
                        cameraTemplateOverlay.stopOverlayRendering(true)
                    } else if (isTemplate == false) {
                        cameraOverlay.stopOverlayRendering(true)
                    }
                },
                onFailure = { e ->
                    Log.e("IdCardOcrPreview", "Line extraction failed", e)
                    runOnUiThread {
                        hideOcrProcessing()
                        Toast.makeText(
                            this,
                            "Extraction failed: ${e.localizedMessage}",
                            Toast.LENGTH_SHORT
                        ).show()
                        isCapturing = false
                        showSourceSelectionDialog()
                    }

                }
            )
        } catch (e: Exception) {
            Log.e("IdCardOcrPreview", "Exception during line extraction setup", e)
            runOnUiThread{
                isCapturing = false
                showSourceSelectionDialog()
            }
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

    private fun showCornerEditorDialog(
        cardBmp: Bitmap,
        detectedCorners: List<PointF>?,
        onConfirm: (Bitmap) -> Unit
    ) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        cameraOverlay.stopOverlayRendering(true)
        val dialogView = layoutInflater.inflate(R.layout.dialog_cnic_corner_editor, null)
        val editorView = dialogView.findViewById<CnicCornerEditorView>(R.id.dialogCornerEditorView)
        val btnRotate = dialogView.findViewById<Button>(R.id.btnDialogRotate)
        val btnFlipH = dialogView.findViewById<Button>(R.id.btnDialogFlipH)
        val btnFlipV = dialogView.findViewById<Button>(R.id.btnDialogFlipV)

        // Load bitmap and detected corners into interactive view
        editorView.setImageAndCorners(cardBmp, detectedCorners)

        btnRotate.setOnClickListener { editorView.rotate90() }
        btnFlipH.setOnClickListener { editorView.flipHorizontal() }
        btnFlipV.setOnClickListener { editorView.flipVertical() }

        AlertDialog.Builder(this)
            .setTitle("Adjust CNIC Corners")
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("Crop & Process") { dialog, _ ->
                dialog.dismiss()
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                // Warp & flatten image using edited points and orientation settings
                val flattenedBitmap = ManualCardTransformer.cropAndTransform(
                    sourceBitmap = cardBmp,
                    pixelCorners = editorView.getPixelCorners(),
                    rotationDegrees = editorView.rotationDegrees,
                    isFlippedH = editorView.isFlippedHorizontal,
                    isFlippedV = editorView.isFlippedVertical
                )

                cameraOverlay.stopOverlayRendering(false)
                onConfirm(flattenedBitmap)
            }
            .setNegativeButton("Retake") { dialog, _ ->
                dialog.dismiss()
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                isCapturing = false
                showSourceSelectionDialog()
            }
            .show()
    }

    private fun showLineExtractionDialog(
        result: CardOcrResult,
        fields: CnicFields,
        cardBmp: Bitmap,
        alignedCard: Bitmap
    ) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        cameraTemplateOverlay.visibility = View.GONE
        cameraOverlay.visibility = View.GONE

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

        container.addView(sectionLabel("ORIGINAL CAPTURE"))
        container.addView(ImageView(context).apply {
            setImageBitmap(cardBmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * density).toInt() }
        })

        container.addView(sectionLabel("VALIDATION ANNOTATED MAP"))
        container.addView(ImageView(context).apply {
            setImageBitmap(alignedCard)
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

        if (fields.debugBlocks.isNotEmpty()) {
            container.addView(sectionLabel("DEBUG BLOCKS"))
            fields.debugBlocks.forEach { (blockName, rawText) ->
                addKeyValue(blockName, rawText.ifEmpty { "N/A" })
            }
        }

        container.addView(sectionLabel("ANNOTATED CARD (All lines + Urdu regions)"))
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
            container.addView(sectionLabel("URDU NAME CROP"))
            addCropRow(it, heightDp = 60)
        }
        result.urduFatherNameCrop?.let {
            container.addView(sectionLabel("URDU FATHER NAME CROP"))
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
                    this.alignedCardByteArray,
                )
                dialog.dismiss()
                finish()
            }
            .setNegativeButton("Retake") { dialog, _ ->
                dialog.dismiss()
                isCapturing = false
                showSourceSelectionDialog()
            }
            .show()
    }
}