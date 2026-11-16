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
