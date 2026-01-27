package com.awab.ai

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class AppNamesActivity : AppCompatActivity() {

    private lateinit var searchField: EditText
    private lateinit var appsContainer: LinearLayout
    private val customNames = mutableMapOf<String, MutableList<String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        loadCustomNames()
        setupUI()
        loadInstalledApps()
    }

    private fun setupUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F5F5.toInt())
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF075E54.toInt())
            setPadding(20, 40, 20, 20)
        }

        val backButton = Button(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            background = null
            setOnClickListener { finish() }
        }

        val title = TextView(this).apply {
            text = "أسماء التطبيقات المخصصة"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = 20
            }
        }

        header.addView(backButton)
        header.addView(title)

        searchField = EditText(this).apply {
            hint = "🔍 ابحث عن تطبيق..."
            textSize = 16f
            setPadding(20, 16, 20, 16)
            background = createRoundedBackground(0xFFFFFFFF.toInt(), 12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(20, 20, 20, 10)
            }
            
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filterApps(s.toString())
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        val scrollView = ScrollView(this)
        appsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 10, 20, 20)
        }

        scrollView.addView(appsContainer)
        rootLayout.addView(header)
        rootLayout.addView(searchField)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
    }

    private fun loadInstalledApps() {
        appsContainer.removeAllViews()
        
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { 
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || 
                it.packageName.contains("whatsapp") ||
                it.packageName.contains("youtube") ||
                it.packageName.contains("camera")
            }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        for (app in apps) {
            val card = createAppCard(app, pm)
            appsContainer.addView(card)
        }
    }

    private fun createAppCard(app: ApplicationInfo, pm: PackageManager): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedBackground(0xFFFFFFFF.toInt(), 12f)
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 12)
            }
            tag = app.loadLabel(pm).toString()
        }

        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val icon = ImageView(this).apply {
            setImageDrawable(app.loadIcon(pm))
            layoutParams = LinearLayout.LayoutParams(48, 48).apply {
                marginEnd = 12
            }
        }

        val appName = TextView(this).apply {
            text = app.loadLabel(pm).toString()
            textSize = 16f
            setTextColor(0xFF000000.toInt())
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val addButton = Button(this).apply {
            text = "+ إضافة"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            background = createRoundedBackground(0xFF075E54.toInt(), 8f)
            setPadding(20, 12, 20, 12)
            setOnClickListener {
                showAddNameDialog(app.packageName, app.loadLabel(pm).toString())
            }
        }

        infoRow.addView(icon)
        infoRow.addView(appName)
        infoRow.addView(addButton)
        card.addView(infoRow)

        val names = customNames[app.packageName] ?: emptyList()
        if (names.isNotEmpty()) {
            val namesText = TextView(this).apply {
                text = "الأسماء: ${names.joinToString(", ")}"
                textSize = 12f
                setTextColor(0xFF2E7D32.toInt())
                setPadding(0, 8, 0, 0)
            }
            card.addView(namesText)
        }

        return card
    }

    private fun showAddNameDialog(packageName: String, appName: String) {
        val input = EditText(this).apply {
            hint = "مثال: واتس، وتس، whats"
            setPadding(16, 16, 16, 16)
        }

        AlertDialog.Builder(this)
            .setTitle("إضافة اسم لـ: $appName")
            .setMessage("يمكنك إضافة عدة أسماء مفصولة بفاصلة")
            .setView(input)
            .setPositiveButton("إضافة") { _, _ ->
                val names = input.text.toString()
                    .split(",", "،")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                
                for (name in names) {
                    addCustomName(packageName, name)
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun addCustomName(packageName: String, name: String) {
        if (!customNames.containsKey(packageName)) {
            customNames[packageName] = mutableListOf()
        }
        
        if (!customNames[packageName]!!.contains(name)) {
            customNames[packageName]!!.add(name)
            saveCustomNames()
            loadInstalledApps()
            Toast.makeText(this, "✅ تم إضافة: $name", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "⚠️ الاسم موجود مسبقاً", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCustomNames() {
        val prefs = getSharedPreferences("app_names", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        val jsonString = customNames.entries.joinToString(";") { (pkg, names) ->
            "$pkg:${names.joinToString(",")}"
        }
        
        editor.putString("custom_names", jsonString)
        editor.apply()
    }

    private fun loadCustomNames() {
        val prefs = getSharedPreferences("app_names", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("custom_names", "") ?: ""
        
        customNames.clear()
        
        if (jsonString.isNotBlank()) {
            jsonString.split(";").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val packageName = parts[0]
                    val names = parts[1].split(",").toMutableList()
                    customNames[packageName] = names
                }
            }
        }
    }

    private fun filterApps(query: String) {
        for (i in 0 until appsContainer.childCount) {
            val card = appsContainer.getChildAt(i) as? LinearLayout
            val appName = card?.tag as? String ?: ""
            
            card?.visibility = if (appName.contains(query, ignoreCase = true)) {
                LinearLayout.VISIBLE
            } else {
                LinearLayout.GONE
            }
        }
    }

    private fun createRoundedBackground(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    companion object {
        fun getCustomNames(context: Context): Map<String, List<String>> {
            val prefs = context.getSharedPreferences("app_names", Context.MODE_PRIVATE)
            val jsonString = prefs.getString("custom_names", "") ?: ""
            
            val customNames = mutableMapOf<String, List<String>>()
            
            if (jsonString.isNotBlank()) {
                jsonString.split(";").forEach { entry ->
                    val parts = entry.split(":")
                    if (parts.size == 2) {
                        val packageName = parts[0]
                        val names = parts[1].split(",")
                        customNames[packageName] = names
                    }
                }
            }
            
            return customNames
        }
    }
}
