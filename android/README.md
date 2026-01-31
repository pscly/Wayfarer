# Wayfarer Android

This directory is a standalone Android Gradle project.

## Prerequisites

- Windows
- Android Studio (or Android SDK installed)
- JDK 17

## AMap (Gaode) API key

The AMap key is injected into `android/app/src/main/AndroidManifest.xml` via
`manifestPlaceholders`.

The manifest meta-data name must be exactly:

- `com.amap.api.v2.apikey`

Key source priority (highest -> lowest):

1) Gradle project property:

   `-PWAYFARER_AMAP_API_KEY=...`

2) Environment variable:

   `WAYFARER_AMAP_API_KEY`

3) `android/local.properties` (local-only, gitignored)

   You can add this alongside `sdk.dir=...`:

   `WAYFARER_AMAP_API_KEY=...`

Important:

- Do NOT commit real API keys.
- `.env` files are NOT automatically loaded by Gradle. If you want to use the env var
  path, export it in your shell/session before running Gradle.

## Missing key behavior (CI-safe)

If the key is missing (blank/sentinel), the app:

- shows a clear "AMap API key missing" screen
- skips `MapView` initialization

This keeps `gradlew.bat test` and `gradlew.bat assembleDebug` CI-safe.

## Commands

From `android/`:

- Unit tests: `gradlew.bat test`
- Debug APK: `gradlew.bat assembleDebug`

## Non-ASCII Windows paths

This repo path contains non-ASCII characters. Android Gradle Plugin may warn/fail on
Windows when the project path is non-ASCII.

Mitigations:

- `android/gradle.properties` enables `android.overridePathCheck=true` (the older `com.android.build.gradle.overridePathCheck` flag was removed in AGP 8.2.2).
- On non-ASCII paths, `app/build.gradle.kts` also swaps the default AGP unit test tasks
  with a tiny JUnit4 launcher to keep `gradlew.bat test` working.
- If you still hit toolchain errors (AAPT2/resource processing/Gradle classpath), the
  most reliable workaround is to build from an ASCII-only path.

One practical option is to use `subst` to map an ASCII drive letter:

1) `subst W: "F:\\ai_codes\\3其他\\1Wayfarer"`
2) Open a new shell and run Gradle from `W:\\android`.
