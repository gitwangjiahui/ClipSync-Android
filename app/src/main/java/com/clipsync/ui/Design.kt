package com.clipsync.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/**
 * ClipSync 移动端设计系统。
 *
 * 建这个文件的直接原因：原来各页面写的是 setPadding(24, 32, 24, 24)，
 * 这些数字是**像素**不是 dp，在 3x 密度屏上 24px 只有 8dp，
 * 于是所有留白都被压扁了。现在统一走 dp() 换算。
 *
 * 配色也在这里收口：以前同屏混用靛蓝/蓝/紫/绿/橙五种色相，
 * 缺主次。现在只留一个主色，语义色仅用于状态表达。
 */
object Design {

    // MARK: - 尺寸换算

    /** dp → px。所有间距、圆角、控件尺寸都必须经过它 */
    fun dp(ctx: Context, value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics
    ).toInt()

    // MARK: - 配色（支持深色模式）

    object Color {
        // ===== 浅色方案 =====
        private object Light {
            // 主色
            const val PRIMARY = 0xFF3B5BDB.toInt()
            const val PRIMARY_PRESSED = 0xFF2F4BC4.toInt()
            const val PRIMARY_TINT = 0xFFEDF0FB.toInt()

            // 语义色
            const val SUCCESS = 0xFF15803D.toInt()
            const val SUCCESS_TINT = 0xFFDCFCE7.toInt()
            const val SUCCESS_TINT_SOFT = 0xFFF0FDF4.toInt()
            const val WARNING = 0xFFB45309.toInt()
            const val WARNING_TINT = 0xFFFEF3C7.toInt()
            const val WARNING_TINT_SOFT = 0xFFFFFBEB.toInt()
            const val WARNING_BORDER = 0xFFF3E4BC.toInt()
            const val DANGER = 0xFFB91C1C.toInt()
            const val DANGER_TINT = 0xFFFEE2E2.toInt()

            // 文字层级
            const val INK = 0xFF111318.toInt()
            const val INK_SECONDARY = 0xFF5B6270.toInt()
            const val INK_MUTED = 0xFF8B92A0.toInt()
            const val INK_DISABLED = 0xFFA8AEB8.toInt()

            // 表面与描边
            const val SURFACE = 0xFFFFFFFF.toInt()
            const val CANVAS = 0xFFF4F5F7.toInt()
            const val SUBTLE = 0xFFF7F8FA.toInt()
            const val BORDER = 0xFFE4E7EC.toInt()
            const val BORDER_LIGHT = 0xFFF0F1F4.toInt()
            const val BORDER_CARD = 0xFFE9EBEF.toInt()
            const val BORDER_ROW = 0xFFF2F3F6.toInt()

            // 停用态
            const val DISABLED_BG = 0xFFEDEFF2.toInt()

            // 中性
            const val NEUTRAL = 0xFF98A0AC.toInt()
            const val NEUTRAL_TINT = 0xFFEDEFF2.toInt()
            const val NEUTRAL_TINT_SOFT = 0xFFF6F7F9.toInt()

            // 组件特定色
            const val TOGGLE_OFF = 0xFFD6DAE1.toInt()
            const val ACTION_BAR = 0xEBFFFFFF.toInt()
        }

        // ===== 深色方案（优化版：高对比度、层次感强） =====
        private object Dark {
            // 主色：更亮更饱和，在深色背景上清晰醒目
            const val PRIMARY = 0xFF8B9DF5.toInt()
            const val PRIMARY_PRESSED = 0xFF7A8CE8.toInt()
            const val PRIMARY_TINT = 0xFF1E2545.toInt()

            // 语义色：高对比度版本
            const val SUCCESS = 0xFF34D399.toInt()
            const val SUCCESS_TINT = 0xFF064E3B.toInt()
            const val SUCCESS_TINT_SOFT = 0xFF022C22.toInt()
            const val WARNING = 0xFFFBBF24.toInt()
            const val WARNING_TINT = 0xFF78350F.toInt()
            const val WARNING_TINT_SOFT = 0xFF451A03.toInt()
            const val WARNING_BORDER = 0xFF92400E.toInt()
            const val DANGER = 0xFFF87171.toInt()
            const val DANGER_TINT = 0xFF7F1D1D.toInt()

