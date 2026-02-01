# Wayfarer Android（安卓端）

本目录是一个独立的 Android Gradle 工程。

## 前置条件

- Windows
- Android Studio（或已安装 Android SDK）
- JDK 17

## 高德（AMap）API Key

高德 Key 会通过 `manifestPlaceholders` 注入到：

- `android/app/src/main/AndroidManifest.xml`

Manifest 的 meta-data name 必须严格为：

- `com.amap.api.v2.apikey`

Key 的来源优先级（从高到低）：

1) Gradle project property：

   `-PWAYFARER_AMAP_API_KEY=...`

2) 环境变量：

   `WAYFARER_AMAP_API_KEY`

3) `android/local.properties`（仅本地，已 gitignore）

   可以与 `sdk.dir=...` 同文件添加：

   `WAYFARER_AMAP_API_KEY=...`

重要说明：

- 不要提交真实 API Key。
- Gradle 不会自动加载 `.env`。如果使用环境变量方式，请在运行 Gradle 前在 shell/session 中提前导出环境变量。

## Key 缺失时行为（CI 安全）

当 Key 缺失（空值/占位符）时，App 会：

- 显示明确的“高德 API Key 缺失”提示
- 跳过 `MapView` 初始化

这样可以保持 `gradlew.bat test` 与 `gradlew.bat assembleBenchmark` 在 CI/无 Key 环境下仍可执行。

## 常用命令

在 `android/` 目录运行：

- 单元测试：`gradlew.bat test`
- Benchmark APK（交付推荐）：`gradlew.bat assembleBenchmark`
- Debug APK（仅开发用）：`gradlew.bat assembleDebug`

## Windows 非 ASCII 路径

本仓库路径包含非 ASCII 字符。Android Gradle Plugin 在 Windows 上可能因此警告/失败。

已做的缓解：

- `android/gradle.properties` 启用 `android.overridePathCheck=true`
- 在非 ASCII 路径下，`app/build.gradle.kts` 会用一个极简的 JUnit4 launcher 替换默认的 AGP unit test tasks，以尽量保证 `gradlew.bat test` 可用

如果仍遇到工具链错误（AAPT2/resource processing/Gradle classpath 等），最可靠的方式是将工程放在 ASCII-only 路径下构建。

一个实用方案：使用 `subst` 映射一个 ASCII 盘符：

1) `subst W: "F:\\ai_codes\\3其他\\1Wayfarer"`
2) 打开新 shell，在 `W:\\android` 下运行 Gradle。
