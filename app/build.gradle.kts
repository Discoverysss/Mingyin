plugins { id("com.android.application") }

android {
    namespace = "com.example.voicepitch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.voicepitch"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
