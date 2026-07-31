package com.clipsync

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.clipsync.service.SyncService
import com.clipsync.state.ConnectionBus
import com.clipsync.ui.HistoryActivity
import com.clipsync.ui.SettingsActivity

/**
 * 主界面：只显示连接状态 + 操作按钮 + 历史记录入口。
 * - 右上角齿轮 → 设置
 * - 底部"历史记录"按钮 → HistoryActivity（内部筛选短信/剪贴板）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: TextView
    private lateinit var statusText: TextView
    private lateinit var statusHint: TextView
    private lateinit var toggleBtn: Button
    private lateinit var targetText: TextView

    private var dotAnimator: ObjectAnimator? = null
    private var lastWasConnecting = false

    private val stateListener: (String) -> Unit = { state -> renderState(state) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ClipSync"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(64, 96, 64, 64)
        }

        // 大字号连接状态
        statusDot = TextView(this).apply {
            text = "●"
            textSize = 42f
            setPadding(0, 0, 0, 8)
        }
        statusText = TextView(this).apply {
            text = "未连接"
            textSize = 22f
            setPadding(0, 0, 0, 6)
        }
        statusHint = TextView(this).apply {
            text = " "
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }
        root.addView(statusDot, centerParams())
        root.addView(statusText, centerParams())
        root.addView(statusHint, centerParams())

        // 连接目标（服务器地址）—— 圆角卡片，居中，图标 + 链接文本
        targetText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF374151.toInt())
            gravity = Gravity.CENTER
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f
                setColor(0xFFF3F4F6.toInt())
            }
            background = bg
            setPadding(28, 16, 28, 16)
        }
        renderTarget()
        root.addView(targetText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = 32
        })

        // 启动/停止 二合一按钮
        toggleBtn = styledButton("启动同步服务", 0xFF22C55E.toInt(), 0xFFFFFFFF.toInt()) { toggleSync() }
        root.addView(toggleBtn, buttonParams())

        // 历史记录按钮 — 蓝色
        val historyBtn = styledButton("历史记录", 0xFF3B82F6.toInt(), 0xFFFFFFFF.toInt()) {
            startActivity(Intent(this@MainActivity, HistoryActivity::class.java))
        }
        root.addView(historyBtn, buttonParams())

        setContentView(root)
    }

    /** 创建带圆角背景的彩色按钮 */
    private fun styledButton(text: String, bgColor: Int, textColor: Int, onClick: () -> Unit): Button {
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f
            setColor(bgColor)
        }
        return Button(this).apply {
            this.text = text
            setTextColor(textColor)
            background = drawable
            setPadding(0, 36, 0, 36)
            textSize = 15f
            setOnClickListener { onClick() }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 100, 0, "设置")?.apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setIcon(android.R.drawable.ic_menu_manage)
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            100 -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        ConnectionBus.addListener(stateListener)
        // 每次回到主界面刷新一下"连接目标"，用户改完设置回来能立即看到新地址
        renderTarget()
        // 切回前台时：重新注册剪贴板监听 + 立即读取一次并上传。
        // 这样用户"别的 App 复制 → 切回 ClipSync"就能自动推送，无需无障碍权限。
        com.clipsync.clipboard.ClipboardManagerHelper.onForeground()
    }

    override fun onPause() {
        super.onPause()
        ConnectionBus.removeListener(stateListener)
        stopDotAnimation()
    }

    private fun renderState(state: String) {
        when (state) {
            ConnectionBus.STATE_OPEN -> {
                stopDotAnimation()
                statusDot.setTextColor(0xFF22C55E.toInt())
                statusText.text = "已连接"
                statusHint.text = "同步中，可正常收发消息"
                updateToggleBtn(ConnectionBus.STATE_OPEN)
            }
            ConnectionBus.STATE_CONNECTING -> {
                startDotAnimation()
                statusDot.setTextColor(0xFFF59E0B.toInt())
                statusText.text = "连接中"
                statusHint.text = "正在连接服务器…"
                updateToggleBtn(ConnectionBus.STATE_CONNECTING)
            }
            else -> {
                stopDotAnimation()
                statusDot.setTextColor(0xFF9CA3AF.toInt())
                statusText.text = "未连接"
                if (lastWasConnecting) {
                    statusHint.text = "连接失败，请检查网络或服务器后再次启动"
                    Toast.makeText(this, "连接失败", Toast.LENGTH_SHORT).show()
                } else {
                    statusHint.text = "请检查网络或到设置里检查配置"
                }
                updateToggleBtn(ConnectionBus.STATE_CLOSED)
            }
        }
        lastWasConnecting = (state == ConnectionBus.STATE_CONNECTING)
    }

    /** 从设置读取当前服务器地址并渲染到主界面 */
    private fun renderTarget() {
        val sp = getSharedPreferences("clipsync", MODE_PRIVATE)
        val server = sp.getString("server", null) ?: com.clipsync.BuildConfig.DEFAULT_SERVER
        val token = sp.getString("token", null) ?: com.clipsync.BuildConfig.DEFAULT_TOKEN
        val tokenTip = if (token.isBlank()) "  ·  ⚠ 未配置 token" else ""
        targetText.text = "🔗  $server$tokenTip"
    }

    private fun updateToggleBtn(state: String) {
        when (state) {
            ConnectionBus.STATE_OPEN -> {
                toggleBtn.text = "停止同步服务"
                (toggleBtn.background as? GradientDrawable)?.setColor(0xFFEF4444.toInt())
                toggleBtn.isEnabled = true
            }
            ConnectionBus.STATE_CONNECTING -> {
                toggleBtn.text = "取消连接"
                (toggleBtn.background as? GradientDrawable)?.setColor(0xFFEF4444.toInt())
                toggleBtn.isEnabled = true
            }
            else -> {
                toggleBtn.text = "启动同步服务"
                (toggleBtn.background as? GradientDrawable)?.setColor(0xFF22C55E.toInt())
                toggleBtn.isEnabled = true
            }
        }
    }

    private fun startDotAnimation() {
        if (dotAnimator?.isStarted == true) return
        dotAnimator = ObjectAnimator.ofFloat(statusDot, "alpha", 1f, 0.15f).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopDotAnimation() {
        dotAnimator?.cancel()
        dotAnimator = null
        statusDot.alpha = 1f
    }

    private fun toggleSync() {
        val current = ConnectionBus.current
        if (current == ConnectionBus.STATE_OPEN || current == ConnectionBus.STATE_CONNECTING) {
            stopSync()
        } else {
            // 点击后立即切换到"连接中"，给用户即时反馈
            renderState(ConnectionBus.STATE_CONNECTING)
            startSync()
        }
    }

    private fun startSync() {
        val intent = Intent(this, SyncService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopSync() {
        stopService(Intent(this, SyncService::class.java))
        // 立即把 UI 切回未连接，不用等服务 publish CLOSED
        renderState(ConnectionBus.STATE_CLOSED)
        lastWasConnecting = false
    }

    private fun centerParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { gravity = Gravity.CENTER_HORIZONTAL }

    private fun buttonParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = 16 }
}
