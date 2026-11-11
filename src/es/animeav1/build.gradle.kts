plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "eu.kanade.tachiyomi.animeextension.es.animeav1"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.animeextension.es.animeav1"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "16.1"
    }

    base {
        archivesName.set("aniyomi-es.animeav1-v16.1")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signingkey.jks")
            storePassword = "unboxpassword"
            keyAlias = "unbox"
            keyPassword = "unboxpassword"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs += "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
    }
}

dependencies {
    compileOnly("com.github.aniyomiorg:extensions-lib:14")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.12")
}
