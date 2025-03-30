package com.antinsfw.antinsfw

import android.util.Log

object PerformanceStats {
    private const val TAG = "PerformanceStats"
    private const val MAX_SAMPLES = 20

    // Preprocessing, inference, and postprocessing times
    private val preprocessingTimes = mutableListOf<Long>()
    private val inferenceTimes = mutableListOf<Long>()
    private val postprocessingTimes = mutableListOf<Long>()
    private val totalProcessingTimes = mutableListOf<Long>()

    // Add timing data
    fun addTimings(preprocessTime: Long, inferenceTime: Long, postprocessTime: Long) {
        synchronized(this) {
            preprocessingTimes.add(preprocessTime)
            inferenceTimes.add(inferenceTime)
            postprocessingTimes.add(postprocessTime)
            totalProcessingTimes.add(preprocessTime + inferenceTime + postprocessTime)
            
            // Keep only the latest MAX_SAMPLES
            if (preprocessingTimes.size > MAX_SAMPLES) {
                preprocessingTimes.removeAt(0)
                inferenceTimes.removeAt(0)
                postprocessingTimes.removeAt(0)
                totalProcessingTimes.removeAt(0)
            }
            
            Log.d(TAG, "Added timing data: preprocess=${preprocessTime}ms, inference=${inferenceTime}ms, postprocess=${postprocessTime}ms")
        }
    }

    // Get average preprocessing time
    fun getAveragePreprocessTime(): Long {
        synchronized(this) {
            return if (preprocessingTimes.isEmpty()) 0 
                   else preprocessingTimes.sum() / preprocessingTimes.size
        }
    }

    // Get average inference time
    fun getAverageInferenceTime(): Long {
        synchronized(this) {
            return if (inferenceTimes.isEmpty()) 0 
                   else inferenceTimes.sum() / inferenceTimes.size
        }
    }

    // Get average postprocessing time
    fun getAveragePostprocessTime(): Long {
        synchronized(this) {
            return if (postprocessingTimes.isEmpty()) 0 
                   else postprocessingTimes.sum() / postprocessingTimes.size
        }
    }

    // Get average total processing time
    fun getAverageTotalTime(): Long {
        synchronized(this) {
            return if (totalProcessingTimes.isEmpty()) 0 
                   else totalProcessingTimes.sum() / totalProcessingTimes.size
        }
    }

    // Get sample count
    fun getSampleCount(): Int {
        synchronized(this) {
            return preprocessingTimes.size
        }
    }

    // Reset all stats
    fun reset() {
        synchronized(this) {
            preprocessingTimes.clear()
            inferenceTimes.clear()
            postprocessingTimes.clear()
            totalProcessingTimes.clear()
            Log.d(TAG, "Performance stats reset")
        }
    }
}
