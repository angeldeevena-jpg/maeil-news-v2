plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.maeilnews.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.maeilnews.app"
        // Android 10+: downloads use MediaStore RELATIVE_PATH and IS_PENDING, which need API 29.
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
        // The public GitHub Pages site of maeil-news-v2 (the daily files news.json and quiz.json are read from here).
        buildConfigField("String", "CONTENT_BASE_URL", "\"https://angeldeevena-jpg.github.io/maeil-news-v2/\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
