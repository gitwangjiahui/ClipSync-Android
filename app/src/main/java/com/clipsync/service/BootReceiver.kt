package com.clipsync.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机 / 应用更新后的占位接收器。
 *
 * 旧版实现是直接 startForegroundService 拉起同步服务，但这违反了"用时打开、
 * 不用不启动"的诉求。现在改成只记一条日志：用户是否需要同步由他自己决定，
 * 在主界面点「启动同步」即可。
 *
 * 保留这个 receiver 是为了兼容旧版 manifest 里的静态注册，避免用户从旧版本
 * 升级上来之后系统还在投递开机广播却找不到处理者。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        // 开机/更新/快速启动：都不再自动拉起服务
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" ->
                Log.i("ClipSync", "ℹ️ 系统广播 $action 已收到，但不会自动启动同步服务")
        }
    }
}
