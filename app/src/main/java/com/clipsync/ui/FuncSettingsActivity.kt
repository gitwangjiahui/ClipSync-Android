package com.clipsync.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import com.clipsync.BuildConfig
import com.clipsync.R
import com.clipsync.clipboard.ClipboardManagerHelper
import com.clipsync.crypto.PayloadCipher
import com.clipsync.net.AuthClient
import com.clipsync.net.ServerAddress
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

    /**
     * 进入本页时的连接相关配置快照，离开时用来判断要不要重连。
     * 内容：用户名、登录密码、服务器地址、加密开关、同步密码。
     */
    private var connectionSnapshotAtEntry: List<String> = emptyList()

    /**
     * 指纹计算任务。
     *
     * 派生密钥是 20 万轮 PBKDF2，在主线程上跑会让密码框每敲一个字符卡一下。
     * 所以挪到后台协程，并且下一次输入会取消上一次还没跑完的计算。
     */
    private var fingerprintJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "功能设置"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val sp = getSharedPreferences("clipsync", MODE_PRIVATE)
        val server = sp.getString("server", null) ?: BuildConfig.DEFAULT_SERVER
        // 在建控件之前拍快照：控件的 TextWatcher 一挂上就会回写规范化后的值，
        // 那不是用户的改动，不该被算成"配置变了"
        connectionSnapshotAtEntry = connectionSnapshot()
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 64)
        }

        // ====== 卡片：账号（用户名 + 密码换 Token） ======
        val connCard = cardLayout()
        connCard.addView(sectionTitle("账号", 0xFF3B82F6.toInt()))

        // 地址只填 host:port，ws:// 由程序补齐（ServerAddress.normalize）
        val serverEdit = EditText(this).apply {
            hint = "服务器地址，例如 192.168.1.10:8080"
            setText(ServerAddress.displayForm(server))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = roundedBg(0xFFF3F4F6.toInt(), 12f)
            setPadding(24, 20, 24, 20)
        }
        connCard.addView(serverEdit, marginParams(8))

        // 实时回显程序真正会连的地址，用户不用猜前缀补成了什么
        val serverResolvedText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF6B7280.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(4, 0, 0, 0)
        }
        connCard.addView(serverResolvedText, marginParams(16))

        val refreshServerHint = {
            val normalized = ServerAddress.normalize(serverEdit.text.toString())
            serverResolvedText.text =
                if (normalized.isEmpty()) "请填写服务器地址" else "将连接 $normalized"
        }
        serverEdit.addTextChangedListener(afterTextChanged {
            // 存规范化后的完整地址，读取方（SyncService / AuthClient）拿到的就是可用值
            sp.edit().putString("server", ServerAddress.normalize(serverEdit.text.toString())).apply()
            refreshServerHint()
        })
        refreshServerHint()

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
        connCard.addView(passwordRow(passwordEdit), marginParams(12))

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
            hint = "同步密码（留空则用内置默认密码）"
            setText(PayloadCipher.syncPassword(this@FuncSettingsActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
            background = roundedBg(0xFFF3F4F6.toInt(), 12f)
            setPadding(24, 20, 24, 20)
        }

        // 同步密码不随打字保存：派生一次是 20 万轮 PBKDF2，边敲边算会卡住输入框。
        // 用户点「确定」（或输入法回车）才落库并重算指纹。
        val applySyncPwd = {
            val typed = syncPwdEdit.text.toString()
            if (typed == PayloadCipher.syncPassword(this)) {
                toast("同步密码未改动")
            } else {
                PayloadCipher.setSyncPassword(this, typed)
                refreshFingerprint(fingerprintText)
                toast(if (typed.isEmpty()) "已清空，将使用内置默认密码" else "同步密码已保存")
            }
        }
        syncPwdEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applySyncPwd()
                true
            } else {
                false
            }
        }
        // 未保存提示：让用户明白光打字不生效，得点确定
        syncPwdEdit.addTextChangedListener(afterTextChanged {
            val dirty = syncPwdEdit.text.toString() != PayloadCipher.syncPassword(this)
            if (dirty) {
                fingerprintText.text = "同步密码已修改，点「确定」后生效"
                fingerprintText.setTextColor(0xFFD97706.toInt())
            } else {
                refreshFingerprint(fingerprintText)
            }
        })
        val syncPwdRow = passwordRow(syncPwdEdit, onConfirm = applySyncPwd)

        val e2eeCb = CheckBox(this).apply {
            text = "启用端到端加密（服务端只转发密文）"
            isChecked = PayloadCipher.isEnabled(this@FuncSettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                PayloadCipher.setEnabled(this@FuncSettingsActivity, checked)
                // 关闭加密时整行隐藏：明文传输下这个输入框没有意义
                syncPwdRow.visibility = if (checked) View.VISIBLE else View.GONE
                refreshFingerprint(fingerprintText)
            }
        }
        syncPwdRow.visibility = if (e2eeCb.isChecked) View.VISIBLE else View.GONE

        cryptoCard.addView(e2eeCb, marginParams(12))
        cryptoCard.addView(syncPwdRow, marginParams(12))
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

    /**
     * 说清当前加密状态，并展示密钥指纹方便和电脑端比对。
     *
     * 三种情况：关闭（明文）、启用但没填密码（内置默认密码）、启用且填了密码。
     */
    private fun refreshFingerprint(view: TextView) {
        fingerprintJob?.cancel()

        if (!PayloadCipher.isEnabled(this)) {
            view.text = "加密已关闭：消息以明文传输"
            view.setTextColor(0xFF6B7280.toInt())
            return
        }

        // 状态文案先立刻显示，指纹是慢活儿，算完再往后面补
        val builtin = PayloadCipher.usingBuiltinPassword(this)
        val prefix = if (builtin) {
            "未填同步密码，正在使用内置默认密码（各端通用，强度低于自设密码）"
        } else {
            "使用自设同步密码"
        }
        view.text = "$prefix\n密钥指纹计算中…"
        view.setTextColor(if (builtin) 0xFFD97706.toInt() else 0xFF059669.toInt())

        val password = PayloadCipher.effectivePassword(this)
        fingerprintJob = lifecycleScope.launch {
            val fp = withContext(Dispatchers.Default) {
                // 单次派生在手机上实测约 2.8 秒（20 万轮 PBKDF2），必须离开主线程。
                // 只在密码确认保存时才走到这里，不会被打字过程反复触发。
                PayloadCipher.fingerprint(password)
            }
            view.text = when {
                fp == null -> "$prefix\n无法派生密钥，请换一个密码"
                builtin -> "$prefix\n密钥指纹 $fp"
                else -> "$prefix · 密钥指纹 $fp（两端一致才能互相解密）"
            }
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

    /** 只关心"输入完成"这一刻的 TextWatcher 简写 */
    private fun afterTextChanged(action: () -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = action()
    }

    /**
     * 把密码输入框和「小眼睛」按钮并成一行，点眼睛切换明文 / 密文。
     *
     * 用 transformationMethod 而不是改 inputType：后者会让输入法重置状态，
     * 已输入内容的字体也可能跳变。切换后要把光标挪回末尾，否则会跳到开头。
     *
     * 传了 onConfirm 就在眼睛右边再加一个「确定」按钮，用于那些"保存代价很高、
     * 不能边打字边保存"的输入框（同步密码要跑 20 万轮 PBKDF2）。
     */
    private fun passwordRow(edit: EditText, onConfirm: (() -> Unit)? = null): LinearLayout {
        val eye = ImageButton(this).apply {
            setImageResource(R.drawable.ic_eye_off)
            background = null
            contentDescription = "显示密码"
            setPadding(20, 0, 8, 0)
        }
        eye.setOnClickListener {
            val nowRevealed = edit.transformationMethod == null
            if (nowRevealed) {
                edit.transformationMethod = PasswordTransformationMethod.getInstance()
                eye.setImageResource(R.drawable.ic_eye_off)
                eye.contentDescription = "显示密码"
            } else {
                edit.transformationMethod = null
                eye.setImageResource(R.drawable.ic_eye)
                eye.contentDescription = "隐藏密码"
            }
            edit.setSelection(edit.text.length)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                edit,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                eye,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            if (onConfirm != null) {
                val confirm = Button(this@FuncSettingsActivity).apply {
                    text = "确定"
                    textSize = 13f
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(28, 0, 28, 0)
                    background = roundedBg(0xFF8B5CF6.toInt(), 12f)
                    setTextColor(0xFFFFFFFF.toInt())
                    setOnClickListener { onConfirm() }
                }
                addView(
                    confirm,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = 12 }
                )
            }
        }
    }

    /**
     * 离开设置页时，只要连接相关配置变了就重启同步服务。
     *
     * 地址、账密要重新换 token，加密设置要重新派生密钥 —— 这些参数都是
     * WsClient 构造时读的，不重启不生效。放在 onPause 是为了避免边打字边重连。
     */
    override fun onPause() {
        super.onPause()
        val now = connectionSnapshot()
        if (now != connectionSnapshotAtEntry) {
            connectionSnapshotAtEntry = now
            if (SyncService.activeWs() != null) {
                SyncService.restart(this)
            }
        }
    }

    /** 影响连接的所有设置，用来判断离开设置页后是否需要重连 */
    private fun connectionSnapshot(): List<String> {
        val sp = getSharedPreferences("clipsync", MODE_PRIVATE)
        return listOf(
            AuthClient.savedUsername(this),
            AuthClient.savedPassword(this),
            // 比规范化后的值，否则"补上 ws:// 前缀"这个自动回写会被误判成用户改了地址
            ServerAddress.normalize(sp.getString("server", "") ?: ""),
            PayloadCipher.isEnabled(this).toString(),
            PayloadCipher.syncPassword(this)
        )
    }
}
