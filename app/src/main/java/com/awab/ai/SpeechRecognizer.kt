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
    
    // ✅ التعديل: نحدد 8 ثوانٍ كحجم ثابت لضمان دقة النموذج السوداني v19
    private val fixedRequiredSamples = 128000 
    
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

    fun loadModelFromFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) return false
            
            val modelBuffer = loadModelFromPath(file)
            
            // ✅ إضافة الـ FlexDelegate الضروري لفك التشفير في النسخة Ultimate
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                addDelegate(FlexDelegate())
            }
            
            interpreter = Interpreter(modelBuffer, options)
            
            // ✅ السطر السحري: إجبار النموذج على قبول 8 ثوانٍ بدلاً من 193 عينة فقط
            interpreter?.resizeInput(0, intArrayOf(1, fixedRequiredSamples))
            interpreter?.allocateTensors()
            
            listener?.onModelLoaded(file.name)
            true
        } catch (e: Exception) {
            listener?.onError("فشل التحميل: ${e.message}")
            false
        }
    }

    fun loadModelFromAssets(modelFileName: String = "speech_model.tflite"): Boolean {
        return try {
            val modelBuffer = loadModelFromAssetsInternal(modelFileName)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                addDelegate(FlexDelegate())
            }
            interpreter = Interpreter(modelBuffer, options)
            
            // ✅ توسيع المدخلات لـ 128 ألف عينة
            interpreter?.resizeInput(0, intArrayOf(1, fixedRequiredSamples))
            interpreter?.allocateTensors()
            
            listener?.onModelLoaded(modelFileName)
            true
        } catch (e: Exception) {
            listener?.onError("لم يتم العثور على النموذج في assets")
            false
        }
    }

    private fun loadModelFromPath(file: File): MappedByteBuffer {
        val inputStream = FileInputStream(file)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
    }
    
    private fun loadModelFromAssetsInternal(modelFileName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelFileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    fun startRecording() {
        if (isRecording || interpreter == null) return
        try {
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return

            isRecording = true
            audioRecord?.startRecording()
            listener?.onRecordingStarted()
            
            Thread { recordAndRecognize() }.start()
        } catch (e: Exception) {
            isRecording = false
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
                    
                    // إرسال الكتلة مرة واحدة عند وصولها لـ 8 ثوانٍ
                    if (audioData.size >= fixedRequiredSamples) {
                        processAudioChunk(audioData.take(fixedRequiredSamples).toShortArray())
                        audioData.clear()
                    }
                }
            }
            // معالجة ما تبقى عند الضغط على Stop
            if (audioData.isNotEmpty()) processAudioChunk(audioData.toShortArray())
        } catch (e: Exception) { }
    }

    private fun processAudioChunk(audioArray: ShortArray) {
        val text = recognizeSpeech(audioArray)
        if (text.isNotBlank()) listener?.onTextRecognized(text)
    }

    private fun recognizeSpeech(audioData: ShortArray): String {
        return try {
            // تجهيز الـ Buffer بالحجم الذي حددناه في الـ Resize (128,000)
            val inputBuffer = ByteBuffer.allocateDirect(fixedRequiredSamples * 4)
            inputBuffer.order(ByteOrder.nativeOrder())
            
            for (i in 0 until fixedRequiredSamples) {
                if (i < audioData.size) {
                    inputBuffer.putFloat(audioData[i] / 32768.0f)
                } else {
                    inputBuffer.putFloat(0.0f) // Padding
                }
            }
            inputBuffer.rewind()
            
            val outputShape = interpreter?.getOutputTensor(0)?.shape() ?: intArrayOf(1, 100)
            val outputBuffer = IntArray(outputShape[1])
            
            interpreter?.run(inputBuffer, outputBuffer)
            
            decodeCTCOutput(outputBuffer)
        } catch (e: Exception) { "" }
    }

    private fun decodeCTCOutput(indices: IntArray): String {
        val vocabulary = loadVocabulary()
        val result = StringBuilder()
        var lastIdx = -1
        
        for (idx in indices) {
            if (idx == 0 || idx == lastIdx) {
                lastIdx = idx
                continue
            }
            if (idx < vocabulary.size) result.append(vocabulary[idx])
            lastIdx = idx
        }
        return result.toString()
    }

    private fun loadVocabulary(): List<String> {
        return try {
            context.assets.open("vocabulary.txt").bufferedReader().readLines().map { it.trim() }
        } catch (e: Exception) { emptyList() }
    }

    private fun calculateVolume(buffer: ShortArray, size: Int): Float {
        var sum = 0.0
        for (i in 0 until size) sum += (buffer[i] * buffer[i]).toDouble()
        return (sqrt(sum / size) / Short.MAX_VALUE).toFloat()
    }

    fun stopRecording() {
        isRecording = false
        audioRecord?.apply { stop(); release() }
        audioRecord = null
        listener?.onRecordingStopped()
    }

    fun cleanup() {
        stopRecording()
        interpreter?.close()
    }

    companion object {
        private const val TAG = "SpeechRecognizer"
    }
}
