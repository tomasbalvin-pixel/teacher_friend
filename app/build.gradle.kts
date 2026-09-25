plugins {
    id("com.android.application")
}

android {
    namespace = "cz.teacherfriend.redpen"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.teacherfriend.redpen"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Pro instalaci mimo Google Play stačí debug podpis; pro Play doplňte vlastní signingConfig.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
