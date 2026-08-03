package com.clipsync.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.clipsync.BuildConfig
import com.clipsync.clipboard.ClipboardManagerHelper

/**
 * 功能设置页：服务器地址、Token、剪贴板同步开关。
 */
class FuncSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "功能设置"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val sp = getSharedPreferences("clipsync", MODE_PRIVATE)
        val server = sp.getString("server", null) ?: BuildConfig.DEFAULT_SERVER
        val token = sp.getString("token", null) ?: BuildConfig.DEFAULT_TOKEN

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 64)
        }

        // ====== 卡片：连接配置 ======
        val connCard = cardLayout()
        connCard.addView(sectionTitle("连接配置", 0xFF3B82F6.toInt()))
        val serverEdit = EditText(this).apply {
            hint = "服务器地址 (ws://...)"
            setText(server)
            background = roundedBg(0xFFF3F4F6.toInt(), 12f)
            setPadding(24, 20, 24, 20)
        }
        val tokenEdit = EditText(this).apply {
            hint = "Token"
            setText(token)
            background = roundedBg(0xFFF3F4F6.toInt(), 12f)
            setPadding(24, 20, 24, 20)
        }
        serverEdit.addTextChangedListener(persistWatcher(sp, "server"))
        tokenEdit.addTextChangedListener(persistWatcher(sp, "token"))
        connCard.addView(serverEdit, marginParams(16))
        connCard.addView(tokenEdit, marginParams(8))

        val autoConnectCb = CheckBox(this).apply {
            text = "启动时自动连接并开始同步"
            isChecked = sp.getBoolean("auto_connect", true)
            setOnCheckedChangeListener { _, checked ->
                sp.edit().putBoolean("auto_connect", checked).apply()
            }
        }
        connCard.addView(autoConnectCb)
        container.addView(connCard, cardParams())

        // ====== 卡片：剪贴板同步 ======
        val clipCard = cardLayout()
        clipCard.addView(sectionTitle("剪贴板同步", 0xFF10B981.toInt()))
        ClipboardManagerHelper.loadPrefs(this)

        val autoApplyCb = CheckBox(this).apply {
            text = "自动应用远端剪贴板到本机"
            isChecked = ClipboardManagerHelper.autoApplyEnabled
            setOnCheckedChangeListener { _, checked ->
                ClipboardManagerHelper.autoApplyEnabled = checked
                ClipboardManagerHelper.savePrefs(this@FuncSettingsActivity)
            }
        }
        val uploadCb = CheckBox(this).apply {
            text = "自动推送剪贴板到电脑（关闭后只能手动点「推送剪切板」）"
            isChecked = ClipboardManagerHelper.uploadEnabled
            setOnCheckedChangeListener { _, checked ->
                ClipboardManagerHelper.uploadEnabled = checked
                ClipboardManagerHelper.savePrefs(this@FuncSettingsActivity)
            }
        }
        clipCard.addView(autoApplyCb, marginParams(12))
        clipCard.addView(uploadCb)
        container.addView(clipCard, cardParams())

        scroll.addView(container)
        setContentView(scroll)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
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

    private fun roundedBg(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }

    private fun persistWatcher(
        sp: android.content.SharedPreferences,
        key: String
    ): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            sp.edit().putString(key, s?.toString()?.trim() ?: "").apply()
        }
    }
}
