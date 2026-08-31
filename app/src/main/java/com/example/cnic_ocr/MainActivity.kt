package com.example.cnic_ocr

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.cnic_ocr.databinding.ActivityMainBinding
import org.json.JSONObject
import pk.pitb.cnic_ocr_detection.OCRManager
import pk.pitb.cnic_ocr_detection.callbacks.OcrDetectionCallback

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.openOrcActivity.setOnClickListener {
            startOcrFlow()
        }
        startOcrFlow()
    }
    private fun startOcrFlow() {
        // 1. Initialize the OCRManager with the result callback
        val ocrManager = OCRManager.getInstance(object : OcrDetectionCallback() {
            override fun onOcrDetection(
                jsonObject: JSONObject,
                imagePath: String,
                processedImagePath: String
            ) {
                // Handle the extracted data here
                Log.d("OCRResult", "Extracted JSON: $jsonObject")
                Log.d("OCRResult", "Original Image Path: $imagePath")
                Log.d("OCRResult", "Processed Image Path: $processedImagePath")

                Toast.makeText(
                    this@MainActivity,
                    "OCR Completed: ${jsonObject.optString("cnic", "No CNIC found")}",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        // 2. Trigger the camera permission check and start OcrActivity
        ocrManager.requestOcrDetection(this)
    }
}