            // 文字层级：在深色背景上保证 WCAG AA 对比度
            const val INK = 0xFFFFFFFF.toInt()
            const val INK_SECONDARY = 0xFFC4C9D4.toInt()
            const val INK_MUTED = 0xFF8B93A3.toInt()
            const val INK_DISABLED = 0xFF5A6272.toInt()

            // 表面与描边：三级灰度层次
            const val SURFACE = 0xFF262830.toInt()
            const val CANVAS = 0xFF16171B.toInt()
            const val SUBTLE = 0xFF1E2026.toInt()
            const val BORDER = 0xFF3A3D45.toInt()
            const val BORDER_LIGHT = 0xFF30333B.toInt()
            const val BORDER_CARD = 0xFF32353D.toInt()
            const val BORDER_ROW = 0xFF2A2D34.toInt()

            // 停用态
            const val DISABLED_BG = 0xFF2E3139.toInt()

            // 中性
            const val NEUTRAL = 0xFF9CA3AF.toInt()
            const val NEUTRAL_TINT = 0xFF2E3139.toInt()
            const val NEUTRAL_TINT_SOFT = 0xFF1E2026.toInt()

            // 组件特定色
            const val TOGGLE_OFF = 0xFF3A3D45.toInt()
            const val ACTION_BAR = 0xE616171B.toInt()
        }

        // ===== 动态属性：根据主题返回对应颜色 =====
        private val L = Light
        private val D = Dark

        val PRIMARY: Int get() = if (ThemeManager.isDark) D.PRIMARY else L.PRIMARY
        val PRIMARY_PRESSED: Int get() = if (ThemeManager.isDark) D.PRIMARY_PRESSED else L.PRIMARY_PRESSED
        val PRIMARY_TINT: Int get() = if (ThemeManager.isDark) D.PRIMARY_TINT else L.PRIMARY_TINT

        val SUCCESS: Int get() = if (ThemeManager.isDark) D.SUCCESS else L.SUCCESS
        val SUCCESS_TINT: Int get() = if (ThemeManager.isDark) D.SUCCESS_TINT else L.SUCCESS_TINT
        val SUCCESS_TINT_SOFT: Int get() = if (ThemeManager.isDark) D.SUCCESS_TINT_SOFT else L.SUCCESS_TINT_SOFT
        val WARNING: Int get() = if (ThemeManager.isDark) D.WARNING else L.WARNING
        val WARNING_TINT: Int get() = if (ThemeManager.isDark) D.WARNING_TINT else L.WARNING_TINT
        val WARNING_TINT_SOFT: Int get() = if (ThemeManager.isDark) D.WARNING_TINT_SOFT else L.WARNING_TINT_SOFT
        val WARNING_BORDER: Int get() = if (ThemeManager.isDark) D.WARNING_BORDER else L.WARNING_BORDER
        val DANGER: Int get() = if (ThemeManager.isDark) D.DANGER else L.DANGER
        val DANGER_TINT: Int get() = if (ThemeManager.isDark) D.DANGER_TINT else L.DANGER_TINT

        val INK: Int get() = if (ThemeManager.isDark) D.INK else L.INK
        val INK_SECONDARY: Int get() = if (ThemeManager.isDark) D.INK_SECONDARY else L.INK_SECONDARY
        val INK_MUTED: Int get() = if (ThemeManager.isDark) D.INK_MUTED else L.INK_MUTED
        val INK_DISABLED: Int get() = if (ThemeManager.isDark) D.INK_DISABLED else L.INK_DISABLED

