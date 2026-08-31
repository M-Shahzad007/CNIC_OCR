package pk.pitb.cnic_ocr_detection

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.util.Log
import pk.pitb.cnic_ocr_detection.callbacks.OcrDetectionCallback
import pk.pitb.cnic_ocr_detection.utils.hasPermission
import pk.pitb.cnic_ocr_detection.utils.showPermissionDialog
import pk.pitb.cnic_ocr_detection.views.IdCardOcrPreviewActivity
import pk.pitb.cnic_ocr_detection.views.OcrActivity

class OCRManager private constructor() {

    companion object {
        const val REQUEST_CODE: Int = 1001

        @Volatile
        private var INSTANCE: OCRManager? = null
        lateinit var ocrDetectionResult: OcrDetectionCallback

        fun getInstance(ocrDetectionResult: OcrDetectionCallback): OCRManager {
            this.ocrDetectionResult = ocrDetectionResult

            return INSTANCE ?: synchronized(this) {
                val instance = OCRManager()
                INSTANCE = instance

                instance
            }
        }
    }

    private fun checkPermissionsAndStartActivity(
        activity: Activity,
        requestCode: Int,
    ) {
        if (activity.hasPermission(Manifest.permission.CAMERA)) {
            when (requestCode) {
                REQUEST_CODE -> startOcrDetection(activity)
            }
        } else {
            activity.showPermissionDialog(title = "Camera Permission Required",
                body = "This app needs access to your camera to capture photos.",
                trueBtnText = "Proceed",
                permissions = arrayOf(Manifest.permission.CAMERA),
                onPermissionGranted = {
                    when (requestCode) {
                        REQUEST_CODE -> startOcrDetection(activity)
                    }
                },
                onPermissionDenied = {
                    Log.e("FacialAttendance", "Permission denied for camera.")
                })
        }
    }

    private fun startOcrDetection(
        activity: Activity,
    ) {/*
        val intent = Intent(
            activity, OcrActivity::class.java
        )*/
        val intent = Intent(
            activity, IdCardOcrPreviewActivity::class.java
        )
        activity.startActivity(intent)
    }

    fun requestOcrDetection(
        activity: Activity
    ) {

        checkPermissionsAndStartActivity(
            activity,
            REQUEST_CODE
        )
    }

}
