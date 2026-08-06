package com.clipsync.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * 与设计系统同源的弹窗。
 *
 * 换掉框架 AlertDialog 的两个原因：
 * 1. 风格不符——它用的是 Material 默认圆角、默认字号和纯文字按钮，
 *    和 App 里的卡片描边、字阶、主色按钮完全两套。
 * 2. 会挡按钮——带输入框时键盘弹起会把底部操作按钮压到屏幕外。
 *    这里内容区可滚动，并按键盘实际高度把弹窗整体上移（见 keepAboveKeyboard），
 *    键盘再高也压不住确认按钮。
 */
object DesignDialog {

    /**
     * 构建弹窗内容。
     *
     * @param body 调用方往这个容器里塞输入框等内容，为空则只有标题和说明
     * @param destructive 确认按钮用危险色，用于清空这类不可撤销操作
     * @param onConfirm 点确认后执行，弹窗已先关闭
     */
    fun show(
        ctx: Context,
        title: String,
        message: String? = null,
        confirmLabel: String = "保存",
        cancelLabel: String = "取消",
        destructive: Boolean = false,
        body: ((LinearLayout) -> Unit)? = null,
        onConfirm: () -> Unit
    ): Dialog {
        // 关掉默认标题栏：标题由我们自己按字阶画，留着会多一条灰边
        val dialog = Dialog(ctx).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        }
        val pad = Design.dp(ctx, Design.Space.XL)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Design.roundedBg(ctx, Design.Color.SURFACE, Design.Radius.CARD)
            setPadding(pad, pad, pad, Design.dp(ctx, Design.Space.L))
        }

        card.addView(
            Design.text(ctx, title, Design.Text.TITLE, Design.Color.INK, bold = true)
        )
        if (message != null) {
            card.addView(
                Design.text(ctx, message, Design.Text.CAPTION, Design.Color.INK_SECONDARY).apply {
                    setLineSpacing(0f, 1.45f)
                    setPadding(0, Design.dp(ctx, 6f), 0, 0)
                }
            )
        }

        // 内容区单独放进 ScrollView：小屏加大字体时也不会把按钮顶出弹窗
        if (body != null) {
            val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            body(content)
            // 第一个输入框自动聚焦并唤起键盘，省用户一次点击
            firstEditText(content)?.apply {
                requestFocus()
                setSelection(text.length)
            }
            val scroll = ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                addView(content)
            }
            card.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Design.dp(ctx, Design.Space.L) })
        }

        card.addView(
            buttonRow(ctx, confirmLabel, cancelLabel, destructive,
                onCancel = { dialog.dismiss() },
                onConfirm = {
                    dialog.dismiss()
                    onConfirm()
                }),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Design.dp(ctx, Design.Space.XL) }
        )

        dialog.setContentView(card)
        dialog.window?.apply {
            // 去掉框架自带的白底九宫格，否则新圆角外面会露出一圈旧背景
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val margin = Design.dp(ctx, 28f)
            val width = ctx.resources.displayMetrics.widthPixels - margin * 2
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            // 有输入框就直接把键盘带出来，纯确认类弹窗不打扰
            setSoftInputMode(
                if (body != null) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                else WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
            )
        }

        // 键盘弹起时把弹窗整体上移，让确认按钮始终露在键盘上方。
        // 用 insets 监听而不是 SOFT_INPUT_ADJUST_RESIZE：后者在新 API 已废弃，
        // 且对非全屏 Dialog 本来就不生效。
        keepAboveKeyboard(card)

        dialog.show()
        return dialog
    }

    /**
     * 让弹窗躲开键盘。
     *
     * 取键盘高度和弹窗底部的重叠量，把重叠部分作为向上位移补偿，
     * 键盘收起时归零。这样底部按钮在任何键盘高度下都点得到。
     */
    private fun keepAboveKeyboard(card: View) {
        card.setOnApplyWindowInsetsListener { view, insets ->
            val keyboard = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            if (keyboard <= 0) {
                view.translationY = 0f
            } else {
                val rect = android.graphics.Rect()
                view.getGlobalVisibleRect(rect)
                val screenBottom = view.resources.displayMetrics.heightPixels
                val overlap = rect.bottom - (screenBottom - keyboard)
                // 加一点余量，别让按钮正好贴着键盘边缘
                val gap = Design.dp(view.context, Design.Space.L)
                view.translationY =
                    if (overlap > 0) -(overlap + gap).toFloat() else view.translationY
            }
            insets
        }
    }

    /** 取消在左（描边），确认在右（主色实心），和底部悬浮层的主次关系一致 */
    private fun buttonRow(
        ctx: Context,
        confirmLabel: String,
        cancelLabel: String,
        destructive: Boolean,
        onCancel: () -> Unit,
        onConfirm: () -> Unit
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val cancel = Design.text(ctx, cancelLabel, Design.Text.LABEL, Design.Color.INK_SECONDARY)
            .apply {
                gravity = Gravity.CENTER
                background = Design.outlinedBg(
                    ctx, Design.Color.SURFACE, Design.Color.BORDER, Design.Radius.BUTTON
                )
                val v = Design.dp(ctx, 13f)
                setPadding(0, v, 0, v)
                isClickable = true
                setOnClickListener { onCancel() }
            }
        val confirmColor = if (destructive) Design.Color.DANGER else Design.Color.PRIMARY
        val confirm = Design.text(ctx, confirmLabel, Design.Text.LABEL, Design.Color.SURFACE, bold = true)
            .apply {
                gravity = Gravity.CENTER
                background = Design.roundedBg(ctx, confirmColor, Design.Radius.BUTTON)
                val v = Design.dp(ctx, 13f)
                setPadding(0, v, 0, v)
                isClickable = true
                setOnClickListener { onConfirm() }
            }
        row.addView(cancel, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))
        row.addView(confirm, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.25f
        ).apply { marginStart = Design.dp(ctx, Design.Space.M) })
        return row
    }

    /** 弹窗里的输入框：和设置页的行同一套描边和圆角 */
    private fun firstEditText(group: ViewGroup): EditText? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is EditText) return child
            if (child is ViewGroup) firstEditText(child)?.let { return it }
        }
        return null
    }

    /** 弹窗里的输入框：和设置页的行同一套描边和圆角 */
    fun input(ctx: Context, hint: String, value: String, inputType: Int): EditText =
        EditText(ctx).apply {
            this.hint = hint
            setText(value)
            this.inputType = inputType
            setSingleLine()
            textSize = Design.Text.LABEL
            setTextColor(Design.Color.INK)
            setHintTextColor(Design.Color.INK_MUTED)
            background = Design.outlinedBg(
                ctx, Design.Color.SUBTLE, Design.Color.BORDER, Design.Radius.INPUT
            )
            val h = Design.dp(ctx, 14f)
            val v = Design.dp(ctx, 13f)
            setPadding(h, v, h, v)
        }
}
