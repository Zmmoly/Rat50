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

class SpeechRecognizer(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    
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
            
            Log.d(TAG, "📂 محاولة تحميل: ${file.name} (${file.length()} bytes)")
            
            val modelBuffer = loadModelBuffer(file)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            
            interpreter = Interpreter(modelBuffer, options)
            
            val inputDetails = interpreter?.getInputTensor(0)
            val outputDetails = interpreter?.getOutputTensor(0)
            
            Log.d(TAG, "╔════════════════════════════════════════╗")
            Log.d(TAG, "║ معلومات النموذج                       ║")
            Log.d(TAG, "╠════════════════════════════════════════╣")
            Log.d(TAG, "║ 📥 Input: ${inputDetails?.shape()?.contentToString()}")
            Log.d(TAG, "║    Type: ${inputDetails?.dataType()}")
            Log.d(TAG, "║ 📤 Output: ${outputDetails?.shape()?.contentToString()}")
            Log.d(TAG, "║    Type: ${outputDetails?.dataType()}")
            Log.d(TAG, "╚════════════════════════════════════════╝")
            
            Log.d(TAG, "✅ تم تحميل النموذج: ${file.name}")
            listener?.onModelLoaded(file.name)
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل تحميل النموذج: ${e.message}", e)
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
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun startRecording() {
        if (isRecording) {
            Log.w(TAG, "⚠️ التسجيل قيد العمل بالفعل")
            return
        }
        
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
            
            Log.d(TAG, "🎤 بدأ التسجيل...")

            Thread {
                recordAndRecognize()
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "❌ خطأ في بدء التسجيل: ${e.message}")
            listener?.onError("فشل بدء التسجيل: ${e.message}")
            isRecording = false
        }
    }

    fun stopRecording() {
        if (!isRecording) {
            return
        }

        isRecording = false
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            
            listener?.onRecordingStopped()
            Log.d(TAG, "🛑 توقف التسجيل")
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطأ في إيقاف التسجيل: ${e.message}")
        }
    }

    private fun recordAndRecognize() {
        val audioBuffer = ShortArray(bufferSize)
        val audioData = mutableListOf<Short>()
        
        val recognizedText = StringBuilder()
        var silenceCount = 0
        val silenceThreshold = 0.01f
        
        val windowDuration = 0.5f
        val windowSize = (sampleRate * windowDuration).toInt()
        val overlapRatio = 0.5f
        val hopSize = (windowSize * (1 - overlapRatio)).toInt()
        
        Log.d(TAG, "📊 بدء التسجيل - windowSize: $windowSize, hopSize: $hopSize")
        
        try {
            while (isRecording) {
                val readSize = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                
                if (readSize > 0) {
                    val volume = computeVolume(audioBuffer, readSize)
                    listener?.onVolumeChanged(volume)
                    
                    val isSilent = volume < silenceThreshold
                    
                    if (isSilent) {
                        silenceCount++
                        if (silenceCount > 10 && recognizedText.isNotEmpty()) {
                            if (recognizedText.last() != ' ') {
                                recognizedText.append(" ")
                                listener?.onTextRecognized(recognizedText.toString())
                            }
                        }
                    } else {
                        silenceCount = 0
                    }
                    
                    for (i in 0 until readSize) {
                        audioData.add(audioBuffer[i])
                    }
                    
                    while (audioData.size >= windowSize) {
                        val windowData = audioData.take(windowSize).toShortArray()
                        
                        if (!isSilent) {
                            val text = recognizeSpeech(windowData)
                            
                            if (text.isNotBlank()) {
                                recognizedText.append(text)
                                listener?.onTextRecognized(recognizedText.toString())
                            }
                        }
                        
                        val toRemove = kotlin.math.min(hopSize, audioData.size)
                        repeat(toRemove) { audioData.removeAt(0) }
                    }
                }
            }
            
            if (audioData.size >= windowSize / 2 && recognizedText.isNotEmpty()) {
                val remainingData = audioData.toShortArray()
                val text = recognizeSpeech(remainingData)
                if (text.isNotBlank()) {
                    recognizedText.append(text)
                }
            }
            
            if (recognizedText.isNotEmpty()) {
                val finalText = recognizedText.toString().trim()
                Log.d(TAG, "📝 نص نهائي: $finalText")
                listener?.onTextRecognized(finalText)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطأ أثناء التسجيل: ${e.message}")
            listener?.onError("خطأ أثناء التسجيل")
        }
    }

    private fun computeVolume(buffer: ShortArray, size: Int): Float {
        var sum = 0.0
        for (i in 0 until size) {
            sum += (buffer[i] * buffer[i]).toDouble()
        }
        val rms = sqrt(sum / size)
        return (rms / Short.MAX_VALUE).toFloat()
    }

    // ========== المعالجة والتعرف - مبسطة ومباشرة ==========
    
    /**
     * التعرف على الكلام
     * النموذج يقوم بكل شيء - نحن فقط نترجم الـ indices
     */
    private fun recognizeSpeech(audioData: ShortArray): String {
        try {
            // 1. تحويل الصوت لـ Spectrogram [1, time, 257]
            val spectrogram = audioToSpectrogram(audioData)
            
            // 2. تحضير المدخلات
            interpreter?.resizeInput(0, spectrogram.shape)
            interpreter?.allocateTensors()
            
            val inputBuffer = createInputBuffer(spectrogram)
            
            // 3. تحضير المخرجات
            val outputDetails = interpreter?.getOutputTensor(0)
            val tensorSize = outputDetails?.numBytes() ?: 0
            
            if (tensorSize == 0) {
                Log.e(TAG, "❌ حجم المخرجات = 0")
                return ""
            }
            
            val outputBuffer = ByteBuffer.allocateDirect(tensorSize)
            outputBuffer.order(ByteOrder.nativeOrder())
            
            // 4. تشغيل النموذج
            interpreter?.run(inputBuffer, outputBuffer)
            
            // 5. قراءة النتائج
            outputBuffer.rewind()
            
            val dataType = outputDetails?.dataType()
            Log.d(TAG, "📤 Output type: $dataType, size: $tensorSize bytes")
            
            val text = when (dataType) {
                org.tensorflow.lite.DataType.INT32 -> {
                    val numElements = tensorSize / 4
                    val indices = IntArray(numElements) {
                        outputBuffer.int
                    }
                    Log.d(TAG, "📊 Indices (${indices.size}): ${indices.joinToString()}")
                    decodeIndices(indices)
                }
                org.tensorflow.lite.DataType.INT64 -> {
                    val numElements = tensorSize / 8
                    val indices = IntArray(numElements) {
                        outputBuffer.long.toInt()
                    }
                    Log.d(TAG, "📊 Indices (${indices.size}): ${indices.joinToString()}")
                    decodeIndices(indices)
                }
                else -> {
                    Log.e(TAG, "❌ نوع مخرجات غير مدعوم: $dataType")
                    ""
                }
            }
            
            if (text.isNotBlank()) {
                Log.d(TAG, "✅ النص: '$text'")
            }
            
            return text
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطأ في التعرف: ${e.message}", e)
            return ""
        }
    }
    
    /**
     * تحويل الصوت إلى Spectrogram
     * Input: ShortArray (raw audio)
     * Output: [1, time, 257] spectrogram
     */
    private fun audioToSpectrogram(audioData: ShortArray): Spectrogram {
        // 1. تطبيع الصوت
        val audio = normalizeAudio(audioData)
        
        // 2. STFT مع المعاملات المطلوبة
        val nFFT = 512
        val hopLength = 128
        val winLength = 400
        val stft = computeSTFT(audio, nFFT, hopLength, winLength)
        
        // 3. تحويل لـ dB
        val specDB = amplitudeToDb(stft)
        
        // 4. التطبيع: (spec + 80) / 80
        val normalized = Array(specDB.size) { t ->
            FloatArray(specDB[t].size) { f ->
                (specDB[t][f] + 80f) / 80f
            }
        }
        
        return Spectrogram(
            data = normalized,
            shape = intArrayOf(1, normalized.size, normalized[0].size)
        )
    }
    
    private fun normalizeAudio(audioData: ShortArray): FloatArray {
        val floatData = FloatArray(audioData.size) { i ->
            audioData[i].toFloat() / Short.MAX_VALUE
        }
        
        val maxAbs = floatData.maxOf { kotlin.math.abs(it) }
        return if (maxAbs > 0f) {
            FloatArray(floatData.size) { i -> floatData[i] / maxAbs }
        } else {
            floatData
        }
    }
    
    private fun computeSTFT(audio: FloatArray, nFFT: Int, hopLength: Int, winLength: Int): Array<FloatArray> {
        val numFrames = (audio.size - nFFT) / hopLength + 1
        val fftSize = nFFT / 2 + 1
        val stft = Array(numFrames) { FloatArray(fftSize) }
        
        val window = FloatArray(winLength) { i ->
            0.5f * (1f - cos(2f * Math.PI.toFloat() * i / (winLength - 1)))
        }
        
        for (frame in 0 until numFrames) {
            val start = frame * hopLength
            val fftInput = FloatArray(nFFT) { 0f }
            
            for (i in 0 until kotlin.math.min(winLength, audio.size - start)) {
                fftInput[i] = audio[start + i] * window[i]
            }
            
            for (k in 0 until fftSize) {
                var real = 0f
                var imag = 0f
                
                for (n in 0 until nFFT) {
                    val angle = -2f * Math.PI.toFloat() * k * n / nFFT
                    real += fftInput[n] * cos(angle)
                    imag += fftInput[n] * kotlin.math.sin(angle)
                }
                
                stft[frame][k] = kotlin.math.sqrt(real * real + imag * imag)
            }
        }
        
        return stft
    }
    
    private fun amplitudeToDb(stft: Array<FloatArray>): Array<FloatArray> {
        val refValue = stft.maxOf { frame -> frame.maxOrNull() ?: 0f }
        
        return Array(stft.size) { t ->
            FloatArray(stft[t].size) { f ->
                val magnitude = stft[t][f]
                20f * ln((magnitude + 1e-10f) / (refValue + 1e-10f)) / ln(10f)
            }
        }
    }
    
    /**
     * فك تشفير الـ Indices - مباشر وبسيط
     * النموذج أخرج indices جاهزة، نحن فقط نترجمها
     */
    private fun decodeIndices(indices: IntArray): String {
        val vocabulary = loadVocabulary()
        val result = StringBuilder()
        
        for (idx in indices) {
            // الـ indices تبدأ من 0
            // 0 = مسافة (أو أول حرف في vocabulary)
            // 1-31 = باقي الأحرف
            
            if (idx >= 0 && idx < vocabulary.size) {
                val char = vocabulary[idx]
                result.append(char)
                Log.d(TAG, "  idx=$idx → char='$char'")
            } else {
                Log.w(TAG, "  idx=$idx → خارج النطاق (vocabulary size=${vocabulary.size})")
            }
        }
        
        return result.toString()
    }
    
    private fun createInputBuffer(spectrogram: Spectrogram): ByteBuffer {
        val totalSize = spectrogram.data.sumOf { it.size }
        val buffer = ByteBuffer.allocateDirect(totalSize * 4)
        buffer.order(ByteOrder.nativeOrder())
        
        for (row in spectrogram.data) {
            for (value in row) {
                buffer.putFloat(value)
            }
        }
        
        buffer.rewind()
        return buffer
    }

    /**
     * تحميل القاموس
     * السطر الأول = مسافة (index 0)
     * باقي الأسطر = الأحرف (indices 1-31)
     */
    private fun loadVocabulary(): List<String> {
        return try {
            val lines = context.assets.open("vocabulary.txt").bufferedReader().readLines()
            Log.d(TAG, "📖 تم تحميل ${lines.size} حرف من vocabulary.txt")
            lines
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ فشل تحميل vocabulary.txt، استخدام القائمة الافتراضية")
            // القائمة الافتراضية - تأكد أن المسافة في الأول
            listOf(
                " ", "ا", "ب", "ت", "ث", "ج", "ح", "خ", "د", "ذ", 
                "ر", "ز", "س", "ش", "ص", "ض", "ط", "ظ", "ع", "غ", 
                "ف", "ق", "ك", "ل", "م", "ن", "هـ", "و", "ي", 
                "ى", "ئ", "ؤ"
            )
        }
    }

    fun cleanup() {
        stopRecording()
        interpreter?.close()
        interpreter = null
        Log.d(TAG, "🧹 تم تنظيف الموارد")
    }

    companion object {
        private const val TAG = "SpeechRecognizer"
    }
    
    private data class Spectrogram(
        val data: Array<FloatArray>,
        val shape: IntArray
    )
}
