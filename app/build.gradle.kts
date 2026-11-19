plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// O motor sherpa-onnx (Parakeet TDT) só entra na compilação se o AAR estiver em app/libs/.
val sherpaAvailable = file("libs").listFiles()?.any { it.name.endsWith(".aar") } == true

android {
    namespace = "com.telefonechamador.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.telefonechamador.app"
        minSdk = 26
        // targetSdk 34 de propósito: em 35+ o Android restringe ainda mais a captura
        // de áudio de chamada (VOICE_CALL); 34 mantém a sonda funcionando nos aparelhos
        // que ainda permitem a fonte nativa. Uso manual (sideload), sem Play Store.
        targetSdk = 34
        // Só arm64: o AAR do sherpa-onnx embute .so de 4 ABIs (157 MB no APK;
        // x86/x86_64 só servem em emulador) — de 166 MB para ~48 MB. Os
        // aparelhos de teste (HyperOS) são arm64; se um dia precisar de 32-bit,
        // adicionar "armeabi-v7a" aqui (+29 MB).
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
        versionCode = 4
        versionName = "1.0.3"
    }

    buildTypes {
        release {
            // A1 (Regras.txt): R8 SEMPRE no release — otimizações ligadas
            // (proguard-android-optimize.txt + android.enableR8.fullMode em
            // gradle.properties) e recursos encolhidos.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Assinatura de debug só para TESTE LOCAL em aparelho (sideload);
            // a Store reassina o artefato dela (A2/W2).
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets {
        getByName("main") {
            if (sherpaAvailable) java.srcDirs("src/sherpa/java")
        }
    }
    // AIDL (serviço do Shizuku) — no AGP 8.x vem desligado por padrão
    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.7.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.preference:preference-ktx:1.2.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.apache.commons:commons-compress:1.26.2")

    // Shizuku: permissões elevadas via ADB, sem root (opcional)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Reflexão em APIs ocultas (ActivityThread/AudioRecord) dentro do UserService
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    // Vosk: transcrição offline em streaming (parciais quase instantâneas)
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47")

    if (sherpaAvailable) implementation(fileTree("libs") { include("*.aar") })
}
