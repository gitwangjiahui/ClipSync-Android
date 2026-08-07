package com.clipsync.ui

import android.content.Context
import android.content.res.Configuration

/**
 * 主题管理器：跟随系统深色/浅色模式切换。
 * 通过 [isDark] 判断当前主题，Design.Color 内部会根据它返回对应颜色。
 */
object ThemeManager {

    /** 当前是否深色模式 */
    var isDark: Boolean = false
        private set

    /** 初始化：读取系统夜间模式设置 */
    fun init(context: Context) {
        isDark = isSystemDark(context)
    }

    /** 刷新：系统配置变化时调用 */
    fun refresh(context: Context) {
        val newDark = isSystemDark(context)
        if (newDark != isDark) {
            isDark = newDark
        }
    }

    private fun isSystemDark(context: Context): Boolean {
        val nightFlag = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightFlag == Configuration.UI_MODE_NIGHT_YES
    }
}
