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

## 正式版（release）签名配置

本工程的 `release` 构建会在“生成 APK/AAB”时强制要求配置签名，否则会直接失败并提示缺失项（避免误产出未签名/不可安装的 APK）。

签名配置来源优先级（从高到低）：

1) Gradle project property：`-P<KEY>=...`
2) 环境变量：`<KEY>=...`
3) `android/local.properties`（仅本地，已 gitignore）

需要提供的 KEY：

- `WAYFARER_ANDROID_KEYSTORE_PATH`（相对 `android/` 的路径或绝对路径，例如：`keystore.jks`）
- `WAYFARER_ANDROID_KEYSTORE_PASSWORD`
- `WAYFARER_ANDROID_KEY_ALIAS`
- `WAYFARER_ANDROID_KEY_PASSWORD`

示例（本地构建 release APK）：

```bash
cd android
export WAYFARER_ANDROID_KEYSTORE_PATH="keystore.jks"
export WAYFARER_ANDROID_KEYSTORE_PASSWORD="***"
export WAYFARER_ANDROID_KEY_ALIAS="***"
export WAYFARER_ANDROID_KEY_PASSWORD="***"
./gradlew :app:assembleRelease
```

## GitHub Actions 一键打包 APK（正式版/Benchmark）

仓库内提供了 `Android APK` Workflow：`.github/workflows/android-apk.yml`。

你可以在 GitHub → Actions → `Android APK`：

- 手动触发（workflow_dispatch）
  - `variant=release`：构建**正式签名** APK（需要配置签名 secrets）
  - `variant=benchmark`：构建**调试签名** APK（可直接安装，适合内部测试）
- 打 tag 自动触发（push tags）
  - 推送 `android-v*`（例如 `android-v0.2.0`）会自动构建 `release` APK，并发布 GitHub Release（把 APK 作为附件上传）

需要在 GitHub Secrets 配置（release 必需）：

- `ANDROID_KEYSTORE_BASE64`：JKS/keystore 文件的 base64（单行）
- `WAYFARER_ANDROID_KEYSTORE_PASSWORD`
- `WAYFARER_ANDROID_KEY_ALIAS`
- `WAYFARER_ANDROID_KEY_PASSWORD`

建议配置（正式版建议必须提供，否则地图会降级/不可用）：

- `WAYFARER_AMAP_API_KEY`

在 Linux 生成 base64（单行）示例：

```bash
base64 -w0 keystore.jks
```

## Windows 非 ASCII 路径

本仓库路径包含非 ASCII 字符。Android Gradle Plugin 在 Windows 上可能因此警告/失败。

已做的缓解：

- `android/gradle.properties` 启用 `android.overridePathCheck=true`
- 在非 ASCII 路径下，`app/build.gradle.kts` 会用一个极简的 JUnit4 launcher 替换默认的 AGP unit test tasks，以尽量保证 `gradlew.bat test` 可用

如果仍遇到工具链错误（AAPT2/resource processing/Gradle classpath 等），最可靠的方式是将工程放在 ASCII-only 路径下构建。

一个实用方案：使用 `subst` 映射一个 ASCII 盘符：

1) `subst W: "F:\\ai_codes\\3其他\\1Wayfarer"`
2) 打开新 shell，在 `W:\\android` 下运行 Gradle。
