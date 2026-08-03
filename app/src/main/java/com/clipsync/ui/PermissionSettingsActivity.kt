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

        // ====== 单卡片：所有权限 ======
        val permCard = cardLayout()
        permCard.addView(sectionTitle("权限授权", 0xFFF59E0B.toInt()))
        statusText = TextView(this).apply { textSize = 13f }
        permCard.addView(statusText, marginParams(16))

        permCard.addView(hintText(
            "小米/红米手机必须开启以下全部权限，否则短信推送无法工作：\n" +
            "1. 短信 & 通知权限 —— 读取短信内容\n" +
            "2. 通知使用权 —— 绕开厂商短信广播拦截\n" +
            "3. 自启动权限（MIUI 关键）—— 让系统允许绑定通知监听服务\n" +
            "4. 关闭电池优化 / 省电策略设为「无限制」—— 防止后台被冻结"
        ), marginParams(16))

        permCard.addView(coloredButton("短信 & 通知权限", 0xFF6366F1.toInt()) { requestNeededPermissions() }, marginParams(12))
        permCard.addView(coloredButton("通知监听（绕开厂商短信拦截）", 0xFF8B5CF6.toInt()) { openNotificationListenerSettings() }, marginParams(12))
        permCard.addView(coloredButton("自启动管理（MIUI 关键）", 0xFFDC2626.toInt()) { openMiuiAutoStartSettings() }, marginParams(12))
        permCard.addView(coloredButton("关闭电池优化 / 省电策略", 0xFFF97316.toInt()) { requestIgnoreBatteryOptimization() }, marginParams(12))
        permCard.addView(coloredButton("重新触发通知监听绑定", 0xFFEF4444.toInt()) { rebindNotificationListener() })
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
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            @Suppress("DEPRECATION")
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
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

    /**
     * 跳转 MIUI/HyperOS 自启动管理页面。
     * 不同 MIUI 版本的 Activity 名略有差异，按优先级尝试，全部失败则打开安全中心首页。
     *
     * 这是短信推送不工作的最常见根因：MIUI 的 AutoStartManagerService 会拦截
     * NotificationListenerService 的绑定，导致 onListenerConnected 永远不会被调用。
     */
    private fun openMiuiAutoStartSettings() {
        val candidates = listOf(
            // HyperOS / MIUI 14+
            Intent().setClassName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            // MIUI 12/13
            Intent().setClassName("com.miui.securitycenter",
                "com.miui.permcenter.activity.AutoStartManagementActivity"),
            // 旧版 MIUI
            Intent().setClassName("com.miui.securitycenter",
                "com.miui.securitycenter.autostart.AutoStartManagementActivity")
        )
        for (intent in candidates) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (packageManager.resolveActivity(intent, 0) != null) {
                    startActivity(intent)
                    Toast.makeText(this,
                        "请在列表中找到 ClipSync 并打开自启动开关",
                        Toast.LENGTH_LONG).show()
                    return
                }
            } catch (_: Exception) { /* 尝试下一个 */ }
        }
        // 兜底：打开安全中心首页
        try {
            val fallback = Intent().setClassName("com.miui.securitycenter",
                "com.miui.permcenter.activity.MainActivity")
            fallback.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(fallback)
            Toast.makeText(this,
                "请在安全中心 → 应用管理 → 自启动管理中开启 ClipSync",
                Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this,
                "无法打开 MIUI 自启动管理，请手动进入：设置 → 应用设置 → 应用管理 → ClipSync → 自启动",
                Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 重新触发系统对 NotificationListenerService 的绑定。
     *
     * 场景：用户刚开了自启动权限，但系统不会主动重新绑定 listener，
     * 需要调用 requestRebind 主动触发一次绑定请求。
     * 也可以让用户在通知使用权页面「关掉再打开」开关达到同样效果。
     */
    private fun rebindNotificationListener() {
        val cn = android.content.ComponentName(this, com.clipsync.sms.NotificationSmsListener::class.java)
        try {
            // 反射调用 requestRebind（API 25+，部分系统对第三方隐藏）
            val service = getSystemService("notification")
            val method = service.javaClass.getMethod("requestRebind", android.content.ComponentName::class.java)
            method.invoke(service, cn)
            Toast.makeText(this,
                "已请求重新绑定通知监听，请等待几秒后查看状态",
                Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // 兜底：引导用户手动操作
            openNotificationListenerSettings()
            Toast.makeText(this,
                "请把 ClipSync 的通知使用权开关「关掉再打开」以触发绑定",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { it.contains(packageName) }
    }

    /**
     * 检查 NotificationListenerService 是否已被系统实际绑定（处于 Live 状态）。
     * 仅 enabled=true 还不够，MIUI 可能拦截绑定请求，需确认 listener 真的连上。
     */
    private fun isNotificationListenerLive(): Boolean {
        return try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE)
            // NotificationManager.getActiveNotificationListeners()（API 25+）
            val method = nm.javaClass.getMethod("getActiveNotificationListeners")
            @Suppress("UNCHECKED_CAST")
            val list = method.invoke(nm) as? List<android.content.ComponentName>
            list?.any { it.packageName == packageName } == true
        } catch (e: Exception) {
            // 退回到 enabled 标志位检查
            isNotificationListenerEnabled()
        }
    }

    private fun updateStatusText() {
        val smsOk = hasAllPermissions()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val notifEnabled = isNotificationListenerEnabled()
        val notifLive = isNotificationListenerLive()
        // MIUI 自启动权限无法通过 API 直接查询，根据 listener 是否实际连上来反推
        val autoStartOk = notifLive

        val ssb = SpannableStringBuilder()
        listOf(
            "短信 & 通知权限" to smsOk,
            "通知使用权" to notifEnabled,
            "通知监听已连接" to notifLive,
            "自启动（MIUI）" to autoStartOk,
            "电池白名单" to batteryOk
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
        // 若通知监听授权了但没连上，额外提示
        if (notifEnabled && !notifLive) {
            ssb.append("\n⚠ 通知监听授权但未连上，多半是自启动被拦，请点「自启动管理」开启后再点「重新触发通知监听绑定」")
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

    private fun hintText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0xFF6B7280.toInt())
        setLineSpacing(0f, 1.4f)
        background = roundedBg(0xFFF3F4F6.toInt(), 12f)
        setPadding(24, 20, 24, 20)
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
