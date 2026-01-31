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

android {
    namespace = "com.wayfarer.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wayfarer.android"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Inject AMap key into AndroidManifest.xml via placeholders.
        // Placeholder name follows the plan text (${AMAP_API_KEY}) while value source
        // remains WAYFARER_AMAP_API_KEY.
        manifestPlaceholders["AMAP_API_KEY"] = amapKey
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
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
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // AMap SDK (MapView + AMap).
    implementation("com.amap.api:3dmap-location-search:latest.integration")

    // Play Services Location (includes ActivityRecognitionClient).
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Room (local persistent store for track points).
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

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
