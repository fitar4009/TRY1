# קלא אריכתא – Qala Arikha
### Fully Offline Hebrew Voice Assistant for Android

---

## Project Structure

```
qala-arikha/
├── gradle/
│   └── libs.versions.toml          # Central version catalog
├── settings.gradle.kts
├── build.gradle.kts                # Root build file
└── app/
    ├── build.gradle.kts            # App module dependencies
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── kotlin/com/qalaarikha/assistant/
        │   ├── App.kt                          # Application class / DI root
        │   ├── MainActivity.kt                 # Compose host + permissions
        │   ├── data/
        │   │   ├── model/
        │   │   │   ├── AssistantState.kt       # Full state machine + IntentResult
        │   │   │   └── Contact.kt              # Unified contact model
        │   │   ├── preferences/
        │   │   │   └── PreferencesManager.kt   # DataStore-backed settings
        │   │   └── repository/
        │   │       ├── ContactRepository.kt    # Interface
        │   │       └── ContactRepositoryImpl.kt# Merges 3 contact sources
        │   ├── domain/
        │   │   ├── HebrewNumberNormalizer.kt   # "אפס חמש" → "05"
        │   │   ├── IntentRecognizer.kt         # Utterance → IntentResult
        │   │   └── ContactMatcher.kt           # Fuzzy Hebrew name matching
        │   ├── speech/
        │   │   ├── SpeechEvent.kt              # STT event sealed class
        │   │   ├── SpeechRecognitionManager.kt # Offline Android STT wrapper
        │   │   └── TtsManager.kt               # Android TTS wrapper
        │   └── ui/
        │       ├── home/
        │       │   ├── HomeUiState.kt          # UI state + effects
        │       │   ├── HomeViewModel.kt        # Core state machine
        │       │   └── HomeScreen.kt           # Main Compose screen
        │       ├── settings/
        │       │   ├── SettingsViewModel.kt
        │       │   └── SettingsScreen.kt
        │       ├── nav/
        │       │   └── Navigation.kt           # NavHost graph
        │       └── theme/
        │           ├── Color.kt
        │           ├── Theme.kt
        │           └── Type.kt
        └── res/
            ├── values/strings.xml
            └── xml/file_paths.xml
```

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  UI Layer  (Jetpack Compose + Material 3, forced RTL)        │
│  HomeScreen ◄──StateFlow── HomeViewModel ──Effects──► Intent │
│  SettingsScreen ◄── SettingsViewModel                        │
├─────────────────────────────────────────────────────────────┤
│  Domain Layer                                                │
│  HebrewNumberNormalizer  IntentRecognizer  ContactMatcher    │
├─────────────────────────────────────────────────────────────┤
│  Data Layer                                                  │
│  ContactRepositoryImpl  ←  3 sources:                        │
│    1. Android ContactsContract                               │
│    2. Bluetooth-synced (PBAP account type)                   │
│    3. TXT file: Android/data/<pkg>/files/contacts.txt        │
│  PreferencesManager (DataStore)                              │
├─────────────────────────────────────────────────────────────┤
│  Speech Layer                                                │
│  SpeechRecognitionManager   TtsManager                       │
│  (Android SpeechRecognizer, EXTRA_PREFER_OFFLINE=true)       │
│  (Android TextToSpeech,     Locale("he","IL"))               │
└─────────────────────────────────────────────────────────────┘
```

### State Machine

```
Idle
 │ startListening()
 ▼
Listening ──── final result ──────► Processing
    │                                    │
    │                          ┌─────────┴─────────────────┐
    │                    dial cmd                      unknown/dictation
    │                          │
    │               ┌──────────┴──────────────┐
    │            1 match                  2-3 matches
    │               │                         │
    │        confirm off?           WaitingForSelection
    │               │               (say 1/2/3)
    │        WaitingForConfirmation
    │               │ yes
    │               ▼
    │            Speaking ─── TTS done ──► Listening (restart)
    │
    └── ERROR_NO_MATCH / TIMEOUT ──────► Listening (silent retry)
```

---

## Setup Instructions

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- JDK 17+
- A physical device OR emulator with the **Google Speech Services** APK installed

### 1. Open in Android Studio
```
File → Open → select the qala-arikha/ folder
```
Gradle will sync automatically.

### 2. Install Hebrew Offline Speech Model
The app uses Android's built-in SpeechRecognizer with `EXTRA_PREFER_OFFLINE = true`.
For offline use you must install the Hebrew language pack on the device:

```
Device Settings → System → Language & input
  → Voice input (or Speech Recognition)
    → Google (or default engine)
      → Download languages → עברית (Hebrew)
