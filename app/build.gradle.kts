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

    // lite: normal kurulur. full: erişilebilirlik servisi içerir (Play Protect elle kurulumu engelleyebilir).
    flavorDimensions += "v"
    productFlavors {
        create("lite") { dimension = "v" }
        create("full") { dimension = "v" }
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

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}
