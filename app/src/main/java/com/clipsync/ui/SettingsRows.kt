package com.clipsync.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 设置页的行组件。
 *
 * 抽出来是因为合并后的设置页有十几行，每行都手写一遍
 * LinearLayout + TextView 会让 Activity 变得没法读。
 */
object SettingsRows {

    /** 分组容器：小标题 + 白底圆角卡片。返回卡片本体，往里塞行 */
    fun group(ctx: Context, parent: LinearLayout, label: String): LinearLayout {
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = Design.text(
            ctx, label, Design.Text.MICRO, Design.Color.INK_MUTED
        ).apply {
            setPadding(Design.dp(ctx, Design.Space.XS), 0, 0, Design.dp(ctx, 7f))
        }
        wrap.addView(title)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Design.outlinedBg(
                ctx, Design.Color.SURFACE, Design.Color.BORDER_CARD, 14f
            )
            val h = Design.dp(ctx, 15f)
            setPadding(h, 0, h, 0)
        }
        wrap.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        parent.addView(wrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = Design.dp(ctx, Design.Space.L) })
        return card
    }

    /** 行间分隔线，缩进到与文字对齐 */
    fun separator(ctx: Context, card: LinearLayout) {
        card.addView(
            Design.divider(ctx, Design.Color.BORDER_ROW),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Design.dp(ctx, 1f).coerceAtLeast(1)
            )
        )
    }

    /**
     * 卡片内的子分组：一组可以整体显示/隐藏的行。
     * 用于「关掉加密开关，下面的密码行和指纹说明一起收起」这类联动。
     */
    fun subgroup(ctx: Context, card: LinearLayout): LinearLayout {
        val sub = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(sub, matchWrap())
        return sub
    }

    /** 脚注：卡片底部的一段小字说明，比行低一级 */
    fun note(ctx: Context, card: LinearLayout, content: String): TextView {
        val v = Design.text(ctx, content, Design.Text.MICRO, Design.Color.INK_MUTED).apply {
            setLineSpacing(0f, 1.4f)
            setPadding(0, 0, 0, Design.dp(ctx, 13f))
        }
        card.addView(v, matchWrap())
        return v
    }

    /**
     * 值行：左标题 + 右值 + 箭头。点击进入二级页或弹窗编辑。
     * 返回值 TextView，方便调用方后续刷新显示。
     */
    fun valueRow(
        ctx: Context,
        card: LinearLayout,
        label: String,
        value: String,
        mono: Boolean = false,
        onClick: (() -> Unit)? = null
    ): TextView {
        val row = rowBase(ctx, onClick)
        row.addView(Design.text(ctx, label, Design.Text.LABEL, Design.Color.INK))

        val v = Design.text(
            ctx, value, if (mono) 11.5f else Design.Text.CAPTION,
            Design.Color.INK_SECONDARY, mono = mono
        ).apply {
            gravity = Gravity.END
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        row.addView(v, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginStart = Design.dp(ctx, Design.Space.M) })

        // 箭头是「点进去还有内容」的承诺，纯展示行（如版本号）不该有
        if (onClick != null) {
            row.addView(chevron(ctx))
        }
        card.addView(row, matchWrap())
        return v
    }

    /**
     * 开关行：标题 + 可选副标题 + 右侧开关。
     * 用自绘开关而不是 Switch，是为了和设计稿的尺寸/配色一致。
     */
    fun switchRow(
        ctx: Context,
        card: LinearLayout,
        label: String,
        desc: String?,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): View {
        val row = rowBase(ctx, null)

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Design.text(ctx, label, Design.Text.LABEL, Design.Color.INK))
        if (desc != null) {
            col.addView(
                Design.text(ctx, desc, Design.Text.MICRO, Design.Color.INK_MUTED).apply {
                    setPadding(0, Design.dp(ctx, 3f), 0, 0)
                    setLineSpacing(0f, 1.35f)
                }
            )
        }
        row.addView(col, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))

        val toggle = toggleView(ctx, checked)
        row.addView(toggle.first, LinearLayout.LayoutParams(
            Design.dp(ctx, 38f), Design.dp(ctx, 22f)
        ).apply { marginStart = Design.dp(ctx, Design.Space.M) })

        var state = checked
        row.isClickable = true
        row.setOnClickListener {
            state = !state
            applyToggle(toggle.first, toggle.second, state)
            onChange(state)
        }
        card.addView(row, matchWrap())
        return row
    }

    /** 导航行：标题 + 副标题 + 可选徽章 + 箭头 */
    fun navRow(
        ctx: Context,
        card: LinearLayout,
        label: String,
        desc: String,
        badge: String? = null,
        onClick: () -> Unit
    ): TextView? {
        val row = rowBase(ctx, onClick)

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Design.text(ctx, label, Design.Text.LABEL, Design.Color.INK))
        col.addView(
            Design.text(ctx, desc, Design.Text.MICRO, Design.Color.INK_MUTED).apply {
                setPadding(0, Design.dp(ctx, 3f), 0, 0)
            }
        )
        row.addView(col, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))

        var badgeView: TextView? = null
        if (badge != null) {
            badgeView = Design.text(ctx, badge, Design.Text.TAG, Design.Color.WARNING).apply {
                background = Design.outlinedBg(
                    ctx, Design.Color.WARNING_TINT_SOFT, Design.Color.WARNING_BORDER, 5f
                )
                val h = Design.dp(ctx, 6f)
                val v = Design.dp(ctx, 2f)
                setPadding(h, v, h, v)
            }
            row.addView(badgeView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Design.dp(ctx, Design.Space.S) })
        }

        row.addView(chevron(ctx))
        card.addView(row, matchWrap())
        return badgeView
    }

    // MARK: - 内部构件

    private fun rowBase(ctx: Context, onClick: (() -> Unit)?): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val v = Design.dp(ctx, 13f)
            setPadding(0, v, 0, v)
            // 触控区最少 48dp，符合可点击控件的最小尺寸
            minimumHeight = Design.dp(ctx, 48f)
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }

    private fun chevron(ctx: Context): TextView =
        Design.text(ctx, "›", 15f, Design.Color.INK_MUTED).apply {
            setPadding(Design.dp(ctx, Design.Space.S), 0, 0, 0)
        }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    /** 返回 (轨道, 滑块)，方便后续切换状态 */
    private fun toggleView(ctx: Context, checked: Boolean): Pair<FrameLayout, View> {
        val track = FrameLayout(ctx).apply {
            background = Design.roundedBg(
                ctx, if (checked) Design.Color.PRIMARY else Design.Color.TOGGLE_OFF, 11f
            )
        }
        val knobSize = Design.dp(ctx, 18f)
        val knob = View(ctx).apply {
            background = Design.circleBg(Design.Color.SURFACE)
        }
        track.addView(knob, FrameLayout.LayoutParams(knobSize, knobSize).apply {
            gravity = if (checked) Gravity.END or Gravity.CENTER_VERTICAL
            else Gravity.START or Gravity.CENTER_VERTICAL
            marginStart = Design.dp(ctx, 2f)
            marginEnd = Design.dp(ctx, 2f)
        })
        return track to knob
    }

    private fun applyToggle(track: FrameLayout, knob: View, checked: Boolean) {
        (track.background as? GradientDrawable)?.setColor(
            if (checked) Design.Color.PRIMARY else Design.Color.TOGGLE_OFF
        )
        (knob.layoutParams as FrameLayout.LayoutParams).gravity =
            if (checked) Gravity.END or Gravity.CENTER_VERTICAL
            else Gravity.START or Gravity.CENTER_VERTICAL
        knob.requestLayout()
    }
}
