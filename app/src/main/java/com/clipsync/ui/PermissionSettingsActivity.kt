package com.clipsync.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 权限设置页：所有权限授权入口 + 状态总览。
 */
class PermissionSettingsActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isEmpty()) {
            Toast.makeText(this, "全部权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未授权: ${denied.joinToString()}", Toast.LENGTH_LONG).show()
        }
        updateStatusText()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "权限设置"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 64)
        }

        // ====== 卡片：权限授权 ======
        val permCard = cardLayout()
        permCard.addView(sectionTitle("权限授权", 0xFFF59E0B.toInt()))
        statusText = TextView(this).apply { textSize = 13f }
        permCard.addView(statusText, marginParams(16))

        permCard.addView(coloredButton("短信 & 通知权限", 0xFF6366F1.toInt()) { requestNeededPermissions() }, marginParams(12))
        permCard.addView(coloredButton("通知监听（绕开厂商短信拦截）", 0xFF8B5CF6.toInt()) { openNotificationListenerSettings() }, marginParams(12))
        permCard.addView(coloredButton("关闭电池优化（后台常驻）", 0xFFF97316.toInt()) { requestIgnoreBatteryOptimization() })
        container.addView(permCard, cardParams())

        scroll.addView(container)
        setContentView(scroll)
        updateStatusText()
    }

    override fun onResume() {
        super.onResume()
        updateStatusText()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // MARK: - 权限相关

    private fun neededPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    private fun hasAllPermissions(): Boolean =
        neededPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestNeededPermissions() {
        val missing = neededPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isEmpty()) {
            Toast.makeText(this, "权限已全部授予", Toast.LENGTH_SHORT).show()
            return
        }
        permissionLauncher.launch(missing)
    }

    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已加入电池白名单", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "打开设置失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openNotificationListenerSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, "在列表中找到 ClipSync 并打开开关", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "打开设置失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { it.contains(packageName) }
    }

    private fun updateStatusText() {
        val ok = hasAllPermissions()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val whitelisted = pm.isIgnoringBatteryOptimizations(packageName)
        val notifOk = isNotificationListenerEnabled()
        val ssb = SpannableStringBuilder()
        listOf(
            "短信权限" to ok,
            "通知监听" to notifOk,
            "电池白名单" to whitelisted
        ).forEachIndexed { i, (label, isOk) ->
            if (i > 0) ssb.append("\n")
            val dot = if (isOk) "●" else "○"
            val color = if (isOk) 0xFF22C55E.toInt() else 0xFFEF4444.toInt()
            val tag = if (isOk) "已开启" else "未开启"
            val start = ssb.length
            ssb.append("$dot $label · $tag")
            ssb.setSpan(
                ForegroundColorSpan(color),
                start, start + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        statusText.text = ssb
    }

    // MARK: - UI 辅助

    private fun cardLayout(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(0xFFFFFFFF.toInt(), 20f)
        setPadding(32, 32, 32, 40)
        elevation = 4f
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = 32 }

    private fun marginParams(bottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = bottom }

    private fun sectionTitle(text: String, color: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 20)
        }
        val bar = View(this).apply {
            background = roundedBg(color, 4f)
            layoutParams = LinearLayout.LayoutParams(12, 48)
        }
        val title = TextView(this).apply {
            this.text = text
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF1F2937.toInt())
            setPadding(16, 0, 0, 0)
        }
        row.addView(bar)
        row.addView(title)
        return row
    }

    private fun coloredButton(text: String, bgColor: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            background = roundedBg(bgColor, 16f)
            setPadding(0, 32, 0, 32)
            textSize = 14f
            setOnClickListener { onClick() }
        }
    }

    private fun roundedBg(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }
}
