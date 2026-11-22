plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNo = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.kuryuk.jetbrowser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kuryuk.jetbrowser"
        minSdk = 23
        targetSdk = 34
        versionCode = buildNo
        versionName = "1.0.$buildNo"
    }

    signingConfigs {
        create("release") {
            storeFile = file("jetbrowser.jks")
            storePassword = "jetbrowser"
            keyAlias = "jetbrowser"
            keyPassword = "jetbrowser"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
