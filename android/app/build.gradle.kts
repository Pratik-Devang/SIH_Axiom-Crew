plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.percorsa.sensorlogger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.percorsa.sensorlogger"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
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

    // The new XML-based sensor logger replaces the earlier Compose prototype.
    // Keep the legacy files in Git for reference, but do not compile them with
    // this app because this build intentionally has no Compose dependencies.
    sourceSets {
        getByName("main").java.exclude("com/percorsa/navigation/**")
        getByName("test").java.exclude("com/percorsa/navigation/**")
        getByName("androidTest").java.exclude("com/percorsa/navigation/**")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}
