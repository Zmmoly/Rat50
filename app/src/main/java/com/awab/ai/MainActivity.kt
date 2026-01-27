package com.awab.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var chatContainer: LinearLayout
    private lateinit var inputField: EditText
    private lateinit var scrollView: ScrollView
    private lateinit var rootLayout: LinearLayout
    private lateinit var commandHandler: CommandHandler
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var micButton: TextView
    
    private var isRecording = false
    private val RECORD_AUDIO_PERMISSION_CODE = 200
    private val PICK_MODEL_FILE_CODE = 201
    private var modelLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize command handler
        commandHandler = CommandHandler(this)
        
        // Initialize speech recognizer
        speechRecognizer = SpeechRecognizer(this)
        setupSpeechRecognizer()
        
        // Hide action bar
        supportActionBar?.hide()
        
        // Root layout
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFFF0F2F5.toInt())
            fitsSystemWindows = true
        }

        // Chat area (scrollable)
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isScrollbarFadingEnabled = false
        }

        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(16, 48, 16, 16)
        }

        scrollView.addView(chatContainer)
        rootLayout.addView(scrollView)

        // Input area
        val inputArea = createInputArea()
        rootLayout.addView(inputArea)

        setContentView(rootLayout)
        
        // Handle keyboard insets
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            view.setPadding(
                systemInsets.left,
                systemInsets.top,
                systemInsets.right,
                imeInsets.bottom
            )
            
            // Scroll to bottom when keyboard appears
            if (imeInsets.bottom > 0) {
                rootLayout.post {
                    scrollToBottom()
                }
            }
            
            WindowInsetsCompat.CONSUMED
        }

        // Welcome message
        addBotMessage("مرحباً! أنا أواب AI 🤖\n\nكيف يمكني مساعدتك اليوم؟")
    }

    private fun createInputArea(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(16, 16, 16, 16)
            gravity = Gravity.CENTER_VERTICAL

            inputField = EditText(this@MainActivity).apply {
                hint = "اكتب رسالتك هنا..."
                textSize = 16f
                setPadding(20, 16, 20, 16)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                background = createRoundedBackground(0xFFF0F2F5.toInt(), 24f)
            }
            addView(inputField)

            // Microphone button
            micButton = TextView(this@MainActivity).apply {
                text = "🎤"
                textSize = 24f
                setTextColor(0xFF075E54.toInt())
                setPadding(16, 0, 0, 0)
                setOnClickListener {
                    toggleRecording()
                }
            }
            addView(micButton)

            val sendText = TextView(this@MainActivity).apply {
                text = "➤"
                textSize = 28f
                setTextColor(0xFF075E54.toInt())
                setPadding(16, 0, 0, 0)
                setOnClickListener {
                    sendMessage()
                }
            }
            addView(sendText)
            
            val settingsIcon = TextView(this@MainActivity).apply {
                text = "⚙️"
                textSize = 24f
                setTextColor(0xFF075E54.toInt())
                setPadding(16, 0, 0, 0)
                setOnClickListener {
                    openSettings()
                }
            }
            addView(settingsIcon)
        }
    }

    private fun sendMessage() {
        val message = inputField.text.toString().trim()
        if (message.isEmpty()) return

        addUserMessage(message)
        inputField.text.clear()

        android.os.Handler(mainLooper).postDelayed({
            handleBotResponse(message)
        }, 500)
    }

    private fun handleBotResponse(userMessage: String) {
        // استخراج الأوامر من النص بذكاء
        val extractedCommands = extractCommandsFromText(userMessage)
        
        // إذا وجدنا أوامر صحيحة
        if (extractedCommands.isNotEmpty()) {
            if (extractedCommands.size > 1) {
                // عدة أوامر
                addBotMessage("🔄 وجدت ${extractedCommands.size} أوامر، سأنفذها بالترتيب...")
                executeMultipleCommands(extractedCommands, 0)
            } else {
                // أمر واحد فقط
                val response = commandHandler.handleCommand(extractedCommands[0])
                if (response != null) {
                    addBotMessage(response)
                }
            }
            return
        }
        
        // ثانياً: الردود العادية (إذا لم يكن هناك أوامر)
        val response = when {
            userMessage.contains("مرحبا", ignoreCase = true) || 
            userMessage.contains("السلام", ignoreCase = true) ||
            userMessage.contains("هلا", ignoreCase = true) -> {
                "مرحباً بك! 👋\n\nأنا مساعدك الذكي. يمكنني:\n\n📱 فتح أي تطبيق:\n• افتح [اسم أي تطبيق]\n• اعرض التطبيقات (لرؤية القائمة)\n\n📞 الاتصال بجهات الاتصال:\n• اتصل أحمد\n• اتصل بأحمد\n• اضرب لأحمد\n• اتصل 0501234567\n\n⚙️ التحكم الكامل:\n• شغل الواي فاي\n• سكرين شوت\n• أقفل التطبيق\n\nجرب أي أمر!"
            }
            userMessage.contains("أذونات", ignoreCase = true) || 
            userMessage.contains("صلاحيات", ignoreCase = true) ||
            userMessage.contains("permission", ignoreCase = true) -> {
                "لإدارة الأذونات، اضغط على زر الإعدادات ⚙️ في الأسفل.\n\nهناك يمكنك:\n✓ طلب الأذونات العادية\n✓ الأذونات الخاصة\n✓ إمكانية الوصول"
            }
            userMessage.contains("كيف", ignoreCase = true) || 
            userMessage.contains("ساعد", ignoreCase = true) ||
            userMessage.contains("help", ignoreCase = true) ||
            userMessage.contains("أوامر", ignoreCase = true) -> {
                "📋 الأوامر المتاحة:\n\n📱 التطبيقات:\n• افتح [اسم أي تطبيق]\n• أقفل [اسم التطبيق] ⭐\n• اعرض التطبيقات\n\n📞 الاتصال:\n• اتصل [اسم أو رقم]\n• اتصل ب[اسم]\n• اضرب ل[اسم]\n• كلم [اسم]\n\n⚙️ الإعدادات:\n• شغل الواي فاي ⭐\n• شغل البلوتوث ⭐\n• رجوع / هوم ⭐\n\n🔊 الصوت:\n• على الصوت\n• خفض الصوت\n\n📸 أخرى:\n• سكرين شوت ⭐\n• اقرا الشاشة ⭐\n• اضغط على \"نص\" ⭐\n\n🔗 أوامر متعددة:\n• افتح واتساب، على الصوت\n• اتصل بأحمد ثم افتح يوتيوب\n• استخدم: ، أو ، أو ثم أو سطر جديد\n\n⭐ = يحتاج Accessibility"
            }
            userMessage.contains("إعدادات", ignoreCase = true) || 
            userMessage.contains("settings", ignoreCase = true) -> {
                openSettings()
                "سأفتح لك صفحة الإعدادات..."
            }
            else -> {
                // إذا كانت الرسالة قصيرة وعادية، رد بشكل طبيعي
                when {
                    userMessage.length < 50 && !userMessage.contains("افتح") && !userMessage.contains("شغل") -> {
                        "أنا هنا لمساعدتك! 😊\n\nيمكنني:\n• فتح التطبيقات\n• الاتصال بجهات الاتصال\n• التحكم في الإعدادات\n\nاكتب \"أوامر\" لرؤية كل ما يمكنني فعله!"
                    }
                    else -> {
                        "لم أفهم الأمر 🤔\n\nجرب:\n• \"أوامر\" - لرؤية كل الأوامر\n• \"افتح واتساب\"\n• \"شغل الواي فاي\"\n• \"على الصوت\""
                    }
                }
            }
        }

        addBotMessage(response)
    }

    private fun extractCommandsFromText(text: String): List<String> {
        val commands = mutableListOf<String>()
        
        // أنماط الأوامر - تستخرج الأمر فقط من أي نص
        val commandPatterns = mapOf(
            // فتح التطبيقات: "افتح [اسم]"
            "open_app" to Regex("(?:افتح|شغل|فتح)\\s+([^،,\\n]+?)(?=\\s*(?:[،,\\n]|ثم|و(?=\\s)|$))", RegexOption.IGNORE_CASE),
            
            // إغلاق التطبيقات: "أقفل [اسم]"
            "close_app" to Regex("(?:أقفل|اقفل|سكر)\\s+([^،,\\n]+?)(?=\\s*(?:[،,\\n]|ثم|و(?=\\s)|$))", RegexOption.IGNORE_CASE),
            
            // الاتصال: "اتصل ب[اسم]" أو "اضرب ل[اسم]"
            "call" to Regex("(?:اتصل\\s+ب|اضرب\\s+ل|اتصل|كلم)\\s+([^،,\\n]+?)(?=\\s*(?:[،,\\n]|ثم|و(?=\\s)|$))", RegexOption.IGNORE_CASE),
            
            // التحكم في الصوت
            "volume" to Regex("(على\\s+الصوت|خفض\\s+الصوت|كتم\\s+الصوت)", RegexOption.IGNORE_CASE),
            
            // الإعدادات
            "settings" to Regex("(شغل\\s+الواي\\s+فاي|اطفي\\s+الواي\\s+فاي|شغل\\s+البلوتوث|اطفي\\s+البلوتوث)", RegexOption.IGNORE_CASE),
            
            // النظام
            "system" to Regex("(رجوع|ارجع|back|هوم|home|الشاشة\\s+الرئيسية|recent|التطبيقات\\s+الأخيرة)", RegexOption.IGNORE_CASE),
            
            // أخرى
            "screenshot" to Regex("(سكرين\\s+شوت|لقطة\\s+شاشة|screenshot)", RegexOption.IGNORE_CASE),
            "read_screen" to Regex("(اقرا\\s+الشاشة|ماذا\\s+في\\s+الشاشة)", RegexOption.IGNORE_CASE),
            "click" to Regex("(?:اضغط\\s+على|انقر\\s+على)\\s+(.+?)(?=\\s*(?:[،,\\n]|ثم|و(?=\\s)|$))", RegexOption.IGNORE_CASE),
            "notifications" to Regex("(?:افتح\\s+)?(?:الإشعارات|الاشعارات)", RegexOption.IGNORE_CASE)
        )
        
        // البحث عن جميع الأوامر
        for ((type, pattern) in commandPatterns) {
            val matches = pattern.findAll(text)
            for (match in matches) {
                val fullMatch = match.value.trim()
                
                // بناء الأمر
                val command = when (type) {
                    "open_app" -> {
                        val appName = match.groupValues.getOrNull(1)?.trim() ?: fullMatch.substringAfter(" ").trim()
                        "افتح $appName"
                    }
                    "close_app" -> {
                        val appName = match.groupValues.getOrNull(1)?.trim() ?: fullMatch.substringAfter(" ").trim()
                        "أقفل $appName"
                    }
                    "call" -> {
                        val contact = match.groupValues.getOrNull(1)?.trim() ?: ""
                        if (fullMatch.contains("اتصل ب", ignoreCase = true)) {
                            "اتصل ب$contact"
                        } else if (fullMatch.contains("اضرب ل", ignoreCase = true)) {
                            "اضرب ل$contact"
                        } else {
                            fullMatch
                        }
                    }
                    "click" -> {
                        val element = match.groupValues.getOrNull(1)?.trim() ?: ""
                        "اضغط على $element"
                    }
                    else -> fullMatch
                }
                
                // تحقق إذا كان أمر صحيح
                val response = commandHandler.handleCommand(command)
                if (response != null && !response.contains("لم أفهم الأمر")) {
                    commands.add(command)
                }
            }
        }
        
        return commands.distinct() // إزالة التكرار
    }

    private fun addUserMessage(message: String) {
        val messageView = createMessageBubble(message, isUser = true)
        chatContainer.addView(messageView)
        scrollToBottom()
    }

    private fun addBotMessage(message: String) {
        val messageView = createMessageBubble(message, isUser = false)
        chatContainer.addView(messageView)
        scrollToBottom()
    }

    private fun createMessageBubble(message: String, isUser: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 8)
            }
            gravity = if (isUser) Gravity.END else Gravity.START

            val bubble = TextView(this@MainActivity).apply {
                text = message
                textSize = 16f
                setPadding(20, 16, 20, 16)
                setTextColor(if (isUser) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
                background = createRoundedBackground(
                    if (isUser) 0xFF075E54.toInt() else 0xFFFFFFFF.toInt(),
                    16f
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                maxWidth = (resources.displayMetrics.widthPixels * 0.75).toInt()
            }
            addView(bubble)
        }
    }

    private fun createRoundedBackground(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun scrollToBottom() {
        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun executeMultipleCommands(commands: List<String>, currentIndex: Int) {
        if (currentIndex >= commands.size) {
            addBotMessage("✅ تم تنفيذ جميع الأوامر!")
            return
        }
        
        val command = commands[currentIndex]
        addBotMessage("▶️ الأمر ${currentIndex + 1}/${commands.size}: \"$command\"")
        
        // تنفيذ الأمر الحالي
        android.os.Handler(mainLooper).postDelayed({
            val response = commandHandler.handleCommand(command)
            
            if (response != null && !response.contains("لم أفهم الأمر")) {
                addBotMessage(response)
            } else {
                addBotMessage("⚠️ لم أفهم الأمر: \"$command\"")
            }
            
            // الانتظار قليلاً ثم تنفيذ الأمر التالي
            android.os.Handler(mainLooper).postDelayed({
                executeMultipleCommands(commands, currentIndex + 1)
            }, 1500) // انتظر 1.5 ثانية بين الأوامر
            
        }, 500)
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }
    
    // ========== Speech Recognition Functions ==========
    
    private fun setupSpeechRecognizer() {
        // تحميل النموذج من المسار المحفوظ
        val prefs = getSharedPreferences("speech_settings", MODE_PRIVATE)
        val modelPath = prefs.getString("model_path", null)
        
        if (modelPath != null) {
            val modelFile = java.io.File(modelPath)
            if (modelFile.exists()) {
                val success = speechRecognizer.loadModelFromFile(modelPath)
                if (!success) {
                    Toast.makeText(this, "⚠️ فشل تحميل النموذج. اختر نموذج من الإعدادات", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "⚠️ ملف النموذج غير موجود. اختر نموذج جديد من الإعدادات", Toast.LENGTH_LONG).show()
            }
        } else {
            // محاولة التحميل من assets (للاختبار فقط)
            val success = speechRecognizer.loadModelFromAssets()
            if (!success) {
                // لا نموذج - المستخدم سيُخبر عند الضغط على الميكروفون
                Log.d("MainActivity", "لا يوجد نموذج محمّل. المستخدم يحتاج لاختيار نموذج من الإعدادات")
            }
        }
        
        // إعداد المستمع
        speechRecognizer.setListener(object : SpeechRecognizer.RecognitionListener {
            override fun onTextRecognized(text: String) {
                runOnUiThread {
                    // إضافة النص إلى حقل الإدخال
                    val currentText = inputField.text.toString()
                    val newText = if (currentText.isBlank()) text else "$currentText $text"
                    inputField.setText(newText)
                    inputField.setSelection(newText.length) // نقل المؤشر للنهاية
                    
                    // إظهار إشعار
                    Toast.makeText(this@MainActivity, "✅ تم التعرف: $text", Toast.LENGTH_SHORT).show()
                }
            }
            
            override fun onError(error: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "❌ $error", Toast.LENGTH_SHORT).show()
                    stopRecordingUI()
                }
            }
            
            override fun onRecordingStarted() {
                runOnUiThread {
                    startRecordingUI()
                }
            }
            
            override fun onRecordingStopped() {
                runOnUiThread {
                    stopRecordingUI()
                }
            }
            
            override fun onVolumeChanged(volume: Float) {
                // يمكن استخدام هذا لتحريك أيقونة الميكروفون
                runOnUiThread {
                    val alpha = 0.5f + (volume * 0.5f)
                    micButton.alpha = alpha.coerceIn(0.5f, 1f)
                }
            }
            
            override fun onModelLoaded(modelName: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "✅ تم تحميل النموذج: $modelName", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }
    
    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }
    
    private fun startRecording() {
        // فحص تحميل النموذج فعلياً
        if (!speechRecognizer.isModelLoaded()) {
            val prefs = getSharedPreferences("speech_settings", MODE_PRIVATE)
            val modelPath = prefs.getString("model_path", null)
            
            if (modelPath == null) {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ النموذج غير محمّل")
                    .setMessage("يرجى اختيار نموذج التعرف الصوتي أولاً من الإعدادات")
                    .setPositiveButton("فتح الإعدادات") { _, _ ->
                        openSettings()
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
                return
            }
            
            // التحقق من وجود الملف ومحاولة تحميله
            val modelFile = java.io.File(modelPath)
            if (!modelFile.exists()) {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ ملف النموذج غير موجود")
                    .setMessage("الملف المحفوظ لم يعد موجوداً. يرجى اختيار النموذج مرة أخرى من الإعدادات")
                    .setPositiveButton("فتح الإعدادات") { _, _ ->
                        openSettings()
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
                return
            }
            
            // محاولة تحميل النموذج الآن
            Toast.makeText(this, "⏳ جاري تحميل النموذج...", Toast.LENGTH_SHORT).show()
            val success = speechRecognizer.loadModelFromFile(modelPath)
            
            if (!success) {
                AlertDialog.Builder(this)
                    .setTitle("❌ فشل تحميل النموذج")
                    .setMessage("تعذر تحميل النموذج. قد يكون الملف تالفاً أو بصيغة غير صحيحة.\n\nيرجى اختيار نموذج آخر من الإعدادات")
                    .setPositiveButton("فتح الإعدادات") { _, _ ->
                        openSettings()
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
                return
            }
            
            Toast.makeText(this, "✅ تم تحميل النموذج بنجاح!", Toast.LENGTH_SHORT).show()
        }
        
        // فحص الأذونات
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            // طلب الإذن
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_CODE
            )
            return
        }
        
        // بدء التسجيل
        speechRecognizer.startRecording()
    }
    
    private fun stopRecording() {
        speechRecognizer.stopRecording()
    }
    
    private fun startRecordingUI() {
        isRecording = true
        micButton.text = "⏹️" // أيقونة إيقاف
        micButton.setTextColor(0xFFDC3545.toInt()) // أحمر
        inputField.hint = "🎤 جاري التسجيل..."
        Toast.makeText(this, "🎤 بدأ التسجيل", Toast.LENGTH_SHORT).show()
    }
    
    private fun stopRecordingUI() {
        isRecording = false
        micButton.text = "🎤" // أيقونة ميكروفون
        micButton.setTextColor(0xFF075E54.toInt()) // اللون الأصلي
        micButton.alpha = 1f
        inputField.hint = "اكتب رسالتك هنا..."
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "✅ تم منح إذن الميكروفون", Toast.LENGTH_SHORT).show()
                startRecording()
            } else {
                Toast.makeText(this, "❌ تم رفض إذن الميكروفون", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.cleanup()
    }
}
