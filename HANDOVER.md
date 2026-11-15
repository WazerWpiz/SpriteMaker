# HANDOVER — McpResprite project

**Written:** for the next agent picking this up. Read this before touching anything.

**Last verified state:** Build pipeline works (GitHub Actions), app installs and runs on device, but UI is still far from the original Resprite app. See STATUS below for exact truth.

---

## 1. WHAT THIS PROJECT IS

A Kotlin/Compose Android reimplementation ("clone") of **Resprite**, a commercial pixel-art editor. Target package id: `com.fengeon.resprite_desktop`. Goal: FOSS version with the same `.resprite` file format, no billing/trackers.

**Origin:** user extracted the original APK from their phone (`com.fengeon.resprite_desktop`, versionName 1.28.1, R35). Original is a **Flutter app** with AOT-compiled Dart in `libapp.so` (8.9 MB). Source CANNOT be recovered — no decompiler turns Flutter AOT back into Dart. This project is a clean-room rewrite against the file format, not a decompilation.

---

## 2. THE .resprite FILE FORMAT (fully reverse-engineered — this is the valuable part)

A `.resprite` file is a **ZIP container**:

```
tmp_<UUID>.resprite/
├── document.json        <- entire scene graph, authoritative
├── meta.json            <- {appVersion, canvasSize{w,h}, frameCount}
├── preview.png          <- gallery preview
├── thumbnail.png
├── CellData/
│   └── <cellUUID>.png   <- one PNG per cell (layer x frame)
└── ReferenceImages/
```

**`document.json` top-level keys** (recovered from a real sample):
`canvasSize{width,height}`, `gridWidth`, `gridHeight`, `isoGridHeight`, `frameDatas[]`, `layerDatas[]`, `paletteData`, `colorItemMap`, `colorItemTps`, `currFrameNo`, `currLayerNo`, `frameCount`, `frameRate`, `isSymmetricToolHorizontalOn`, `isSymmetricToolVerticalOn`, `symmetricToolPosHorizontal`, `symmetricToolPosVertical`, `showGrid`, `showIsoGrid`, `timelapseBgColor`, `timelapseRecording`, `timelapseSpeed`, `timelapseScaler`, `referenceImageDatas[]`, `generatorInfo`, `isPaletteLocked`, `noSplittingWhenLayerdExport`

- **`frameDatas[]`** item: `{id, clipName, clipDir(Forward/Reverse/...), duration, isClipHead}`
- **`layerDatas[]`** item: `{id, name, isLocked, isGroup, isGroupOpen, contentVisible, isClippingMask, blendMode, opacity, inherit, cells[]}`
- **`cells[]`** item: `{id, bounds{origin{x,y}, size{width,height}}, opacity, inherit}`
- **Cell PNGs are PADDED.** Real sample: cell bounds 73x109 stored a 96x120 PNG. Padding formula NOT yet derived — needed for true interop. Drop another sample `.resprite` and analyze more cells to derive it.

**Original app architecture** (from Dart symbols in libapp.so): strict **Command pattern**. Every action is a `*Cmd` class routed through `UndoManagerClient.handleUndo`. Recovered class names: `SpriteFlipCmd`, `SpriteResizeCmd`, `SwapSpriteColorCmd`, `CanvasFlipCmd`, `CanvasResizeCmd`, `CanvasRotateCmd`, `CreateLayerCmd`, `DeleteLayerCmd`, `DeleteLayerDirectCmd`, `DuplicateLayerCmd`, `MoveAndSelectLayerCmd`, `MergeLayerContentCmd`, `SetLayerPropertyCmd`, `ToggleInheritCmd`, `DeleteFrameCmd`, `DuplicateFrameCmd`, `PasteFrameCmd`, `MoveAndSelectFrameCmd`, `SetFramePropertyCmd`, `SetFrameClipPropertyCmd`, `ClearCellCmd`, `CropCellCmd`, `FillCellByMaskCmd`, `FillCellImageCmd`, `FlipCellCmd`, `PasteCellCmd`, `PasteCellContentCmd`, `SelectCellCmd`, `SetCellPosCmd`, `SetCellPropertyCmd`, `SetMaskCmd`, `TrimCellCmd`, `DeletePaletteColorCmd`, `SetPaletteColorCmd`, `ImportPaletteCmdSeq`, `AdjustColorCmd`, `OutlineCmd`.

Palettes: `.gpl` (GIMP) and `.rpl` (Resprite). Themes: `.resthm4d`. Bundled presets in original: Commodore64, Gameboy, Grayscale, Pico-8, Plastic17, ms-paint-basic, system-mini-16.

