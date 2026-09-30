# Scripta integration

- Upstream: https://github.com/YuKongA/scripta
- Pinned commit: `23820ff3085016a7490b1c1bb1c9dc76168be052`
- License: Apache-2.0; LICENSE retained and bundled into the app.
- Editor source files are unchanged.
- `editor/reamicro.android.gradle` is an Android-only adapter using the parent
  project's Kotlin 2.4.20 / Compose 1.12.0 / AGP 9.2.1 and JDK 17.
  Upstream's Gradle file is preserved for reference; no sandbox app is included.
- Large editor documents must use `rememberCodeEditorController`, not Bundle-based
  saveable text state. Snapshot the text/version on the UI thread; write on IO.

- Full integration/limitations: `docs/epub-scripta-integration.md`.
