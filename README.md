# Prompt IME

Android IME focused on Solar-powered prompt continuation. The primary flow is normal keyboard → type `/p` → the marker is consumed → Solar continuation appears in the keyboard → accept a word, phrase, or the full continuation → continue.

## Build

```bash
cd android-ime
gradle testDebugUnitTest assembleDebug
```

The project intentionally calls Upstage directly from the Android app. The API key is entered in the app settings and encrypted with Android Keystore; it is not committed to the repository or embedded into the APK.

## Privacy boundary

Outside Prompt Mode the IME does not make Solar requests. Password/PIN editors are never eligible for Prompt Mode. Context is bounded around the cursor and includes right-side context for mid-document editing.

## CI

`codemagic.yaml` runs unit tests and produces a debug APK artifact.