```

On Android 13+, the app uses `SpeechRecognizer.createOnDeviceSpeechRecognizer()`
which explicitly avoids network calls.

### 3. Contacts TXT File (Source 3)
Place a file at:
```
Android/data/com.qalaarikha.assistant/files/contacts.txt
```
Format (one contact per line):
```
משה כהן=0501234567
אמא=0529876543
דוד לוי=0541111111
```
The path is configurable in **Settings → קובץ אנשי קשר**.

---

## Build Instructions

### Debug APK
```bash
cd qala-arikha
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Release APK (unsigned)
```bash
./gradlew assembleRelease
```

### Signed Release APK

**Step 1 – Create a keystore (one time)**
```bash
keytool -genkey -v \
  -keystore qala-release.jks \
  -alias qala \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

**Step 2 – Add signing config to `app/build.gradle.kts`**
```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile     = file("../qala-release.jks")
            storePassword = "YOUR_STORE_PASSWORD"
            keyAlias      = "qala"
            keyPassword   = "YOUR_KEY_PASSWORD"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // ...existing config...
        }
    }
}
```
> For CI/CD use environment variables instead of hardcoding passwords.

**Step 3 – Build**
```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

**Step 4 – Verify signature**
```bash
apksigner verify --verbose app-release.apk
```

---

## Component Descriptions

| Component | Responsibility |
|---|---|
| `App` | Application subclass; creates `PreferencesManager` and `ContactRepository` singletons used as manual DI |
| `MainActivity` | Single activity; hosts Compose, forces RTL, requests RECORD_AUDIO on launch |
| `SpeechRecognitionManager` | Wraps `SpeechRecognizer`; on API 33+ uses `createOnDeviceSpeechRecognizer()`; emits `SpeechEvent` flow |
| `TtsManager` | Wraps `TextToSpeech` with `Locale("he","IL")`; exposes `isSpeaking: StateFlow` |
| `HebrewNumberNormalizer` | Converts Hebrew spoken digits ("אפס חמש") to ASCII digits ("05") before intent matching |
| `IntentRecognizer` | Regex + lookup-table parser → `IntentResult` (DialContact, DialNumber, StartDictation, …) |
| `ContactMatcher` | Levenshtein similarity + Hebrew phonetic normalisation; threshold configurable 50–100% |
| `ContactRepositoryImpl` | Queries `ContactsContract` (Android + Bluetooth PBAP accounts) and reads TXT file; deduplicates by phone number |
| `PreferencesManager` | DataStore persistence for: fuzzy threshold, contacts file path, dial-immediately, silence length |
| `HomeViewModel` | Full assistant state machine; owns STT/TTS lifecycle; emits `HomeUiState` + `UiEffect` |
| `HomeScreen` | Animated mic orb, state label, partial text, candidate list, confirmation card, dictation area |
| `SettingsScreen` | Sliders for threshold/silence, toggle for dial-immediately, text field for file path, reload button |

---

## Voice Commands Reference

| What to say | Result |
|---|---|
| `חייג למשה` | Fuzzy-searches contacts for "משה", dials match |
| `התקשר לאמא` | Dials the contact named "אמא" |
| `חייג לאפס חמש שתיים שבע` | Normalises to "0527…", dials number |
| `חייג לכוכבית אפס חמש חמש חמש` | Dials *0555 |
| `כן` / `לא` / `בטל` | Confirmation response |
| `1` / `אחד` / `שתיים` / `שלוש` | Disambiguation selection |
| `מצב הכתבה` | Enters dictation mode |
| `עצור הכתבה` | Exits dictation mode |

---

## Permissions

| Permission | When requested | Purpose |
|---|---|---|
| `RECORD_AUDIO` | App launch | Microphone for STT |
| `READ_CONTACTS` | First dial attempt | Access Android phonebook |
| `CALL_PHONE` | First direct call | Dial without opening the dialer UI |
| `BLUETOOTH_CONNECT` | Background (no dialog) | Detect Bluetooth-synced contacts |

---

## Offline Operation

The app works entirely in Airplane Mode provided:
1. The Hebrew offline speech model is installed (see Setup §2).
2. TTS: Android's built-in Google TTS ships with Hebrew voices offline.
3. No network calls are made by any component of this app.
