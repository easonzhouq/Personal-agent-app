# Agent Chat Android

第一阶段是本机优先的 Android 聊天客户端：模型配置、流式对话、本地历史、语音输入和附件草稿。生产依赖由 `AgentChatApplication` 创建的 `AppContainer` 统一持有，Activity 只负责 Compose 导航和 Activity Result 回调。

## 开发环境

- Android Studio（建议使用当前稳定版）
- JDK 17
- Android SDK Platform 37、Build Tools 和 Platform Tools
- Android 模拟器或已连接的 Android 设备

当前工程的 `compileSdk`/`targetSdk` 为 37。部分 Android Studio/SDK Manager 版本会把预览平台目录命名为 `android-37.0`；这与工程的 API 37 配置兼容。运行前可用下面的命令确认本机目录和 `platforms;android-37` 的可见性：

```bash
test -d /Users/shmizhouyicheng/Library/Android/sdk/platforms/android-37.0
/Users/shmizhouyicheng/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager --list | grep 'platforms;android-37'
```

如果本机 SDK Manager 使用了不同的预览目录，请以 `sdkmanager --list` 显示的确切 package id 安装对应平台，再重新同步 Gradle。

## 构建与测试

在 `android-agent` 目录执行：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/Users/shmizhouyicheng/Library/Android/sdk
export GRADLE_USER_HOME="$PWD/.gradle-user-home"

./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew lintDebug
./gradlew assembleDebug
```

`connectedDebugAndroidTest` 需要已启动的 API 37 设备或模拟器。若 Gradle wrapper 尚未缓存 8.9，需要先允许 Gradle 下载 distribution；不要把网络失败报告为测试通过。

测试分类：`testDebugUnitTest` 只保留纯 Kotlin 的 Provider、SSE、Domain、Markdown Exporter 和使用测试 dispatcher 的 ViewModel 测试；依赖 Android `Uri`、`ContentResolver`、`Base64`、`Handler/Looper`、Keystore、Room 或 Activity 的测试放在 `src/androidTest`，通过 `connectedDebugAndroidTest` 在真实 Android runtime 验证。没有设备时该命令应真实失败并报告 `No connected devices!`，不能通过跳过测试掩盖环境缺失。

## 模型配置

在“模型配置”页填写显示名称、Base URL、模型名称和协议，保存时可设为默认，也可以从列表中选择“使用此模型”。当前生产 provider 是 OpenAI-compatible SSE；API Key 由 Android Keystore 加密后仅保存在本机，不写入 Room、Logcat 或 Markdown 导出。导出内容也不会包含附件 `contentUri`。

## 权限与数据边界

- `INTERNET`：调用用户配置的模型服务。
- `RECORD_AUDIO`：语音输入；拒绝后保持文字输入可用。
- 附件通过系统文档选择器访问，并只保留必要的持久化 URI 权限。
- 当前附件支持图片、纯文本、Markdown 和 JSON；PDF 暂不提供标准 provider payload，因此不会出现在选择器中。
- 会话、消息和附件元数据保存在本机 Room；API Key 不在 Room 中。
- 历史页的“清空本地数据”会二次确认并清除本机 Room 会话/消息/附件/模型配置、Keystore API Key 和持久化附件 URI 权限。

第一阶段不包含联网搜索、手机自动化、新工具执行器或云端会话同步。它们属于后续 Phase 2/3 的边界，需要另行设计权限、网络和数据同步策略。

`domain/tool/PhaseHandoffContracts.kt` 仅提供 Phase 2/3 的 typed handoff contracts；Phase 1 不调用 `ActionExecutor`，也不能构造 `ConfirmedAgentAction`。
