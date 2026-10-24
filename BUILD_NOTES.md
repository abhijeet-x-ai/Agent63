# Building DevStation (Agent63)

## Requirements

- JDK 17
- Android SDK: platform `android-34`, build-tools `34.0.0`
- Gradle 8.3 (provided by the Gradle wrapper in this repo)

## Local build

```bash
./gradlew testDebugUnitTest   # run the unit test suites
./gradlew assembleDebug       # build the debug APK
```

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`

## CI build (GitHub Actions)

The workflow `.github/workflows/build-apk.yml` runs the unit tests and builds
the debug APK on GitHub-hosted runners for every push to `main`, every pull
request targeting `main`, and on demand via **workflow_dispatch** (Actions tab
-> "Build Android APK" -> "Run workflow"). On pushes to `main` it also
publishes the APK to the project release.

## App facts

- versionName `1.1.5` (versionCode 7)
- minSdk 21, targetSdk 34, compileSdk 34
- Kotlin 1.9.22, Jetpack Compose, Material 3, Room
