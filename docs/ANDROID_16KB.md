# Android 16 KB page size — Venqis notes

## Baseline

- Source baseline: **upstream Git tag [`v1.2.0`](https://github.com/apivideo/api.video-flutter-live-stream/releases/tag/v1.2.0)** (commit `a153f5d` on `apivideo/api.video-flutter-live-stream`).
- Branch: `android-16kb-v120` — toolchain-only changes on top of that tag (no StreamPack 3 / `SingleStreamer` migration in this workstream).

## Toolchain applied (plugin `android/build.gradle`)

| Component | Value |
|-----------|--------|
| Android Gradle Plugin | 8.7.2 (≥ 8.5.1) |
| Gradle wrapper (`android/` + `example/android/`) | 8.9 |
| Kotlin | 2.1.10 |
| Java | 17 |
| `compileSdk` (library) | 36 |
| StreamPack (legacy coordinates) | `2.6.1` (`io.github.thibaultbee:streampack`, `streampack-extension-rtmp`) |

The **example app** uses Flutter’s `flutter.compileSdkVersion`, `flutter.ndkVersion`, and the **declarative** Flutter Gradle plugin (`settings.gradle` + `dev.flutter.flutter-gradle-plugin`), required for current Flutter SDKs.

## Verificación de bibliotecas nativas

From the repository root, after a **release** build of the example app:

```bash
./scripts/verify_android_16k_page_size.sh example/build/app/outputs/flutter-apk/app-release.apk
```

Requirements: `llvm-readelf` on `PATH` (bundled with Android NDK / LLVM).

### Resultado actual (ejemplo `app-release.apk`)

| Library | arm64-v8a / x86_64 max LOAD align |
|---------|-------------------------------------|
| `libflutter.so`, `libapp.so` | OK (0x10000) |
| `librtmp.so`, `librtmpdroid.so`, `libssl.so`, `libcrypto.so` | **FAIL** (0x1000) |

These four come from the **StreamPack RTMP extension** stack. Rebuilding the app with **AGP 8.7 + NDK r28** does **not** relink third-party `.so` inside prebuilt AARs; alignment is determined by how those artifacts were **published**.

**Conclusion:** achieving **full** 16 KB ELF alignment for RTMP requires **newer StreamPack artifacts** that ship 16 KB–aligned JNI (typically the **3.x** modular stack and the corresponding Kotlin integration — separate, non-minimal migration), **or** an upstream rebuild of the 2.x RTMP extension.

## App host (monorepo producción)

- Set **`ndkVersion`** to **NDK r28 or newer** (Flutter often exposes this via `flutter.ndkVersion`).
- Keep **target / compile SDK** in line with [Google Play target API](https://developer.android.com/google/play/requirements/target-sdk) and plugin requirements (often **compileSdk 35+**).
- Run the same `verify_android_16k_page_size.sh` on the **staging/production AAB or APK** before release.

## Google Play / Android 15+

- ZIP alignment for 16 KB devices is checked separately (`zipalign -P 16` / Bundletool where applicable); the script attempts APK `zipalign` when ELF checks pass.
- Pre-launch report and Play Console warnings should be monitored after bumping AGP / NDK.

## Risks

| Risk | Mitigation |
|------|------------|
| StreamPack **2.x** JNI remains 4 KB–aligned | Plan migration to StreamPack **3.x** (or vendor-specific rebuild) when product accepts API/Kotlin scope. |
| Kotlin / AGP drift vs consuming app | Align `settings.gradle` Kotlin and AGP with the host Flutter app. |
| Example app Gradle template | Example was migrated to **Flutter 3.38+** plugin apply; older hosts may need analogous `settings.gradle` / `app` changes. |

## Checklist post-migración

1. `./scripts/verify_android_16k_page_size.sh` on release APK/AAB (expect FAIL on RTMP `.so` until StreamPack upgrades).
2. `adb shell getconf PAGE_SIZE` on test hardware (16384 on 16 KB test devices).
3. Manual: camera preview, start/stop RTMP, rotation (regression).
4. Play Console: targetSdk policy + any new 16 KB / alignment notices.
