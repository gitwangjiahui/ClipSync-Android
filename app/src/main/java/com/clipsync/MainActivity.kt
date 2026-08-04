package com.clipsync

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.clipsync.clipboard.ClipboardManagerHelper
import com.clipsync.service.SyncService
import com.clipsync.state.ConnectionBus
import com.clipsync.ui.HistoryActivity
import com.clipsync.ui.SettingsActivity

/**
 * 主界面（重设计样式）
 * - 顶部：大号状态卡（图标 + 状态文字 + 服务器地址 + 启动/停止按钮）
 * - 中间：剪贴板预览 + 手动推送（保留原"当前剪贴板"功能）
 * - 底部：历史按钮
 * - 右上角齿轮 → 设置（恢复原入口）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: TextView
    private lateinit var statusText: TextView
    private lateinit var statusHint: TextView
    private lateinit var statusBadge: LinearLayout
    private lateinit var toggleBtn: Button
    private lateinit var targetText: TextView
    private lateinit var clipPreviewText: TextView
    private lateinit var clipPreviewImage: ImageView
    private lateinit var clipCard: LinearLayout
    private lateinit var pushBtn: Button

    private var dotAnimator: ObjectAnimator? = null
    private var lastWasConnecting = false

    private val stateListener: (String) -> Unit = { state -> renderState(state) }
    private val previewListener: () -> Unit = { refreshClipPreview() }

    private val mediaPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        android.util.Log.i("ClipSync", "图片权限授权结果: $granted")
        refreshClipPreview()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ClipSync"
        ClipboardManagerHelper.loadPrefs(this)
        // 重要：先初始化 ClipboardManagerHelper，否则主页预览拿不到剪贴板
        // （之前依赖 SyncService 启动，但预览要在服务启动前就能用）
        ClipboardManagerHelper.init(applicationContext, null)
        requestMediaPermissionIfNeeded()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            setBackgroundColor(0xFFF6F7FB.toInt())
        }

        root.addView(buildStatusCard(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // "立即推送"按钮放在状态卡下面，剪贴板预览之上
        pushBtn = styledButton("推送剪切板", 0xFF6366F1.toInt(), 0xFFFFFFFF.toInt()) { pushClipboard() }
        root.addView(pushBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 })

        // 剪贴板预览卡（自由高度，内容自适应）
        root.addView(buildClipCard(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ConnectionBus.addListener(stateListener)
        renderTarget()
        com.clipsync.clipboard.ClipboardManagerHelper.onForeground()
        // 服务没连上（被杀过，或上次连接时没网）就重新发起；已连上则 kick 加速重连。
        // 注意 activeWs()==null 也包含"服务活着但换 token 时失败"这种情况，
        // startSync() 带 ACTION_CONNECT 能让服务重试，不会空转。
        if (SyncService.activeWs() == null) {
            startSync()
        } else {
            SyncService.kick()
        }
        ClipboardManagerHelper.addPreviewListener(previewListener)
        // 第一次预览（窗口未获焦点，可能读不到内容，下面 onWindowFocusChanged 会再读）
        refreshClipPreview()
        // 兜底：onWindowFocusChanged 在某些 ROM 上不会触发，所以延迟再读几次
        clipCard.postDelayed({ refreshClipPreview() }, 300)
        clipCard.postDelayed({ refreshClipPreview() }, 800)
        clipCard.postDelayed({ refreshClipPreview() }, 1500)
        renderState(ConnectionBus.current)
        autoConnectIfNeeded()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        android.util.Log.i("ClipSync", "onWindowFocusChanged: hasFocus=$hasFocus")
        if (hasFocus) {
            // 关键：Android 10+ 后台无法读剪贴板内容，必须等窗口真正获焦点后再读。
            // 多数 ROM 还需要再延迟一帧（MIUI 更严格）
            clipCard.postDelayed({ refreshClipPreview() }, 150)
        }
    }

    override fun onPause() {
        super.onPause()
        ClipboardManagerHelper.removePreviewListener(previewListener)
        ConnectionBus.removeListener(stateListener)
        stopDotAnimation()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 200, 0, "历史")?.apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setIcon(android.R.drawable.ic_menu_recent_history)
        }
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
            200 -> {
                startActivity(Intent(this, HistoryActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // MARK: - 状态卡（重设计：背景随状态变色，大号圆点 + 状态文字 + 服务器地址 + 启动按钮）

    private fun buildStatusCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(0xFFFFFFFF.toInt(), 28f)
            setPadding(22, 20, 22, 20)
            elevation = 8f
        }

        // 大号圆形状态徽章（背景色随状态变化）
        val badgeSize = 56
        statusBadge = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBg(0xFFEEF2FF.toInt(), badgeSize / 2f)
            layoutParams = LinearLayout.LayoutParams(badgeSize, badgeSize)
        }
        statusDot = TextView(this).apply {
            text = "●"
            textSize = 22f
            setTextColor(0xFF9CA3AF.toInt())
        }
        statusBadge.addView(statusDot)
        card.addView(statusBadge, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = 16 })

        // 中间文字区（状态文字 + 服务器地址）
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        statusText = TextView(this).apply {
            text = "未连接"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF1F2937.toInt())
        }
        statusHint = TextView(this).apply {
            text = " "
            textSize = 12f
            setTextColor(0xFF9CA3AF.toInt())
            setPadding(0, 2, 0, 0)
        }
        // 服务器地址（带左侧小色条）
        val targetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 0)
        }
        val targetBar = View(this).apply {
            background = roundedBg(0xFF6366F1.toInt(), 2f)
            layoutParams = LinearLayout.LayoutParams(3, 14)
        }
        targetText = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF6B7280.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(8, 0, 0, 0)
            maxLines = 1
        }
        targetRow.addView(targetBar)
        targetRow.addView(targetText, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))
        textCol.addView(statusText)
        textCol.addView(statusHint)
        textCol.addView(targetRow)
        card.addView(textCol, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))

        // 右侧启动/停止按钮
        toggleBtn = styledButton("启动", 0xFF22C55E.toInt(), 0xFFFFFFFF.toInt()) { toggleSync() }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = 12 }
        card.addView(toggleBtn, btnParams)

        renderTarget()
        return card
    }

    // MARK: - 剪贴板预览卡（占满中间）

    private fun buildClipCard(): View {
        clipCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(0xFFFFFFFF.toInt(), 24f)
            setPadding(24, 20, 24, 20)
            elevation = 6f
        }

        // 标题行：左侧色条 + "当前剪贴板"
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }
        val titleBar = View(this).apply {
            background = roundedBg(0xFF6366F1.toInt(), 3f)
            layoutParams = LinearLayout.LayoutParams(5, 20)
        }
        val title = TextView(this).apply {
            text = "当前剪贴板"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF1F2937.toInt())
            setPadding(10, 0, 0, 0)
        }
        titleRow.addView(titleBar)
        titleRow.addView(title)
        clipCard.addView(titleRow)

        // 内容区（自由高度，文本/图片按内容自适应）
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = roundedBg(0xFFF9FAFB.toInt(), 16f)
            setPadding(20, 16, 20, 16)
        }
        clipPreviewText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF374151.toInt())
            setLineSpacing(0f, 1.5f)
            gravity = Gravity.TOP
            visibility = View.GONE
        }
        clipPreviewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            background = roundedBg(0xFFF3F4F6.toInt(), 12f)
        }
        contentFrame.addView(clipPreviewText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        contentFrame.addView(clipPreviewImage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        clipCard.addView(contentFrame)

        return clipCard
    }

    // MARK: - 状态渲染

    private fun renderState(state: String) {
        if (!::statusDot.isInitialized || !::statusText.isInitialized ||
            !::statusHint.isInitialized || !::statusBadge.isInitialized ||
            !::toggleBtn.isInitialized
        ) {
            return
        }
        when (state) {
            ConnectionBus.STATE_OPEN -> {
                stopDotAnimation()
                statusDot.setTextColor(0xFF22C55E.toInt())
                (statusBadge.background as? GradientDrawable)?.setColor(0xFFDCFCE7.toInt())
                statusText.text = "已连接"
                statusHint.text = "同步中，可正常收发消息"
                statusHint.setTextColor(0xFF9CA3AF.toInt())
                toggleBtn.text = "停止"
                (toggleBtn.background as? GradientDrawable)?.setColor(0xFFEF4444.toInt())
                toggleBtn.isEnabled = true
                // 已连接成功，清掉"曾在连接中"标记，避免之后正常断开也弹失败
                lastWasConnecting = false
                return
            }
            ConnectionBus.STATE_CONNECTING -> {
                startDotAnimation()
                statusDot.setTextColor(0xFFF59E0B.toInt())
                (statusBadge.background as? GradientDrawable)?.setColor(0xFFFEF3C7.toInt())
                statusText.text = "连接中"
                statusHint.text = "正在连接服务器…"
                statusHint.setTextColor(0xFF9CA3AF.toInt())
                toggleBtn.text = "取消"
                (toggleBtn.background as? GradientDrawable)?.setColor(0xFFEF4444.toInt())
                toggleBtn.isEnabled = true
                lastWasConnecting = true
                return
            }
            else -> {
                // 服务还活着 = 只是瞬时断线，内部在自动重连，不报失败。
                // 但已经有明确失败原因时（账密不对 / 网络不通）就别再假装在重连了。
                val reason = ConnectionBus.failureReason
                val serviceAlive = SyncService.activeWs() != null && reason == null
                if (serviceAlive) {
                    startDotAnimation()
                    statusDot.setTextColor(0xFFF59E0B.toInt())
                    (statusBadge.background as? GradientDrawable)?.setColor(0xFFFEF3C7.toInt())
                    statusText.text = "重连中"
                    statusHint.text = "连接中断，正在自动重连…"
                    toggleBtn.text = "取消"
                    (toggleBtn.background as? GradientDrawable)?.setColor(0xFFEF4444.toInt())
                } else {
                    stopDotAnimation()
                    statusDot.setTextColor(0xFF9CA3AF.toInt())
                    (statusBadge.background as? GradientDrawable)?.setColor(0xFFEEF2FF.toInt())
                    statusText.text = "未连接"
                    // 优先展示服务端 / 网络层给出的具体原因，笼统提示只作兜底
                    statusHint.text = when {
                        reason != null -> reason
                        lastWasConnecting -> "连接失败，请检查网络或服务器后再次启动"
                        else -> "点「启动」开始同步"
                    }
                    statusHint.setTextColor(
                        if (reason != null) 0xFFDC2626.toInt() else 0xFF9CA3AF.toInt()
                    )
                    toggleBtn.text = "启动"
                    (toggleBtn.background as? GradientDrawable)?.setColor(0xFF22C55E.toInt())
                }
                toggleBtn.isEnabled = true
                lastWasConnecting = false
            }
        }
    }

    private fun renderTarget() {
        val sp = getSharedPreferences("clipsync", MODE_PRIVATE)
        val raw = sp.getString("server", null) ?: com.clipsync.BuildConfig.DEFAULT_SERVER
        // 展示规范化后的完整地址，让用户看到程序实际连的是哪里（ws:// 是自动补的）
        val server = com.clipsync.net.ServerAddress.normalize(raw).ifEmpty { "未填写服务器地址" }
        // 账号密码没填就连不上；token 由连接时自动换取，不需要用户关心
        val tip = if (com.clipsync.net.AuthClient.hasCredentials(this)) "" else "  ·  ⚠ 未填写账号密码"
        targetText.text = "🔗  $server$tip"
    }

    // MARK: - 剪贴板预览（修复图片显示）

    private fun refreshClipPreview() {
        val shot = com.clipsync.clipboard.ScreenshotWatcher.peekPending()
        val shotNewer = shot != null &&
            com.clipsync.clipboard.ScreenshotWatcher.pendingTimestamp() >=
            ClipboardManagerHelper.clipTimestamp()

        // 1. 有新截图（比剪贴板新）→ 优先显示截图
        if (shot != null && shotNewer) {
            showScreenshot(shot)
            return
        }

        // 2. 真实剪贴板里的图片
        val clipImage = ClipboardManagerHelper.peekClipboardImageUri()
        if (clipImage != null) {
            val bmp = ClipboardManagerHelper.decodeImageUri(clipImage)
            if (bmp != null) {
                clipPreviewImage.setImageBitmap(bmp)
                clipPreviewImage.visibility = View.VISIBLE
                clipPreviewText.visibility = View.GONE
                pushBtn.isEnabled = true
                pushBtn.alpha = 1f
                return
            }
            android.util.Log.w("ClipSync", "refreshClipPreview: 图片解码失败 uri=$clipImage")
            clipPreviewImage.visibility = View.GONE
            clipPreviewText.visibility = View.VISIBLE
            clipPreviewText.text = "（图片无法预览，但仍可尝试推送）"
            pushBtn.isEnabled = true
            pushBtn.alpha = 1f
            return
        }

        // 3. 剪贴板文本
        val text = ClipboardManagerHelper.peekText()
        if (!text.isNullOrBlank()) {
            clipPreviewImage.visibility = View.GONE
            clipPreviewImage.setImageDrawable(null)
            clipPreviewText.visibility = View.VISIBLE
            clipPreviewText.text = text
            pushBtn.isEnabled = true
            pushBtn.alpha = 1f
            return
        }

        // 4. 剪贴板为空 → 回落截图
        if (shot != null) {
            showScreenshot(shot)
            return
        }

        // 5. 什么都没有
        clipPreviewImage.visibility = View.GONE
        clipPreviewImage.setImageDrawable(null)
        clipPreviewText.visibility = View.VISIBLE
        clipPreviewText.text = "（剪贴板为空）"
        pushBtn.isEnabled = false
        pushBtn.alpha = 0.5f
    }

    private fun showScreenshot(shot: android.net.Uri) {
        val bmp = ClipboardManagerHelper.decodeImageUri(shot)
        if (bmp != null) {
            clipPreviewImage.setImageBitmap(bmp)
            clipPreviewImage.visibility = View.VISIBLE
            clipPreviewText.visibility = View.VISIBLE
            clipPreviewText.text = "📷 新截图 · 点击推送发送到电脑"
            pushBtn.isEnabled = true
            pushBtn.alpha = 1f
            android.util.Log.i("ClipSync", "refreshClipPreview: 回落截图 $shot")
        }
    }

    private fun pushClipboard() {
        if (!ClipboardManagerHelper.hasContent()) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }
        if (ConnectionBus.current != ConnectionBus.STATE_OPEN) {
            Toast.makeText(this, "未连接，请先启动同步", Toast.LENGTH_SHORT).show()
            return
        }
        when (ClipboardManagerHelper.manualUpload()) {
            com.clipsync.clipboard.ClipboardManagerHelper.UploadResult.SENT ->
                Toast.makeText(this, "已推送", Toast.LENGTH_SHORT).show()
            com.clipsync.clipboard.ClipboardManagerHelper.UploadResult.QUEUED ->
                Toast.makeText(this, "已推送（离线暂存，连上自动发）", Toast.LENGTH_SHORT).show()
            com.clipsync.clipboard.ClipboardManagerHelper.UploadResult.FAILED ->
                Toast.makeText(this, "推送失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    /** 截图监听需要读媒体库权限，首次启动时请求 */
    private fun requestMediaPermissionIfNeeded() {
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            @Suppress("DEPRECATION")
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, perm)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            mediaPermLauncher.launch(perm)
        }
    }

    // MARK: - 启动 / 停止

    /**
     * 启动 / 取消。
     *
     * 判断依据是"同步服务是否还活着"，不是 ConnectionBus 的瞬时状态：
     * 退避重连期间状态会在 connecting 和 closed 之间来回跳，按状态判断的话，
     * 用户点在 closed 那一瞬间反而会再启动一次服务，看起来就是"取消没反应"。
     */
    private fun toggleSync() {
        if (isSyncServiceRunning()) {
            stopSync()
        } else {
            renderState(ConnectionBus.STATE_CONNECTING)
            startSync()
        }
    }

    /**
     * 同步服务是否正在连接或已连接。
     *
     * activeWs() 只在 WebSocket 建起来后才非空，"正在换 token"那段它还是 null，
     * 所以要再看一眼总线状态兜住空窗，否则那几百毫秒里「取消」会被当成「启动」。
     * 反过来，已经有明确失败原因时就算是"停下了"，用户该看到「启动」。
     */
    private fun isSyncServiceRunning(): Boolean {
        if (ConnectionBus.failureReason != null) return false
        return SyncService.activeWs() != null ||
            ConnectionBus.current == ConnectionBus.STATE_CONNECTING ||
            ConnectionBus.current == ConnectionBus.STATE_OPEN
    }

    private fun startSync() {
        // 带上 ACTION_CONNECT：服务已存活但连接没建起来时，让它重试而不是空转
        val intent = Intent(this, SyncService::class.java).apply {
            action = SyncService.ACTION_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /** 启动时自动连接（功能设置里可关，默认开） */
    private fun autoConnectIfNeeded() {
        val sp = getSharedPreferences("clipsync", MODE_PRIVATE)
        if (!sp.getBoolean("auto_connect", true)) return
        if (isSyncServiceRunning()) return
        // 上次已经明确失败过（比如密码错了）就别再自动重试，让用户先去改设置
        if (ConnectionBus.failureReason != null) return
        renderState(ConnectionBus.STATE_CONNECTING)
        startSync()
    }

    private fun stopSync() {
        stopService(Intent(this, SyncService::class.java))
        // 主动停止不是"失败"，把上一次的错误原因清掉，别让红字留在界面上
        ConnectionBus.publish(ConnectionBus.STATE_CLOSED, null)
        renderState(ConnectionBus.STATE_CLOSED)
        lastWasConnecting = false
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

    // MARK: - UI 辅助

    private fun styledButton(text: String, bgColor: Int, textColor: Int, onClick: () -> Unit): Button {
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 22f
            setColor(bgColor)
        }
        return Button(this).apply {
            this.text = text
            setTextColor(textColor)
            background = drawable
            setPadding(28, 24, 28, 24)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
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
