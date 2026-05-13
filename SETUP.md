# קלא אריכתא – Setup Guide

## What this is

A fully offline, Hebrew voice dialer for Android smartphones and automotive head units.  
**Zero internet permission** — all processing is local.

---

## Project structure

```
KalaArichta/
├── app/
│   ├── src/main/
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt          ← build config for whisper.cpp JNI
│   │   │   ├── whisper_jni.cpp         ← JNI bridge (Kotlin ↔ C++)
│   │   │   └── whisper/                ← ⚠️  YOU MUST ADD whisper.cpp HERE
│   │   │       └── .gitkeep
│   │   └── java/com/kala/arichta/
│   │       ├── AppPreferences.kt
│   │       ├── audio/
│   │       │   ├── AudioFocusManager.kt
│   │       │   ├── AudioFocusService.kt
│   │       │   └── AudioRecorder.kt
│   │       ├── contacts/
│   │       │   ├── Contact.kt
│   │       │   └── ContactRepository.kt
│   │       ├── nlp/
│   │       │   ├── ContactMatcher.kt
│   │       │   └── HebrewNumberParser.kt
│   │       ├── ui/
│   │       │   ├── DisambiguationActivity.kt
│   │       │   ├── MainActivity.kt
│   │       │   └── SettingsActivity.kt
│   │       └── whisper/
│   │           └── WhisperEngine.kt
│   └── build.gradle.kts
├── .github/workflows/build.yml
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/libs.versions.toml
```

---

## Step 1 — Add whisper.cpp sources (REQUIRED)

The C++ sources are not bundled. Add them as a **git submodule** (recommended):

```bash
cd app/src/main/cpp
git submodule add https://github.com/ggerganov/whisper.cpp whisper
```

Or clone manually:

```bash
cd app/src/main/cpp
git clone --depth 1 https://github.com/ggerganov/whisper.cpp whisper
```

The CMakeLists.txt expects these files inside `app/src/main/cpp/whisper/`:

| File | Purpose |
|------|---------|
| `whisper.h` / `whisper.cpp` | Main whisper API |
| `ggml.h` / `ggml.c` | GGML tensor library |
| `ggml-alloc.c` | GGML allocator |
| `ggml-backend.c` | GGML backend |
| `ggml-quants.c` | GGML quantization |

> **Tip**: whisper.cpp is actively developed. Tested with commit tags `v1.6.x` and later.  
> If the build fails due to new files, add them to `CMakeLists.txt`.

---

## Step 2 — Download a Whisper GGUF model

Recommended models for Hebrew (balance of speed vs accuracy):

| Model | Size | Hebrew quality | Recommended device |
|-------|------|----------------|-------------------|
| `ggml-tiny.bin` | ~75 MB | Fair | Low-power HU |
| `ggml-base.bin` | ~141 MB | Good | Most HUs |
| `ggml-small.bin` | ~466 MB | Very good | Flagship phones |
| `ggml-medium.bin` | ~1.5 GB | Excellent | High-RAM devices |

Download from the official Hugging Face repo:

```
https://huggingface.co/ggerganov/whisper.cpp/tree/main
```

Place the `.bin` or `.gguf` file at:

```
/sdcard/Android/data/com.kala.arichta/files/ggml-base.bin
```

(You can also select it via the in-app Settings → Model picker.)

---

## Step 3 — Prepare contacts (if no Bluetooth phone)

Create a plain-text file at:

```
/sdcard/Android/data/com.kala.arichta/files/contacts.txt
```

Format — one contact per line, `Name=Number`:

```
משה כהן=0521234567
חנה לוי=0531112233
David Smith=+972541234567
חירום=112
```

Lines starting with `#` are ignored (comments).

---

## Step 4 — Build

### Android Studio

1. Open the `KalaArichta` folder in Android Studio Hedgehog (2023.1+) or newer.
2. Let Gradle sync.
3. Build → Make Project.
4. Run on device or emulator (API 26+).

### Command line

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (unsigned)
./gradlew assembleRelease
```

APKs land in `app/build/outputs/apk/`.

### GitHub Actions

Push to `main` or `master` branch. The workflow (`.github/workflows/build.yml`) will:
- Install NDK 26
- Check whisper.cpp sources exist
- Build debug + release APKs
- Upload both as artifacts

> **Important**: whisper.cpp submodule must be committed and pushed before CI runs.

---

## Step 5 — First launch

1. Grant **Microphone** permission when prompted.
2. Open **Settings** (⚙️ top-right) → select your model file.
3. The app immediately starts listening on launch.
4. Say a contact name or phone number in Hebrew.

---

## How it works

```
Launch
  │
  ├─ Request AudioFocus (ducks radio)
  ├─ Load Whisper GGUF model
  ├─ Load contacts (BT PBAP → contacts.txt)
  │
  └─ AudioRecord (16kHz mono PCM)
        │
        ├─ VAD: silence after 1.5s → stop
        │
        └─ WhisperEngine.transcribe() [C++ JNI]
              │
              ├─ HebrewNumberParser → dial number directly
              │
              └─ ContactMatcher (FuzzyWuzzy)
                    ├─ 1 match  → confirm dialog → ACTION_CALL
                    ├─ 2+ match → DisambiguationActivity (voice select)
                    └─ 0 match  → retry prompt
```

---

## Troubleshooting

| Issue | Fix |
|-------|-----|
| `UnsatisfiedLinkError: kala_whisper_jni` | whisper.cpp sources missing from `cpp/whisper/` |
| `Failed to load model` | Model path wrong in Settings, or wrong format (needs GGUF/bin) |
| No contacts found | Check contacts.txt path and format; check BT pairing status |
| Hebrew transcription garbled | Use `ggml-small` or larger model; speak clearly |
| VAD cuts off too early | Increase silence timeout in Settings (default: 1500ms) |
| App crashes on old HU | Some head units lock AudioRecord to 44100Hz — file an issue |

---

## Notes for Automotive Head Units

- Set `android:screenOrientation="landscape"` in AndroidManifest if needed.
- Some HUs run Android 8 (API 26) — `minSdk = 26` handles this.
- Bluetooth PBAP contacts only appear after the phone pairs AND "Sync contacts" is enabled in the HU Bluetooth menu.
- If the HU has no `CALL_PHONE` permission flow, the `ACTION_CALL` Intent may open the system dialer instead of dialing directly. This is expected.

---

## License

MIT License — see `LICENSE` file.  
whisper.cpp is MIT licensed by Georgi Gerganov.  
FuzzyWuzzy Java port is MIT licensed by xdrop.
