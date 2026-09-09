package pk.pitb.cnic_ocr_detection.views.urduExtractor.helper

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

object OpenCVCardTransformer {

    /**
     * Result wrapper containing the transformed flattened image and relative corner points (0.0 .. 1.0)
     */
    data class CardDetectionResult(
        val transformedBitmap: Bitmap,
        var relativeCorners: List<PointF>? // [Top-Left, Top-Right, Bottom-Right, Bottom-Left]
    )

    fun detectAndFlatten(inputBmp: Bitmap): CardDetectionResult {
        val srcMat = Mat()
        Utils.bitmapToMat(inputBmp, srcMat)

        val detectedCorners = findCardCorners(srcMat)
        srcMat.release()

        if (detectedCorners == null) {
            return CardDetectionResult(
                transformedBitmap = flattenImage(inputBmp),
                relativeCorners = null
            )
        }

        val sortedCorners = sortCorners(detectedCorners)

        // Convert pixel points to normalized (0.0 to 1.0) relative coordinates for UI drawing
        val width = inputBmp.width.toFloat()
        val height = inputBmp.height.toFloat()
        val relativeCorners = sortedCorners.map {
            PointF((it.x / width).toFloat(), (it.y / height).toFloat())
        }

        val flattenedBitmap = warpPerspectiveToRect(inputBmp, sortedCorners)

        return CardDetectionResult(
            transformedBitmap = flattenedBitmap,
            relativeCorners = relativeCorners
        )
    }

    fun flattenImage(inputBmp: Bitmap): Bitmap {
        val corners = arrayOf(
            Point(0.0, 0.0),
            Point((inputBmp.width - 1).toDouble(), 0.0),
            Point((inputBmp.width - 1).toDouble(), (inputBmp.height - 1).toDouble()),
            Point(0.0, (inputBmp.height - 1).toDouble())
        )
        return warpPerspectiveToRect(inputBmp, corners)
    }

    private fun warpPerspectiveToRect(inputBmp: Bitmap, corners: Array<Point>): Bitmap {
        if (corners.size != 4) return inputBmp

        val srcMat = Mat()
        Utils.bitmapToMat(inputBmp, srcMat)

        val sortedCorners = sortCorners(corners)

        val tl = sortedCorners[0]
        val tr = sortedCorners[1]
        val br = sortedCorners[2]
        val bl = sortedCorners[3]

        val widthA = sqrt((br.x - bl.x).pow(2) + (br.y - bl.y).pow(2))
        val widthB = sqrt((tr.x - tl.x).pow(2) + (tr.y - tl.y).pow(2))
        val maxWidth = max(widthA.toInt(), widthB.toInt())

        val heightA = sqrt((tr.y - br.y).pow(2) + (tr.x - br.x).pow(2))
        val heightB = sqrt((tl.y - bl.y).pow(2) + (tl.x - bl.x).pow(2))
        val maxHeight = max(heightA.toInt(), heightB.toInt())

        if (maxWidth <= 0 || maxHeight <= 0) {
            srcMat.release()
            return inputBmp
        }

        val srcPoints = MatOfPoint2f(tl, tr, br, bl)
        val dstPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(maxWidth - 1.0, 0.0),
            Point(maxWidth - 1.0, maxHeight - 1.0),
            Point(0.0, maxHeight - 1.0)
        )

        val perspectiveTransform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        val outputMat = Mat()

        Imgproc.warpPerspective(
            srcMat,
            outputMat,
            perspectiveTransform,
            Size(maxWidth.toDouble(), maxHeight.toDouble())
        )

