package pk.pitb.cnic_ocr_detection.views.urduExtractor.helper

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.ceil

class UTRNetRecognizer(context: Context, glyphsText: String) {

    private val interpreter: Interpreter
    private val labelMap: Map<Int, Char>

    init {
        val modelBuffer = loadModelFile(context, "utrnet_sim_float32.tflite")
        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }
        interpreter = Interpreter(modelBuffer, options)

        // Build character map identical to CTCLabelConverter in Python
        val characters = glyphsText.replace("\r", "").replace("\n", "") + " "
        val map = mutableMapOf<Int, Char>()
        characters.forEachIndexed { index, ch ->
            map[index + 1] = ch // Index 0 is reserved for CTC Blank token
        }
        labelMap = map
    }

    private fun loadModelFile(context: Context, modelName: String): ByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    fun recognizeText(srcBitmap: Bitmap): String {
        val inputBuffer = preprocessImage(srcBitmap)

        // Output tensor shape: [1, 400, 182]
        val output = Array(1) { Array(400) { FloatArray(182) } }

        interpreter.run(inputBuffer, output)

        return decodeCTC(output[0])
    }

    private fun preprocessImage(srcBitmap: Bitmap): ByteBuffer {
        val targetH = 32
        val targetW = 400

        // 1. Calculate aspect ratio resize (matching Python math.ceil(imgH * ratio))
        val ratio = srcBitmap.width.toFloat() / srcBitmap.height.toFloat()
        val calculatedW = ceil(targetH * ratio).toInt()
        val resizedW = if (calculatedW > targetW) targetW else calculatedW

        // 2. Resize with bilinear/bicubic filter enabled
        val resizedBitmap = Bitmap.createScaledBitmap(srcBitmap, resizedW, targetH, true)

        // 3. Prepare direct float buffer [1, 32, 400, 1] -> 1 * 32 * 400 * 1 * 4 bytes
        val inputBuffer = ByteBuffer.allocateDirect(1 * targetH * targetW * 1 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(resizedBitmap.width * resizedBitmap.height)
        resizedBitmap.getPixels(pixels, 0, resizedW, 0, 0, resizedW, targetH)

        // Python code performs FLIP_LEFT_RIGHT on the original image before padding
        for (row in 0 until targetH) {
            for (col in 0 until targetW) {
                // Read pixels in reverse order along X-axis to achieve FLIP_LEFT_RIGHT
                val flippedCol = (resizedW - 1) - col

                if (col < resizedW && flippedCol >= 0) {
                    val pixel = pixels[row * resizedW + flippedCol]

                    // Extract RGB
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF

                    // Standard PIL 'L' mode grayscale conversion formula
                    val gray = (0.299f * r + 0.587f * g + 0.114f * b)

                    // NormalizePAD formula: (gray / 255.0 - 0.5) / 0.5 = (gray / 127.5) - 1.0
                    val normalized = (gray / 127.5f) - 1.0f
                    inputBuffer.putFloat(normalized)
                } else {
                    // Right padding with -1.0f (corresponds to normalized 0 grayscale value)
                    inputBuffer.putFloat(-1.0f)
                }
            }
        }
        return inputBuffer
    }

    private fun decodeCTC(logits: Array<FloatArray>): String {
        val timeSteps = logits.size // 400
        val rawIndices = IntArray(timeSteps)

        for (i in 0 until timeSteps) {
            var maxIdx = 0
            var maxVal = logits[i][0]
            for (j in 1 until logits[i].size) {
                if (logits[i][j] > maxVal) {
                    maxVal = logits[i][j]
                    maxIdx = j
                }
            }
            rawIndices[i] = maxIdx
        }

        // CTC Greedy Decoding: Collapse duplicate contiguous indices and remove index 0 (blank)
        val sb = StringBuilder()
        var prevIdx = -1

        for (idx in rawIndices) {
            if (idx != 0 && idx != prevIdx) {
                labelMap[idx]?.let { sb.append(it) }
            }
            prevIdx = idx
        }

        return sb.toString()
    }
}