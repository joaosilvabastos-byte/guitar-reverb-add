plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.bastos.guitarreverbadd"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bastos.guitarreverbadd"
        minSdk = 24
        targetSdk = 37
        versionCode = 15
        versionName = "2.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
}

dependencies {
    // Biblioteca de Faturação oficial da Google com extensões Kotlin
    implementation("com.android.billingclient:billing-ktx:8.0.0")

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}