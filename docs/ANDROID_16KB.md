# Android 16 KB page size — Venqis notes

## Baseline

- Dart / MethodChannel API still aligned with upstream **`v1.2.0`**.
- Branch: **`android-16kb-v120`** — includes **toolchain 16 KB**, then **StreamPack 3.1.2** (`SingleStreamer`, `RtmpMediaDescriptor`, `FlutterLiveStreamView` refactor).

## Toolchain applied (plugin `android/build.gradle`)

| Component | Value |
|-----------|--------|
| Android Gradle Plugin | **8.9.1** (required by `androidx.core` 1.17+ pulled transitively) |
| Gradle wrapper (`android/` + `example/android/`) | **8.11.1** |
| Kotlin | **2.1.10** |
| Java | 17 |
| `compileSdk` (library) | 36 |
| `minSdk` (library) | **24** (raised with StreamPack 3.x) |
| StreamPack | **`io.github.thibaultbee.streampack:streampack-core`** + **`streampack-rtmp`** at **3.1.2** |

The **example app** uses Flutter’s `flutter.compileSdkVersion`, `flutter.ndkVersion`, and the declarative Flutter Gradle plugin (`settings.gradle` + `dev.flutter.flutter-gradle-plugin`).

## Verificación de bibliotecas nativas (`scripts/verify_android_16k_page_size.sh`)

From the repository root, after a **release** build of the example app:

```bash
./scripts/verify_android_16k_page_size.sh example/build/app/outputs/flutter-apk/app-release.apk
```

Requirements: `llvm-readelf` on `PATH` (Android NDK / LLVM). For `zipalign -P 16`, install Android **build-tools 35+** and ensure `zipalign` is on `PATH` (the script skips if missing).

### Resultado ejemplo `app-release.apk` (arm64-v8a / x86_64)

| Observation |
|---------------|
| **`libflutter.so`**, **`libapp.so`**: max LOAD align **OK** (`0x10000`). |
| The example APK from this repo’s **release** build currently packages **only** Flutter engine + app JNI for those ABIs; **separate** `librtmp*.so` / OpenSSL are **not** listed as top-level entries in that artifact (merge may differ in the full monorepo app). |

**Always re-run the script** on the **staging/production APK or AAB** built from [campaing-psoe-app-mobile](file:///Users/gperez/Documents/projects/venqis/campaing-psoe-app-mobile): that graph may include additional `.so` from all modules.

## App host (monorepo) — git dependency

[`modules/features/feature_live/pubspec.yaml`](file:///Users/gperez/Documents/projects/venqis/campaing-psoe-app-mobile/modules/features/feature_live/pubspec.yaml) points to this plugin via GitHub, e.g. `ref: android-16kb-v120`.

After you **push** commits from this branch (or tag a release, e.g. `v1.3.0-streampack3`), **update `ref:`** to that commit SHA or tag so CI and teammates resolve the StreamPack 3 build.

- Set **`ndkVersion`** to **NDK r28+** (via `flutter.ndkVersion` where applicable).
- Keep **targetSdk / compileSdk** per [Google Play target API](https://developer.android.com/google/play/requirements/target-sdk).

## Google Play / Android 15+

- Run **`zipalign -v -c -P 16 4`** on release APK when build-tools support it; use Bundletool for AAB alignment per [16 KB guidance](https://developer.android.com/guide/practices/page-sizes).
- Monitor Play Console pre-launch report for alignment / page-size notices.

## Risks

| Risk | Mitigation |
|------|------------|
| Preview / camera behaviour changes under `SingleStreamer` | Regression tests: preview, start/stop RTMP, camera toggle. Avoid double `startPreview` after `initialize()` in the host (`ApiVideoLiveStreamController`). |
| AGP / Kotlin vs host app drift | Align `settings.gradle` plugin versions with the root `android/` app when possible. |
| `minSdk` 24 | Drops API 21–23 devices; confirm product policy. |

## Checklist post-migración

1. `./scripts/verify_android_16k_page_size.sh` on **product** release APK/AAB.
2. `adb shell getconf PAGE_SIZE` on a 16 KB test device when available.
3. Manual: camera preview, start/stop RTMP, cold start.
4. Update **`ref:`** in `feature_live` after publishing this branch/tag.
5. Play Console: targetSdk + alignment warnings.
