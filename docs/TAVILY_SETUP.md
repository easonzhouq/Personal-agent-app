# Tavily 联网搜索配置

在项目根目录的 `local.properties` 增加一行：

```properties
TAVILY_API_KEY=tvly-your-key
```

然后重新构建 APK：

```bash
./gradlew assembleDebug
```

`local.properties` 不会提交到 GitHub。Tavily Key 会被编译进 APK，因此该方式只适合个人体验；正式发布应改成后端代理，避免 API Key 被提取。

天气查询仍使用无需 API Key 的 Open-Meteo。
