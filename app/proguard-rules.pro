# Vosk / JNA
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn org.vosk.**
-dontwarn com.sun.jna.**

# sherpa-onnx (quando presente em app/libs/)
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.**

-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# A2 (Regras.txt): stack traces desofuscáveis via mapping.txt
-keepattributes SourceFile,LineNumberTable

# Reflexão: UserServiceImpl é instanciado PELO NOME dentro do processo do
# servidor Shizuku — sem keep o R8 remove/renomeia e a gravação via shell quebra.
-keep class com.telefonechamador.app.shizuku.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }

# Reflexão ENTRE source sets: OfflineFileStt/OfflineFileTts/CallEngine chamam
# SherpaEngine e SherpaTts pelo NOME (Class.forName + getMethod/getConstructor),
# mas eles moram no source set condicional sherpa. Sem keep o R8 renomeia a
# classe (ex.: z1.e) e ADAPTA a string do Class.forName, enquanto o nome do
# método no getMethod fica literal → NoSuchMethodException: z1.e.transcribeFile
# (quebra transcrição de arquivo, TTS do Laboratório e STT em ligação).
-keep class com.telefonechamador.app.stt.SherpaEngine { *; }
-keep class com.telefonechamador.app.stt.SherpaTts { *; }
