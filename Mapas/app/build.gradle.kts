import java.io.FileInputStream
import java.util.Properties
import kotlin.apply

// Carrega as propriedades do local.properties
val propriedades = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(FileInputStream(localPropertiesFile))
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.android.libraries.mapsplatform.secrets.gradle.plugin)
}

android {
    namespace = "br.edu.utfpr.mapas"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.edu.utfpr.mapas"
        minSdk = 25
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Injeta a chave da API do maps como um campo de BuildConfig
        buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"${propriedades.getProperty("google.maps.api.key")}\"")
        manifestPlaceholders["googleMapsApiKey"] = propriedades.getProperty("google.maps.api.key")

        // Injeta a chave da API do geocoding como um campo de BuildConfig
        //buildConfigField("String", "GOOGLE_GEOCODING_API_KEY", "\"${properties.getProperty("google.geocoding.api.key")}\"")
        val googleGeocodingApiKey = propriedades.getProperty("google.geocoding.api.key") ?: "null"
        buildConfigField("String", "GOOGLE_GEOCODING_API_KEY", "\"$googleGeocodingApiKey\"")

        val googleMapsStaticApiKey = propriedades.getProperty("google.mapsstatic.api.key") ?: "null"
        buildConfigField("String", "GOOGLE_MAPSSTATIC_API_KEY", "\"$googleMapsStaticApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.play.services.maps)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}