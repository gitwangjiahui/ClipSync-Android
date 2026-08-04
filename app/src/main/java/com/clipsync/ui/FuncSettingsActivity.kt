package com.clipsync.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.clipsync.BuildConfig
import com.clipsync.clipboard.ClipboardManagerHelper
import com.clipsync.crypto.PayloadCipher
import com.clipsync.net.AuthClient
import com.clipsync.service.SyncService
import kotlinx.coroutines.launch

/**
 * 功能设置页：服务器地址、账号登录（用户名/密码换 Token）、
 * 端到端加密同步密码、剪贴板同步开关。
 *
 * Token 不再手填：登录成功后由服务端签发并写入 SharedPreferences。
 */
class FuncSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "功能设置"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val sp = getSharedPreferences("clipsync", MODE_PRIVATE)
        val server = sp.getString("server", null) ?: BuildConfig.DEFAULT_SERVER
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 64)
        }

        // ====== 卡片：账号（用户名 + 密码换 Token） ======
        val connCard = cardLayout()
        connCard.addView(sectionTitle("账号", 0xFF3B82F6.toInt()))

        val serverEdit = EditText(this).apply {
            hint = "服务器地址 (ws://...)"
            setText(server)
            background = roundedBg(0xFFF3F4F6.toInt(), 12f)
            setPadding(24, 20, 24, 20)
        }
        serverEdit.addTextChangedListener(persistWatcher(sp, "server"))
        connCard.addView(serverEdit, marginParams(16))

        val usernameEdit = EditText(this).apply {
            hint = "用户名"
            setText(
                AuthClient.savedUsername(this@FuncSettingsActivity)
                    .ifEmpty { BuildConfig.DEFAULT_USERNAME }
            )
            inputType = InputType.TYPE_CLASS_TEXT
            background = roundedBg(0xFFF3F4F6.toInt(), 12f)
            setPadding(24, 20, 24, 20)
        }
        val passwordEdit = EditText(this).apply {
            hint = "密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = roundedBg(0xFFF3F4F6.toInt(), 12f)
            setPadding(24, 20, 24, 20)
        }
        connCard.addView(usernameEdit, marginParams(12))
        connCard.addView(passwordEdit, marginParams(12))

        // 登录状态提示：已登录显示账号名，未登录提示先登录
        val statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, 12)
        }
        connCard.addView(statusText)
        refreshLoginStatus(statusText)

        val loginBtn = Button(this).apply { text = "登录" }
        val registerBtn = Button(this).apply { text = "注册" }
        val logoutBtn = Button(this).apply { text = "退出登录" }

        loginBtn.setOnClickListener {
            val name = usernameEdit.text.toString().trim()
            val pwd = passwordEdit.text.toString()
            if (name.isEmpty() || pwd.isEmpty()) {
                toast("请填写用户名和密码")
                return@setOnClickListener
            }
            loginBtn.isEnabled = false
            lifecycleScope.launch {
                try {
                    val session = AuthClient.login(
                        serverEdit.text.toString().trim(), name, pwd
                    )
                    AuthClient.saveSession(this@FuncSettingsActivity, session)
                    passwordEdit.setText("")
                    toast(
                        if (session.reused)
                            "登录成功：已有 ${session.onlineDevices} 台设备在线，复用同一 Token"
                        else
                            "登录成功：已签发新 Token"
                    )
                    refreshLoginStatus(statusText)
                    // 重启同步服务，让新 token 生效
                    SyncService.restart(this@FuncSettingsActivity)
                } catch (e: Exception) {
                    toast(e.message ?: "登录失败")
                } finally {
                    loginBtn.isEnabled = true
                }
            }
        }

        registerBtn.setOnClickListener {
            val name = usernameEdit.text.toString().trim()
            val pwd = passwordEdit.text.toString()
            if (name.isEmpty() || pwd.isEmpty()) {
                toast("请填写用户名和密码")
                return@setOnClickListener
            }
            registerBtn.isEnabled = false
            lifecycleScope.launch {
                try {
                    AuthClient.register(serverEdit.text.toString().trim(), name, pwd)
                    toast("注册成功，请点「登录」")
                } catch (e: Exception) {
                    toast(e.message ?: "注册失败")
                } finally {
                    registerBtn.isEnabled = true
                }
            }
        }

        logoutBtn.setOnClickListener {
            val currentServer = serverEdit.text.toString().trim()
            val token = AuthClient.savedToken(this)
            AuthClient.clearSession(this)
            refreshLoginStatus(statusText)
            toast("已退出登录")
            lifecycleScope.launch { AuthClient.logout(currentServer, token) }
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(loginBtn, rowItemParams())
            addView(registerBtn, rowItemParams())
            addView(logoutBtn, rowItemParams())
        }
        connCard.addView(btnRow, marginParams(12))

        val autoConnectCb = CheckBox(this).apply {
            text = "启动时自动连接并开始同步"
            isChecked = sp.getBoolean("auto_connect", true)
            setOnCheckedChangeListener { _, checked ->
                sp.edit().putBoolean("auto_connect", checked).apply()
            }
        }
        connCard.addView(autoConnectCb)
        container.addView(connCard, cardParams())

        // ====== 卡片：端到端加密 ======
        val cryptoCard = cardLayout()
        cryptoCard.addView(sectionTitle("端到端加密", 0xFF8B5CF6.toInt()))

        val fingerprintText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF6B7280.toInt())
        }

        val syncPwdEdit = EditText(this).apply {
            hint = "同步密码（两端需填一致）"
            setText(PayloadCipher.syncPassword(this@FuncSettingsActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = roundedBg(0xFFF3F4F6.toInt(), 12f)
            setPadding(24, 20, 24, 20)
        }
        syncPwdEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                PayloadCipher.setSyncPassword(this@FuncSettingsActivity, s?.toString() ?: "")
                refreshFingerprint(fingerprintText)
            }
        })

        val e2eeCb = CheckBox(this).apply {
            text = "启用端到端加密（服务端只转发密文）"
            isChecked = PayloadCipher.isEnabled(this@FuncSettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                PayloadCipher.setEnabled(this@FuncSettingsActivity, checked)
                syncPwdEdit.isEnabled = checked
                refreshFingerprint(fingerprintText)
            }
        }
        syncPwdEdit.isEnabled = e2eeCb.isChecked

        cryptoCard.addView(e2eeCb, marginParams(12))
        cryptoCard.addView(syncPwdEdit, marginParams(12))
        cryptoCard.addView(fingerprintText)
        refreshFingerprint(fingerprintText)
        container.addView(cryptoCard, cardParams())

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

    // MARK: - 状态刷新

    /** 已登录显示账号名，未登录提示先登录 */
    private fun refreshLoginStatus(view: TextView) {
        if (AuthClient.isLoggedIn(this)) {
            view.text = "已登录：${AuthClient.savedUsername(this)}（Token 由服务端签发）"
            view.setTextColor(0xFF059669.toInt())
        } else {
            view.text = "未登录：请填写用户名和密码后点「登录」"
            view.setTextColor(0xFFDC2626.toInt())
        }
    }

    /** 展示密钥指纹，方便和电脑端比对是否一致 */
    private fun refreshFingerprint(view: TextView) {
        val active = PayloadCipher.isActive(this)
        val fp = if (active) PayloadCipher.fingerprint(PayloadCipher.syncPassword(this)) else null
        view.text = when {
            !PayloadCipher.isEnabled(this) -> "加密已关闭：消息将以明文发送"
            fp == null -> "未设置同步密码：消息将以明文发送"
            else -> "密钥指纹 $fp（两端一致才能互相解密）"
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun rowItemParams() = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f
    ).apply { rightMargin = 12 }

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
