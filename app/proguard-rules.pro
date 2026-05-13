# Keep JNI-called classes
-keep class com.kala.arichta.whisper.WhisperEngine {
    native <methods>;
    *;
}

# Keep data classes used for serialization
-keep class com.kala.arichta.contacts.Contact { *; }

# FuzzyWuzzy
-keep class me.xdrop.fuzzywuzzy.** { *; }

# Preference
-keep class androidx.preference.** { *; }
