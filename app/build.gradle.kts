import java.util.Properties

plugins {
    id("com.android.application")
}

val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "za.schoolstreet.cameras"
    compileSdk = 34

    defaultConfig {
        applicationId = "za.schoolstreet.cameras"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "kind"
    productFlavors {
        create("plain") {
            dimension = "kind"
            buildConfigField("boolean", "ALERTS", "false")
            manifestPlaceholders["appLabel"] = "Cameras"
        }
        create("alert") {
            dimension = "kind"
            applicationIdSuffix = ".alert"
            versionCode = 12
            versionName = "2.1"
            buildConfigField("boolean", "ALERTS", "true")
            manifestPlaceholders["appLabel"] = "CCTV"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile", "cameras.keystore"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
