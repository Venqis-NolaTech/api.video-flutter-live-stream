# Host app: dependencia Git del plugin

El monorepo consume `apivideo_live_stream` desde GitHub, por ejemplo:

```yaml
# modules/features/feature_live/pubspec.yaml
apivideo_live_stream:
  git:
    url: https://github.com/Venqis-NolaTech/api.video-flutter-live-stream.git
    ref: android-16kb-v120  # actualizar al SHA o tag tras publicar (p. ej. v1.3.0)
```

Después de **push** de la rama `android-16kb-v120` (o de crear un tag `v1.3.0`), actualiza **`ref`** al commit o tag publicado y ejecuta `dart pub get` en la raíz del monorepo (`melos bootstrap` si aplica).
