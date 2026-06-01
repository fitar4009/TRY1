# Keep DataStore generated classes
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
# Keep SpeechRecognizer listener callbacks
-keep class android.speech.** { *; }
# Keep TTS
-keep class android.speech.tts.** { *; }
