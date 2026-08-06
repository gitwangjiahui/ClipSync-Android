package com.clipsync.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 权限状态判断，从 PermissionSettingsActivity 里抽出来。
 *
 * 抽出的原因：设置页要在「权限」这一行显示「N 项待开启」的徽章，
 * 需要和权限页用同一套判断标准，否则两处显示会不一致。
 */
object PermissionStatus {

    /** 一项权限的状态：标题、是否就绪、以及为什么需要它 */
    data class Item(val label: String, val granted: Boolean, val why: String)

    fun runtimePermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            @Suppress("DEPRECATION")
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return list.toTypedArray()
    }

    fun missingRuntimePermissions(ctx: Context): Array<String> =
        runtimePermissions().filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    fun hasRuntimePermissions(ctx: Context): Boolean =
        missingRuntimePermissions(ctx).isEmpty()

    fun batteryUnrestricted(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** 用户在系统设置里勾了「通知使用权」 */
    fun notificationListenerEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(
            ctx.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return flat.split(":").any { it.contains(ctx.packageName) }
    }

    /**
     * 监听服务是否真的被系统绑定上了。
     * 只看 enabled 不够：MIUI 会拦截绑定请求，勾了开关但服务没连上。
     */
    fun notificationListenerLive(ctx: Context): Boolean = try {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
        val method = nm.javaClass.getMethod("getActiveNotificationListeners")
        @Suppress("UNCHECKED_CAST")
        val list = method.invoke(nm) as? List<ComponentName>
        list?.any { it.packageName == ctx.packageName } == true
    } catch (e: Exception) {
        notificationListenerEnabled(ctx)
    }

    /**
     * 四项检查，顺序即引导顺序。
     *
     * 之前还有一项「自启动允许」（跳 MIUI 安全中心），但「系统自启动」与用户的
     * 诉求冲突：开 App 才同步、划掉就停、不需要 ROM 把 ClipSync 拉起来。
     * 这条引导也一并去掉，剩四项都是后台保活必须的最小集合。
     */
    fun all(ctx: Context): List<Item> {
        return listOf(
            Item("短信与通知权限", hasRuntimePermissions(ctx), "读取短信内容、发送本机通知"),
            Item("通知使用权", notificationListenerEnabled(ctx), "绕开厂商的短信广播拦截"),
            Item("通知监听已连接", notificationListenerLive(ctx), "服务真正被系统绑定，短信才会触发"),
            Item("电池策略无限制", batteryUnrestricted(ctx), "防止后台被系统冻结")
        )
    }

    fun pendingCount(ctx: Context): Int = all(ctx).count { !it.granted }
}
