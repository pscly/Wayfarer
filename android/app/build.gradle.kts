import java.util.Properties
import com.android.build.gradle.tasks.factory.AndroidUnitTest

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

val missingSentinel = "MISSING_WAYFARER_AMAP_API_KEY"
val amapKey: String = run {
    val fromGradleProp = (project.findProperty("WAYFARER_AMAP_API_KEY") as String?)?.trim().orEmpty()
    val fromEnv = System.getenv("WAYFARER_AMAP_API_KEY")?.trim().orEmpty()
    val fromLocal = localProperties.getProperty("WAYFARER_AMAP_API_KEY")?.trim().orEmpty()

    when {
        fromGradleProp.isNotBlank() -> fromGradleProp
        fromEnv.isNotBlank() -> fromEnv
        fromLocal.isNotBlank() -> fromLocal
        else -> missingSentinel
    }
}

// Base URL injected into BuildConfig.WAYFARER_API_BASE_URL.
// Override priority (highest -> lowest):
// 1) Gradle property: -PWAYFARER_API_BASE_URL=...
// 2) Environment variable: WAYFARER_API_BASE_URL
// 3) Per-buildType default (debug uses emulator-friendly localhost).
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

    defaultConfig {
        applicationId = "com.wayfarer.android"
        minSdk = 33
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Inject AMap key into AndroidManifest.xml via placeholders.
        // Placeholder name follows the plan text (${AMAP_API_KEY}) while value source
        // remains WAYFARER_AMAP_API_KEY.
        manifestPlaceholders["AMAP_API_KEY"] = amapKey
    }

    buildTypes {
        debug {
            val url = resolveWayfarerApiBaseUrl("http://10.0.2.2:8000")
            buildConfigField("String", "WAYFARER_API_BASE_URL", "\"$url\"")
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

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
