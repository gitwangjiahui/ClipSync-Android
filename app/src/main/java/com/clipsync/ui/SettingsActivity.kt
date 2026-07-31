package com.clipsync.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 设置主页：只作为路由，展示两张入口卡片。
 * - 功能设置：服务器地址、Token、剪贴板同步开关
 * - 权限设置：短信 & 通知权限、通知监听、无障碍、电池优化
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "设置"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 64)
        }

        // 卡片：功能设置（蓝色色条）
        container.addView(
            entryCard(
                title = "功能设置",
                subtitle = "服务器地址、Token、剪贴板同步",
                barColor = 0xFF3B82F6.toInt()
            ) {
                startActivity(Intent(this, FuncSettingsActivity::class.java))
            },
            cardParams()
        )

        // 卡片：权限设置（橙色色条）
        container.addView(
            entryCard(
                title = "权限设置",
                subtitle = "短信 & 通知、通知监听、无障碍、电池优化",
                barColor = 0xFFF59E0B.toInt()
            ) {
                startActivity(Intent(this, PermissionSettingsActivity::class.java))
            },
            cardParams()
        )

        scroll.addView(container)
        setContentView(scroll)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // MARK: - UI 辅助

    /** 一整张"点击进入"入口卡片：左侧色条 + 标题/副标题 + 右侧箭头 */
    private fun entryCard(
        title: String,
        subtitle: String,
        barColor: Int,
        onClick: () -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(0xFFFFFFFF.toInt(), 20f)
            setPadding(28, 28, 28, 28)
            elevation = 4f
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        // 左侧色条
        val bar = View(this).apply {
            background = roundedBg(barColor, 4f)
            layoutParams = LinearLayout.LayoutParams(12, 64)
        }
        card.addView(bar)

        // 中间：标题 + 副标题
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { leftMargin = 20 }
        }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF1F2937.toInt())
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, 6, 0, 0)
        })
        card.addView(textCol)

        // 右侧箭头
        card.addView(TextView(this).apply {
            text = "›"
            textSize = 28f
            setTextColor(0xFF9CA3AF.toInt())
            setPadding(16, 0, 8, 0)
        })

        return card
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = 24 }

    private fun roundedBg(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }
}
