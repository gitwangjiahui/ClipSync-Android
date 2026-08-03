<p align="center">
  <img src="icon.png" width="120" alt="ClipSync"/>
</p>

# ClipSync-Android

<p align="center">
  <b>ClipSync 三端同步体系的 Android 客户端</b><br/>
  自动监听短信验证码、监控剪贴板、分享菜单推送文本/图片，实时同步到你的电脑。<br/>
  Kotlin + 前台服务 + 无障碍服务，针对国产 ROM 后台保活深度适配。
</p>

<p align="center">
  <a href="https://github.com/gitwangjiahui/ClipSync-Android/releases">⬇️ 下载 APK</a> ·
  <a href="https://github.com/orgs/gitwangjiahui/packages">📦 Packages (ghcr.io)</a> ·
  <a href="https://github.com/gitwangjiahui/ClipSync-Server">🖧 服务端</a> ·
  <a href="https://github.com/gitwangjiahui/ClipSync-Mac">🖥️ Mac 端</a>
</p>

---

## 一、它能干什么

| 能力 | 说明 | 实现方式 |
|---|---|---|
| **短信验证码同步** | 收到验证码短信自动识别 4-8 位数字并推给电脑 | `SmsReceiver` 广播 + `NotificationSmsListener` 通知监听（绕 MIUI 拦截） |
| **剪贴板自动同步** | 手机复制文本/图片 → 电脑收到并可自动写入剪贴板 | 无障碍服务后台读剪贴板（绕 Android 10+ 限制） |
| **分享菜单推送** | 任意 App 长按选中文字/图片 → 分享 → 选 ClipSync | `ShareActivity`（独立 task，推完回到来源 App） |
| **历史记录** | 本地保存推送过的内容，可回看 | `HistoryActivity` + 本地存储 |
| **开机自启** | 重启/升级后自动恢复同步服务 | `BootReceiver` |
| **后台保活** | 前台常驻通知 + 申请忽略电池优化 | `SyncService`(foregroundServiceType=dataSync) |

## 二、下载与安装

到 [Releases](https://github.com/gitwangjiahui/ClipSync-Android/releases) 下载 `ClipSync-vX.Y.Z-release.apk`（已签名，v2 签名方案）。

1. 传到手机打开安装（首次需开启「允许安装未知来源应用」）
2. 打开 App → 设置里填 **服务器地址**（如 `ws://192.168.1.100:8080`）和 **Token**（与电脑端一致即自动配对）
3. 按引导授予权限（见下节）→ 点「启动同步服务」→ 通知栏出现常驻通知即成功

### 权限清单及用途

| 权限 | 用途 |
|---|---|
| 短信（RECEIVE/READ_SMS） | 监听验证码 |
| 通知使用权 | MIUI 等拦截短信广播时的兜底读取通道 |
| 无障碍服务 | 后台读写剪贴板（Android 10+ 限制下的合规绕法） |
| 前台服务/唤醒锁 | 后台保活 |
| 忽略电池优化 | 防国产 ROM 杀后台 |
| 读媒体库 | 截图/图片剪贴板同步 |

## 三、消息协议（与服务端约定）

```json
{ "type": "notify_pc", "kind": "sms_code", "text": "【某银行】验证码 314159" }
```

- `notify_pc`：只发给 PC 端（验证码场景）
- `clipboard`：广播所有端，接收方按各自开关决定是否写入剪贴板
- 同 token 设备自动成组；服务端不存数据

## 四、开发环境搭建

### 1. 装 Android Studio

https://developer.android.com/studio ，首次打开引导装 SDK（API 34）。

### 2. 命令行环境变量

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator
source ~/.zshrc
```

### 3. 真机调试

设置 → 关于本机 → 连点版本号 7 次 → 开发者选项 → USB 调试 → 数据线连电脑选「文件传输」。

### 4. 运行

Android Studio Open 本目录 → Gradle Sync → 选设备 → ▶️（Shift+F10）。
或命令行：`./gradlew installDebug`。

## 五、打包

### Debug 包（自用）

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### Release 签名包（分发）

签名配置从 `local.properties` 读取（该文件已 gitignore，**密钥不进仓库**）：

```properties
clipsync.storeFile=../keystore/release.jks
clipsync.storePassword=***
clipsync.keyAlias=clipsync
clipsync.keyPassword=***
```

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk（自动签名）
```

没有 keystore 时 `signingConfigs` 不创建，打出来是未签名包（装不上，仅 CI 兜底）。

### CI 自动打包（推荐）

打 tag 即自动构建签名包并发 Release + 推容器包：

```bash
git tag v1.2.0 && git push origin v1.2.0
```

签名密钥以 base64 存在仓库 Secrets（`ANDROID_KEYSTORE_B64` 等四个），workflow 自动注入，本地无需任何配置。

## 六、项目结构

```
app/src/main/java/...
├── MainActivity.kt              # 主页：连接状态、启停服务
├── clip/ShareActivity.kt        # 分享菜单入口
├── clipboard/ClipReaderActivity.kt  # MIUI 读剪贴板兜底（透明一帧）
├── sms/SmsReceiver.kt           # 短信广播接收
├── sms/NotificationSmsListener.kt   # 通知监听兜底
├── accessibility/ClipSyncAccessibilityService.kt  # 后台剪贴板
├── service/SyncService.kt       # 前台常驻同步服务（WS 客户端宿主）
├── service/BootReceiver.kt      # 开机自启
└── ui/                          # 设置/功能设置/权限设置/历史
```

## 七、常见问题

| 问题 | 解决 |
|---|---|
| 收不到验证码 | 授予短信权限 + 通知使用权；关电池优化 |
| 剪贴板不同步 | 开启无障碍服务「ClipSync 剪贴板同步」 |
| 后台被杀 | 锁定 App / 加白名单 / 允许自启动 |
| 连不上服务端 | 同 WiFi；防火墙放行 8080；地址用 `ws://IP:8080` |
| 分享菜单没有 ClipSync | 重启系统或重装 |
