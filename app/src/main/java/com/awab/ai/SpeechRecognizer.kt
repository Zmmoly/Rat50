package com.awab.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.flex.FlexDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

class SpeechRecognizer(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    
    // النموذج v19 Ultimate يتوقع 8 ثوانٍ من الصوت الخام (16000 * 8)
    private val requiredSamples = 128000 
    
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

    fun isModelLoaded(): Boolean = interpreter != null

    /**
     * تحميل النموذج مع توسيع الأبعاد وتفعيل الـ Flex
     */
    fun loadModelFromFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) return false
            
            val modelBuffer = loadModelFromPath(file)
            
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                // ✅ ضروري جداً لفك تشفير مخرجات v19
                addDelegate(FlexDelegate()) 
            }
            
            interpreter = Interpreter(modelBuffer, options)
            
            // ✅ توسيع "البوابة" لتستوعب الـ 128,000 عينة (512,000 بايت)
            interpreter?.resizeInput(0, intArrayOf(1, requiredSamples))
            interpreter?.allocateTensors()
            
            Log.d(TAG, "✅ تم ضبط النموذج v19 لاستقبال 8 ثوانٍ من الصوت")
            listener?.onModelLoaded(file.name)
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل التحميل: ${e.message}")
            listener?.onError("خطأ في تحميل النموذج")
            false
        }
    }

    private fun loadModelFromPath(file: File): MappedByteBuffer {
        val inputStream = FileInputStream(file)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
    }

    fun startRecording() {
        if (isRecording || interpreter == null) return

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                listener?.onError("الميكروفون غير جاهز")
                return
            }

            isRecording = true
            audioRecord?.startRecording()
            listener?.onRecordingStarted()
            
            Thread { recordAndRecognize() }.start()
        } catch (e: Exception) {
            listener?.onError("خطأ في التسجيل: ${e.message}")
        }
    }

    private fun recordAndRecognize() {
        val audioBuffer = ShortArray(bufferSize)
        val audioData = mutableListOf<Short>()
        
        try {
            while (isRecording) {
                val readSize = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                if (readSize > 0) {
                    val volume = calculateVolume(audioBuffer, readSize)
                    listener?.onVolumeChanged(volume)
                    
                    for (i in 0 until readSize) audioData.add(audioBuffer[i])
                    
                    // إذا وصل التسجيل لـ 8 ثوانٍ، عالجه تلقائياً
                    if (audioData.size >= requiredSamples) {
                        processChunk(audioData.take(requiredSamples).toShortArray())
                        audioData.clear()
                    }
                }
            }
            // معالجة ما تبقى عند الضغط على Stop
            if (audioData.isNotEmpty()) processChunk(audioData.toShortArray())
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في حلقة التسجيل: ${e.message}")
        }
    }

    private fun processChunk(audioArray: ShortArray) {
        val text = recognizeSpeech(audioArray)
        if (text.isNotBlank()) listener?.onTextRecognized(text)
    }

    private fun recognizeSpeech(audioData: ShortArray): String {
        return try {
            // 1. تجهيز الـ Buffer بحجم 512,000 بايت (128,000 float)
            val inputBuffer = ByteBuffer.allocateDirect(requiredSamples * 4)
            inputBuffer.order(ByteOrder.nativeOrder())
            
            // تحويل الصوت لـ Float وتطبيعه (-1.0 إلى 1.0) مع حشو الباقي أصفار
            for (i in 0 until requiredSamples) {
                if (i < audioData.size) {
                    inputBuffer.putFloat(audioData[i] / 32768.0f)
                } else {
                    inputBuffer.putFloat(0.0f)
                }
            }
            inputBuffer.rewind()
            
            // 2. تجهيز مخرجات النموذج (Indices)
            val outputShape = interpreter?.getOutputTensor(0)?.shape() ?: intArrayOf(1, 100)
            val outputBuffer = IntArray(outputShape[1])
            
            // 3. تشغيل المعالجة
            interpreter?.run(inputBuffer, outputBuffer)
            
            decodeCTCOutput(outputBuffer)
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطأ معالجة: ${e.message}")
            ""
        }
    }

    /**
     * فك تشفير CTC لتحويل الأرقام إلى نصوص سودانية
     */
    private fun decodeCTCOutput(indices: IntArray): String {
        val vocabulary = loadVocabulary()
        if (vocabulary.isEmpty()) return "خطأ: قاموس الحروف مفقود"
        
        val result = StringBuilder()
        var lastIdx = -1
        
        for (idx in indices) {
            // تخطي الفراغ (0) والمكرر حسب قواعد CTC
            if (idx == 0 || idx == lastIdx) {
                lastIdx = idx
                continue
            }
            if (idx < vocabulary.size) {
                result.append(vocabulary[idx])
            }
            lastIdx = idx
        }
        return result.toString()
    }

    private fun loadVocabulary(): List<String> {
        return try {
            context.assets.open("vocabulary.txt").bufferedReader().readLines().map { it.trim() }
        } catch (e: Exception) {
            Log.e(TAG, "❌ لم يتم العثور على vocabulary.txt في assets")
            emptyList()
        }
    }

    private fun calculateVolume(buffer: ShortArray, size: Int): Float {
        var sum = 0.0
        for (i in 0 until size) sum += (buffer[i] * buffer[i]).toDouble()
        return (sqrt(sum / size) / Short.MAX_VALUE).toFloat()
    }

    fun stopRecording() {
        isRecording = false
        audioRecord?.apply { 
            if (state == AudioRecord.STATE_INITIALIZED) {
                stop()
                release()
            }
        }
        audioRecord = null
        listener?.onRecordingStopped()
    }

    fun cleanup() {
        stopRecording()
        interpreter?.close()
        interpreter = null
    }

    companion object {
        private const val TAG = "SpeechRecognizer"
    }
}
