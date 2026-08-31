package pk.pitb.cnic_ocr_detection.views

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import org.json.JSONObject
import pk.pitb.cnic_ocr_detection.OCRManager
import pk.pitb.cnic_ocr_detection.R
import pk.pitb.cnic_ocr_detection.utils.ImagePickerHandler
import pk.pitb.cnic_ocr_detection.utils.Rectangle
import pk.pitb.cnic_ocr_detection.utils.Utils
import pk.pitb.cnic_ocr_detection.utils.Utils.Companion.parseCnicTextToJson
import pk.pitb.cnic_ocr_detection.utils.Utils.Companion.preProcessBitmap
import pk.pitb.cnic_ocr_detection.utils.capitalizeWords
import pk.pitb.cnic_ocr_detection.utils.openImageInViewer
import pk.pitb.cnic_ocr_detection.utils.saveBitmapToFile
import pk.pitb.cnic_ocr_detection.views.urduExtractor.CnicUrduCropper
import pk.pitb.cnic_ocr_detection.views.urduExtractor.CroppedUrduField
import pk.pitb.cnic_ocr_detection.views.urduExtractor.UTRNetRecognizer
import pk.pitb.cnic_ocr_detection.views.urduExtractor.rough.showUrduCropsDialog
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OcrActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var surface: LinearLayout
    private lateinit var btnCapture: CardView
    private lateinit var cameraExecutor: ExecutorService
    private var latestFrameBitmap: Bitmap? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var processed: Bitmap? = null
    private var imageUri: Uri? = null
    private var imageUriProcessed: Uri? = null

    private lateinit var imagePickerHandler: ImagePickerHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ocr)

        previewView = findViewById(R.id.previewView)
        surface = findViewById(R.id.surface)
        btnCapture = findViewById(R.id.btnCapture)
        cameraExecutor = Executors.newSingleThreadExecutor()

        initializePicker()
        initializeUI()

        // Prompt the user to select source right on launch
        imagePickerHandler.showSourceChooserDialog()
    }

    private fun initializePicker() {
        imagePickerHandler = ImagePickerHandler(
            activity = this,
            onCameraSelected = {
                // Show camera UI & start CameraX
                previewView.visibility = View.VISIBLE
                surface.visibility = View.VISIBLE
                btnCapture.visibility = View.VISIBLE
                startCamera()
            },
            onImagePicked = { galleryBitmap ->
                // Hide camera preview UI since image came from Gallery
                previewView.visibility = View.GONE
                surface.visibility = View.GONE
                btnCapture.visibility = View.GONE

                processImage(galleryBitmap)
            }
        )
    }

    private fun initializeUI() {
        surface.addView(Rectangle(this))

        btnCapture.setOnClickListener {
            latestFrameBitmap?.let { bitmap ->
                stopCamera()

                val bWidth = bitmap.width
                val bHeight = bitmap.height
                val rectangleWidth = (bWidth * 0.7).toInt()
                val rectangleHeight = (bHeight * 0.8).toInt()
                val rectangleX = (bWidth * 0.15).toInt()
                val rectangleY = (bHeight * 0.1).toInt()

                val croppedImage = Bitmap.createBitmap(
                    bitmap, rectangleX, rectangleY, rectangleWidth, rectangleHeight
                )

                processImage(croppedImage)
            } ?: Toast.makeText(this, "No frame to capture yet", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processImage(bitmap: Bitmap) {
        imageUri = saveBitmapToFile(bitmap)
        processed = preProcessBitmap(bitmap)
        processed?.let { fullBitmap ->
            val cropper = CnicUrduCropper()
            cropper.processCnicImage(
                cnicBitmap = bitmap,
                onSuccess = { extractionResult ->
                    // 1. Run Urdu OCR on cropped regions
                    val resList = runUrduExtractor(extractionResult.cropsUrdu)
                    var englishJson : List<Text.Line>? = extractionResult.extractedEnglishText?.let { extractSortedLines(it) }

                    // Construct display content
                    val dialogMessage = StringBuilder()

                    // Add extracted Urdu Fields
                    dialogMessage.append("--- URDU FIELDS ---\n")
                    if (resList.isNotEmpty()) {
                        resList.forEach { result ->
                            dialogMessage.append("${result.first}: ${result.second}\n")
                        }
                    } else {
                        dialogMessage.append("No Urdu fields extracted.\n")
                    }

                    // Add extracted English/JSON Data
                    dialogMessage.append("\n--- ENGLISH OCR DATA ---\n")

                    englishJson?.forEachIndexed { index, line ->
                        val rect = line.boundingBox
                        val top = rect?.top ?: 0
                        val left = rect?.left ?: 0

                        // Print index, line text, and spatial position
                        //  println("Line ${index + 1} [y=$top, x=$left]: ${line.text}")
                        // Log.d("recognizedText","Line ${index + 1} [y=$top, x=$left]: ${line.text}")
                        dialogMessage.append("Line ${index + 1}- ${line.text}\n" )
                    }
                    // 3. Show unified Dialog on UI Thread
                    runOnUiThread {

                      /*  val res = UrduExtractionResult(
                            annotatedFullImage = extractionResult.annotatedFullImage,
                            extractedEnglishText = englishJson,
                            cropsUrdu = extractionResult.cropsUrdu
                        )*/
                        showUrduCropsDialog(this@OcrActivity, extractionResult,dialogMessage.toString())

                        /*    val drawer = CnicAllTextDrawer()
                            drawer.drawAllTextBoundingBoxes(
                                cnicBitmap = bitmap,
                                onComplete = { annotatedBitmap ->
                                    val res = UrduExtractionResult(
                                        annotatedFullImage = annotatedBitmap,
                                        crops = emptyList()
                                    )
                                    showUrduCropsDialog(this@OcrActivity, res,dialogMessage.toString())
                                },
                                onFailure = { error ->
                                    Toast.makeText(this, "Failed to draw boxes: ${error.localizedMessage}", Toast.LENGTH_SHORT).show()
                                }
                            )*/
                    }



                    // 2. Run ML Kit English OCR on the processed full bitmap
                    runTextRecognitionMLKit(fullBitmap) { visionText ->

                    }
                },
                onFailure = { exception ->
                    Log.e("UrduCropper", "Cropping failed", exception)
                    Toast.makeText(this@OcrActivity, "Failed to crop Urdu regions", Toast.LENGTH_SHORT).show()
                }
            )
        }
/*
        processed?.let {fullBitmap->
            val cropper = CnicUrduCropper()
            cropper.processCnicImage(
                cnicBitmap = bitmap,
                onSuccess = { extractionResult ->
                    val resList = runUrduExtractor(extractionResult.crops)
                    runTextRecognitionMLKit(fullBitmap){englishJson->
                        when(englishJson){
                            null->{ }
                            else ->{
                             // Alert dialogue to show text?

                            }
                        }
                    }
                    */
/* val nameInUrdu: Bitmap? = extractionResult.crops.getOrNull(0)?.croppedBitmap
                    val father_NameInUrdu: Bitmap? = extractionResult.crops.getOrNull(1)?.croppedBitmap
                    nameInUrdu?.let {
                        runUrduExtractor(it)
                    } ?: run {
                        Log.d("recognizedText", "Text: Name not extracted")
                    }
                    father_NameInUrdu?.let {
                        runUrduExtractor(it)
                    } ?: run {
                        Log.d("recognizedText", "Text: Father Name not extracted")
                    }*//*


                },
                onFailure = { error ->
                    Toast.makeText(this, "Extraction failed: ${error.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            )
            //runTextRecognitionMLKit(it)
      */
/*      val drawer = CnicAllTextDrawer()
            drawer.drawAllTextBoundingBoxes(
                cnicBitmap = bitmap,
                onComplete = { annotatedBitmap ->
                    // Pass annotatedBitmap to your ImageView or Dialog to display

                    val res = UrduExtractionResult(
                        annotatedFullImage = annotatedBitmap,
                        crops = emptyList()
                    )
                    showUrduCropsDialog(this@OcrActivity, res)
                  //  myImageView.setImageBitmap(annotatedBitmap)
                },
                onFailure = { error ->
                    Toast.makeText(this, "Failed to draw boxes: ${error.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            )*//*


            // runTextRecognitionMLKit(it)
            // runTextRecognition(it)
        } ?: Toast.makeText(this, "Unable to process the image", Toast.LENGTH_SHORT).show()
*/
    }

  /*  private fun runUrduExtractor(bitmap: Bitmap) {
        val glyphsText = assets.open("UrduGlyphs.txt").bufferedReader().use { it.readText() }
        val recognizer = UTRNetRecognizer(this@OcrActivity, glyphsText)
        val recognizedText = recognizer.recognizeText(bitmap)
        Log.d("recognizedText", "Text: $recognizedText")
        Toast.makeText(this, "Text: $recognizedText", Toast.LENGTH_SHORT).show()
    }*/
    private fun runUrduExtractor(crops: List<CroppedUrduField>): List<Pair<String, String>> {
        val glyphsText = assets.open("UrduGlyphs.txt").bufferedReader().use { it.readText() }
        val recognizer = UTRNetRecognizer(this@OcrActivity, glyphsText)

        return crops.map { crop ->
            val text = recognizer.recognizeText(crop.croppedBitmap)
            Log.d("recognizedText", "${crop.fieldName}: $text")
            Pair( crop.fieldName, text)
        }
    }

    @SuppressLint("UnsafeOptInUsageError", "UseKtx")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            var maxSize = Size(640, 480)
            try {
                val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
                val cameraId = cameraManager.cameraIdList[0]
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                map?.getOutputSizes(ImageFormat.YUV_420_888)?.let { sizes ->
                    maxSize = Collections.max(
                        sizes.asList(), Comparator.comparingInt { it.width * it.height })
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val imageAnalysis = ImageAnalysis.Builder().setTargetResolution(maxSize)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                latestFrameBitmap = imageProxy.toBitmap()
                imageProxy.close()
            }

            val cameraSelector =
                CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

            cameraProvider?.unbindAll()
            val camera =
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)

            previewView.post {
                val factory = SurfaceOrientedMeteringPointFactory(
                    previewView.width.toFloat(), previewView.height.toFloat()
                )
                val focusPoint =
                    factory.createPoint(previewView.width / 2f, previewView.height / 2f)
                val action = FocusMeteringAction.Builder(focusPoint)
                    .setAutoCancelDuration(5, TimeUnit.SECONDS).build()
                camera?.cameraControl?.startFocusAndMetering(action)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
    }

    private fun runTextRecognitionMLKit(bitmap: Bitmap, onComplete:(Text?)-> Unit) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        imageUriProcessed = saveBitmapToFile(bitmap)

        recognizer.process(image).addOnSuccessListener { visionText ->
            onComplete(visionText)
           // val json = parseCnicTextToJson(visionText.text)
            printSortedOcrText(visionText)
           // displayDialog(json)
        }.addOnFailureListener {
            Toast.makeText(this, "OCR Failed", Toast.LENGTH_SHORT).show()
            onComplete(null)
            imagePickerHandler.showSourceChooserDialog()
        }
    }

    private fun runTextRecognition(bitmap: Bitmap) {
        imageUriProcessed = saveBitmapToFile(bitmap)

        cameraExecutor.execute {
            var tessApi: TessBaseAPI? = null
            try {
                val languages = "eng+urd"
                val dataPath = Utils.prepareTesseractData(this, languages)

                tessApi = TessBaseAPI().apply {
                    init(dataPath, languages)
                    pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
                    setImage(bitmap)
                }

                val extractedText = tessApi.utF8Text ?: ""
                Log.d("extractedText", extractedText)

                val json = parseCnicTextToJson(extractedText)

                runOnUiThread {
                    if (extractedText.isNotBlank()) {
                        displayDialog(json)
                    } else {
                        Toast.makeText(this, "No text detected", Toast.LENGTH_SHORT).show()
                        imagePickerHandler.showSourceChooserDialog()
                    }
                }
            } catch (e: Exception) {
                Log.e("TesseractOCR", "Error during recognition", e)
                runOnUiThread {
                    Toast.makeText(this, "OCR Failed", Toast.LENGTH_SHORT).show()
                    imagePickerHandler.showSourceChooserDialog()
                }
            } finally {
                tessApi?.recycle()
            }
        }
    }

    fun printSortedOcrText(visionText: Text) {
        val sortedLines = extractSortedLines(visionText)

        Log.d("recognizedText","=== Result ===")
        Log.d("recognizedText","${visionText.text}")
        sortedLines.forEachIndexed { index, line ->
            val rect = line.boundingBox
            val top = rect?.top ?: 0
            val left = rect?.left ?: 0

            // Print index, line text, and spatial position
          //  println("Line ${index + 1} [y=$top, x=$left]: ${line.text}")
            Log.d("recognizedText","Line ${index + 1} [y=$top, x=$left]: ${line.text}")
        }
    }
    fun extractSortedLines(visionText: Text): List<Text.Line> {
        val allLines = visionText.textBlocks.flatMap { it.lines }

        // Group lines that fall within the same vertical band (~15px threshold)
        val lineThreshold = 15

        return allLines.sortedWith(Comparator { l1, l2 ->
            val y1 = l1.boundingBox?.top ?: 0
            val y2 = l2.boundingBox?.top ?: 0

            if (Math.abs(y1 - y2) <= lineThreshold) {
                // Same line: Sort left to right
                val x1 = l1.boundingBox?.left ?: 0
                val x2 = l2.boundingBox?.left ?: 0
                x1.compareTo(x2)
            } else {
                // Different lines: Sort top to bottom
                y1.compareTo(y2)
            }
        })
    }
    private fun displayDialog(json: JSONObject) {
        val scrollView = ScrollView(this).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }

        val containerLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        processed?.let { bmp ->
            val imageView = ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(250, 250)
                setOnClickListener {
                    imageUriProcessed?.let { uri -> openImageInViewer(uri) }
                }
            }
            containerLayout.addView(imageView)
        }

        val tableLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.optString(key, "")

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 16)
            }

            val keyView = TextView(this).apply {
                text = "${key.replace("_", " ").capitalizeWords()}: "
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val valueView = TextView(this).apply {
                text = value
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
            }

            row.addView(keyView)
            row.addView(valueView)
            tableLayout.addView(row)
        }

        containerLayout.addView(tableLayout)
        scrollView.addView(containerLayout)

        val dialog = AlertDialog.Builder(this).setTitle("CNIC Details").setCancelable(false)
            .setView(scrollView).setPositiveButton("OK") { dialogInterface, _ ->
                OCRManager.ocrDetectionResult.onOcrDetection(
                    json, imageUri?.path ?: "", imageUriProcessed?.path ?: ""
                )
                finish()
                dialogInterface.dismiss()
            }.setNegativeButton("Retry") { dialogInterface, _ ->
                dialogInterface.dismiss()
                imagePickerHandler.showSourceChooserDialog()
            }.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setTextColor(ContextCompat.getColor(this, R.color.black))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            .setTextColor(ContextCompat.getColor(this, R.color.black))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}