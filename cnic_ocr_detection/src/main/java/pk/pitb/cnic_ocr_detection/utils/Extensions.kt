package pk.pitb.cnic_ocr_detection.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.net.Uri
import android.os.Environment
import android.util.DisplayMetrics
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import androidx.core.graphics.createBitmap

fun Activity.responsiveSize(staticDp: Float): Int {
    val displayMetrics: DisplayMetrics = resources.displayMetrics

    val baseWidthDp = 360f
    val currentWidthDp = displayMetrics.widthPixels / displayMetrics.density

    val scaleFactor = currentWidthDp / baseWidthDp
    val dynamicDp = staticDp * scaleFactor

    return (dynamicDp * displayMetrics.density).toInt()
}

fun String.capitalizeWords(): String {
    if (this.isEmpty()) return this

    val result = StringBuilder()
    val words =
        this.lowercase(Locale.getDefault()).split(" ".toRegex()).dropLastWhile { it.isEmpty() }
            .toTypedArray()

    for (word in words) {
        if (!word.isEmpty()) {
            result.append(word[0].uppercaseChar())
            if (word.length > 1) {
                result.append(word.substring(1))
            }
            result.append(" ")
        }
    }

    return result.toString().trim { it <= ' ' }
}

fun Activity.saveBitmapToFile(bitmap: Bitmap): Uri? {
    try {
        val imagesDir: File =
            File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "tempImages")
        if (!imagesDir.exists()) {
            imagesDir.mkdirs()
        }

        val imageFile = File(imagesDir, "preview_" + System.currentTimeMillis() + ".png")

        val fos = FileOutputStream(imageFile)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        fos.flush()
        fos.close()

        return FileProvider.getUriForFile(
            this,
            getPackageName() + ".provider",  // 👈 must match your provider in AndroidManifest
            imageFile
        )
    } catch (e: IOException) {
        e.printStackTrace()
        return null
    }
}

fun Activity.openImageInViewer(imageUri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW)
    intent.setDataAndType(imageUri, "image/*")
    intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(intent, "Open with"))
}

fun imageToYuvImage(image: Image): YuvImage {
    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    yBuffer[nv21, 0, ySize]
    vBuffer[nv21, ySize, vSize]
    uBuffer[nv21, ySize + vSize, uSize]

    return YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
}

fun Image.toBitmap2(rotationDegrees: Int): Bitmap {
    val out = ByteArrayOutputStream()
    val yuvImage: YuvImage = imageToYuvImage(this)
    yuvImage.compressToJpeg(Rect(0, 0, this.width, this.height), 100, out)

    val jpegBytes = out.toByteArray()
    var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

    if (rotationDegrees != 0) {
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    return bitmap
}


@OptIn(ExperimentalGetImage::class)
fun ImageProxy.toUprightViewportBitmap(): Bitmap {
    val full = this.toBitmap()
    val crop = this.cropRect

    val safeLeft = crop.left.coerceIn(0, full.width - 1)
    val safeTop = crop.top.coerceIn(0, full.height - 1)
    val safeWidth = crop.width().coerceIn(1, full.width - safeLeft)
    val safeHeight = crop.height().coerceIn(1, full.height - safeTop)

    val fov = Bitmap.createBitmap(full, safeLeft, safeTop, safeWidth, safeHeight)

    val rotation = this.imageInfo.rotationDegrees
    return if (rotation != 0) {
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        Bitmap.createBitmap(fov, 0, 0, fov.width, fov.height, matrix, true)
    } else {
        fov
    }
}

fun compressBitmapToByteArray(bitmap: Bitmap, quality: Int = 100): ByteArray {
    val stream = java.io.ByteArrayOutputStream()
    // JPEG compression reduces raw Bitmap size by 70-90%
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
    return stream.toByteArray()
}