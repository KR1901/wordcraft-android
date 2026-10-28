plugins {
    id("com.android.application")
}

// Deliberately dependency-free (Java, no androidx, no Kotlin stdlib, no native code).
// Its only job is to answer: can this device run an app built from this repository at all?
android {
    namespace = "com.wordcraft.probe"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.wordcraft.probe"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
