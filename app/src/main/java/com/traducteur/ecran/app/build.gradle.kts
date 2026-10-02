plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.traducteur.ecran"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.traducteur.ecran"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    // Lecture du texte à l'écran (modèle intégré, hors-ligne)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Traduction anglais -> français sur le téléphone (gratuit, hors-ligne après 1er téléchargement)
    implementation("com.google.mlkit:translate:17.0.3")
}
