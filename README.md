# McpResprite

FOSS Kotlin/Compose clone of Resprite (package `com.fengeon.resprite_desktop`).
Reads/writes the same `.resprite` zip format (document.json + CellData/*.png).
No billing, no WeChat Pay, no analytics.

## Build

Requires JDK 17 + Android SDK (compileSdk 34).

```bash
# generate the wrapper once (JDK 17 required)
gradle wrapper --gradle-version 8.7

# tell gradle where the SDK is
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Signed release

```bash
mkdir -p keystore
keytool -genkeypair -keystore keystore/mcpresprite.keystore \\
  -alias mcpresprite -keyalg RSA -keysize 2048 -validity 10000 \\
  -storepass mcpresprite -keypass mcpresprite -dname "CN=McpResprite"

export RESPRITE_KEYSTORE_PASS=mcpresprite
export RESPRITE_KEY_ALIAS=mcpresprite
export RESPRITE_KEY_PASS=mcpresprite
./gradlew :app:assembleRelease
```

## Layout

- `model/Document.kt` — the `.resprite` scene graph schema (kotlinx.serialization)
- `format/RespriteFile.kt` — zip reader/writer for `.resprite` files
- `commands/` — Command pattern + UndoManager (mirrors original app's `*Cmd` classes)
- `ui/editor/` — Compose canvas compositor + editor screen

## Status

Scaffold: complete source. Not compiled in this workspace (no JDK/SDK on the target).
