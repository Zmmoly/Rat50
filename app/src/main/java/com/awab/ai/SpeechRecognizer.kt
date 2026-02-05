package com.awab.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt
import kotlin.math.ln
import kotlin.math.cos
import kotlin.math.sin

class SpeechRecognizer(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private val inputSize = 16000
    
    interface RecognitionListener {
        fun onTextRecognized(text: String)
        fun onError(error: String)
        fun onRecordingStarted()
        fun onRecordingStopped()
        fun onVolumeChanged(volume: Float)
        fun onModelLoaded(modelName: String)
    }
    
    private var listener: RecognitionListener? = null
    
    fun setListener(listener: RecognitionListener) {
        this.listener = listener
    }
    
    fun isModelLoaded(): Boolean {
        return interpreter != null
    }

    fun loadModelFromFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "❌ الملف غير موجود: $filePath")
                listener?.onError("الملف غير موجود")
                return false
            }
            
            if (!file.name.endsWith(".tflite")) {
                Log.e(TAG, "❌ صيغة خاطئة: ${file.name}")
                listener?.onError("الملف يجب أن يكون بصيغة .tflite")
                return false
            }
            
            val modelBuffer = loadModelBuffer(file)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            
            interpreter = Interpreter(modelBuffer, options)
            Log.d(TAG, "✅ تم تحميل النموذج: ${file.name}")
            listener?.onModelLoaded(file.name)
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل تحميل النموذج: ${e.message}")
            listener?.onError("فشل تحميل النموذج: ${e.message}")
            false
        }
    }
    
    fun loadModelFromAssets(modelFileName: String = "speech_model.tflite"): Boolean {
        return try {
            val modelBuffer = loadModelFromAssetsBuffer(modelFileName)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.d(TAG, "✅ تم تحميل النموذج من assets: $modelFileName")
            listener?.onModelLoaded(modelFileName)
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل تحميل النموذج من assets: ${e.message}")
            listener?.onError("لم يتم العثور على النموذج في assets")
            false
        }
    }

    private fun loadModelBuffer(file: File): MappedByteBuffer {
        val inputStream = FileInputStream(file)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
    }
    
    private fun loadModelFromAssetsBuffer(modelFileName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelFileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    fun startRecording() {
        if (isRecording) return
        if (interpreter == null) {
            listener?.onError("يرجى تحميل النموذج أولاً")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                listener?.onError("فشل تهيئة التسجيل الصوتي")
                return
            }

            isRecording = true
            audioRecord?.startRecording()
            listener?.onRecordingStarted()
            
            Thread { recordAndRecognize() }.start()

        } catch (e: Exception) {
            listener?.onError("فشل بدء التسجيل: ${e.message}")
            isRecording = false
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            listener?.onRecordingStopped()
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطأ في إيقاف التسجيل: ${e.message}")
        }
    }

    private fun recordAndRecognize() {
        val audioBuffer = ShortArray(bufferSize)
        val audioData = mutableListOf<Short>()
        val recognizedText = StringBuilder()
        val silenceThreshold = 0.01f
        
        // تعديل: نافذة 2.0 ثانية لضمان استقرار النموذج
        val windowDuration = 2.0f
        val windowSize = (sampleRate * windowDuration).toInt()
        val overlapRatio = 0.2f 
        val hopSize = (windowSize * (1 - overlapRatio)).toInt()
        
        try {
            while (isRecording) {
                val readSize = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                if (readSize > 0) {
                    val volume = computeVolume(audioBuffer, readSize)
                    listener?.onVolumeChanged(volume)
                    
                    for (i in 0 until readSize) audioData.add(audioBuffer[i])
                    
                    while (audioData.size >= windowSize) {
                        val windowData = audioData.take(windowSize).toShortArray()
                        if (volume >= silenceThreshold) {
                            val text = recognizeSpeech(windowData)
                            if (text.isNotBlank()) {
                                recognizedText.append(text).append(" ")
                                listener?.onTextRecognized(recognizedText.toString().trim())
                            }
                        }
                        val toRemove = kotlin.math.min(hopSize, audioData.size)
                        repeat(toRemove) { audioData.removeAt(0) }
                    }
                }
            }
        } catch (e: Exception) {
            listener?.onError("خطأ أثناء التسجيل")
        }
    }

    private fun computeVolume(buffer: ShortArray, size: Int): Float {
        var sum = 0.0
        for (i in 0 until size) sum += (buffer[i] * buffer[i]).toDouble()
        return (sqrt(sum / size) / Short.MAX_VALUE).toFloat()
    }

    private fun recognizeSpeech(audioData: ShortArray): String {
        try {
            val features = prepareAudioLikeLibrosa(audioData)
            interpreter?.resizeInput(0, features.shape)
            interpreter?.allocateTensors()
            
            val inputBuffer = createInputBuffer(features)
            val outputDetails = interpreter?.getOutputTensor(0) ?: return ""
            val outputShape = outputDetails.shape()
            
            return when (outputShape.size) {
                1 -> {
                    val tensorSize = outputDetails.numBytes()
                    val outputBuffer = ByteBuffer.allocateDirect(tensorSize).order(ByteOrder.nativeOrder())
                    interpreter?.run(inputBuffer, outputBuffer)
                    outputBuffer.rewind()
                    val intArray = IntArray(tensorSize / 4) { outputBuffer.int }
                    decodeIndicesArray(intArray)
                }
                else -> {
                    val timeSteps = outputShape[1]
                    val vocabSize = outputShape[2]
                    val outputArray = Array(1) { Array(timeSteps) { FloatArray(vocabSize) } }
                    interpreter?.run(inputBuffer, outputArray)
                    ctcDecodeGreedy(outputArray[0])
                }
            }
        } catch (e: Exception) {
            return ""
        }
    }
    
    private fun prepareAudioLikeLibrosa(audioData: ShortArray): ProcessedAudio {
        val audio = normalizeAudio(audioData)
        val stft = computeSTFT(audio, 512, 128, 400)
        val specDB = amplitudeToDb(stft)
        val normalizedSpec = Array(specDB.size) { t ->
            FloatArray(specDB[t].size) { f -> (specDB[t][f] + 80f) / 80f }
        }
        return ProcessedAudio(normalizedSpec, intArrayOf(1, normalizedSpec.size, normalizedSpec[0].size))
    }
    
    private fun normalizeAudio(audioData: ShortArray): FloatArray {
        val floatData = FloatArray(audioData.size) { audioData[it].toFloat() / Short.MAX_VALUE }
        val maxAbs = floatData.maxOfOrNull { kotlin.math.abs(it) } ?: 1.0f
        return if (maxAbs > 0f) FloatArray(floatData.size) { floatData[it] / maxAbs } else floatData
    }
    
    private fun computeSTFT(audio: FloatArray, nFFT: Int, hopLength: Int, winLength: Int): Array<FloatArray> {
        val numFrames = (audio.size - nFFT) / hopLength + 1
        val fftSize = nFFT / 2 + 1
        val stft = Array(numFrames) { FloatArray(fftSize) }
        val window = FloatArray(winLength) { i -> 0.5f * (1f - cos(2f * Math.PI.toFloat() * i / (winLength - 1))) }
        
        for (frame in 0 until numFrames) {
            val start = frame * hopLength
            for (k in 0 until fftSize) {
                var real = 0f
                var imag = 0f
                for (n in 0 until nFFT) {
                    if (start + n < audio.size && n < winLength) {
                        val angle = -2f * Math.PI.toFloat() * k * n / nFFT
                        val sample = audio[start + n] * window[n]
                        real += sample * cos(angle)
                        imag += sample * sin(angle).toFloat()
                    }
                }
                stft[frame][k] = sqrt(real * real + imag * imag)
            }
        }
        return stft
    }
    
    private fun amplitudeToDb(stft: Array<FloatArray>): Array<FloatArray> {
        val refValue = stft.maxOfOrNull { frame -> frame.maxOrNull() ?: 0f } ?: 1e-10f
        return Array(stft.size) { t ->
            FloatArray(stft[t].size) { f -> 20f * ln((stft[t][f] + 1e-10f) / (refValue + 1e-10f)) / ln(10f) }
        }
    }
    
    private fun ctcDecodeGreedy(logits: Array<FloatArray>): String {
        val vocabulary = loadVocabulary()
        val blankIndex = vocabulary.size 
        val result = StringBuilder()
        var lastChar = -1
        for (t in logits.indices) {
            var maxIdx = 0
            var maxProb = Float.MIN_VALUE
            for (i in logits[t].indices) {
                if (logits[t][i] > maxProb) {
                    maxProb = logits[t][i]
                    maxIdx = i
                }
            }
            if (maxIdx != lastChar && maxIdx != blankIndex && maxIdx < vocabulary.size) {
                result.append(vocabulary[maxIdx])
            }
            lastChar = maxIdx
        }
        return result.toString().trim()
    }
    
    private fun decodeIndicesArray(indices: IntArray): String {
        val vocabulary = loadVocabulary()
        val result = StringBuilder()
        for (idx in indices) {
            if (idx >= 0 && idx < vocabulary.size) result.append(vocabulary[idx])
        }
        return result.toString().trim()
    }
    
    private fun decodeFloatArray(floats: FloatArray): String {
        val vocabulary = loadVocabulary()
        val blankIndex = vocabulary.size
        val result = StringBuilder()
        var lastChar = -1
        val chunkSize = vocabulary.size + 1
        for (t in 0 until (floats.size / chunkSize)) {
            val startIdx = t * chunkSize
            var maxIdx = 0
            var maxProb = Float.MIN_VALUE
            for (i in 0 until chunkSize) {
                if (floats[startIdx + i] > maxProb) {
                    maxProb = floats[startIdx + i]
                    maxIdx = i
                }
            }
            if (maxIdx != lastChar && maxIdx != blankIndex && maxIdx < vocabulary.size) result.append(vocabulary[maxIdx])
            lastChar = maxIdx
        }
        return result.toString().trim()
    }
    
    private fun createInputBuffer(features: ProcessedAudio): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(features.data.size * features.data[0].size * 4).order(ByteOrder.nativeOrder())
        for (row in features.data) for (value in row) buffer.putFloat(value)
        return buffer.apply { rewind() }
    }

    private fun loadVocabulary(): List<String> {
        return listOf(
            " ", "ا", "ب", "ت", "ث", "ج", "ح", "خ", "د", "ذ", 
            "ر", "ز", "س", "ش", "ص", "ض", "ط", "ظ", "ع", "غ", 
            "ف", "ق", "ك", "ل", "م", "ن", "هـ", "و", "ي", 
            "ى", "ئ", "ؤ"
        )
    }

    fun cleanup() {
        stopRecording()
        interpreter?.close()
        interpreter = null
    }

    companion object {
        private const val TAG = "SpeechRecognizer"
    }
    
    private data class ProcessedAudio(val data: Array<FloatArray>, val shape: IntArray)
}
