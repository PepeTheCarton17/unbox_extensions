plugins {
    id("com.android.application")
    kotlin("plugin.serialization")
}

android {
    namespace = "eu.kanade.tachiyomi.animeextension.es.pluto"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.animeextension.es.pluto"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "16.1"
    }

    base {
        archivesName.set("aniyomi-es.pluto-v16.1")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signingkey.jks")
            storePassword = "aextsigning"
            keyAlias = "aext"
            keyPassword = "aextsigning"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    // El lint de release intenta resolver dependencias transitivas muertas del
    // extensions-lib antiguo (injekt-core de JitPack) y rompe la compilación.
    // Las extensiones no necesitan lint vital; la compilación Kotlin basta.
    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
            freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
        }
    }
}

dependencies {
    compileOnly("com.github.aniyomiorg:extensions-lib:14")
    compileOnly("androidx.preference:preference:1.2.1")
    compileOnly("uy.kohesive.injekt:injekt-core:1.16.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.12")
}
