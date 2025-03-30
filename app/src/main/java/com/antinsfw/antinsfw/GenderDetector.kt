package com.antinsfw.antinsfw

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

object GenderDetector {
    private const val TAG = "GenderDetector"
    private const val TARGET_SIZE = 96
    private const val INPUT_MEAN = 127.5f
    private const val INPUT_STD = 128.0f

    private lateinit var ortEnvironment: OrtEnvironment
    private lateinit var ortSession: OrtSession
    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return

        try {
            ortEnvironment = OrtEnvironment.getEnvironment()
            val modelPath = assetFilePath(context, "genderage.onnx")

            val sessionOptions = OrtSession.SessionOptions().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    try {
                        addNnapi()
                    } catch (e: Exception) {
                        Log.w(TAG, "NNAPI not available: ${e.message}")
                    }
                }
                addCPU(true)
            }

            ortSession = ortEnvironment.createSession(modelPath, sessionOptions)
            isInitialized = true
            Log.i(TAG, "GenderAge model successfully initialized")

            debugModelOutputs()
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed: ${e.message}")
            throw RuntimeException("Gender detector initialization failed", e)
        }
    }

    private fun debugModelOutputs() {
        val outputNames = ortSession.outputNames
        Log.d(TAG, "Output names: $outputNames")
        val outputInfo = ortSession.outputInfo
        for (name in outputNames) {
            val info = outputInfo[name]
            Log.d(TAG, "Output $name: info=$info")
        }
    }

    fun verifyGender(originalBitmap: Bitmap, faceBox: Rect, context: Context): Boolean {
        if (!isInitialized) {
            Log.e(TAG, "GenderDetector not initialized")
            return false
        }

        Log.d(TAG, "Verifying gender for box: $faceBox, bitmap size: ${originalBitmap.width}x${originalBitmap.height}")

        return try {
            val croppedBitmap = cropAndAlignFace(originalBitmap, faceBox) ?: return false
            Log.d(TAG, "Cropped bitmap size: ${croppedBitmap.width}x${croppedBitmap.height}")

            saveDebugImage(croppedBitmap, context)

            val inputTensor = preprocess(croppedBitmap)
            Log.d(TAG, "Preprocessing completed")

            val outputs = ortSession.run(mapOf("data" to inputTensor))
            val genderResult = processModelOutput(outputs)

            Log.d(TAG, "Verification result: ${if (genderResult) "Female" else "Male"}")
            genderResult
        } catch (e: Exception) {
            Log.e(TAG, "Verification error: ${e.message}")
            false
        }
    }

    private fun processModelOutput(outputs: OrtSession.Result): Boolean {
        val predictions = (outputs.get(0)?.value as? Array<*>?)?.get(0) as FloatArray
        Log.d(TAG, "Raw predictions: ${predictions.joinToString()}")

        val genderLogits = predictions.take(2)  // [female_logit, male_logit]
        val gender = genderLogits.indexOfMax()  // 0 = female, 1 = male
        val isFemale = gender == 0
        Log.d(TAG, "Gender logits: ${genderLogits.joinToString()}, Gender index: $gender, isFemale: $isFemale")

        return isFemale
    }

    private fun cropAndAlignFace(bitmap: Bitmap, box: Rect): Bitmap? {
        return try {
            if (box.width() <= 0 || box.height() <= 0) {
                Log.e(TAG, "Invalid box dimensions: width=${box.width()}, height=${box.height()}")
                return null
            }
            if (box.left < 0 || box.top < 0 || box.right > bitmap.width || box.bottom > bitmap.height) {
                Log.e(TAG, "Box coordinates out of bounds: $box, bitmap size=${bitmap.width}x${bitmap.height}")
                return null
            }

            // Yüzün maksimum boyutunu hesapla ve 1.5 kat büyüt
            val w = box.width()
            val h = box.height()
            val m = max(w, h)
            val size = (m * 1.5).toInt() // Colab'daki gibi 1.5 kat genişletme

            // Merkez koordinatları
            val centerX = box.centerX()
            val centerY = box.centerY()

            // Yeni kırpma sınırlarını hesapla
            val newLeft = max(0, centerX - size / 2)
            val newTop = max(0, centerY - size / 2)
            val newRight = min(bitmap.width, centerX + size / 2)
            val newBottom = min(bitmap.height, centerY + size / 2)

            // Kırpılan bölgenin boyutlarını kontrol et
            val croppedWidth = newRight - newLeft
            val croppedHeight = newBottom - newTop
            if (croppedWidth <= 0 || croppedHeight <= 0) {
                Log.e(TAG, "Cropped dimensions invalid: width=$croppedWidth, height=$croppedHeight")
                return null
            }

            // Bitmap'i kırp
            val cropped = Bitmap.createBitmap(bitmap, newLeft, newTop, croppedWidth, croppedHeight)

            // Hedef boyuta (96x96) ölçeklendir
            val scaledBitmap = Bitmap.createScaledBitmap(cropped, TARGET_SIZE, TARGET_SIZE, true)
            cropped.recycle()

            scaledBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Face processing failed: ${e.message}")
            null
        }
    }

    private fun preprocess(bitmap: Bitmap): OnnxTensor {
        val floatBuffer = FloatArray(3 * TARGET_SIZE * TARGET_SIZE)
        for (i in 0 until TARGET_SIZE * TARGET_SIZE) {
            val pixel = bitmap.getPixel(i % TARGET_SIZE, i / TARGET_SIZE)
            // RGB formatında model için uygun hale getir
            floatBuffer[i] = ((pixel shr 16 and 0xFF).toFloat() - INPUT_MEAN) / INPUT_STD // R
            floatBuffer[i + TARGET_SIZE * TARGET_SIZE] = ((pixel shr 8 and 0xFF).toFloat() - INPUT_MEAN) / INPUT_STD // G
            floatBuffer[i + 2 * TARGET_SIZE * TARGET_SIZE] = ((pixel and 0xFF).toFloat() - INPUT_MEAN) / INPUT_STD // B
        }
        return OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(floatBuffer),
            longArrayOf(1, 3, TARGET_SIZE.toLong(), TARGET_SIZE.toLong())
        )
    }

    private fun saveDebugImage(bitmap: Bitmap, context: Context) {
        try {
            val file = File(context.getExternalFilesDir(null), "debug_face_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
            Log.d(TAG, "Debug image saved: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save debug image: ${e.message}")
        }
    }

    private fun assetFilePath(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (file.exists() && file.length() > 0) return file.absolutePath

        context.assets.open(assetName).use { input ->
            FileOutputStream(file).use { output ->
                val buffer = ByteArray(4 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        }
        return file.absolutePath
    }

    private fun List<Float>.indexOfMax(): Int {
        var maxIndex = 0
        for (i in indices) {
            if (this[i] > this[maxIndex]) maxIndex = i
        }
        return maxIndex
    }

    fun release() {
        if (::ortSession.isInitialized) ortSession.close()
        if (::ortEnvironment.isInitialized) ortEnvironment.close()
        isInitialized = false
    }
}