        val SURFACE: Int get() = if (ThemeManager.isDark) D.SURFACE else L.SURFACE
        val CANVAS: Int get() = if (ThemeManager.isDark) D.CANVAS else L.CANVAS
        val SUBTLE: Int get() = if (ThemeManager.isDark) D.SUBTLE else L.SUBTLE
        val BORDER: Int get() = if (ThemeManager.isDark) D.BORDER else L.BORDER
        val BORDER_LIGHT: Int get() = if (ThemeManager.isDark) D.BORDER_LIGHT else L.BORDER_LIGHT
        val BORDER_CARD: Int get() = if (ThemeManager.isDark) D.BORDER_CARD else L.BORDER_CARD
        val BORDER_ROW: Int get() = if (ThemeManager.isDark) D.BORDER_ROW else L.BORDER_ROW

        val DISABLED_BG: Int get() = if (ThemeManager.isDark) D.DISABLED_BG else L.DISABLED_BG

        val NEUTRAL: Int get() = if (ThemeManager.isDark) D.NEUTRAL else L.NEUTRAL
        val NEUTRAL_TINT: Int get() = if (ThemeManager.isDark) D.NEUTRAL_TINT else L.NEUTRAL_TINT
        val NEUTRAL_TINT_SOFT: Int get() = if (ThemeManager.isDark) D.NEUTRAL_TINT_SOFT else L.NEUTRAL_TINT_SOFT

        val TOGGLE_OFF: Int get() = if (ThemeManager.isDark) D.TOGGLE_OFF else L.TOGGLE_OFF
        val ACTION_BAR: Int get() = if (ThemeManager.isDark) D.ACTION_BAR else L.ACTION_BAR
    }

    // MARK: - 字阶（sp）

    object Text {
        const val DISPLAY = 22f
        const val TITLE = 17f
        const val CARD_TITLE = 14f
        const val BODY = 13f
        const val LABEL = 13.5f
        const val CAPTION = 12f
        const val MICRO = 10.5f
        const val TAG = 9.5f
    }

    // MARK: - 间距（dp，8dp 网格）

    object Space {
        const val XS = 4f
        const val S = 8f
        const val M = 12f
        const val L = 16f
        const val XL = 20f
    }

    // MARK: - 圆角（dp）

    object Radius {
        const val CARD = 16f
        const val BUTTON = 13f
        const val INPUT = 12f
        const val CHIP = 11f
        const val TAG = 6f
    }

    /** 文本预览区固定高度：文字再多也不撑开卡片 */
    const val CLIP_PREVIEW_HEIGHT = 96f

    /**
     * 图片预览的高度上限。
     *
     * 图片不能沿用文本那个 96dp：截图是竖长图，塞进 96dp 里只能露出中间一条。
     * 现在按原图宽高比算高度并封顶在这里，配合 FIT_CENTER，
     * 横图铺满宽度、竖图两侧留白，任何比例都完整可见、不裁切。
     *
     * 240dp 是权衡：再高竖图能大一点，但预览卡会占掉半屏，
     * 把状态卡挤出首屏。按钮在悬浮层，卡片变高不会推走它。
     */
    const val CLIP_IMAGE_MAX_HEIGHT = 240f

    // MARK: - 组件构造

    /** 圆角实心背景 */
    fun roundedBg(ctx: Context, color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(color)
        }

    /** 圆角描边背景（次要按钮、卡片） */
    fun outlinedBg(
        ctx: Context,
        fill: Int,
        stroke: Int,
        radiusDp: Float,
        strokeDp: Float = 1f
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(ctx, radiusDp).toFloat()
        setColor(fill)
        setStroke(dp(ctx, strokeDp).coerceAtLeast(1), stroke)
    }

    /** 正圆背景，用于状态光环 */
    fun circleBg(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** 统一的文字构造，避免每处重复 setTextColor/setTextSize */
    fun text(
        ctx: Context,
        content: CharSequence,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        mono: Boolean = false
    ): TextView = TextView(ctx).apply {
        text = content
        textSize = sizeSp
        setTextColor(color)
        if (mono) {
            typeface = Typeface.MONOSPACE
        }
        if (bold) {
            setTypeface(typeface, Typeface.BOLD)
        }
    }

    /** 1dp 分隔线 */
    fun divider(ctx: Context, color: Int = Color.BORDER_ROW): View = View(ctx).apply {
        setBackgroundColor(color)
    }
}
