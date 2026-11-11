plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val storePw = providers.gradleProperty("RELEASE_STORE_PASSWORD").get()

android {
    namespace = "de.jugie.firelauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.jugie.firelauncher"
        minSdk = 25
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("release.keystore")
            storePassword = storePw
            keyAlias = "firelauncher"
            keyPassword = storePw
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
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("dev.mobile:dadb:1.2.10")
}
