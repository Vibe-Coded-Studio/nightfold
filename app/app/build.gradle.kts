plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.vibecodedstudio.nightfold"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.vibecodedstudio.nightfold"
        minSdk = 34          // RuntimeShader (AGSL) 33, special-use foreground service 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { aidl = true }
}
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