        val outputBitmap = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outputMat, outputBitmap)

        srcMat.release()
        outputMat.release()
        perspectiveTransform.release()
        srcPoints.release()
        dstPoints.release()

        return outputBitmap
    }

    private fun findCardCorners2(srcMat: Mat): Array<Point>? {
        val gray = Mat()
        val blurred = Mat()
        val edged = Mat()
        val closed = Mat()

        Imgproc.cvtColor(srcMat, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
        Imgproc.Canny(blurred, edged, 50.0, 150.0)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(edged, closed, Imgproc.MORPH_CLOSE, kernel)

        val contours = ArrayList<org.opencv.core.MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            closed,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        val minCardArea = (srcMat.width() * srcMat.height()) * 0.25
        contours.sortByDescending { Imgproc.contourArea(it) }

        var cardCorners: Array<Point>? = null

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < minCardArea) continue

            val contour2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(contour2f, true)
            val approx2f = MatOfPoint2f()

            val epsilon = 0.02 * peri
            Imgproc.approxPolyDP(contour2f, approx2f, epsilon, true)

            if (approx2f.total() == 4L && Imgproc.isContourConvex(org.opencv.core.MatOfPoint(*approx2f.toArray()))) {
                cardCorners = approx2f.toArray()
                contour2f.release()
                approx2f.release()
                break
            }

            contour2f.release()
            approx2f.release()
        }

        gray.release()
        blurred.release()
        edged.release()
        closed.release()
        kernel.release()
        hierarchy.release()
        contours.forEach { it.release() }

        return cardCorners
    }
    private fun findCardCorners(srcMat: Mat): Array<Point>? {
        val prepMat = Mat()
        val blurred = Mat()
        val edged = Mat()
        val closed = Mat()

        // 1. Process across Color Channels (Handles White-on-White & Dark-on-Light)
        // Convert to Lab color space where L = Lightness, a & b = Color channels
        val lab = Mat()
        Imgproc.cvtColor(srcMat, lab, Imgproc.COLOR_BGR2Lab)

        val channels = ArrayList<Mat>()
        Core.split(lab, channels)

        // Merge grayscale contrast (L) with color difference (a & b)
        // This allows detecting edges even when background and card have similar brightness
        Core.addWeighted(channels[0], 0.5, channels[1], 0.5, 0.0, prepMat)

        // 2. Reduce noise
        Imgproc.GaussianBlur(prepMat, blurred, Size(5.0, 5.0), 0.0)

        // 3. Adaptive Canny Edge Detection
        Imgproc.Canny(blurred, edged, 20.0, 80.0)

        // 4. Morphological Closing to seal gaps in faint/rounded borders
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        Imgproc.morphologyEx(edged, closed, Imgproc.MORPH_CLOSE, kernel)

        // 5. Find Contours
        val contours = ArrayList<org.opencv.core.MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            closed,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        val minCardArea = (srcMat.width() * srcMat.height()) * 0.20
        contours.sortByDescending { Imgproc.contourArea(it) }

        var cardCorners: Array<Point>? = null

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < minCardArea) continue

            // 6. Compute Convex Hull to bridge rounded corners into straight boundary lines
            val hullIndices = org.opencv.core.MatOfInt()
            Imgproc.convexHull(contour, hullIndices)

            val contourPoints = contour.toArray()
            val hullPoints = hullIndices.toArray().map { contourPoints[it] }.toTypedArray()
            val hullMat = MatOfPoint2f(*hullPoints)

            // 7. Polygon Approximation on the Convex Hull
            val peri = Imgproc.arcLength(hullMat, true)
            val approx2f = MatOfPoint2f()

            // Test multiple epsilon tolerances to guarantee 4 corner points
            val epsilonRatios = doubleArrayOf(0.03, 0.04, 0.05, 0.02, 0.06)

            for (ratio in epsilonRatios) {
                Imgproc.approxPolyDP(hullMat, approx2f, ratio * peri, true)

                if (approx2f.total() == 4L && Imgproc.isContourConvex(org.opencv.core.MatOfPoint(*approx2f.toArray()))) {
                    cardCorners = approx2f.toArray()
                    break
                }
            }

            hullIndices.release()
            hullMat.release()
            approx2f.release()

            if (cardCorners != null) break
        }

        // Cleanup OpenCV memory
        prepMat.release()
        lab.release()
        blurred.release()
        edged.release()
        closed.release()
        kernel.release()
        hierarchy.release()
        channels.forEach { it.release() }
        contours.forEach { it.release() }

        return cardCorners
    }
    private fun sortCorners(pts: Array<Point>): Array<Point> {
        val sorted = Array(4) { Point() }

        val sums = pts.map { it.x + it.y }
        sorted[0] = pts[sums.indexOfMin()] // TL
        sorted[2] = pts[sums.indexOfMax()] // BR

        val diffs = pts.map { it.y - it.x }
        sorted[1] = pts[diffs.indexOfMin()] // TR
        sorted[3] = pts[diffs.indexOfMax()] // BL

        return sorted
    }

    private fun List<Double>.indexOfMin(): Int = this.indices.minByOrNull { this[it] } ?: 0
    private fun List<Double>.indexOfMax(): Int = this.indices.maxByOrNull { this[it] } ?: 0
}