# WARP TV Companion

一个面向 Android TV 的轻量遥控器界面，用于开启或关闭电视上安装的官方 Cloudflare WARP（1.1.1.1）应用。

项目不会修改、反编译、重签名或重新打包 Cloudflare APK。它通过 Android AccessibilityService 打开官方应用，并在用户主动操作后模拟触摸已有的 WARP 控件。

> [!IMPORTANT]
> 本项目是非官方、非商业的个人工具，与 Cloudflare, Inc. 没有隶属、赞助或认可关系。Cloudflare、WARP 和 1.1.1.1 是其各自权利人的商标。

## 功能

- 适合 Android TV 遥控器的简洁界面
- 使用 `ConnectivityManager` 检测 VPN 状态
- 动态打开官方 Cloudflare 应用，不依赖内部 Activity 类名
- 通过 Accessibility 节点定位 WARP 开关
- 自动选择“直到我重新开启为止”完成永久关闭
- 等待关闭弹窗第五项边界稳定后只派发一次点击，避免动画期间误点相邻选项
- 节点中心动态点击，并为已验证的 1080p Sony BRAVIA 提供坐标 fallback
- 等待 Cloudflare 界面明确显示“已连接”后才判定开启成功
- 操作锁、分阶段超时、手势完成回调和错误提示
- 无广告、无分析、无网络请求、无需 root、无需 Shizuku、无需常驻 ADB

## 已验证环境

| 项目 | 配置 |
| --- | --- |
| 电视 | Sony BRAVIA 4K UR2 |
| 系统 | Android TV 10 / API 29 |
| 分辨率 | 1920 × 1080 |
| Cloudflare 包名 | `com.cloudflare.onedotonedotonedotone` |
| 应用包名 | `dev.local.warptvcompanion` |
| 最低 Android 版本 | API 23 |
| Target / Compile SDK | API 35 |

其他 Android TV 设备可能可以运行，但尚未验证。

## 工作原理

开启流程：

```text
Companion → 打开 Cloudflare → 定位 launchSwitch → dispatchGesture
          → 等待 VPN transport → 等待 Cloudflare 显示“已连接” → 返回
```

关闭流程：

```text
Companion → 打开 Cloudflare → 点击 launchSwitch → 等待关闭确认框
          → 定位“直到我重新开启为止” → 点击其 clickable 父节点
          → 等待 VPN transport 消失 → 返回
```

Cloudflare 的开关节点在部分 Android TV 上对 `AccessibilityNodeInfo.ACTION_CLICK` 返回 `true`，却不会产生真实触摸效果。因此本项目有意不使用 `ACTION_CLICK`，所有 Cloudflare 控件都通过 `AccessibilityService.dispatchGesture()` 操作。

## 前置条件

1. Android TV 设备。
2. 已安装官方 Cloudflare WARP / 1.1.1.1 应用。
3. WARP 已在官方应用内完成初始配置。
4. 在系统设置中启用 WARP TV Companion 的无障碍服务。

## 构建

需要：

- JDK 17 或 21
- Android SDK Platform 35
- Android SDK Build Tools 36.1.0
- 网络仅用于 Gradle 首次下载构建插件

```bash
git clone <your-repository-url>
cd "Android TV Warp"
./gradlew clean assembleDebug
```

生成的 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 不提交到仓库。Android Studio 通常会自动生成；命令行构建时也可以设置 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT`。

## 安装

开启电视的开发者选项和网络调试后：

```bash
adb connect TV_IP_ADDRESS:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 首次使用

1. 打开 **WARP TV Companion**。
2. 选择 **Open Accessibility Settings**。
3. 启用 **WARP TV Companion control**。
4. 返回应用。
5. 使用遥控器选择 **Turn On** 或 **Turn Off**。

应用只监听官方 Cloudflare 包产生的无障碍窗口事件。无障碍服务不会读取或上传网络流量、账号信息或其他应用内容。

## 调试

统一日志标签为 `WarpTV`：

```bash
adb logcat -c
adb logcat -s WarpTV:V '*:S'
```

确认 VPN 接口：

```bash
adb shell ip addr show tun0
```

典型的开启日志：

```text
launchSwitch found checked=false
tapNode ... center=(960.0,538.0)
dispatchGesture accepted=true
Gesture COMPLETED
VPN connected and Cloudflare UI shows 已连接
```

典型的关闭日志：

```text
launchSwitch found checked=true
Gesture COMPLETED at (960.0,538.0)
disable forever clickable parent found
Gesture COMPLETED at (960.0,754.32)
VPN disconnected
```

## 项目结构

```text
app/src/main/
├── AndroidManifest.xml
├── java/dev/local/warptvcompanion/
│   ├── AccessibilityUtils.kt
│   ├── MainActivity.kt
│   ├── VpnStateDetector.kt
│   ├── WarpAccessibilityService.kt
│   └── WarpConstants.kt
└── res/
    ├── layout/activity_main.xml
    ├── values/
    └── xml/accessibility_service_config.xml
```

## 已知限制

- VPN 状态检测表示设备上存在任意 VPN transport；它不会证明该 VPN 一定属于 Cloudflare。
- 当前中文 UI 匹配依赖“已连接”和“直到我重新开启为止”。Cloudflare 更改语言或文案后可能需要更新常量。
- 坐标 fallback `960,538` 和 `960,752` 仅在 1920×1080 Sony BRAVIA 上验证。正常情况下主开关使用节点中心；第五个关闭选项使用节点下半部，以避开相邻第四项。
- Cloudflare 改动资源 ID 或界面层级后，Accessibility 节点定位可能需要调整。
- 这是本地 sideload 工具，尚未为 Google Play 发布流程配置签名或商店素材。

## 隐私与安全

- 不包含网络请求代码。
- 不收集、存储或上传数据。
- 不包含 analytics、广告或第三方运行时 SDK。
- 不需要 root 权限。
- 不修改 Cloudflare 应用或其数据。

## 贡献

欢迎提交 issue 或 pull request。报告兼容性问题时，请附上：

- 电视型号和 Android 版本
- 屏幕分辨率
- Cloudflare 应用版本和界面语言
- 已脱敏的 `WarpTV` logcat
- 相关节点的 `uiautomator dump` 片段
