plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.percorsa.sensorlogger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.percorsa.sensorlogger"
        minSdk = 21
        targetSdk = 34
        versionCode = 3
        versionName = "2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Keep the deployed TCN contract in the APK. ONNX models are already
    // compressed binary files, so storing them uncompressed also avoids an
    // unnecessary decompression/copy step during model initialization.
    sourceSets {
        getByName("main") {
            assets.srcDir("src/main/assets")
        }
    }
    androidResources {
        noCompress += "onnx"
    }
}

val verifyTcnAssets by tasks.registering {
    val model = layout.projectDirectory.file("src/main/assets/tcn.onnx")
    val normalization = layout.projectDirectory.file("src/main/assets/normalization.json")
    inputs.files(model, normalization)

    doLast {
        require(model.asFile.isFile && model.asFile.length() > 0L) {
            "Missing or empty Android TCN model: ${model.asFile}"
        }
        require(normalization.asFile.isFile && normalization.asFile.length() > 0L) {
            "Missing or empty Android TCN normalization data: ${normalization.asFile}"
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyTcnAssets)
}

dependencies {
    // Existing
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Coroutines — for async search/routing calls
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Lifecycle — ViewModel + StateFlow integration
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Networking — for Nominatim/OSRM REST calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing — for Nominatim/OSRM response parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // On-device TCN speed inference
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    testImplementation("junit:junit:4.13.2")
}
