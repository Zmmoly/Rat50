# 🎯 تكوين نموذج v19_ULTIMATE

## 📋 المواصفات الكاملة:

### 1️⃣ الهوية (Identity)
```
الاسم: Sudanese End-to-End ASR (v19_ULTIMATE)
المعمارية: Bi-directional RNN/LSTM
الـ Decoder: FlexCTCGreedyDecoder (مدمج)
البيئة: TensorFlow Lite
```

### 2️⃣ المدخلات (Input)
```
Tensor Name: audio_input
Shape: [1, 128000]
Type: Float32
Sample Rate: 16000 Hz
Duration: 8 seconds (ثابت)
Range: [-1.0, 1.0]

معالجة الصوت:
PCM 16-bit → Float32
التحويل: audio_float = audio_int16 / 32768.0

إذا < 8 ثوان:
Padding بالأصفار حتى 128000
```

### 3️⃣ المخرجات (Output)
```
Type: Int32 (Indices)
Format: Array of integers
Meaning: فهارس الحروف في القاموس

معالجة بعدية:
1. حذف Blank token (عادة 0)
2. دمج الحروف المتكررة (CTC)
3. تحويل الفهارس إلى حروف من labels.txt
```

---

## 🔄 التغييرات المطلوبة:

### ❌ النموذج القديم:
```
Input: [1, 1, 193] (Spectrogram)
Processing: Audio → STFT → Mel → Log → Mean
Output: [1, 1, 37] (حرف واحد)
Decoding: Simple argmax
```

### ✅ النموذج الجديد:
```
Input: [1, 128000] (Raw audio)
Processing: Audio → Normalize [-1, 1] → Pad to 8s
Output: Int32[] (Sequence of indices)
Decoding: CTC (built-in decoder)
```

---

## 🔧 التعديلات المطلوبة في الكود:

### 1. تحديث `prepareInputBuffer()`
```kotlin
// القديم:
if (inputShape.size >= 3) {
    // Spectrogram
    prepareSpectrogramInput(...)
}

// الجديد:
fun prepareInputBuffer(audioData: ShortArray): ByteBuffer {
    // Check input shape
    val inputTensor = interpreter?.getInputTensor(0)
    val shape = inputTensor?.shape() ?: intArrayOf(1, 128000)
    
    val expectedSize = shape[1]  // 128000
    
    // Normalize to [-1.0, 1.0]
    val normalized = FloatArray(expectedSize) { i ->
        if (i < audioData.size) {
            audioData[i] / 32768.0f
        } else {
            0.0f  // Padding
        }
    }
    
    // Create ByteBuffer
    val buffer = ByteBuffer.allocateDirect(expectedSize * 4)
    buffer.order(ByteOrder.nativeOrder())
    
    normalized.forEach { buffer.putFloat(it) }
    buffer.rewind()
    
    return buffer
}
```

### 2. تحديث `recognizeSpeech()`
```kotlin
fun recognizeSpeech(audioData: ShortArray): String {
    try {
        // Get input/output shapes
        val inputShape = interpreter?.getInputTensor(0)?.shape() 
            ?: intArrayOf(1, 128000)
        val outputShape = interpreter?.getOutputTensor(0)?.shape() 
            ?: intArrayOf(1, 100)
        
        Log.d(TAG, "📊 Input: ${inputShape.contentToString()}")
        Log.d(TAG, "📊 Output: ${outputShape.contentToString()}")
        
        // Prepare input (8 seconds = 128000 samples)
        val inputBuffer = prepareInputBuffer(audioData)
        
        // Output buffer (Int32 array)
        val maxOutputLength = outputShape[1]
        val outputBuffer = IntArray(maxOutputLength)
        
        // Run inference
        interpreter?.run(inputBuffer, outputBuffer)
        
        Log.d(TAG, "✅ Model inference completed")
        
        // Decode CTC output
        val text = decodeCTCOutput(outputBuffer)
        
        Log.d(TAG, "📝 Decoded: '$text'")
        
        return text
        
    } catch (e: Exception) {
        Log.e(TAG, "❌ Error: ${e.message}")
        e.printStackTrace()
        return ""
    }
}
```