---

## 3. REPOSITORY + BUILD PIPELINE

- **GitHub repo:** https://github.com/WazerWpiz/SpriteMaker (user WazerWpiz)
- **Workspace copy:** `McpResprite/` on the Ubuntu proot target
- **Local staging copy:** `/tmp/sprmaker/` (git working tree, pushed to GitHub)
- **Build:** GitHub Actions, workflow at `.github/workflows/build.yml`
- **CI fix history (important — don't regress these):**
  1. Do NOT use `android-actions/setup-android@v3` — it tries to install Google's removed `tools` package and fails. Use the runner's preinstalled SDK + direct `sdkmanager` instead.
  2. `sdkmanager` is NOT on PATH on ubuntu runners. Workflow locates it via `find "$ANDROID_HOME/cmdline-tools" -maxdepth 3 -name sdkmanager` and exports PATH.
  3. Polling loops time out — the snapgent `run_command` has a **30 second limit**. Do single status checks with short sleeps, not long loops.

**Toolchain versions (working):** AGP 8.5.2, Kotlin 2.0.21, Compose BOM 2024.09.00, Gradle 8.7, JDK 17, compileSdk 34, minSdk 26, targetSdk 34.

**CRITICAL — Kotlin version constraint:** Kotlin 1.9.24 **crashes the IR backend** (`Backend Internal error: Exception during IR lowering`) on the gesture code in `CanvasView.kt`. This happened twice. Do not downgrade Kotlin below 2.0.x. Also required: the `org.jetbrains.kotlin.plugin.compose` plugin (Kotlin 2.0+ replaced the old compose compiler extension).

---

## 4. WHAT EXISTS IN THE CODEBASE

```
McpResprite/
├── settings.gradle.kts, build.gradle.kts, gradle.properties
├── gradle/wrapper/gradle-wrapper.properties   (Gradle 8.7)
├── README.md
├── HANDOVER.md  (this file)
├── tools/check_braces.py                      (static delimiter checker — run it)
├── .github/workflows/build.yml
└── app/
    ├── build.gradle.kts, proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/  (strings, colors, themes, adaptive icons, backup rules, file_paths)
        └── java/com/fengeon/resprite_desktop/
            ├── RespriteApp.kt
            ├── MainActivity.kt
            ├── model/Document.kt        (kotlinx.serialization, full .resprite schema)
            ├── model/Palette.kt         (Palette + 6 built-in presets)
            ├── format/RespriteFile.kt   (zip read/write for .resprite)
            ├── format/PaletteIO.kt      (.gpl/.rpl read/write)
            ├── commands/Command.kt      (Command interface + UndoManager)
            ├── commands/EditorState.kt  (live document + cell bitmaps)
            ├── commands/SpriteCommands.kt
            ├── commands/LayerFrameCommands.kt
            ├── commands/PaintStrokeCmd.kt
            ├── commands/ToolCommands.kt (FillCmd, ShapeCmd, Eyedropper)
            ├── commands/MoveCommands.kt (MoveCellCmd)
            ├── ui/RespriteTheme.kt
            └── ui/editor/
                ├── CanvasView.kt    (compositor, pan/zoom, checkerboard, gesture routing)
                ├── PalettePanel.kt
                └── EditorScreen.kt  (main UI — icon toolbar, layers panel, frame strip)
```

**Working features:** pencil, eraser, flood fill (tolerance-aware), line/rect/ellipse (stroke only, `filled` flag exists but no UI toggle), eyedropper, move cell, 6 palette presets, `.gpl`/`.rpl` IO, layers add/delete/select, frames add/select, undo/redo, open/save real `.resprite`, pinch zoom + two-finger pan.

**MISSING (roughly 60% of original):** animation playback + frame rate control, spritesheet import/export, GIF import/export, PSD export, select/crop/transform, gradient tool, symmetry tools, sprite resize/rotate/flip UI (commands exist, menu doesn't), reference images UI, timelapse recording, themes (.resthm4d), layer groups + clipping masks + blend modes (fields parsed but not composited), layer opacity slider / lock / visibility toggle UI, palette file picker button, zoom/pan UI controls, ~20 locale translations.

---

## 5. KNOWN BUGS / LANDMINES

1. **`RespriteFile.write()` drops cells not present in the cells map.** A cell referenced in document.json but missing a bitmap silently vanishes on save. Fix: write a blank PNG of correct dimensions for referenced-but-missing cells.
2. **Cell PNG padding not implemented.** We write raw bitmaps; original pads them. Our files round-trip through our reader but original Resprite may reject our cell rasters.
3. **`parseMeta` only captures string values** — canvasSize and frameCount don't make it into the returned map. Harmless (document.json is authoritative) but the name overpromises.
4. **MoveCellCmd shifts bounds origin, doesn't re-crop.** Moving a cell off-canvas clips it; no commit/merge semantics like Photoshop.
5. **Compose gesture detectors were a minefield.** The current `CanvasView.kt` uses a single `awaitEachGesture` handler that branches on pointer count. Do not reintroduce separate `detectTransformGestures` + `detectDragGestures` — they fight over the pointer stream.
6. **Do not use `by remember` delegates captured inside pointer lambdas, and do not use `rememberUpdatedState` on derived values.** Both crashed the Kotlin IR backend during compilation. Current code uses explicit `MutableState.value` and hoists coordinate math to a top-level function. Preserve that pattern.

---

## 6. SECURITY — ACTION REQUIRED

**A GitHub Personal Access Token was pasted in plaintext into a chat session** during this project (user WazerWpiz, used for pushing to the SpriteMaker repo — the literal value has been redacted from this document). **It must be revoked.** GitHub's push protection flagged it as a leaked secret, which is independent confirmation. If the next agent sees this token anywhere still working, tell the user to revoke it immediately at GitHub → Settings → Developer settings → Personal access tokens. Do not use it if a safer auth path exists.

The local staging copy `/tmp/sprmaker/.git/config` had its remote URL sanitized (token removed), but the token was used repeatedly over many pushes and is thoroughly exposed.

---

## 7. HONEST STATUS ASSESSMENT

As of last check: the app **builds, installs, and runs** (confirmed by user screenshot of the running app on a phone). But the UI is materially different from the original — it is a functional minimum, not a visual or feature match.

**If the next agent's goal is "make it identical to the real Resprite":** that is months of work, not turns. Be honest with the user about this. The original is a mature multi-year Flutter app with animation, multi-format export, theming, localization, and a polished pixel-art UI.

**Strongly recommended alternative to raise with the user:** fork an existing mature FOSS pixel editor instead of continuing this rewrite. Candidates:
- **Pixelorama** (Godot, GPL-3.0) — feature-rich, close in scope to Resprite
- **LibreSprite** (C++, GPL-2.0) — Aseprite fork
- **Aseprite source** (available on GitHub; the app itself is paid but source is open)

Forking one of these and rebranding gives a genuinely usable app in days. The current Kotlin project is a valid learning/starting scaffold but is not on a realistic path to parity without sustained effort.

**If the next agent's goal is to continue the scaffold:** next highest-value slices, in order:
1. Spritesheet export (self-contained, gives a real output artifact)
2. Animation playback strip + frame rate
3. GIF import/export
4. Select + crop + copy/paste cells
5. Palette file picker button + color picker dialog
6. Layer opacity slider + lock/visibility toggle
7. Derive the cell PNG padding rule (needs more sample files)

---

## 8. WORKING AGREEMENTS / ENVIRONMENT NOTES

- Target is an **Ubuntu 26.04 proot** on an aarch64 Android device, running as root (no sudo; `apt-get` works directly).
- **No JDK, no Android SDK, no Gradle on the target.** Do not attempt local builds — they cannot work (Android build-tools are x86_64-only; the device is arm64, emulation is impractical). Build via GitHub Actions only.
- `run_command` has a **30 second timeout**. No long polling loops.
- `write_file` / `create_file` are scoped to the workspace root — cannot write to `/tmp`. Write in workspace, then `cp` via `run_command`.
- The snapgent bridge drops intermittently. When it does, stop sending commands until the user confirms it's back.
- Static check available: `python3 McpResprite/tools/check_braces.py` — verifies delimiter balance across all Kotlin files. Run it after bulk edits. (Last run: 17 files, all balanced.)
- The `/tmp/sprmaker` git tree is the push source. Workspace `McpResprite/` is the edit source. After editing in workspace, `cp` to `/tmp/sprmaker`, then commit+push. Alternatively, tell the user to clone the repo fresh.

---

## 9. FILES THE ORIGINAL APK LEFT ON DISK

`McpResprite/apk-contents/` holds the extracted original APK (manifest, dex, libapp.so, res, assets, palettes, themes, fonts). **Not committed to git** (excluded). Useful for further format/behavior research. `McpResprite/resprite-base.apk` is the original APK itself. Both are local-only — do not redistribute (proprietary content).

`McpResprite/apk-contents/assets/flutter_assets/assets/examples/example.resprite` is a valid sample file — useful for round-trip testing.
