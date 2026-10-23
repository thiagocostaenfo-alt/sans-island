plugins {
    id("com.android.application")
}

android {
    namespace = "com.thiagocosta.sansisland"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.thiagocosta.sansisland"
        minSdk = 29
        targetSdk = 37
        versionCode = 6
        versionName = "1.5"
    }

    signingConfigs {
        // fixed key so every new APK installs over the previous one
        create("sans") {
            storeFile = rootProject.file("signing/sans-island.jks")
            storePassword = "sansisland"
            keyAlias = "sans"
            keyPassword = "sansisland"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sans")
        }
        debug {
            signingConfig = signingConfigs.getByName("sans")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
}