### 3. إضافة `decodeCTCOutput()`
```kotlin
fun decodeCTCOutput(indices: IntArray): String {
    val vocabulary = loadVocabulary()
    val result = StringBuilder()
    var lastIdx = -1
    
    Log.d(TAG, "🔍 CTC Decoding...")
    Log.d(TAG, "🔍 First 20 indices: ${indices.take(20)}")
    
    for (idx in indices) {
        // Skip blank (usually 0)
        if (idx == 0) continue
        
        // Skip repeated characters (CTC rule)
        if (idx == lastIdx) continue
        
        // Valid index
        if (idx > 0 && idx < vocabulary.size) {
            val char = vocabulary[idx]
            result.append(char)
            Log.d(TAG, "  idx=$idx → '$char'")
        }
        
        lastIdx = idx
    }
    
    return result.toString()
}
```

### 4. تحديث `recordAndRecognize()`
```kotlin
fun recordAndRecognize() {
    val audioBuffer = ShortArray(bufferSize)
    val audioData = mutableListOf<Short>()
    
    // 8 seconds = 128000 samples
    val requiredSize = 128000
    
    Log.d(TAG, "📊 Recording for 8 seconds...")
    
    while (isRecording) {
        val readSize = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
        
        if (readSize > 0) {
            for (i in 0 until readSize) {
                audioData.add(audioBuffer[i])
            }
            
            // When we have 8 seconds
            if (audioData.size >= requiredSize) {
                Log.d(TAG, "🎯 Got 8 seconds - processing...")
                
                val audioArray = audioData.take(requiredSize).toShortArray()
                val text = recognizeSpeech(audioArray)
                
                if (text.isNotBlank()) {
                    listener?.onTextRecognized(text)
                    Log.d(TAG, "✅ Result: '$text'")
                }
                
                // Clear buffer
                audioData.clear()
            }
        }
    }
}
```

---

## 📝 ملف Labels (vocabulary.txt)

```
يجب أن يحتوي على:
- السطر 0: [blank] أو فارغ
- السطر 1-N: الحروف السودانية

مثال:
[blank]
أ
ب
ت
ث
...
```

---

## 🎯 الاختلافات الرئيسية:

| الميزة | النموذج القديم | v19_ULTIMATE |
|--------|----------------|--------------|
| المدخل | Spectrogram [1,1,193] | Raw Audio [1,128000] |
| المعالجة | STFT + Mel + Log | Normalize only |
| المدة | 2 ثانية (متغيرة) | 8 ثوان (ثابتة) |
| المخرج | Float32 [1,1,37] | Int32 [varies] |
| الـ Decoder | argmax بسيط | CTC (مدمج) |
| النتيجة | حرف واحد | كلمات كاملة! |

---

## ✅ المزايا:

```
✅ لا يحتاج Spectrogram (أسرع!)
✅ لا يحتاج Streaming (يخرج كلمات كاملة)
✅ CTC decoder مدمج (أسهل)
✅ معالجة أبسط (normalize فقط)
✅ دقة أعلى (RNN/LSTM ثنائي الاتجاه)
```

---

## ⚠️ ملاحظات:

```
1. المدة ثابتة: 8 ثوان
   - أقل من 8 ثوان: padding بأصفار
   - أكثر من 8 ثوان: قص إلى 8 ثوان

2. Blank token:
   - عادة index 0
   - يجب تخطيه في الـ decoding

3. CTC Rules:
   - دمج الحروف المتكررة
   - تخطي blank tokens

4. Labels file:
   - يجب أن يطابق ترتيب تدريب النموذج
   - السطر 0 = blank
```

---

## 🚀 الخطوات للتطبيق:

```
1. ✅ تحديث prepareInputBuffer()
2. ✅ تحديث recognizeSpeech()
3. ✅ إضافة decodeCTCOutput()
4. ✅ تحديث recordAndRecognize()
5. ✅ تحديث vocabulary.txt
6. ✅ اختبار النموذج

النتيجة:
كلمات كاملة بدون streaming! 🎉
```
