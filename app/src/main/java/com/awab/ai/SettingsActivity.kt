package com.awab.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var modelStatusTextView: TextView
    private val permissionList = mutableListOf<String>()
    
    // File picker for model selection
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleModelFile(it) }
    }
    
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach {
            val permissionName = it.key.substringAfterLast(".")
            val isGranted = it.value
            logStatus("${if (isGranted) "✓" else "✗"} $permissionName")
        }
        updatePermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(32, 32, 32, 32)
            setBackgroundColor(0xFFF5F5F5.toInt())
        }

        // عنوان الصفحة
        val titleText = TextView(this).apply {
            text = "⚙️ إعدادات الأذونات"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 32)
        }

        // حالة الأذونات
        statusTextView = TextView(this).apply {
            text = "جاري التحميل..."
            textSize = 14f
            setPadding(16, 16, 16, 32)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }

        // حالة النموذج الصوتي
        modelStatusTextView = TextView(this).apply {
            text = getModelStatus()
            textSize = 14f
            setTextColor(0xFF495057.toInt())
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFFE9ECEF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }

        // زر طلب الأذونات العادية
        val requestPermissionsButton = createStyledButton("طلب الأذونات العادية") {
            requestAllPermissionsInBatches()
        }

        // زر الأذونات الخاصة
        val specialPermissionsButton = createStyledButton("طلب الأذونات الخاصة") {
            requestSpecialPermissions()
        }

        // زر إمكانية الوصول
        val accessibilityButton = createStyledButton("فتح إعدادات إمكانية الوصول") {
            openAccessibilitySettings()
        }

        // زر أسماء التطبيقات المخصصة
        val appNamesButton = createStyledButton("📝 أسماء التطبيقات المخصصة", 0xFF2196F3.toInt()) {
            startActivity(Intent(this, AppNamesActivity::class.java))
        }

        // زر اختيار النموذج الصوتي
        val modelPickerButton = createStyledButton("🎤 اختيار نموذج التعرف الصوتي", 0xFF9C27B0.toInt()) {
            openModelPicker()
        }
        
        // زر اختبار النموذج
        val testModelButton = createStyledButton("🔬 اختبار تحميل النموذج", 0xFFFF9800.toInt()) {
            testModelLoading()
        }

        // زر الرجوع
        val backButton = createStyledButton("← رجوع للمحادثة", 0xFF6C757D.toInt()) {
            finish()
        }

        layout.addView(titleText)
        layout.addView(statusTextView)
        layout.addView(modelStatusTextView)
        layout.addView(requestPermissionsButton)
        layout.addView(specialPermissionsButton)
        layout.addView(accessibilityButton)
        layout.addView(appNamesButton)
        layout.addView(modelPickerButton)
        layout.addView(testModelButton)
        layout.addView(backButton)
        scrollView.addView(layout)
        setContentView(scrollView)

        setupPermissionsList()
        updatePermissionStatus()
    }

    private fun createStyledButton(text: String, bgColor: Int = 0xFF007BFF.toInt(), onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 16f
            setBackgroundColor(bgColor)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(32, 24, 32, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun setupPermissionsList() {
        permissionList.clear()
        
        permissionList.add(Manifest.permission.READ_CALENDAR)
        permissionList.add(Manifest.permission.WRITE_CALENDAR)
        permissionList.add(Manifest.permission.CAMERA)
        permissionList.add(Manifest.permission.READ_CONTACTS)
        permissionList.add(Manifest.permission.WRITE_CONTACTS)
        permissionList.add(Manifest.permission.GET_ACCOUNTS)
        permissionList.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissionList.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        permissionList.add(Manifest.permission.RECORD_AUDIO)
        permissionList.add(Manifest.permission.READ_PHONE_STATE)
        permissionList.add(Manifest.permission.CALL_PHONE)
        permissionList.add(Manifest.permission.READ_CALL_LOG)
        permissionList.add(Manifest.permission.WRITE_CALL_LOG)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissionList.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            permissionList.add(Manifest.permission.BODY_SENSORS)
        }
        
        permissionList.add(Manifest.permission.SEND_SMS)
        permissionList.add(Manifest.permission.RECEIVE_SMS)
        permissionList.add(Manifest.permission.READ_SMS)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionList.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissionList.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissionList.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissionList.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissionList.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                permissionList.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionList.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            permissionList.add(Manifest.permission.ACTIVITY_RECOGNITION)
            permissionList.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionList.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionList.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissionList.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
    }

    private fun requestAllPermissionsInBatches() {
        val permissionsToRequest = permissionList.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            Toast.makeText(this, "جميع الأذونات ممنوحة!", Toast.LENGTH_SHORT).show()
            updatePermissionStatus()
            return
        }

        val batches = permissionsToRequest.chunked(3)
        requestNextBatch(batches, 0)
    }

    private fun requestNextBatch(batches: List<List<String>>, index: Int) {
        if (index >= batches.size) {
            logStatus("\n✅ انتهى طلب الأذونات!")
            updatePermissionStatus()
            return
        }

        val batch = batches[index]
        logStatus("\n--- دفعة ${index + 1}/${batches.size} ---")
        
        requestPermissionsLauncher.launch(batch.toTypedArray())
        
        android.os.Handler(mainLooper).postDelayed({
            requestNextBatch(batches, index + 1)
        }, 2000)
    }

    private fun requestSpecialPermissions() {
        AlertDialog.Builder(this)
            .setTitle("الأذونات الخاصة")
            .setItems(arrayOf(
                "رسم فوق التطبيقات الأخرى",
                "تعديل إعدادات النظام",
                "إدارة جميع الملفات",
                "تثبيت الحزم",
                "تجاهل تحسين البطارية"
            )) { _, which ->
                when (which) {
                    0 -> requestOverlayPermission()
                    1 -> requestWriteSettingsPermission()
                    2 -> requestManageStoragePermission()
                    3 -> requestInstallPackagesPermission()
                    4 -> requestBatteryOptimizationPermission()
                }
            }
            .show()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            } else {
                Toast.makeText(this, "الإذن ممنوح بالفعل", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                startActivity(intent)
            } else {
                Toast.makeText(this, "الإذن ممنوح بالفعل", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }
    }

    private fun requestInstallPackagesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                startActivity(intent)
            } else {
                Toast.makeText(this, "الإذن ممنوح بالفعل", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestBatteryOptimizationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "قم بتفعيل خدمة أواب AI", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "خطأ في فتح الإعدادات", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePermissionStatus() {
        val sb = StringBuilder()
        var granted = 0
        var denied = 0
        
        permissionList.forEach { permission ->
            val isGranted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            if (isGranted) granted++ else denied++
        }
        
        sb.append("📊 إحصائيات الأذونات\n")
        sb.append("─".repeat(30))
        sb.append("\n\n")
        sb.append("✅ ممنوحة: $granted\n")
        sb.append("❌ مرفوضة: $denied\n")
        sb.append("📱 الإجمالي: ${permissionList.size}\n")
        
        statusTextView.text = sb.toString()
    }

    private fun logStatus(message: String) {
        runOnUiThread {
            statusTextView.append("$message\n")
        }
    }
    
    // ========== Model Picker Functions ==========
    
    private fun openModelPicker() {
        AlertDialog.Builder(this)
            .setTitle("اختيار نموذج التعرف الصوتي")
            .setMessage("اختر ملف النموذج بصيغة .tflite من ملفات هاتفك\n\nمثال: sudanese_v15_final.tflite")
            .setPositiveButton("اختيار ملف") { _, _ ->
                filePickerLauncher.launch("*/*")
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
    
    private fun handleModelFile(uri: Uri) {
        try {
            // الحصول على المسار الحقيقي
            val filePath = getRealPathFromURI(uri)
            
            if (filePath == null) {
                Toast.makeText(this, "❌ فشل الحصول على مسار الملف", Toast.LENGTH_SHORT).show()
                return
            }
            
            // حفظ المسار
            saveModelPath(filePath)
            
            // تحديث الحالة
            updateModelStatus("✅ تم اختيار النموذج: ${java.io.File(filePath).name}")
            
            Toast.makeText(this, "✅ تم حفظ النموذج بنجاح!", Toast.LENGTH_LONG).show()
            
        } catch (e: Exception) {
            Toast.makeText(this, "❌ خطأ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun getRealPathFromURI(uri: Uri): String? {
        return try {
            // طريقة 1: محاولة الحصول على المسار المباشر
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val columnIndex = it.getColumnIndex("_data")
                    if (columnIndex != -1) {
                        return it.getString(columnIndex)
                    }
                }
            }
            
            // طريقة 2: نسخ الملف إلى مساحة التطبيق
            val fileName = getFileName(uri) ?: "model.tflite"
            val file = java.io.File(filesDir, fileName)
            
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            file.absolutePath
            
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getFileName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) it.getString(nameIndex) else null
            } else null
        }
    }
    
    private fun saveModelPath(path: String) {
        val prefs = getSharedPreferences("speech_settings", MODE_PRIVATE)
        prefs.edit().putString("model_path", path).apply()
    }
    
    private fun getSavedModelPath(): String? {
        val prefs = getSharedPreferences("speech_settings", MODE_PRIVATE)
        return prefs.getString("model_path", null)
    }
    
    private fun getModelStatus(): String {
        val savedPath = getSavedModelPath()
        return if (savedPath != null) {
            val file = java.io.File(savedPath)
            if (file.exists()) {
                "🎤 النموذج الحالي:\n${file.name}\n\nالحجم: ${file.length() / 1024 / 1024} MB"
            } else {
                "⚠️ لم يتم اختيار نموذج بعد\n\nاضغط الزر أدناه لاختيار النموذج"
            }
        } else {
            "⚠️ لم يتم اختيار نموذج بعد\n\nاضغط الزر أدناه لاختيار النموذج"
        }
    }
    
    private fun updateModelStatus(message: String) {
        modelStatusTextView.text = message
    }
    
    private fun testModelLoading() {
        val prefs = getSharedPreferences("speech_settings", MODE_PRIVATE)
        val modelPath = prefs.getString("model_path", null)
        
        if (modelPath == null) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ لا يوجد نموذج محفوظ")
                .setMessage("لم تقم باختيار نموذج بعد.\n\nاضغط زر 'اختيار نموذج التعرف الصوتي' أولاً.")
                .setPositiveButton("حسناً", null)
                .show()
            return
        }
        
        val file = java.io.File(modelPath)
        val diagnosticInfo = StringBuilder()
        
        diagnosticInfo.append("📁 معلومات الملف:\n")
        diagnosticInfo.append("─".repeat(30)).append("\n\n")
        diagnosticInfo.append("المسار:\n$modelPath\n\n")
        diagnosticInfo.append("الاسم: ${file.name}\n")
        diagnosticInfo.append("موجود: ${if (file.exists()) "✅ نعم" else "❌ لا"}\n")
        
        if (file.exists()) {
            diagnosticInfo.append("الحجم: ${file.length() / 1024} KB\n")
            diagnosticInfo.append("قابل للقراءة: ${if (file.canRead()) "✅ نعم" else "❌ لا"}\n\n")
            
            // محاولة التحميل
            diagnosticInfo.append("🔬 اختبار التحميل:\n")
            diagnosticInfo.append("─".repeat(30)).append("\n\n")
            
            try {
                val testRecognizer = SpeechRecognizer(this)
                testRecognizer.setListener(object : SpeechRecognizer.RecognitionListener {
                    override fun onTextRecognized(text: String) {}
                    override fun onError(error: String) {
                        runOnUiThread {
                            diagnosticInfo.append("❌ خطأ: $error\n")
                            showDiagnosticResult(diagnosticInfo.toString())
                        }
                    }
                    override fun onRecordingStarted() {}
                    override fun onRecordingStopped() {}
                    override fun onVolumeChanged(volume: Float) {}
                    override fun onModelLoaded(modelName: String) {
                        runOnUiThread {
                            diagnosticInfo.append("✅ تم التحميل بنجاح!\n")
                            diagnosticInfo.append("النموذج: $modelName\n\n")
                            diagnosticInfo.append("🎉 النتيجة: النموذج يعمل بشكل صحيح!")
                            showDiagnosticResult(diagnosticInfo.toString())
                        }
                    }
                })
                
                val success = testRecognizer.loadModelFromFile(modelPath)
                if (!success) {
                    diagnosticInfo.append("❌ فشل التحميل\n")
                    diagnosticInfo.append("راجع Logcat للمزيد من التفاصيل")
                    showDiagnosticResult(diagnosticInfo.toString())
                }
                
            } catch (e: Exception) {
                diagnosticInfo.append("❌ خطأ: ${e.message}\n")
                showDiagnosticResult(diagnosticInfo.toString())
            }
        } else {
            diagnosticInfo.append("\n⚠️ الملف غير موجود!\n")
            diagnosticInfo.append("\nالحل: اختر النموذج مرة أخرى")
            showDiagnosticResult(diagnosticInfo.toString())
        }
    }
    
    private fun showDiagnosticResult(message: String) {
        AlertDialog.Builder(this)
            .setTitle("🔬 نتيجة الفحص")
            .setMessage(message)
            .setPositiveButton("حسناً", null)
            .setNeutralButton("نسخ", { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("تشخيص النموذج", message)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show()
            })
            .show()
    }
}
