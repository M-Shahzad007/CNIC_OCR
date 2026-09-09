package pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.custom_croper


import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object ManualCardTransformer {

    fun cropAndTransform(
        sourceBitmap: Bitmap,
        pixelCorners: List<PointF>,
        rotationDegrees: Float,
        isFlippedH: Boolean,
        isFlippedV: Boolean
    ): Bitmap {
        // 1. First apply visual Flip/Rotation on the source bitmap if applied by user
        val transformMatrix = Matrix().apply {
            if (rotationDegrees != 0f) postRotate(rotationDegrees)
            val sx = if (isFlippedH) -1f else 1f
            val sy = if (isFlippedV) -1f else 1f
            if (isFlippedH || isFlippedV) postScale(sx, sy)
        }

        val transformedSource = Bitmap.createBitmap(
            sourceBitmap, 0, 0, sourceBitmap.width, sourceBitmap.height, transformMatrix, true
        )

        if (pixelCorners.size != 4) return transformedSource

        // 2. Setup OpenCV Mats
        val srcMat = Mat()
        Utils.bitmapToMat(transformedSource, srcMat)

        // Standard CNIC Aspect ratio: 85.6mm x 53.98mm (~1.586)
        val targetWidth = 1000.0
        val targetHeight = 630.0

        val srcPoints = arrayOf(
            Point(pixelCorners[0].x.toDouble(), pixelCorners[0].y.toDouble()), // Top-Left
            Point(pixelCorners[1].x.toDouble(), pixelCorners[1].y.toDouble()), // Top-Right
            Point(pixelCorners[2].x.toDouble(), pixelCorners[2].y.toDouble()), // Bottom-Right
            Point(pixelCorners[3].x.toDouble(), pixelCorners[3].y.toDouble())  // Bottom-Left
        )

        val dstPoints = arrayOf(
            Point(0.0, 0.0),
            Point(targetWidth, 0.0),
            Point(targetWidth, targetHeight),
            Point(0.0, targetHeight)
        )

        val srcMatOfPoint = MatOfPoint2f(*srcPoints)
        val dstMatOfPoint = MatOfPoint2f(*dstPoints)

        // 3. Compute Perspective Transform Matrix
        val perspectiveTransform = Imgproc.getPerspectiveTransform(srcMatOfPoint, dstMatOfPoint)
        val outputMat = Mat(Size(targetWidth, targetHeight), CvType.CV_8UC4)

        // 4. Warp perspective to flatten
        Imgproc.warpPerspective(srcMat, outputMat, perspectiveTransform, Size(targetWidth, targetHeight))

        val outputBitmap = Bitmap.createBitmap(targetWidth.toInt(), targetHeight.toInt(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outputMat, outputBitmap)

        // Clean memory
        srcMat.release()
        outputMat.release()
        perspectiveTransform.release()
        srcMatOfPoint.release()
        dstMatOfPoint.release()

        return outputBitmap
    }
}