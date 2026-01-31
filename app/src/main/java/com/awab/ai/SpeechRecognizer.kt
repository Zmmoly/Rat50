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
    
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    
    // قائمة الحروف مطابقة تماماً لكود الاختبار (بدون Blank في البداية لأننا سنعالجه بالإزاحة)
    private val charList = listOf(
        " ", "أ", "ب", "ت", "ث", "ج", "ح", "خ", "د", "ذ", "ر", "ز", "س", "ش", "ص", "ض", 
        "ط", "ظ", "ع", "غ", "ف", "ق", "ك", "ل", "م", "ن", "هـ", "و", "ي", "ة", "ى", "ئ", "ء", "ؤ", "آ", "لا"
    )

    interface RecognitionListener {
        fun onTextRecognized(text: String)
        fun onError(error: String)
        fun onRecordingStarted()
        fun onRecordingStopped()
        fun onVolumeChanged(volume: Float)
    }

    private var listener: RecognitionListener? = null
    fun setListener(listener: RecognitionListener) { this.listener = listener }

    // --- 1. معالجة الصوت لتطابق Librosa ---

    private fun computeSTFT(audio: ShortArray, nFFT: Int, hopLength: Int, winLength: Int): Array<FloatArray> {
        // إضافة Padding في البداية والنهاية لمحاكاة center=True في librosa
        val padSize = nFFT / 2
        val paddedAudio = FloatArray(audio.size + 2 * padSize)
        for (i in audio.indices) paddedAudio[i + padSize] = audio[i].toFloat() / Short.MAX_VALUE

        val numFrames = (paddedAudio.size - nFFT) / hopLength + 1
        val fftSize = nFFT / 2 + 1
        val stft = Array(numFrames) { FloatArray(fftSize) }
        
        // Hann Window
        val window = FloatArray(winLength) { i ->
            0.5f * (1f - cos(2f * PI.toFloat() * i / (winLength - 1)))
        }

        for (frame in 0 until numFrames) {
            val start = frame * hopLength
            var realSum = 0f
            var imagSum = 0f
            
            for (k in 0 until fftSize) {
                var re = 0f
                var im = 0f
                for (n in 0 until winLength) {
                    val sample = paddedAudio[start + n] * window[n]
                    val angle = -2f * PI.toFloat() * k * n / nFFT
                    re += sample * cos(angle)
                    im += sample * sin(angle)
                }
                stft[frame][k] = sqrt(re * re + im * im)
            }
        }
        return stft
    }

    private fun computeMelSpectrogram(stft: Array<FloatArray>, nFFT: Int, nMels: Int, targetTimeSteps: Int): Array<FloatArray> {
        val melFilterbank = createMelFilterbank(nFFT, nMels, sampleRate)
        val melSpec = Array(targetTimeSteps) { FloatArray(nMels) { -80f } } // الافتراضي صمت

        for (t in 0 until minOf(stft.size, targetTimeSteps)) {
            for (m in 0 until nMels) {
                var energy = 0f
                for (k in stft[t].indices) {
                    energy += stft[t][k] * melFilterbank[m][k]
                }
                // تحويل إلى dB محاكاة لـ amplitude_to_db
                val db = 20f * log10(energy + 1e-10f)
                melSpec[t][m] = db
            }
        }
        return melSpec
    }

    private fun normalizeSpectrogram(melSpec: Array<FloatArray>): Array<FloatArray> {
        // تطبيق معادلة الاختبار: (spec + 80) / 80
        return Array(melSpec.size) { t ->
            FloatArray(melSpec[0].size) { m ->
                ((melSpec[t][m] + 80f) / 80f).coerceIn(0f, 1f)
            }
        }
    }

    // --- 2. فك التشفير مع مراعاة الإزاحة (idx - 1) ---

    private fun decodeOutput(output: Array<Array<FloatArray>>): String {
        val result = StringBuilder()
        val batchOutput = output[0] // [timeSteps][vocabSize]
        var lastIdx = -1

        for (t in batchOutput.indices) {
            val probs = batchOutput[t]
            val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
            
            // في كود الاختبار: idx-1 تعني أن 0 هو Blank
            if (maxIdx > 0 && maxIdx != lastIdx) {
                val charPos = maxIdx - 1
                if (charPos < charList.size) {
                    result.append(charList[charPos])
                }
            }
            lastIdx = maxIdx
        }
        return result.toString()
    }

    // --- 3. تشغيل الموديل ---

    private fun recognizeSpeech(audioData: ShortArray): String {
        try {
            val inputTensor = interpreter?.getInputTensor(0) ?: return ""
            val inputShape = inputTensor.shape() // [1, time, features]
            
            val timeSteps = inputShape[1]
            val nFeatures = inputShape[2]

            // المعالجة بنفس إعدادات بايثون
            val stft = computeSTFT(audioData, 384, 160, 256)
            val mel = computeMelSpectrogram(stft, 384, nFeatures, timeSteps)
            val finalInput = normalizeSpectrogram(mel)

            val buffer = ByteBuffer.allocateDirect(1 * timeSteps * nFeatures * 4)
            buffer.order(ByteOrder.nativeOrder())
            for (t in 0 until timeSteps) {
                for (f in 0 until nFeatures) {
                    buffer.putFloat(finalInput[t][f])
                }
            }
            buffer.rewind()

            val outputDetails = interpreter?.getOutputTensor(0) ?: return ""
            val outShape = outputDetails.shape()
            val outputBuffer = Array(outShape[0]) { Array(outShape[1]) { FloatArray(outShape[2]) } }

            interpreter?.run(buffer, outputBuffer)
            return decodeOutput(outputBuffer)

        } catch (e: Exception) {
            Log.e("SR", "Recognition error: ${e.message}")
            return ""
        }
    }
    
    // (باقي دوال startRecording و loadModel تبقى كما هي مع استدعاء recognizeSpeech)
}
