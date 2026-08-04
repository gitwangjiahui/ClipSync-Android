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
 * 没有登录 / 注册按钮：账号密码存在本地，连接时自动换 token。
 * 账号由管理员在服务端创建（后续做后台管理界面）。
 */
class FuncSettingsActivity : AppCompatActivity() {

    /** 进入本页时的账号密码，离开时用来判断要不要重连 */
    private var credentialsAtEntry: Pair<String, String> = "" to ""

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
            setText(AuthClient.savedPassword(this@FuncSettingsActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = roundedBg(0xFFF3F4F6.toInt(), 12f)
            setPadding(24, 20, 24, 20)
        }
        connCard.addView(usernameEdit, marginParams(12))
        connCard.addView(passwordEdit, marginParams(12))

        // 状态提示：账密是否填全、当前是否已拿到 token
        val statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, 12)
        }
        connCard.addView(statusText)
        refreshLoginStatus(statusText)

        // 账号密码边填边存，连接时自动校验，所以没有「登录」按钮。
        // 改了账号或密码就作废本地旧 token，否则下次连接还会拿旧身份去连。
        val onCredentialChanged = {
            val name = usernameEdit.text.toString().trim()
            val pwd = passwordEdit.text.toString()
            if (name != AuthClient.savedUsername(this) || pwd != AuthClient.savedPassword(this)) {
                AuthClient.clearSession(this)
            }
            AuthClient.saveCredentials(this, name, pwd)
            refreshLoginStatus(statusText)
        }
        usernameEdit.addTextChangedListener(afterTextChanged(onCredentialChanged))
        passwordEdit.addTextChangedListener(afterTextChanged(onCredentialChanged))
        credentialsAtEntry = AuthClient.savedUsername(this) to AuthClient.savedPassword(this)

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

    /**
     * 三态提示：已拿到 token / 账密已填待连接时校验 / 账密没填全。
     * 账号由管理员在服务端创建，客户端不提供注册入口。
     */
    private fun refreshLoginStatus(view: TextView) {
        when {
            AuthClient.isLoggedIn(this) -> {
                view.text = "已登录：${AuthClient.savedUsername(this)}（Token 由服务端签发）"
                view.setTextColor(0xFF059669.toInt())
            }
            AuthClient.hasCredentials(this) -> {
                view.text = "账号密码已保存，连接时会自动校验"
                view.setTextColor(0xFF2563EB.toInt())
            }
            else -> {
                view.text = "请填写用户名和密码（账号由管理员创建）"
                view.setTextColor(0xFFDC2626.toInt())
            }
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

    /** 只关心"输入完成"这一刻的 TextWatcher 简写 */
    private fun afterTextChanged(action: () -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = action()
    }

    /**
     * 离开设置页时，如果账号密码变了就重启同步服务，让它用新凭据重新换 token。
     *
     * 放在 onPause 而不是每次输入回调里，是为了避免边打字边重连。
     */
    override fun onPause() {
        super.onPause()
        val now = AuthClient.savedUsername(this) to AuthClient.savedPassword(this)
        if (now != credentialsAtEntry) {
            credentialsAtEntry = now
            if (SyncService.activeWs() != null) {
                SyncService.restart(this)
            }
        }
    }
}
