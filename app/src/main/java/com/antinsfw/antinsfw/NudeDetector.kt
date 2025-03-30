package com.antinsfw.antinsfw

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NudeDetector {
    private const val TAG = "NudeDetector"
    private lateinit var ortEnvironment: OrtEnvironment
    private lateinit var ortSession: OrtSession
    private const val TARGET_SIZE = 320
    private const val SCORE_THRESHOLD = 0.25f
    private const val NMS_THRESHOLD = 0.5f
    private val labels = listOf(
        "FEMALE_GENITALIA_COVERED", "FACE_FEMALE", "BUTTOCKS_EXPOSED", "FEMALE_BREAST_EXPOSED",
        "FEMALE_GENITALIA_EXPOSED", "MALE_BREAST_EXPOSED", "ANUS_EXPOSED", "FEET_EXPOSED",
        "BELLY_COVERED", "FEET_COVERED", "ARMPITS_COVERED", "ARMPITS_EXPOSED", "FACE_MALE",
        "BELLY_EXPOSED", "MALE_GENITALIA_EXPOSED", "ANUS_COVERED", "FEMALE_BREAST_COVERED",
        "BUTTOCKS_COVERED"
    )

    fun initialize(context: Context) {
        try {
            ortEnvironment = OrtEnvironment.getEnvironment()
            val modelPath = assetFilePath(context, "320n.onnx")

            val sessionOptions = OrtSession.SessionOptions()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                try {
                    sessionOptions.addNnapi()
                    Log.i(TAG, "NNAPI enabled")
                } catch (e: Exception) {
                    Log.w(TAG, "NNAPI failed: ${e.message}, falling back to CPU")
                }
            }
            sessionOptions.addCPU(true)

            ortSession = ortEnvironment.createSession(modelPath, sessionOptions)
            Log.i(TAG, "NudeDetector model loaded: $modelPath")
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed: ${e.message}", e)
            throw e
        }
    }

    suspend fun detect(bitmap: Bitmap, context: Context): List<Map<String, Any>> = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val (inputTensor, xRatio, yRatio, xPad, yPad, origWidth, origHeight) = preprocessImage(bitmap)
        val preprocessTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "Preprocessing completed: ${preprocessTime}ms")

        val inferenceStart = System.currentTimeMillis()
        val output = ortSession.run(mapOf("images" to inputTensor))
        val outputTensor = output[0] as OnnxTensor
        val outputs = outputTensor.floatBuffer.array()
        val inferenceTime = System.currentTimeMillis() - inferenceStart
        Log.d(TAG, "Inference completed: ${inferenceTime}ms, output size: ${outputs.size}")

        val postprocessStart = System.currentTimeMillis()
        val detections = postprocess(outputs, xPad, yPad, origWidth, origHeight, bitmap, context)
        val postprocessTime = System.currentTimeMillis() - postprocessStart
        Log.d(TAG, "Postprocessing completed: ${postprocessTime}ms, detections: ${detections.size}")

        PerformanceStats.addTimings(preprocessTime, inferenceTime, postprocessTime)

        detections
    }

    private fun preprocessImage(bitmap: Bitmap): PreprocessResult {
        val origWidth = bitmap.width
        val origHeight = bitmap.height
        val maxSize = max(origWidth, origHeight)
        val xPad = maxSize - origWidth
        val yPad = maxSize - origHeight
        val xRatio = maxSize.toFloat() / origWidth
        val yRatio = maxSize.toFloat() / origHeight

        val paddedBitmap = BitmapPool.getBitmap(maxSize, maxSize)
        val canvas = android.graphics.Canvas(paddedBitmap)
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        val resizedBitmap = BitmapPool.getBitmap(TARGET_SIZE, TARGET_SIZE)
        val scaledBitmap = Bitmap.createScaledBitmap(paddedBitmap, TARGET_SIZE, TARGET_SIZE, true)
        val pixelBuffer = IntArray(TARGET_SIZE * TARGET_SIZE)
        scaledBitmap.getPixels(pixelBuffer, 0, TARGET_SIZE, 0, 0, TARGET_SIZE, TARGET_SIZE)

        val floatValues = FloatArray(3 * TARGET_SIZE * TARGET_SIZE)
        val numThreads = Runtime.getRuntime().availableProcessors()
        val chunkSize = TARGET_SIZE * TARGET_SIZE / numThreads
        val jobs = (0 until numThreads).map { threadId ->
            val start = threadId * chunkSize
            val end = if (threadId == numThreads - 1) TARGET_SIZE * TARGET_SIZE else start + chunkSize
            Thread {
                for (i in start until end) {
                    val pixel = pixelBuffer[i]
                    floatValues[i] = ((pixel shr 16 and 0xFF) / 255.0f) // R
                    floatValues[i + TARGET_SIZE * TARGET_SIZE] = ((pixel shr 8 and 0xFF) / 255.0f) // G
                    floatValues[i + 2 * TARGET_SIZE * TARGET_SIZE] = ((pixel and 0xFF) / 255.0f) // B
                }
            }
        }
        jobs.forEach { it.start() }
        jobs.forEach { it.join() }

        val inputTensor = OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(floatValues),
            longArrayOf(1, 3, TARGET_SIZE.toLong(), TARGET_SIZE.toLong())
        )

        BitmapPool.returnBitmap(paddedBitmap)
        BitmapPool.returnBitmap(resizedBitmap)
        scaledBitmap.recycle()
        return PreprocessResult(inputTensor, xRatio, yRatio, xPad, yPad, origWidth, origHeight)
    }

    private fun postprocess(
        outputs: FloatArray,
        xPad: Int,
        yPad: Int,
        origWidth: Int,
        origHeight: Int,
        originalBitmap: Bitmap,
        context: Context
    ): List<Map<String, Any>> {
        val numClasses = labels.size
        val rows = 2100
        val cols = 4 + numClasses

        val outputMatrix = FloatArray(rows * cols)
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                outputMatrix[i * cols + j] = outputs[j * rows + i]
            }
        }

        val boxes = mutableListOf<FloatArray>()
        val scores = mutableListOf<Float>()
        val classIds = mutableListOf<Int>()

        for (i in 0 until rows) {
            val classesScores = FloatArray(numClasses) { outputMatrix[i * cols + 4 + it] }
            val maxScore = classesScores.maxOrNull() ?: continue

            if (maxScore >= SCORE_THRESHOLD) {
                val classId = classesScores.indexOfFirst { it == maxScore }
                if (labels[classId] == "FACE_MALE") {
                    Log.d(TAG, "FACE_MALE detected and skipped: score=$maxScore")
                    continue
                }

                val x = outputMatrix[i * cols]
                val y = outputMatrix[i * cols + 1]
                val w = outputMatrix[i * cols + 2]
                val h = outputMatrix[i * cols + 3]

                val xMin = (x - w / 2) * (origWidth + xPad) / TARGET_SIZE
                val yMin = (y - h / 2) * (origHeight + yPad) / TARGET_SIZE
                val width = w * (origWidth + xPad) / TARGET_SIZE
                val height = h * (origHeight + yPad) / TARGET_SIZE

                val xMinClamped = max(0f, min(xMin, origWidth.toFloat()))
                val yMinClamped = max(0f, min(yMin, origHeight.toFloat()))
                val boxWidth = min(width, origWidth - xMinClamped)
                val boxHeight = min(height, origHeight - yMinClamped)

                if (boxWidth > 1 && boxHeight > 1) {
                    boxes.add(floatArrayOf(xMinClamped, yMinClamped, xMinClamped + boxWidth, yMinClamped + boxHeight))
                    scores.add(maxScore)
                    classIds.add(classId)
                }
            }
        }

        val indices = nms(boxes, scores, NMS_THRESHOLD)
        val detections = mutableListOf<Map<String, Any>>()

        for (i in indices) {
            val box = boxes[i]
            val classId = classIds[i]
            val className = labels[classId]
            val score = scores[i]

            if (className == "FACE_FEMALE") {
                val faceRect = Rect(box[0].toInt(), box[1].toInt(), box[2].toInt(), box[3].toInt())
                val isFemale = GenderDetector.verifyGender(originalBitmap, faceRect, context)
                if (!isFemale) {
                    Log.d(TAG, "FACE_FEMALE rejected (Score: $score)")
                    continue
                }
                Log.d(TAG, "FACE_FEMALE validated (Score: $score)")
            }

            val boxWidth = (box[2] - box[0])
            val boxHeight = (box[3] - box[1])
            if (boxWidth > 1 && boxHeight > 1) {
                detections.add(
                    mapOf(
                        "class" to className,
                        "score" to score,
                        "box" to listOf(box[0].toInt(), box[1].toInt(), boxWidth.toInt(), boxHeight.toInt())
                    )
                )
                Log.d(TAG, "Detection: class=$className, score=$score, box=[${box[0].toInt()}, ${box[1].toInt()}, ${boxWidth.toInt()}, ${boxHeight.toInt()}]")
            }
        }
        return detections
    }

    private fun nms(boxes: List<FloatArray>, scores: List<Float>, threshold: Float): List<Int> {
        val indices = (0 until boxes.size).toMutableList()
        indices.sortByDescending { scores[it] }
        val result = mutableListOf<Int>()

        while (indices.isNotEmpty()) {
            val idx = indices[0]
            result.add(idx)
            val box1 = boxes[idx]
            indices.removeAt(0)

            val iterator = indices.iterator()
            while (iterator.hasNext()) {
                val i = iterator.next()
                val box2 = boxes[i]
                val iou = calculateIoU(box1, box2)
                if (iou > threshold) {
                    iterator.remove()
                }
            }
        }
        return result
    }

    private fun calculateIoU(box1: FloatArray, box2: FloatArray): Float {
        val x1 = max(box1[0], box2[0])
        val y1 = max(box1[1], box2[1])
        val x2 = min(box1[2], box2[2])
        val y2 = min(box1[3], box2[3])

        val intersection = max(0f, x2 - x1) * max(0f, y2 - y1)
        val area1 = (box1[2] - box1[0]) * (box1[3] - box1[1])
        val area2 = (box2[2] - box2[0]) * (box2[3] - box2[1])
        val union = area1 + area2 - intersection

        return if (union == 0f) 0f else intersection / union
    }

    private fun assetFilePath(context: Context, assetName: String): String {
        val file = java.io.File(context.filesDir, assetName)
        if (!file.exists()) {
            context.assets.open(assetName).use { input ->
                java.io.FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return file.absolutePath
    }

    fun release() {
        if (::ortSession.isInitialized) ortSession.close()
        if (::ortEnvironment.isInitialized) ortEnvironment.close()
    }
}

data class PreprocessResult(
    val inputTensor: OnnxTensor,
    val xRatio: Float,
    val yRatio: Float,
    val xPad: Int,
    val yPad: Int,
    val origWidth: Int,
    val origHeight: Int
)