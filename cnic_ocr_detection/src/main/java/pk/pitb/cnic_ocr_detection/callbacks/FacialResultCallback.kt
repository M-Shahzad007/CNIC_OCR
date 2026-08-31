package pk.pitb.cnic_ocr_detection.callbacks

import org.json.JSONObject

abstract class OcrDetectionCallback {

    abstract fun onOcrDetection(
        jsonObject: JSONObject,
        imagePath: String,
        processedImagePath: String
    )
}