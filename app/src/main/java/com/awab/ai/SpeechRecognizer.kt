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
import kotlin.math.*

class SpeechRecognizer(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    
    // Audio parameters - exact match to training
    private val sampleRate = 16000
    private val nFFT = 512
    private val hopLength = 128
    private val winLength = 400
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
    
    fun isModelLoaded(): Boolean = interpreter != null

    fun loadModelFromFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "File not found: $filePath")
                listener?.onError("الملف غير موجود")
                return false
            }
            
            val modelBuffer = loadModelBuffer(file)
            val options = Interpreter.Options().apply { setNumThreads(4) }
            
            interpreter = Interpreter(modelBuffer, options)
            
            val inputTensor = interpreter?.getInputTensor(0)
            val outputTensor = interpreter?.getOutputTensor(0)
            
            Log.d(TAG, "Model Loaded: ${file.name}")
            Log.d(TAG, "Input: ${inputTensor?.shape()?.contentToString()}, Type: ${inputTensor?.dataType()}")
            Log.d(TAG, "Output: ${outputTensor?.shape()?.contentToString()}, Type: ${outputTensor?.dataType()}")
            
            listener?.onModelLoaded(file.name)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            listener?.onError("فشل تحميل النموذج: ${e.message}")
            false
        }
    }

    private fun loadModelBuffer(file: File): MappedByteBuffer {
        val inputStream = FileInputStream(file)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
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
                listener?.onError("فشل تهيئة التسجيل")
                return
            }

            isRecording = true
            audioRecord?.startRecording()
            listener?.onRecordingStarted()

            Thread { recordAndRecognize() }.start()
        } catch (e: Exception) {
            listener?.onError("خطأ: ${e.message}")
            isRecording = false
        }
    }

    fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            listener?.onRecordingStopped()
        } catch (e: Exception) {
            Log.e(TAG, "Stop Error", e)
        }
    }

    private fun recordAndRecognize() {
        val audioBuffer = ShortArray(bufferSize)
        val audioData = mutableListOf<Short>()
        val windowSize = sampleRate // 1 second window
        val hopSize = sampleRate / 2 // 0.5s hop for 50% overlap

        try {
            while (isRecording) {
                val readSize = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                if (readSize > 0) {
                    val volume = computeVolume(audioBuffer, readSize)
                    listener?.onVolumeChanged(volume)
                    
                    for (i in 0 until readSize) audioData.add(audioBuffer[i])
                    
                    while (audioData.size >= windowSize) {
                        val window = audioData.take(windowSize).toShortArray()
                        val text = recognizeSpeech(window)
                        if (text.isNotBlank()) {
                            listener?.onTextRecognized(text)
                        }
                        repeat(hopSize) { if (audioData.isNotEmpty()) audioData.removeAt(0) }
                    }
                }
            }
        } catch (e: Exception) {
            listener?.onError(e.message ?: "Processing error")
        }
    }

    private fun computeVolume(buffer: ShortArray, size: Int): Float {
        var sum = 0.0
        for (i in 0 until size) sum += (buffer[i] * buffer[i]).toDouble()
        return (sqrt(sum / size) / Short.MAX_VALUE).toFloat()
    }

    private fun recognizeSpeech(audioData: ShortArray): String {
        return try {
            val features = prepareAudio(audioData)
            
            interpreter?.resizeInput(0, features.shape)
            interpreter?.allocateTensors()

            val inputBuffer = createInputBuffer(features)
            val outputTensor = interpreter?.getOutputTensor(0)
            val outputShape = outputTensor?.shape() ?: return ""
            
            val batch = outputShape[0]
            val time = outputShape[1]
            val vocab = outputShape[2]
            
            val outputArray = Array(batch) { Array(time) { FloatArray(vocab) } }
            interpreter?.run(inputBuffer, outputArray)
            
            ctcDecodeGreedy(outputArray[0])
        } catch (e: Exception) {
            ""
        }
    }

    private fun prepareAudio(audioData: ShortArray): ProcessedAudio {
        val floatData = FloatArray(audioData.size) { audioData[it].toFloat() / Short.MAX_VALUE }
        val maxAbs = floatData.maxOf { abs(it) }.coerceAtLeast(1e-10f)
        val normalized = FloatArray(floatData.size) { floatData[it] / maxAbs }
        
        val stft = computeSTFT(normalized)
        val specDB = amplitudeToDb(stft)
        
        val finalSpec = Array(specDB.size) { t ->
            FloatArray(specDB[t].size) { f -> (specDB[t][f] + 80f) / 80f }
        }
        
        return ProcessedAudio(finalSpec, intArrayOf(1, finalSpec.size, finalSpec[0].size))
    }

    private fun computeSTFT(audio: FloatArray): Array<FloatArray> {
        val numFrames = (audio.size - nFFT) / hopLength + 1
        val fftSize = nFFT / 2 + 1
        val stft = Array(numFrames) { FloatArray(fftSize) }
        val window = FloatArray(winLength) { i -> 0.5f * (1f - cos(2f * PI.toFloat() * i / (winLength - 1))) }

        for (f in 0 until numFrames) {
            val start = f * hopLength
            for (k in 0 until fftSize) {
                var re = 0f
                var im = 0f
                for (n in 0 until nFFT) {
                    val sample = if (n < winLength && start + n < audio.size) audio[start + n] * window[n] else 0f
                    val angle = -2f * PI.toFloat() * k * n / nFFT
                    re += sample * cos(angle)
                    im += sample * sin(angle)
                }
                stft[f][k] = sqrt(re * re + im * im)
            }
        }
        return stft
    }

    private fun amplitudeToDb(stft: Array<FloatArray>): Array<FloatArray> {
        val ref = stft.maxOf { frame -> frame.maxOrNull() ?: 0f }.coerceAtLeast(1e-10f)
        return Array(stft.size) { t ->
            FloatArray(stft[t].size) { f -> 20f * log10((stft[t][f] + 1e-10f) / ref) }
        }
    }

    private fun ctcDecodeGreedy(logits: Array<FloatArray>): String {
        val vocab = loadVocabulary()
        val blankIndex = vocab.size
        val sb = StringBuilder()
        var lastIdx = -1

        for (t in logits.indices) {
            val maxIdx = logits[t].indices.maxByOrNull { logits[t][it] } ?: -1
            if (maxIdx != lastIdx && maxIdx != blankIndex && maxIdx < vocab.size) {
                sb.append(vocab[maxIdx])
            }
            lastIdx = maxIdx
        }
        return sb.toString()
    }

    private fun createInputBuffer(features: ProcessedAudio): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(features.shape[1] * features.shape[2] * 4)
        buffer.order(ByteOrder.nativeOrder())
        for (row in features.data) for (v in row) buffer.putFloat(v)
        buffer.rewind()
        return buffer
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
    }

    companion object { private const val TAG = "SpeechRecognizer" }
    private data class ProcessedAudio(val data: Array<FloatArray>, val shape: IntArray)
}
