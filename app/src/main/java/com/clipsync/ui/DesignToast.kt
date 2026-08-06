package com.clipsync.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * 轻提示横幅，替代系统 Toast。
 *
 * 换掉系统 Toast 的原因：它固定弹在屏幕底部正中，正好压住主页的悬浮操作按钮，
 * 而 targetSdk 30 以后 Toast.setGravity 对文字 Toast 已经失效，改不动位置。
 * 这里自己叠一层横幅，落点贴着底部操作区上沿。
 *
 * 提示紧挨着触发它的按钮出现，反馈和操作在同一个视觉区域，比甩到屏幕最上方更好读。
 * 让开的高度由页面通过 [clearance] 告知（主页传悬浮操作层实测高度），
 * 没有悬浮层的页面不用设，横幅自然落在屏幕底部安全区上方。
 *
 * 复用同一个视图，连续提示时后一条直接顶掉前一条，不会排队堆叠。
 */
class DesignToast(private val host: FrameLayout) {

    private var pill: LinearLayout? = null
    private var label: android.widget.TextView? = null
    private val hideRunnable = Runnable { animateOut() }

    /**
     * 距屏幕底部要让开的高度（px），通常是悬浮操作层的实测高度。
     * 用实测值而不是常量：系统字体放大后按钮会变高，写死就会被压住。
     * 保持 0 表示页面没有悬浮层，此时按导航条高度让开。
     */
    var clearance: Int = 0
        set(value) {
            field = value
            pill?.let { applyPosition(it) }
        }

    /** @param tone 左侧状态点颜色，传 null 则不显示圆点 */
    fun show(message: String, tone: Int? = null) {
        val ctx = host.context
        val view = pill ?: build().also { pill = it }
        label?.text = message

        (view.getChildAt(0))?.let { dot ->
            if (tone == null) {
                dot.visibility = View.GONE
            } else {
                dot.visibility = View.VISIBLE
                dot.background = Design.circleBg(tone)
            }
        }

        host.removeCallbacks(hideRunnable)
        view.animate().cancel()
        applyPosition(view)
        view.visibility = View.VISIBLE
        view.alpha = 0f
        // 从下往上浮出，和它所在的底部区域同向
        view.translationY = Design.dp(ctx, Design.Space.M).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(180).start()
        host.postDelayed(hideRunnable, DURATION_MS)
    }

    /**
     * 每次显示时重算，而不是建视图时算一次：
     * 挂到 decorView 后 insets 可能还没派发，首次提示会算成 0 贴到导航条上。
     */
    private fun applyPosition(view: View) {
        val ctx = host.context
        // 悬浮层本身已经铺到屏幕最底（含导航条区域），所以两者取其一而不是相加，
        // 否则横幅会被顶高一个导航条的距离，离按钮太远。
        val base = if (clearance > 0) clearance else navBarHeight(view)
        val want = base + Design.dp(ctx, Design.Space.M)
        val lp = view.layoutParams as FrameLayout.LayoutParams
        if (lp.bottomMargin != want) {
            lp.bottomMargin = want
            view.layoutParams = lp
        }
    }

    /**
     * insets 优先，拿不到就退回系统资源里的状态栏高度。
     * 兜底是必要的：提示可能在窗口首帧之前就触发，那时 insets 还是 0，
     * 直接用会把横幅贴到导航条上。
     */
    private fun navBarHeight(view: View): Int {
        androidx.core.view.ViewCompat.getRootWindowInsets(view)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            ?.bottom
            ?.let { if (it > 0) return it }

        val res = view.resources
        val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        if (id > 0) return res.getDimensionPixelSize(id)
        return Design.dp(view.context, 16f)
    }

    private fun animateOut() {
        val view = pill ?: return
        val ctx = host.context
        view.animate()
            .alpha(0f)
            .translationY(Design.dp(ctx, Design.Space.S).toFloat())
            .setDuration(160)
            .withEndAction { view.visibility = View.GONE }
            .start()
    }

    private fun build(): LinearLayout {
        val ctx = host.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Design.outlinedBg(
                ctx, Design.Color.SURFACE, Design.Color.BORDER, Design.Radius.CHIP
            )
            elevation = Design.dp(ctx, 8f).toFloat()
            val h = Design.dp(ctx, 14f)
            val v = Design.dp(ctx, 10f)
            setPadding(h, v, h, v)
            visibility = View.GONE
            // 提示只是反馈，不该拦住下面卡片的点击
            isClickable = false
        }

        val dotSize = Design.dp(ctx, 6f)
        row.addView(View(ctx), LinearLayout.LayoutParams(dotSize, dotSize).apply {
            marginEnd = Design.dp(ctx, Design.Space.S)
        })

        label = Design.text(ctx, "", Design.Text.CAPTION, Design.Color.INK)
        row.addView(label)

        host.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ))
        return row
    }

    companion object {
        private const val DURATION_MS = 1900L

        /**
         * 挂到窗口 decorView 上，这样横幅能盖在页面自己的悬浮操作层之上，
         * 页面也不必为了容纳提示去改自己的布局结构。
         */
        fun attach(activity: Activity): DesignToast =
            DesignToast(activity.window.decorView as FrameLayout)
    }
}
