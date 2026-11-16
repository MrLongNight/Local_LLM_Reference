# Dependency and toolchain policy

Checked: 2026-11-16. Versions are fixed, not dynamic ranges. Dependabot checks Gradle, GitHub Actions and the llama.cpp submodule weekly; an update is accepted only after compatibility and build verification.

## Selected Android toolchain

| Component | Selected version | Decision |
|---|---:|---|
| Android Gradle Plugin | 9.5.1 | Current stable patch release checked on 2026-11-16 |
| Gradle Wrapper | 9.9.1 | Current stable Gradle; exceeds AGP 9.5 minimum 9.6.0 |
| JDK | 17 | Android/AGP toolchain baseline |
| Android compile/target SDK | 36 | Latest stable SDK Platform package exposed by the stable SDK repository in CI |
| Android Build Tools | 36.0.0 | Matching stable build-tools line |
| Android NDK | 30.0.16248370 (r30 LTS) | Current NDK LTS checked on 2026-11-16 |
| CMake | 3.31.6 | Matches the pinned llama.cpp revision's official Android example |
| kotlinx.coroutines | 1.11.0 | Current stable dependency checked on 2026-11-16 |
| AndroidX Activity | 1.13.0 | Current stable release checked on 2026-11-16 |
| JUnit Jupiter | 6.1.3 | Current stable release checked on 2026-11-16 |
| llama.cpp | `9fba51d2c9a1b5cb5f0c47986c031334e2d7663a` | Exact upstream commit; submodule, not moving branch |

Android API 37 exists as the Android 17 platform target, but the platform package was not present on the stable SDK channel used by the CI runner. The build therefore uses API 36, the latest stable SDK Platform package, rather than making the reproducible build depend on a preview-channel package. API 37 can be enabled when the SDK Platform moves to the stable channel and is available from the standard SDK repository.

## Kotlin compiler choice

AGP 9 enables built-in Kotlin support by default; this project uses that supported integration and does not apply a second `org.jetbrains.kotlin.android` plugin. The Kotlin compiler plugin is therefore managed by AGP rather than independently pinned in the root plugin block.

Kotlin 2.4.21 is the latest stable Kotlin release checked on 2026-11-16. However, the published Kotlin Gradle Plugin compatibility table for 2.4.21 currently lists full support only through Gradle 9.7.0 and AGP 9.3.1. The selected newer AGP 9.5.1 / Gradle 9.9.1 combination is outside that declared range. We do not force an unverified external KGP override into the latest AGP built-in Kotlin integration. Revisit as soon as JetBrains publishes a compatibility row covering the selected AGP/Gradle combination.

## CMake selection

CMake 4.5.0 was checked as the newest general CMake release on 2026-11-16. It is not silently substituted into the Android build yet: the exact pinned llama.cpp source's official Android sample uses CMake 3.31.6. That known configuration is retained for the first reproducible baseline. A move to CMake 4.x requires a separate CI build comparison, not just a version-number update.

## Upstream references

- AGP releases and Gradle compatibility: https://developer.android.com/build/releases/about-agp
- AGP 9.5.1 release: https://developer.android.com/build/releases/agp-9-5-0-release-notes
- Built-in Kotlin migration: https://developer.android.com/build/migrate-to-built-in-kotlin
- Kotlin releases: https://kotlinlang.org/docs/releases.html
- Kotlin/Gradle/AGP compatibility: https://kotlinlang.org/docs/gradle-configure-project.html
- Gradle 9.9.1 checksums and releases: https://gradle.org/release-checksums/
- Android SDK platform release notes: https://developer.android.com/tools/releases/platforms
- Android 17 SDK setup: https://developer.android.com/about/versions/17/setup-sdk
- Android NDK releases: https://developer.android.com/ndk/downloads
- AndroidX Activity releases: https://developer.android.com/jetpack/androidx/releases/activity
- JUnit releases: https://junit.org/junit5/docs/current/release-notes/
- kotlinx.coroutines releases: https://github.com/Kotlin/kotlinx.coroutines/releases
- llama.cpp source and Android example: https://github.com/ggml-org/llama.cpp/tree/9fba51d2c9a1b5cb5f0c47986c031334e2d7663a/examples/llama.android
- GitHub Actions releases: https://github.com/actions/checkout/releases, https://github.com/actions/setup-java/releases, https://github.com/gradle/actions/releases, https://github.com/actions/upload-artifact/releases

## Update acceptance

A Dependabot proposal is not automatically accepted. For each update:
1. Check the component's current stable release and official compatibility matrix.
2. Update only the relevant pin(s), including wrapper checksums when Gradle changes.
3. Run the clean Android build and inspect the produced APK artifact.
4. For native dependency changes, build both supported ABIs and verify an on-device smoke test before changing the pinned reference.
