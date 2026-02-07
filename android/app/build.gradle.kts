import java.util.Properties
import com.android.build.gradle.tasks.factory.AndroidUnitTest
import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.kotlin.dsl.closureOf

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

val localProperties: Properties = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.isFile) {
        f.inputStream().use { props.load(it) }
    }
}

fun resolveWayfarerProp(key: String): String {
    val fromGradleProp = (project.findProperty(key) as String?)?.trim().orEmpty()
    val fromEnv = System.getenv(key)?.trim().orEmpty()
    val fromLocal = localProperties.getProperty(key)?.trim().orEmpty()

    return when {
        fromGradleProp.isNotBlank() -> fromGradleProp
        fromEnv.isNotBlank() -> fromEnv
        fromLocal.isNotBlank() -> fromLocal
        else -> ""
    }
}

val missingSentinel = "MISSING_WAYFARER_AMAP_API_KEY"
val amapKey: String = run {
    resolveWayfarerProp("WAYFARER_AMAP_API_KEY").ifBlank { missingSentinel }
}

val releaseKeystorePath = resolveWayfarerProp("WAYFARER_ANDROID_KEYSTORE_PATH")
val releaseKeystorePassword = resolveWayfarerProp("WAYFARER_ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = resolveWayfarerProp("WAYFARER_ANDROID_KEY_ALIAS")
val releaseKeyPassword = resolveWayfarerProp("WAYFARER_ANDROID_KEY_PASSWORD")

val releaseKeystoreFile = releaseKeystorePath.takeIf { it.isNotBlank() }?.let { rootProject.file(it) }

val isReleaseSigningConfigured =
    releaseKeystoreFile?.isFile == true &&
        releaseKeystorePassword.isNotBlank() &&
        releaseKeyAlias.isNotBlank() &&
        releaseKeyPassword.isNotBlank()

// Base URL injected into BuildConfig.WAYFARER_API_BASE_URL.
// Override priority (highest -> lowest):
// 1) Gradle property: -PWAYFARER_API_BASE_URL=...
// 2) Environment variable: WAYFARER_API_BASE_URL
// 3) Per-buildType default (debug/release/benchmark use production by default).
fun resolveWayfarerApiBaseUrl(defaultValue: String): String {
    val fromGradleProp = (project.findProperty("WAYFARER_API_BASE_URL") as String?)?.trim().orEmpty()
    val fromEnv = System.getenv("WAYFARER_API_BASE_URL")?.trim().orEmpty()

    return when {
        fromGradleProp.isNotBlank() -> fromGradleProp
        fromEnv.isNotBlank() -> fromEnv
        else -> defaultValue
    }
}

android {
    namespace = "com.wayfarer.android"
    compileSdk = 34

    signingConfigs {
        if (isReleaseSigningConfigured) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.wayfarer.android"
        minSdk = 33
        targetSdk = 34
        versionCode = 5
        versionName = "0.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Inject AMap key into AndroidManifest.xml via placeholders.
        // Placeholder name follows the plan text (${AMAP_API_KEY}) while value source
        // remains WAYFARER_AMAP_API_KEY.
        manifestPlaceholders["AMAP_API_KEY"] = amapKey
    }

    buildTypes {
        debug {
            // 产品策略：默认直接连线上服务器，不要求用户手动配置服务器地址。
            // 如需本地联调，可通过 -PWAYFARER_API_BASE_URL 或环境变量 WAYFARER_API_BASE_URL 覆盖。
            val url = resolveWayfarerApiBaseUrl("https://waf.pscly.cc")
            buildConfigField("String", "WAYFARER_API_BASE_URL", "\"$url\"")
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            if (isReleaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }

            val url = resolveWayfarerApiBaseUrl("https://waf.pscly.cc")
            buildConfigField("String", "WAYFARER_API_BASE_URL", "\"$url\"")
        }

        // Non-debug build for performance testing. Installable by default (debug keystore).
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")

            // Keep benchmark installable without configuring a real keystore.
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false

            val url = resolveWayfarerApiBaseUrl("https://waf.pscly.cc")
            buildConfigField("String", "WAYFARER_API_BASE_URL", "\"$url\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.02"))

    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // AMap SDK (MapView + AMap).
    implementation("com.amap.api:3dmap-location-search:latest.integration")

    // Play Services Location (includes ActivityRecognitionClient).
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Room (local persistent store for track points).
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Minimal HTTP client for backend sync/login.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Background sync.
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    testImplementation("junit:junit:4.13.2")
}

val hasNonAsciiPath = rootProject.projectDir.absolutePath.any { it.code > 127 }

gradle.taskGraph.whenReady(
    closureOf<TaskExecutionGraph> {
        val wantsReleaseApkOrAab = allTasks.any { task ->
            val n = task.name.lowercase()
            (n.startsWith("assemble") || n.startsWith("bundle")) && n.contains("release")
        }

        if (wantsReleaseApkOrAab && !isReleaseSigningConfigured) {
            throw GradleException(
                """
                Release 签名未配置，无法生成“正式版”APK/AAB。

                你可以通过以下任一方式提供签名配置（优先级：Gradle 参数 > 环境变量 > local.properties）：

                - WAYFARER_ANDROID_KEYSTORE_PATH（相对 android/ 的路径或绝对路径，例如：keystore.jks）
                - WAYFARER_ANDROID_KEYSTORE_PASSWORD
                - WAYFARER_ANDROID_KEY_ALIAS
                - WAYFARER_ANDROID_KEY_PASSWORD

                本地建议写入 android/local.properties（已 gitignore），CI 建议用 GitHub Secrets 注入。
                """.trimIndent(),
            )
        }
    },
)

if (hasNonAsciiPath) {
    // AndroidUnitTest uses a forked JVM under the hood; on Windows + non-ASCII paths the
    // generated classpath can become unreadable, resulting in ClassNotFoundException for test classes.
    // We disable the AGP tasks and replace them with a tiny, direct JUnit4 launcher that discovers
    // tests from compiled test class directories.
    tasks.withType<AndroidUnitTest>().configureEach {
        enabled = false
    }

    fun minimalTestDeps(configurationName: String) =
        configurations.getByName(configurationName).filter { f ->
            f.name.startsWith("junit-") ||
                f.name.startsWith("hamcrest-") ||
                f.name.startsWith("kotlin-stdlib")
        }

    val portableTestDebugUnitTest = tasks.register<JavaExec>("portableTestDebugUnitTest") {
        dependsOn("compileDebugUnitTestKotlin", "compileDebugUnitTestJavaWithJavac")
        mainClass.set("com.wayfarer.android.test.PortableTestMain")
        classpath = files(
            minimalTestDeps("debugUnitTestRuntimeClasspath"),
            // Main classes (tests depend on production code).
            layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
            layout.buildDirectory.dir("intermediates/javac/debug/classes"),
            layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"),
            layout.buildDirectory.dir("intermediates/javac/debugUnitTest/classes"),
        )
        doFirst {
            setArgs(
                listOf(
                    layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest").get().asFile.absolutePath,
                    layout.buildDirectory.dir("intermediates/javac/debugUnitTest/classes").get().asFile.absolutePath,
                ),
            )
        }
    }

    val portableTestReleaseUnitTest = tasks.register<JavaExec>("portableTestReleaseUnitTest") {
        dependsOn("compileReleaseUnitTestKotlin", "compileReleaseUnitTestJavaWithJavac")
        mainClass.set("com.wayfarer.android.test.PortableTestMain")
        classpath = files(
            minimalTestDeps("releaseUnitTestRuntimeClasspath"),
            // Main classes (tests depend on production code).
            layout.buildDirectory.dir("tmp/kotlin-classes/release"),
            layout.buildDirectory.dir("intermediates/javac/release/classes"),
            layout.buildDirectory.dir("tmp/kotlin-classes/releaseUnitTest"),
            layout.buildDirectory.dir("intermediates/javac/releaseUnitTest/classes"),
        )
        doFirst {
            setArgs(
                listOf(
                    layout.buildDirectory.dir("tmp/kotlin-classes/releaseUnitTest").get().asFile.absolutePath,
                    layout.buildDirectory.dir("intermediates/javac/releaseUnitTest/classes").get().asFile.absolutePath,
                ),
            )
        }
    }

    afterEvaluate {
        tasks.named("test").configure {
            dependsOn(portableTestDebugUnitTest)
            dependsOn(portableTestReleaseUnitTest)
        }
    }
}
