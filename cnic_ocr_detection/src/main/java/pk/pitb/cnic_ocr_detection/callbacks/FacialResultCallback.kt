package pk.pitb.cnic_ocr_detection.callbacks

import org.json.JSONObject
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CnicFields

abstract class OcrDetectionCallback {

    abstract fun onOcrDetection(
        cnicFields: CnicFields,
        imageBytes: ByteArray?,
    )
}