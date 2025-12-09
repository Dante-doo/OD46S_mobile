plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    //alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
}

android {
    namespace = "br.edu.utfpr.coletapb"
    compileSdk = 36

    defaultConfig {
        applicationId = "br.edu.utfpr.coletapb"
        minSdk = 25
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
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
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.appcompat)
    implementation(libs.material.v1130)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.appcompat)
    implementation(libs.material.v1130)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.okhttp)

    // Activity KTX (substitua a dependência 'activity' por esta)
    implementation(libs.androidx.activity.ktx)

    // Lifecycle & ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    // ProcessLifecycleOwner para detectar quando app vai para background/foreground
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    
    // SwipeRefreshLayout
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    
    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Room (Banco de dados local)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.play.services.location)
    implementation(libs.okhttp)

    // OSMDroid (OpenStreetMap - Gratuito)
    implementation("org.osmdroid:osmdroid-android:6.1.18") {
        exclude(group = "com.j256.ormlite", module = "ormlite-core")
        exclude(group = "com.j256.ormlite", module = "ormlite-android")
    }
    implementation("org.osmdroid:osmdroid-wms:6.1.18")
    // Removendo dependências que causam conflito com ORMLite
    // implementation("org.osmdroid:osmdroid-mapsforge:6.1.18")
    // implementation("org.osmdroid:osmdroid-geopackage:6.1.18")

}