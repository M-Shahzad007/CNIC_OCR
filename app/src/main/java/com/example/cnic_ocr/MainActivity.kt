package com.example.cnic_ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.cnic_ocr.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import pk.pitb.cnic_ocr_detection.OCRManager
import pk.pitb.cnic_ocr_detection.callbacks.OcrDetectionCallback
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CnicFields
import java.io.File
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.openOrcActivity.setOnClickListener {
            startOcrFlow()
        }

        startOcrFlow()
    }

    private fun startOcrFlow() {
        val ocrManager = OCRManager.getInstance(object : OcrDetectionCallback() {
            override fun onOcrDetection(cnicFields: CnicFields, imageBytes: ByteArray?) {
                // Decode ByteArray directly back to Bitmap only when needed
                val cardBitmap = imageBytes?.let { BitmapFactory.decodeByteArray(imageBytes, 0, it.size) }
                runOnUiThread {
                    displayResults(cnicFields, cardBitmap)
                }
            }
        })

        ocrManager.requestOcrDetection(this)
    }

    private fun displayResults(fields: CnicFields, cardBitmap:  Bitmap?) {
        if (cardBitmap!=null) {
            binding.ivCnicFull.setImageBitmap(cardBitmap)

            // Execute ML Kit Face Detection
            detectAndCropFace(cardBitmap)

        } else {
            Toast.makeText(this, "Image file not found", Toast.LENGTH_SHORT).show()
        }

        // 2. Populate extracted form fields
        populateFormFields(fields)

        // Make containers visible
        binding.cardImages.visibility = View.VISIBLE
        binding.cardFields.visibility = View.VISIBLE
    }

    private fun detectAndCropFace(bitmap: Bitmap) {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()

        val detector = FaceDetection.getClient(options)
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    // Find the face with the largest bounding box area (width * height)
                    val primaryFace = faces.maxByOrNull { face ->
                        face.boundingBox.width() * face.boundingBox.height()
                    } ?: faces[0]

                    val bounds = primaryFace.boundingBox

                    val croppedBitmap = cropFaceWithPadding(bitmap, bounds)
                    binding.ivCroppedFace.setImageBitmap(croppedBitmap)
                } else {
                    Toast.makeText(this, "No face detected in document", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Face detection failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
    }
    private fun cropFaceWithPadding(original: Bitmap, bounds: Rect): Bitmap {
        // Add a 30% margin buffer around the bounding box for a natural photo crop
        val paddingWidth = (bounds.width() * 0.30).toInt()
        val paddingHeight = (bounds.height() * 0.30).toInt()

        val left = max(0, bounds.left - paddingWidth)
        val top = max(0, bounds.top - paddingHeight)
        val right = min(original.width, bounds.right + paddingWidth)
        val bottom = min(original.height, bounds.bottom + paddingHeight)

        val width = right - left
        val height = bottom - top

        return Bitmap.createBitmap(original, left, top, width, height)
    }

    private fun populateFormFields(fields: CnicFields) {
        val container = binding.llFieldsContainer

        // Clear previous dynamically added views (retaining title at index 0)
        if (container.childCount > 1) {
            container.removeViews(1, container.childCount - 1)
        }

        // Ordered key-value mappings
        val fieldMap = listOf(
            "Name" to fields.name,
            "Name (Urdu)" to fields.nameUrdu,
            "Father Name" to fields.fatherName,
            "Father Name (Urdu)" to fields.fatherNameUrdu,
            "Identity Number" to fields.identityNumber,
            "Gender" to fields.gender,
            "Date of Birth" to fields.dateOfBirth,
            "Date of Issue" to fields.dateOfIssue,
            "Date of Expiry" to fields.dateOfExpiry,
            "Country of Stay" to fields.countryOfStay
        )

        for ((key, value) in fieldMap) {
            if (!value.isNullFrancoEmpty()) {
                addKeyValueRow(container, key, value)
            }
        }
    }

    private fun addKeyValueRow(container: LinearLayout, label: String, value: String?) {
        val rowView = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 8)
            }
            orientation = LinearLayout.VERTICAL
        }

        val tvLabel = TextView(this).apply {
            text = label
            textSize = spToPx()
            setTextColor(android.graphics.Color.parseColor("#6C757D"))
        }

        val tvValue = TextView(this).apply {
            text = value ?: "N/A"
            textSize = spToPx()
            setTextColor(android.graphics.Color.parseColor("#212529"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        rowView.addView(tvLabel)
        rowView.addView(tvValue)
        container.addView(rowView)
    }

    private fun String?.isNullFrancoEmpty(): Boolean {
        return this.isNullOrBlank() || this.equals("null", ignoreCase = true)
    }

    private fun TextView.spToPx(): Float {
        return resources.displayMetrics.scaledDensity * 14f / resources.displayMetrics.scaledDensity
    }
}